package com.interview.内存

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.interview.memtrace.MemTraceNative
import com.interview.thread.ThreadPools
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Time: 2026/10/8
 * Author: wgt
 * Description: 内存监控里「**谁在占**」那一半 —— 分三条路把总量拆到持有者身上
 *
 * ══════════════════════════════════════════════════════════════════════
 * 为什么总量监控不够（本模块存在的唯一理由）
 * ══════════════════════════════════════════════════════════════════════
 *
 * `MemoryMetrics` 能告诉你「Java 堆到了 78%、native 涨了 30 MB」，但**不能**告诉你
 * 「涨的是谁」。而线上内存问题的处置动作完全取决于这个答案：
 *
 * ```
 *   Java 堆涨 → 找持有者（Bitmap？集合？数组？）  → 该清缓存 / 该换数据结构
 *   native 涨 → 找分配者（我们的 .so？三方库？位图？） → 该改算法 / 该升级库
 *   两者都不涨，但 PSS 涨 → 找映射（so / 字体 / 资源） → 该瘦身包体 / 该按需加载
 * ```
 *
 * 所以本文件提供三条互补的归因路径（没有任何一条能覆盖全部）：
 *
 * | 路径 | 回答什么 | 成本 | 本机（模拟器 API 36 / arm64） |
 * |---|---|---|---|
 * | [MemoryAllocTracker]（native hook） | native 里**哪个函数**要的内存 | 热路径开销，需显式安装 | ✅ 实测可用 |
 * | [MemoryHeapAnalyzer]（Java 直方图） | Java 堆里**哪一类对象**占了多少 | 一次 dump（秒级冻结） | ✅ 自研 hprof 解析 |
 * | [BitmapTracker]（图形内存） | 位图几张、共多少、谁占大头 | 每次创建一次原子累加 | ✅ |
 *
 * ══════════════════════════════════════════════════════════════════════
 * 诚实边界（先看这一节，否则报告会被误读）
 * ══════════════════════════════════════════════════════════════════════
 *
 * 1. **native 归因是有损采样**（`>=8KB` 全采、`[64B,8KB)` 1/256、拿不到锁就跳过）。
 *    它回答"大头在哪"，**不回答**"有没有漏 200KB"。
 * 2. **Java 直方图只有"类 → 数量/字节"，没有引用链**。它指向"数量异常的**种类**"，
 *    读不出"数量正常但没人放开的循环引用"（那需要支配树，是 MAT/Shark 的领域）。
 * 3. **BitmapTracker 只统计经过我们代码的位图**，因此恒 ≤ 系统的 `summary.graphics`。
 *    **差额是信息不是 bug**：它说明图形内存的大头不在我们的代码路径上。
 * 4. 三条路径都**不回答**"这块内存该不该存在"——那是业务判断。
 */
private const val TAG = "MemoryAnalyze"

/** 图片/缓存这类"按来源归因"的通用标识（也用于日志前缀与事件 name）。 */
internal fun sourceTagOf(prefix: String, detail: String): String =
    if (detail.isBlank()) prefix else "$prefix:$detail"

// ═══════════════════════════════════════════════════════════════════════════
// 路径一：Native 分配归因（memtrace）
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Native 分配追踪器的 **Java 侧门面**。
 *
 * ─── 设计原则：默认不装（与 ANR 的「通道 4」同一纪律）───
 *
 * memtrace 装在**全局 malloc 热路径**上，对被测系统的影响是真实的：
 * 每次大分配多一次 unwind。所以它：
 *   · **不在** `MemoryMonitor.install()` 里自动装；
 *   · 只由实验页/排查时**显式开启**；
 *   · 出报告前 [pause]，避免把报告自己的分配记进去。
 *
 * 这与 `StabilityMonitor` 不自动装 SIGQUIT 通道是同一个判断：
 * **"会改变被测系统行为的探针"必须显式开启，且"开着"这件事要能被看见。**
 */
