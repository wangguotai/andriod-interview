package com.interview.image

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong

/**
 * Time: 2026/10/3
 * Author: wgt
 * Description: 「大图加载」Lab 的证据层 —— 把内存这件事变成可观测的数字。
 *
 * ─── 为什么要专门做一个探针，而不是直接讲道理 ───
 *
 * 降采样教学最容易翻车的地方，是学生「听懂了」但「没看见」。
 * 「inSampleSize = 2 内存变成 1/4」这句话本身没有说服力，因为位图内存在 Java 层
 * 是看不见的：你 new 一个 Bitmap，堆里涨的那点数是壳，真正的像素内存（ARGB_8888
 * 每像素 4 字节）在 Android 8.0（API 26）之后直接分配在 native 堆上，
 * `Runtime.totalMemory()` 根本反映不出来。
 *
 * 所以这里刻意采三类指标，互相印证：
 *
 * 1. **Java 堆**（totalMemory - freeMemory）：能反映 jank 但**反映不了位图**，
 *    这个"测不出来"本身就是 API 26+ 的重要知识点。
 * 2. **native 堆**（Debug.getNativeHeapAllocatedSize）：API 26+ 位图的主战场。
 * 3. **PSS 里的 graphics 段**（ActivityManager，summary.graphics）：这是最权威的
 *    一项 —— 系统把位图内存单独归到 graphics 里统计，涨没涨一目了然。
 *
 * 三项一起看，才能得出可靠结论；只看 Java 堆会误判成「加载大图不占内存」。
 */
object MemoryProbe {

    private const val TAG = "MemoryProbe"

    data class Snapshot(
        /** Java 堆已用（字节） */
        val javaUsedBytes: Long,
        /** Java 堆上限（字节）—— 超过它就 OOM */
        val javaMaxBytes: Long,
        /** native 堆已分配（字节），API 26+ 位图的实际落点 */
        val nativeHeapBytes: Long,
        /** 进程总 PSS（KB） */
        val totalPssKb: Int,
        /** 位图归属的 graphics PSS（KB），API 26+ 才有意义 */
        val graphicsPssKb: Int,
        val nativePssKb: Int,
        val dalvikPssKb: Int,
    ) {
        val javaUsedRatio: Float
            get() = if (javaMaxBytes <= 0) 0f else (javaUsedBytes.toFloat() / javaMaxBytes)

        val graphicsBytes: Long
            get() = graphicsPssKb.toLong() * 1024
    }

    fun snapshot(context: Context): Snapshot {
        val runtime = Runtime.getRuntime()
        val javaUsed = runtime.totalMemory() - runtime.freeMemory()

        var totalPss = 0
        var graphicsPss = 0
        var nativePss = 0
        var dalvikPss = 0
        runCatching {
            val am = context.applicationContext
                .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = am.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()
            if (info != null) {
                totalPss = info.totalPss
                dalvikPss = info.dalvikPss
                nativePss = info.nativePss
                // summary.graphics 是系统对位图内存的专门归类：
                // API 26+ 位图在 native 堆，但仍被计入 graphics；之前是 Java 堆的一部分。
                // 不管落在哪，这一项都跟着位图走，是最可靠的"降采样生效了没"的仪表。
                graphicsPss = runCatching {
                    info.getMemoryStat("summary.graphics").toInt()
                }.getOrDefault(0)
            }
        }.onFailure {
            // 探针失败不能影响教学主流程，降级为"只有 Java 堆可看"。
        }

        return Snapshot(
            javaUsedBytes = javaUsed,
            javaMaxBytes = runtime.maxMemory(),
            nativeHeapBytes = Debug.getNativeHeapAllocatedSize(),
            totalPssKb = totalPss,
            graphicsPssKb = graphicsPss,
            nativePssKb = nativePss,
            dalvikPssKb = dalvikPss,
        )
    }

    /** 人类可读的体积。统一用 MB，保留两位小数，便于不同尺寸直接比大小。 */
    fun format(bytes: Long): String = "%.2f MB".format(bytes.toDouble() / 1024 / 1024)

    fun formatKb(kb: Int): String = format(kb.toLong() * 1024)

    /**
     * 周期性采样器。
     *
     * 放在主线程 Handler 上跑：采样本身很轻（几次系统调用），而结果要直接刷 UI，
     * 再跨线程投递反而多一次拷贝与一帧延迟。间隔默认 1000ms —— 太密会自己制造
     * 掉帧，污染我们要观测的"滚动是否卡顿"这一现象。
     */
    class Sampler(
        private val context: Context,
        private val intervalMs: Long = 1000L,
        private val onSample: (Snapshot) -> Unit,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var running = false

        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                runCatching { onSample(snapshot(context)) }
                handler.postDelayed(this, intervalMs)
            }
        }

        fun start() {
            if (running) return
            running = true
            // 立刻给一帧基线，否则前 1s 界面上是空的，看不到"起点"
            runCatching { onSample(snapshot(context)) }
            handler.postDelayed(tick, intervalMs)
        }

        fun stop() {
            running = false
            handler.removeCallbacks(tick)
        }
    }
}

/**
 * 解码账本：累计记录"这一次页面生命周期里一共解码了多少位图、合计多少字节"。
 *
 * ─── 为什么需要它，光看当前内存不够吗 ───
 *
 * 不够。因为**回收是滞后的**：Glide 的 LruCache 会把老位图留在池子里，
 * 滚动过程中当前内存可能一直在 40MB 上下横盘，看不出"已经分配了 800MB 的位图"。
 * 累计值不撒谎 —— 它直接回答"如果这些位图全部同时存活，会发生什么"。
 *
 * 这也正是线上 OOM 的典型形态：瞬时值看着安全，但短时间内的高频分配
 * 把 GC 逼到跟不上，最终在最不该发生的地方 OOM。
 */
object DecodeLedger {

    private val totalBytes = AtomicLong(0)
    private val count = AtomicLong(0)

    /** 记录一次**真实解码结果**的位图体积（取 bitmap.byteCount，不是估算值）。 */
    fun record(byteCount: Int) {
        totalBytes.addAndGet(byteCount.toLong())
        count.incrementAndGet()
    }

    fun reset() {
        totalBytes.set(0)
        count.set(0)
    }

    fun count(): Long = count.get()

    fun totalBytes(): Long = totalBytes.get()

    fun summary(): String =
        "累计解码 ${count.get()} 张 · 合计 ${MemoryProbe.format(totalBytes.get())}"
}
