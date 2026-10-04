package com.interview.image

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.Trace
import android.util.Log
import android.util.LruCache
import com.interview.image.nativebridge.ImagePipelineBridge
import com.interview.thread.ThreadPools

/**
 * Time: 2026/10/4
 * Author: wgt
 * Description: 用 Rust 流水线给瀑布流卡片算「主色调占位色」。
 *
 * ─── 这个功能为什么存在（不是为凑一个 Rust 用途）───
 *
 * 瀑布流原有的体验断点很具体：item 先 inflate 出来，图片走网络 → 解码，
 * 这中间卡片是空的灰底（`item_image_card.xml` 里 ImageView 的 background）。
 * 若能在**图片到达之前**先铺一层「这张图大概什么颜色」，列表的观感会明显不同。
 *
 * 而「大概什么颜色」这件事，恰恰是 M3 实测里 Rust 唯一稳定大幅领先的算子
 * （主色调 2.5×，且不随尺寸衰减）。所以这里不是「为了用 Rust 而用」，
 * 而是**把 Rust 的优势算子放到了它最合适的位置**。
 *
 * ─── 与 M3 结论对齐的两个实现决策 ───
 *
 * 1. **不走 M3 里更快的降采样路径**：降采样在纯计算上是 0.9×（更慢），
 *    它端到端赢靠的是零拷贝数据通路。而这里手上只有一张解码后的 Bitmap，
 *    要得到主色调必须先取像素 —— 直接调 dominantColor 即可，不需要降采样。
 * 2. **跑在 `ThreadPools.cpu` 而不是自己起线程**：这是仓库的线程治理红线
 *    （`thread-lint` 的三条规则会把 new Thread / Executors / HandlerThread 判为 error）。
 *    native 计算本身不占主线程，但它**必须**由既有泳道调度，否则全 App 的
 *    线程命名、配额与背压治理在 native 这条路上就失效了。
 *
 * ─── 为什么要有缓存与去重 ───
 *
 * 瀑布流会回收复用 item：同一张图滑走再滑回来，`onBindViewHolder` 会重新触发。
 * 若每次都重算，滚动时同一张图会被反复算 N 次，既浪费又让耗时统计失真。
 * 所以：按 spec.id 缓存结果；同一 id 在途时的重复请求直接并入，不重复提交。
 */
object PlaceholderPalette {

    private const val TAG = "ImageLab"

    /** 主色调是小整数，缓存成本极低；64 条足够覆盖几屏。 */
    private val cache = LruCache<String, Int>(64)

    /** 在途去重：正在算的 id 集合（用 cache 自身当锁，避免多一把锁）。 */
    private val inflight = HashSet<String>()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 一次主色调计算的结果，带上「证据」字段供日志与结论引用。 */
    data class Result(
        val id: String,
        /** 不透明的主色调，`0xFFRRGGBB`。 */
        val argb: Int,
        /** 本次是否真的走了 Rust（false 表示回退到 Java 参考实现）。 */
        val usedNative: Boolean,
        /** 计算耗时（毫秒）；缓存命中时为 0。 */
        val elapsedMs: Double,
        /** 是否命中缓存。 */
        val cached: Boolean,
    )

    /** 已缓存的主色调（尚未算出则返回 null）；用于 bind 时立即铺色，避免闪一下。 */
    fun peek(id: String): Int? = cache.get(id)

    /**
     * 异步提取主色调。**回调一定在主线程**（调用方要更新 UI）。
     *
     * @return true 表示本次真的提交了新计算；false 表示命中缓存或已有在途任务
     *         （此时若命中缓存，回调仍会被调用一次，方便调用方统一处理）。
     */
    fun extractAsync(id: String, bitmap: Bitmap, callback: (Result) -> Unit): Boolean {
        cache.get(id)?.let { argb ->
            val r = Result(id, argb, usedNative = true, elapsedMs = 0.0, cached = true)
            mainHandler.post { callback(r) }
            return false
        }
        synchronized(cache) {
            if (!inflight.add(id)) return false // 已有在途任务，复用它的结果
        }

        val accepted = try {
            ThreadPools.cpu.execute(Runnable { compute(id, bitmap, callback) })
            true
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // cpu 泳道是有界队列（见 ThreadPools.CPU_QUEUE_CAPACITY），队列满会拒绝。
            // 这是有意的背压，不能静默吞掉 —— 至少要清掉在途标记，
            // 否则这个 id 之后永远算不出主色调。
            synchronized(cache) { inflight.remove(id) }
            Log.w(TAG, "主色调任务被 cpu 泳道拒绝 id=$id（队列满），本次不铺占位色")
            false
        }
        return accepted
    }

    /** 实际计算。独立成方法是为了让 [extractAsync] 的提交逻辑保持可读。 */
    private fun compute(id: String, bitmap: Bitmap, callback: (Result) -> Unit) {
        val t0 = android.os.SystemClock.elapsedRealtimeNanos()
        Trace.beginSection("rustDominantColor")
        val outcome = try {
            ImagePipelineBridge.dominantColorOutcome(bitmap, preferNative = true)
        } catch (t: Throwable) {
            Log.w(TAG, "主色调计算失败 id=$id：${t.message}")
            null
        } finally {
            Trace.endSection()
        }
        val elapsedMs = (android.os.SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000.0

        synchronized(cache) { inflight.remove(id) }

        if (outcome == null || outcome.color < 0) {
            Log.w(TAG, "主色调结果无效 id=$id")
            return
        }
        // dominantColor 返回 0xRRGGBB（24 位）；占位色必须不透明，
        // 否则卡片底下会透出背景，看起来像「颜色没铺上」。
        val argb = 0xFF000000.toInt() or (outcome.color and 0x00FFFFFF)
        cache.put(id, argb)

        Log.i(
            TAG,
            "占位色 id=$id color=#%06X path=%s 耗时=%.2fms"
                .format(outcome.color and 0xFFFFFF, if (outcome.usedNative) "rust" else "java", elapsedMs),
        )
        val r = Result(id, argb, outcome.usedNative, elapsedMs, cached = false)
        mainHandler.post { callback(r) }
    }

    /** 测试与「重置」用。 */
    fun clear() {
        cache.evictAll()
        synchronized(cache) { inflight.clear() }
    }

    /** 暴露给测试/结论引用的缓存条数。 */
    fun cachedCount(): Int = cache.size()
}