object MemoryAllocTracker {

    @Volatile
    private var installed = false

    @Volatile
    private var lastReport: String? = null

    @Volatile
    private var lastReportAt: Long = 0L

    /** 最近一次分配器读数（供指标层每 2s 采样使用） */
    @Volatile
    var lastAllocatorStats: AllocatorStats = AllocatorStats.EMPTY
        private set
    private val cacheHits = AtomicLong(0)
    private val cacheMisses = AtomicLong(0)

    /** 分配器视角的读数（**成本极低**：一次 mallinfo2 + 一次 malloc_info）。 */
    data class AllocatorStats(
        val usedBytes: Long,
        val freeBytes: Long,
        val mmapBytes: Long,
        val arenaBytes: Long,
        val freeChunks: Long,
        /** 尺寸档（sizeBytes to liveCount），已按占用字节降序，**只含 primary/小对象档** */
        val sizeClasses: List<Pair<Int, Int>>,
    ) {
        companion object {
            val EMPTY = AllocatorStats(-1, -1, -1, -1, -1, emptyList())
        }

        val available: Boolean get() = usedBytes >= 0
    }

    /**
     * 采样路径专用：读分配器数字。
     *
     * ⚠️ 这是**每 2s 一次**的路径，所以只能做轻量的事（native 侧已限定为
     *    `mallinfo2` + `malloc_info`，实测输出百字节级）。
     *    **绝不能**把 [report] 挂在这里 —— 那是几十毫秒 + 几 KB 文本。
     */
    fun refreshAllocatorStats(): AllocatorStats {
        val raw = MemTraceNative.allocatorStats()
        if (raw == null) {
            cacheMisses.incrementAndGet()
            lastAllocatorStats = AllocatorStats.EMPTY
            return AllocatorStats.EMPTY
        }
        cacheHits.incrementAndGet()
        val s = parseAllocatorStats(raw)
        lastAllocatorStats = s
        return s
    }

    /**
     * 解析 native 侧的单行输出（固定字段序，**不用 JSON** —— 见 `memtrace.cpp` 注释）。
     *
     * 格式：`used=<B> free=<B> mmap=<B> arena=<B> ordblks=<N>|sc:<size>:<count>,...`
     *
     * ⚠️ 解析失败返回 [AllocatorStats.EMPTY] 而不是抛：这是每次采样都会走的路径，
     *    任何异常都会变成"监控自己制造的故障"。同时**不做宽松解析**
     *    （不用 contains 猜键名）—— 格式是我们自己定的，宽松只会在格式漂移时静默给错值。
     */
    internal fun parseAllocatorStats(raw: String): AllocatorStats = runCatching {
        val doc = raw.substringBefore("|sc:")
            .split(' ')
            .mapNotNull { kv ->
                val i = kv.indexOf('=')
                if (i <= 0) null else kv.substring(0, i) to kv.substring(i + 1)
            }
            .toMap()
        val sizes = raw.substringAfter("|sc:", "")
            .split(',')
            .mapNotNull { item ->
                val parts = item.split(':')
                if (parts.size != 2) return@mapNotNull null
                val size = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                val count = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
                if (size <= 0 || count <= 0) null else size to count
            }
        AllocatorStats(
            usedBytes = doc["used"]?.toLongOrNull() ?: -1L,
            freeBytes = doc["free"]?.toLongOrNull() ?: -1L,
            mmapBytes = doc["mmap"]?.toLongOrNull() ?: -1L,
            arenaBytes = doc["arena"]?.toLongOrNull() ?: -1L,
            freeChunks = doc["ordblks"]?.toLongOrNull() ?: -1L,
            sizeClasses = sizes,
        )
    }.getOrElse {
        Log.w(TAG, "allocatorStats 解析失败（原始：${raw.take(120)}）", it)
        AllocatorStats.EMPTY
    }

    /**
     * **演示用**：造一批 native 分配（不占 Java 堆）。
     *
     * 用 native 侧自己实现的分配函数（而不是 `ByteBuffer.allocateDirect`），
     * 这样归因报告里会出现**我们自己的函数帧**（libmemtrace.so+0x…），
     * 符号化后就是"哪一行代码要的内存" —— 这是"归因到代码"的完整证明。
     */
    fun allocateNativeDemo(count: Int, size: Int): LongArray = MemTraceNative.allocBlocks(count, size)

    /** 释放 [allocateNativeDemo] 分配的块。 */
    fun freeNativeDemo(blocks: LongArray) = MemTraceNative.freeBlocks(blocks)

    /** 安装 hook。**幂等**。false 表示环境不支持（非 arm64 / prefab 未通 / bytehook 失败）。 */
    fun install(): Boolean {
        if (installed) return true
        val ok = MemTraceNative.install()
        installed = ok
        if (ok) {
            Log.i(TAG, "memtrace 已安装：${MemTraceNative.status()}")
        } else {
            Log.w(TAG, "memtrace 不可用：loadError=${MemTraceNative.loadError} " +
                    "installError=${MemTraceNative.installError}")
        }
        return ok
    }

    fun isInstalled(): Boolean = installed && MemTraceNative.isInstalled()

    /** 出报告。**必须在后台线程调用**（几十毫秒 + 分配几 KB 文本）。 */
    fun report(maxSites: Int = 20): String {
        if (!isInstalled()) {
            return buildString {
                appendLine("memtrace 未安装 ⇒ native 分配归因不可用（其余内存监控不受影响）")
                appendLine("  loadError    = ${MemTraceNative.loadError ?: "(已加载成功)"}")
                appendLine("  installError = ${MemTraceNative.installError ?: "(无)"}")
                appendLine("⚠️ 本机 ndk.abiFilters=arm64-v8a ⇒ 非 arm64 设备上必然不可用，属正常降级。")
            }
        }
        // 出报告前暂停记账：报告本身要 malloc 几 KB 文本，不暂停会被记进站点表，
        // **污染我们自己正在看的数字**。
        MemTraceNative.pause(true)
        val text = runCatching { MemTraceNative.report(maxSites) }.getOrNull()
            ?: "report 返回 null（native 侧失败，见 logcat TAG=MemTrace）"
        MemTraceNative.pause(false)
        lastReport = text
        lastReportAt = System.currentTimeMillis()
        return text
    }

    fun reset() {
        MemTraceNative.reset()
        lastReport = null
    }

    fun status(): String = buildString {
        appendLine("── Native 分配归因（memtrace / bytehook）──")
        appendLine("  已安装：${if (isInstalled()) "是" else "否"}")
        appendLine("  加载错误：${MemTraceNative.loadError ?: "无"}")
        appendLine("  安装错误：${MemTraceNative.installError ?: "无"}")
        appendLine("  native 读数：${MemTraceNative.status() ?: "(库未加载)"}")
        appendLine("  分配器：${lastAllocatorStats.let {
            if (it.available) "used=${MemoryMetrics.fmt(it.usedBytes)} free=${MemoryMetrics.fmt(it.freeBytes)} mmap=${MemoryMetrics.fmt(it.mmapBytes)}"
            else "不可用"
        }}")
        appendLine("  读数次数：ok=${cacheHits.get()} miss=${cacheMisses.get()}")
        appendLine("  最近报告：${if (lastReport == null) "（未生成）" else "${(System.currentTimeMillis() - lastReportAt) / 1000}s 前，${lastReport!!.length} 字符"}")
        appendLine()
        appendLine("  ⚠️ 有损采样：全量 >=8KB；[64B,8KB) 抽样 1/256；拿不到锁则跳过")
        appendLine("     （见 app/src/main/cpp/memtrace.cpp 文件头 §三 的三条取舍）")
    }

    /**
     * 归因报告 → **结构化站点列表**（实验页要排序展示，不能只吐文本）。
     *
     * ⚠️ 解析的是我们自己生成的格式（`memtrace.cpp::reportNative`），按行前缀严格解析；
     *    但**任何解析失败都退化为空列表 + 原文展示**，绝不给出"半截数字"。
     */
    data class Site(
        val rank: Int,
        val liveBytes: Long,
        val liveCount: Long,
        val peakBytes: Long,
        val totalBytes: Long,
        val frames: List<String>,
        val isOther: Boolean,
    )

    fun parseReport(text: String): List<Site> = runCatching {
        val sites = ArrayList<Site>()
        var cur: Site? = null
        val frameBuf = ArrayList<String>()
        fun flush() {
            cur?.let { sites.add(it.copy(frames = frameBuf.toList())) }
            frameBuf.clear()
            cur = null
        }
        text.lineSequence().forEach { line ->
            when {
                line.startsWith("site#") -> {
                    flush()
                    // site#0 bytes=123(1.23 MB) count=4 peak=1.50 MB total=9.00 MB frames=4
                    cur = Site(
                        rank = line.substringAfter("site#").substringBefore(' ').toIntOrNull() ?: 0,
                        liveBytes = numAfter(line, "bytes="),
                        liveCount = numAfter(line, "count="),
                        peakBytes = mbAfter(line, "peak="),
                        totalBytes = mbAfter(line, "total="),
                        frames = emptyList(),
                        isOther = line.contains("[OTHER"),
                    )
                }
                line.trimStart().startsWith("frame[") -> {
                    frameBuf.add(line.substringAfter(']').trim())
                }
            }
        }
        flush()
        sites
    }.getOrElse {
        Log.w(TAG, "报告解析失败（退化为原文展示）", it)
        emptyList()
    }

    /** 取 `key=` 之后到第一个非数字字符之前的值（整数）。 */
    private fun numAfter(line: String, key: String): Long =
        line.substringAfter(key, "").takeWhile { it.isDigit() }.toLongOrNull() ?: 0L

    /** 取 `key=` 之后的 MB 浮点数（形如 `1.50 MB`）→ 字节。 */
    private fun mbAfter(line: String, key: String): Long {
        val seg = line.substringAfter(key, "").takeWhile { it.isDigit() || it == '.' }
        return ((seg.toDoubleOrNull() ?: 0.0) * 1024 * 1024).toLong()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 路径二：Bitmap 图形内存（线上 OOM 的头号成因）
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 位图追踪器：统计**我们自己的代码路径**创建的位图。
 *
 * ─── 为什么这条最值钱 ───
 *
 * 线上 OOM 的成因排序（业界一致，本项目 image Lab 亦有实测）：**位图 > 集合未清理 >
 * 长生命周期回调持有 Activity > native 泄漏**。位图有个特殊性：**它的内存在
 * Java 堆上"看不见"**（API 26+ 后像素数据在 native 堆），
 * 所以"Java 堆没涨却 OOM 了"这个最迷惑人的现象，十有八九是位图。
 *
 * ─── 为什么不用 `WeakHashMap<Bitmap, String>` 记来源 ───
 *
 * 直觉做法是弱引用表。但：
 *   · `WeakHashMap` 的 get/put 有同步开销，且**key 的弱引用语义与 Bitmap 的
 *     native 内存回收时机不一致**（Bitmap 被 GC 后像素可能还没还）——
 *     在最需要准确的时刻恰恰不准；
 *   · 它会让"追踪器自己"成为内存压力的参与者。
 *
 * ⇒ 改为**分类计数**（按来源累加字节/张数），**不持有任何 Bitmap 引用**。
 *
 * ⚠️ **诚实边界**：计数依赖调用方老实调用。Glide 内部创建的位图**不经过这里**
 *    （除非挂 `RequestListener`）。所以 `本计数 ≤ 系统 summary.graphics`，
 *    **差额 = 三方库 + 系统资源**，差额本身是信息。
 */
object BitmapTracker {

    private val totalBytes = AtomicLong(0)
    private val liveCount = AtomicInteger(0)
    private val createdTotal = AtomicLong(0)
    private val recycledTotal = AtomicLong(0)
    private val peak = AtomicLong(0)

    private val bySourceBytes = ConcurrentHashMap<String, AtomicLong>()
    private val bySourceCount = ConcurrentHashMap<String, AtomicInteger>()
    private val byConfigBytes = ConcurrentHashMap<String, AtomicLong>()

    @Volatile
    var enabled: Boolean = true

    /**
     * 记录一次位图创建。
     *
     * ⚠️ 字节数必须用 `allocationByteCount` 而不是 `byteCount`：
     *    · `byteCount` = 宽×高×每像素字节（**逻辑**尺寸）
     *    · `allocationByteCount` = **实际分配**的内存
     *    `inBitmap` 复用时（Glide 的 BitmapPool 就是这么做的）两者会不一致，
     *    用 `byteCount` 会**系统性低估**真实占用 —— 在"降采样到底省了多少"的
     *    对照实验里会直接给出错误结论。
     */
    fun onBitmapCreated(bitmap: Bitmap, source: String) {
        if (!enabled) return
        val bytes = sizeOf(bitmap)
        totalBytes.addAndGet(bytes)
        liveCount.incrementAndGet()
        createdTotal.incrementAndGet()
        bySourceBytes.computeIfAbsent(source) { AtomicLong() }.addAndGet(bytes)
        bySourceCount.computeIfAbsent(source) { AtomicInteger() }.incrementAndGet()
        byConfigBytes.computeIfAbsent(configOf(bitmap)) { AtomicLong() }.addAndGet(bytes)
        // 峰值用 CAS 循环：并发创建时不能丢峰值，而峰值恰恰出现在并发最多的时刻
        var p = peak.get()
        while (true) {
            val now = totalBytes.get()
            if (now <= p) break
            if (peak.compareAndSet(p, now)) break
            p = peak.get()
        }
    }

    /** 记录一次位图回收（`Bitmap.recycle()` 或被 GC 后的显式销账）。 */
    fun onBitmapRecycled(bitmap: Bitmap, source: String) {
        if (!enabled) return
        val bytes = sizeOf(bitmap)
        totalBytes.addAndGet(-bytes)
        liveCount.decrementAndGet()
        recycledTotal.incrementAndGet()
        bySourceBytes[source]?.addAndGet(-bytes)
        bySourceCount[source]?.decrementAndGet()
        byConfigBytes[configOf(bitmap)]?.addAndGet(-bytes)
    }

    private fun sizeOf(b: Bitmap): Long = runCatching { b.allocationByteCount.toLong() }
        .getOrElse { b.width.toLong() * b.height * 4 }

    private fun configOf(b: Bitmap): String =
        runCatching { b.config?.name ?: "UNKNOWN" }.getOrDefault("UNKNOWN")

    fun currentBytes(): Long = totalBytes.get()
    fun currentCount(): Int = liveCount.get()
    fun peakBytes(): Long = peak.get()
    fun createdCount(): Long = createdTotal.get()
    fun recycledCount(): Long = recycledTotal.get()

    fun reset() {
        totalBytes.set(0); liveCount.set(0); createdTotal.set(0)
        recycledTotal.set(0); peak.set(0)
        bySourceBytes.clear(); bySourceCount.clear(); byConfigBytes.clear()
    }

    /** 按来源排序（谁占得多排前面）—— 这是"该动谁"的直接答案。 */
    fun bySource(): List<Triple<String, Long, Int>> =
        bySourceBytes.entries
            .map { (k, v) -> Triple(k, v.get(), bySourceCount[k]?.get() ?: 0) }
            .sortedByDescending { it.second }

    fun byConfig(): List<Pair<String, Long>> =
        byConfigBytes.entries.map { it.key to it.value.get() }.sortedByDescending { it.second }

    /**
     * 与系统口径对照。
     *
     * ⚠️ **差额是正常的、且是有信息的**：这里只统计经过我们代码的位图，
     *    而 `summary.graphics` 是系统对**整个进程**图形内存的归类
     *    （含 Glide 池、系统资源、Surface…）。差额大 ⇒
     *    "图形内存主要不是我们创建的" ⇒ 该往三方库/系统侧找。
     */
    fun describe(systemGraphicsKb: Int): String = buildString {
        appendLine("── 位图（本项目代码路径计数）──")
        appendLine("  当前存活：${currentCount()} 张 · ${MemoryMetrics.fmt(currentBytes())}")
        appendLine("  历史峰值：${MemoryMetrics.fmt(peakBytes())}（并发最多的时刻 —— 比当前值更能解释 OOM）")
        appendLine("  累计创建 ${createdCount()} 张 / 回收 ${recycledCount()} 张")
        if (bySource().isNotEmpty()) {
            appendLine("  按来源：")
            bySource().take(8).forEach { (src, bytes, n) ->
                appendLine("    %-26s %12s  (%d 张)".format(src, MemoryMetrics.fmt(bytes), n))
            }
        }
        if (byConfig().isNotEmpty()) {
            appendLine("  按 Config：")
            byConfig().take(6).forEach { (cfg, bytes) ->
                appendLine("    %-10s %s".format(cfg, MemoryMetrics.fmt(bytes)))
            }
        }
        val sys = systemGraphicsKb.toLong() * 1024
        appendLine("  系统口径 summary.graphics：${MemoryMetrics.fmtKb(systemGraphicsKb)}")
        if (sys > 0) {
            val mine = currentBytes()
            val gap = sys - mine
            appendLine("  差额（三方库/系统资源）：${MemoryMetrics.fmt(gap)}" +
                    if (gap > mine) "   ← 图形内存的大头**不在我们的代码路径**上" else "")
        } else {
            appendLine("  ⚠️ 系统未提供 graphics 统计（无 GPU 驱动上报 / API 26 以下）——" +
                    "此处只能看我们自己的计数")
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 路径三：Java 堆直方图（hprof）—— 解析器本体在 `HeapHistogramParser.kt`
//
// ⚠️ 刻意拆成独立文件：那个文件的体量（子 tag 宽度表 + 两处实测纠正的完整推导）
//    与这里的三条路径导航混在一起会让两边都难读。
//    这里只留与「调用时机」和「结论」相关的两部分：闸门与差分。
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 抓 dump 的**并发/成本闸门**：同一时刻只允许一次，且两次之间有最小间隔。
 *
 * ⚠️ 为什么必须有：`dumpHprofData` 是 stop-the-world 的 ——
 *    并发两次 dump 不会有"两倍信息"，只会有**两倍的冻结时间**（且第二次大概率失败）。
 *    它同时是**成本闸门**：dump 一次几十 MB + 秒级冻结，
 *    没有闸门的自动化触发会在内存告急时把自己压死。
 */
object HeapDumpGate {

    private val running = AtomicBoolean(false)

    @Volatile
    var lastDumpAt: Long = 0L
        private set

    @Volatile
    var lastReason: String = ""
        private set

    @Volatile
    var lastResult: MemoryHeapAnalyzer.Histogram? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** 最小间隔：默认 10 分钟（这是"取证"不是"监控"；生产建议更久） */
    @Volatile
    var minIntervalMs: Long = 10 * 60 * 1000L

    @Volatile
    var enabled: Boolean = true

    fun canDump(): Boolean =
        enabled && !running.get() && (System.currentTimeMillis() - lastDumpAt) > minIntervalMs

    /**
     * 在后台泳道抓一次并解析。返回 false = 被闸门挡住 / 已在运行 / 泳道提交失败。
     *
     * @param onDone 在**后台线程**回调（实验页自己负责切回主线程刷 UI）
     */
    fun request(
        context: Context,
        reason: String,
        onDone: (MemoryHeapAnalyzer.Histogram?) -> Unit = {},
    ): Boolean {
        if (!canDump()) return false
        if (!running.compareAndSet(false, true)) return false
        lastDumpAt = System.currentTimeMillis()
        lastReason = reason
        lastError = null
        val accepted = ThreadPools.background.execute("memory.hprof") {
            try {
                val (file, h) = MemoryHeapAnalyzer.dumpAndParse(context, reason)
                lastResult = h
                MemoryHeapAnalyzer.cleanupOldDumps(context, keep = 2)
                Log.w(TAG, "Top3=${h.top(3).joinToString { "${it.className}(${MemoryMetrics.fmt(it.totalBytes)})" }}")
                Log.w(TAG, "dump 保留在 ${file.absolutePath}（超出 2 份自动清理）")
                onDone(h)
            } catch (t: Throwable) {
                // 取证动作失败必须可见 —— 与采样的静默降级刻意不同
                lastError = "${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, "抓/解析 hprof 失败（reason=$reason）", t)
                onDone(null)
            } finally {
                running.set(false)
            }
        }
        if (!accepted) {
            // 泳道饱和（配额/队列满）：把闸门放回去，别让"提交失败"白白吃掉一个窗口期
            running.set(false)
            lastDumpAt = 0L
        }
        return accepted
    }

    fun describe(): String = buildString {
        appendLine("── Java 堆直方图（hprof 取证）──")
        appendLine("  开关=${enabled}  最小间隔=${minIntervalMs / 60000}min  运行中=${running.get()}")
        appendLine("  上次：${if (lastDumpAt == 0L) "从未" else "${(System.currentTimeMillis() - lastDumpAt) / 1000}s 前，reason=$lastReason"}")
        appendLine("  上次结果：${lastResult?.let { "${it.classCount} 个类 / ${MemoryMetrics.fmt(it.totalBytes)} / 解析 ${it.durationMs}ms" } ?: "（无）"}")
        lastError?.let { appendLine("  上次错误：$it") }
        appendLine("  ⚠️ dumpHprofData 会冻结进程（0.5~2s）并产出几 MB~几十 MB 文件；")
        appendLine("     所以它只在**显式触发 + 去重窗口**下跑，绝不挂在采样周期上。")
    }
}

/**
 * 直方图差分：回答「这次操作里，**是哪些类**涨了」。
 *
 * 这是最有价值的一次读数：单张直方图只能看出"谁最大"（可能一直如此、属正常），
 * 而**差分**直接指出"谁在这次操作中涨的"——那才是要动手改的地方。
 *
 * 纯函数、无 Android 依赖 ⇒ 可以在宿主 JVM 上做单测（见 `MemoryHeapAnalyzerTest`）。
 */
object HeapDiff {

    fun diff(
        before: MemoryHeapAnalyzer.Histogram,
        after: MemoryHeapAnalyzer.Histogram,
        minDeltaBytes: Long = 256 * 1024,
    ): List<MemoryHeapAnalyzer.ClassStat> {
        val beforeMap = before.stats.associateBy { it.className }
        val afterMap = after.stats.associateBy { it.className }
        val out = ArrayList<MemoryHeapAnalyzer.ClassStat>()
        (beforeMap.keys + afterMap.keys).forEach { name ->
            val b = beforeMap[name]
            val a = afterMap[name]
            val deltaBytes = (a?.totalBytes ?: 0L) - (b?.totalBytes ?: 0L)
            val deltaCount = (a?.instanceCount ?: 0L) - (b?.instanceCount ?: 0L)
            if (abs(deltaBytes) >= minDeltaBytes) {
                out.add(MemoryHeapAnalyzer.ClassStat(name, deltaCount, deltaBytes))
            }
        }
        return out.sortedByDescending { it.totalBytes }
    }
}
