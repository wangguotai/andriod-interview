package com.interview.稳定性监控

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Time: 2026/10/7
 * Author: wgt
 * Description: 线上卡顿监控 —— 帧级 / 消息级 两层，各管各的问题
 *
 * ══════════════════════════════════════════════════════════════════════
 * 一、先把"卡顿"这个模糊词拆成三个可度量的东西
 * ══════════════════════════════════════════════════════════════════════
 *
 * ```
 *   ① 帧率（FPS）      —— 一秒画了多少帧。**最没用的指标**：掉到 55 还是 30
 *                        感知差异巨大，而它的均值恒在 60 附近；且"卡 3 帧"
 *                        和"卡 300ms 一次"对它几乎等价。
 *   ② 单帧耗时 / jank  —— 某一帧是否超了预算（60Hz=16.6ms，120Hz=8.3ms）。
 *                        这是**用户真正感知到**的东西。
 *   ③ 无响应时长       —— 主线程连续多久不处理输入（决定"点不动"的体感）。
 *                        由 AnrMonitor 的哨兵负责，**不是本类**。
 * ```
 *
 * 所以本项目的卡顿监控不报 FPS，只报 ②：**超预算的帧 + 它的耗时分段**。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 二、基于什么测：三种取子的取舍（这是面试最常追问的点）
 * ══════════════════════════════════════════════════════════════════════
 *
 * | 手段 | 粒度 | 能否拆时段 | 开销 | 线上可用 |
 * |---|---|---|---|---|
 * | `Choreographer.postFrameCallback` | 帧回调被调用的**间隔** | ❌ 只有总时长 | 极低 | ✅ |
 * | `Window.OnFrameMetricsAvailableListener` | 每帧的**完整流水线分解** | ✅ 是 | 低 | ✅ |
 * | `androidx.metrics:metrics-performance`(JankStats) | 帧 + **界面上下文**（哪个页面） | 部分 | 低 | ✅ |
 * | Perfetto / FrameTimeline | 含系统侧（SurfaceFlinger）判定 | ✅ | — | ❌ 线下 |
 *
 * 本项目选 **FrameMetrics**，理由：
 *
 * 1. **只有它能"拆时段"**。`FrameMetrics` 把一帧拆成
 *    `input → animation → layout/measure → draw → sync → command issue → swap buffers`
 *    每一段一个 `getMetric()`。这直接回答了「是布局慢、绘制慢、还是提交慢」——
 *    而 Choreographer 回调间隔只能告诉你"这一帧 40ms 慢"，
 *    下一步只能靠人肉猜/抓 Perfetto。
 * 2. **它同时给了 deadline**。`FrameMetrics.DEADLINE` 是**系统认为这一帧该在什么时候完成**
 *    （随刷新率变化）。用「`TOTAL_DURATION` vs `DEADLINE - INTENDED_VSYNC`」判定，
 *    就**自动适配 60/90/120Hz**，不需要写死 16.6ms —— 写死 16.6 在 120Hz 设备上
 *    会把一半的正常帧判成卡顿，这是很多自研方案的翻车点。
 * 3. JankStats 更"产品化"（自带启发式 + 界面上下文），但多一个依赖，
 *    且它的启发式我们无法在面试里讲清"为什么这么判"。本项目选**可自证**的路径。
 *
 * ⚠️ **FrameMetrics 拿不到系统侧归因。** 真机/模拟器对照过的一件事
 *    （见本仓库 `tools/perfetto/INTERVIEW-perfetto.md`）：Perfetto 的 `jank_type`
 *    里"App Deadline Missed"才是我们该管的，而"SurfaceFlinger .../Buffer Stuffing"
 *    是合成侧；**同一份代码，模拟器上后者占绝对多数，真机上前者占绝对多数**。
 *    FrameMetrics 只覆盖 app 侧那一半 → **它的 jank 数 ≤ Perfetto 的 jank 数**。
 *    这不是缺陷，是「线上只关心自己能不能改」的正确取舍。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 三、三个必须处理的工程细节
 * ══════════════════════════════════════════════════════════════════════
 *
 * **细节 1：回调在哪个线程？**
 * `addOnFrameMetricsAvailableListener(listener, handler)` 的第二个参数决定回调线程：
 * 传 `Handler(Looper.getMainLooper())` → **回调在主线程**！每帧回调一次，
 * 任何在回调里做的重活（格式化字符串、抓堆栈、锁竞争）都会**变成卡顿源**。
 * 本类的处理：回调里只做**纯算术 + 往无锁队列塞一个不可变对象**，
 * 聚合/上报全部丢给 bg 泳道。见 [onFrameMetricsAvailable]。
 *
 * **细节 2：FrameMetrics 对象会被复用。**
 * 回调收到的 `FrameMetrics` 实例**可能是同一个对象被反复填充**（官方文档明确：
 * 不要持有它，也不要在回调外使用）。所以必须**在回调内**把需要的 metric
 * 读成自己的 long，否则你会拿到"最后一帧"的值覆盖前面所有帧 ——
 * 这是一类**静默数据错误**（数据看着有，但全是错的）。
 * 本实现用 [FrameSample.from] 立刻取值成不可变对象。
 *
 * **细节 3：API 级别。**
 * `addOnFrameMetricsAvailableListener` 是 **API 24+**（项目 minSdk=24，正好可用）。
 * 但 `FrameMetrics.DEADLINE` 常量是 **API 31+** 引入的。
 * → 低版本只能退化成「用 `TOTAL_DURATION` 与一个从刷新率推出来的预算比」。
 *   本类做了这层降级，并且**在输出里标明用的是哪种判据**（不要让人误以为
 *   低版本和高版本的数据可直接比较）。
 */
object JankMonitor {

    private const val TAG = "JankMonitor"

    /** 帧耗时超过预算的这个倍数才算"值得上报"，避免把临界帧全灌进上报通道 */
    private const val JANK_FACTOR = 2.0

    /** 兜底帧预算（60Hz）。仅当拿不到 DEADLINE（API < 31）时使用 */
    private const val FALLBACK_BUDGET_MS = 16.6

    /** 环形缓冲里最多留多少帧样本（本类自己的，用于算分位数） */
    private const val SAMPLE_CAPACITY = 1200

    private val installed = newAtomicFalse()
    private val samples = ConcurrentLinkedQueue<FrameSample>()
    private val totalFrames = AtomicLong(0)
    private val jankFrames = AtomicLong(0)

    /** 帧数统计里有多少帧的 TOTAL_DURATION 是「未指定」（见 [FrameSample.SANE_MAX_MS]） */
    private val unspecifiedFrames = AtomicLong(0)

    /** 每帧开销最大的一段。用于回答"慢了是慢在哪" */
    private val slowSegment = AtomicLong(0)

    private fun newAtomicFalse() = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * 一帧的样本。
     *
     * ⚠️ **不可变**，且在回调内就完成取值（见类注释细节 2：FrameMetrics 会被复用）。
     * 字段全部是 long/int，避免在回调里产生任何分配以外的工作。
     */
    class FrameSample(
        val totalMs: Long,
        val inputMs: Long,
        val animationMs: Long,
        val layoutMeasureMs: Long,
        val drawMs: Long,
        val syncMs: Long,
        val commandIssueMs: Long,
        val swapBuffersMs: Long,
        val unknownDelayMs: Long,
        /** 系统给的帧预算（DEADLINE - INTENDED_VSYNC_START），API 31+ 才有 */
        val budgetMs: Long,
        val isFirstDraw: Boolean,
        /** 这一帧的 TOTAL_DURATION 是否为「未指定」（见 [SANE_MAX_MS]）。true 时不可参与统计 */
        val totalUnspecified: Boolean = false,
    ) {
        val isJank: Boolean get() = !totalUnspecified && totalMs > budgetMs * JANK_FACTOR

        /** 找出最耗时的那一段 —— 归因的第一步 */
        fun dominantSegment(): Pair<String, Long> {
            val segs = listOf(
                "input(输入分发)" to inputMs,
                "animation(动画)" to animationMs,
                "layout/measure(布局)" to layoutMeasureMs,
                "draw(绘制)" to drawMs,
                "sync(渲染线程同步)" to syncMs,
                "commandIssue(命令提交)" to commandIssueMs,
                "swapBuffers(交换缓冲)" to swapBuffersMs,
                "unknownDelay(等待/未知)" to unknownDelayMs,
            )
            return segs.maxByOrNull { it.second } ?: ("none" to 0)
        }

        fun describe(): String {
            val (name, ms) = dominantSegment()
            return "帧 ${totalMs}ms（预算 ${budgetMs}ms，超 ${"%.1f".format(totalMs.toDouble() / budgetMs)}x）" +
                    "｜最耗时段：$name ${ms}ms"
        }

        companion object {
            /**
             * 合理的单帧耗时上限（毫秒）。超过它即认为该 metric **未被系统填充**。
             *
             * ─── 这个常量是怎么来的（不是拍脑袋）───
             *
             * `FrameMetrics.getMetric()` 在指标未填充时会返回**未指定**的哨兵值
             * （其量级约为 `Long.MAX_VALUE` / 那个巨大的纳秒数）。本机实测泄漏出来的
             * 数字是 `9221320829010ms` —— 它在输出里长这样：
             *
             *     帧 9221320829010ms（预算 16ms，超 576332551813.1x）｜最耗时段：draw(绘制) 3ms
             *
             * 荒谬的是它**同时**说"这一帧 292 年"和"最耗时段只有 3ms"——
             * 因为 `TOTAL_DURATION` 是哨兵，而 `DRAW_DURATION` 是真实的。
             * 如果不加这道闸门，这几个离群值会把 P99 / max / 平均值**全部带偏**，
             * 而且它不会报错、不会崩溃，只是安静地给你错的报告。
             *
             * 取 10 分钟（600_000ms）：这已经远超任何真实帧（哪怕是 15 秒 ANR 期间
             * 那一帧也就 ~15000ms），因此不会误杀；同时能把哨兵值全部挡住。
             */
            const val SANE_MAX_MS = 600_000L

            /** 纳秒 → 毫秒，并把「未指定」折叠成 0 并标记出来 */
            private fun ms(ns: Long): Long =
                if (ns < 0 || ns / 1_000_000 > SANE_MAX_MS) 0L else ns / 1_000_000

            private fun unspecified(ns: Long): Boolean =
                ns < 0 || ns / 1_000_000 > SANE_MAX_MS

            /**
             * 从系统给的 FrameMetrics 取出**我们自己**的不可变样本。
             *
             * 这里逐字段调用 `getMetric()` 而不是持有 FrameMetrics —— 见类注释细节 2。
             */
            fun from(m: FrameMetrics): FrameSample {
                val intendedVsync = m.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
                val deadline = if (Build.VERSION.SDK_INT >= 31) {
                    runCatching { m.getMetric(FrameMetrics.DEADLINE) }.getOrDefault(0L)
                } else 0L
                val totalRaw = m.getMetric(FrameMetrics.TOTAL_DURATION)
                // ⚠️ 单位是**纳秒**。整数除法到 ms；低于 1ms 的段会变成 0，
                //    这对"哪段最耗时"的归因没有影响（不会出现"0ms 是瓶颈"的错误结论）。
                val budgetNs = if (deadline > 0 && intendedVsync > 0) deadline - intendedVsync else 0L
                return FrameSample(
                    totalMs = ms(totalRaw),
                    inputMs = ms(m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION)),
                    animationMs = ms(m.getMetric(FrameMetrics.ANIMATION_DURATION)),
                    layoutMeasureMs = ms(m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)),
                    drawMs = ms(m.getMetric(FrameMetrics.DRAW_DURATION)),
                    syncMs = ms(m.getMetric(FrameMetrics.SYNC_DURATION)),
                    commandIssueMs = ms(m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION)),
                    swapBuffersMs = ms(m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)),
                    unknownDelayMs = ms(m.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION)),
                    budgetMs = if (budgetNs > 0) ms(budgetNs).coerceAtLeast(1) else FALLBACK_BUDGET_MS.toLong(),
                    isFirstDraw = m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L,
                    totalUnspecified = unspecified(totalRaw),
                )
            }
        }
    }

    /** 当前监视的窗口（FrameMetrics 是**按 Window** 注册的，不是按进程） */
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    private var watcher: Window? = null

    /**
     * 挂到指定 Activity 的 Window 上。
     *
     * ─── 为什么是「按 Window」而不是「全局」───
     *
     * `FrameMetrics` 的数据源是 `Choreographer` + 渲染流水线的埋点，注册单位就是 Window。
     * 推论（重要）：**没有 Window 的时刻（纯后台、启动早期）没有帧数据**，
     * 所以「冷启动卡顿」用这条链路**测不到**（那时首帧还没走完）。
     * 冷启动要用 `Activity` 生命周期打点 + `ApplicationExitInfo` 的启动时长，
     * 这是**两条不同的监控链路**，不能互相替代。
     *
     * @param handler 回调用的 Handler。**默认给一个专用线程**（见 [callbackHandler]），
     *                而不是主线程 —— 见类注释细节 1。
     */
    fun watch(activity: Activity) {
        if (Build.VERSION.SDK_INT < 24) {
            Log.w(TAG, "API < 24 不支持 FrameMetrics，跳过（降级为无帧级数据）")
            return
        }
        // 同一个 Activity 重复 watch 要先去重，否则一个窗口会被注册多次 → 每帧回报多次
        if (installed.get() && watcher === activity.window) return
        stop()

        val l = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            // ⚠️⚠️ 这里在**回调线程**上执行，且**每帧一次**（60~120 次/秒）。
            // 任何在此处的分配/字符串/抓堆栈都会变成新的卡顿源。
            // 所以：只做「取值（必须的，见细节 2）+ 一次不可变对象分配 + 入队」。
            val sample = FrameSample.from(metrics)
            totalFrames.incrementAndGet()
            if (sample.totalUnspecified) {
                // 不做统计、也不进样本队列 —— 它会把 P99/max 全带偏（见 SANE_MAX_MS）
                unspecifiedFrames.incrementAndGet()
                return@OnFrameMetricsAvailableListener
            }
            if (sample.isJank) {
                jankFrames.incrementAndGet()
                samples.add(sample)
                while (samples.size > SAMPLE_CAPACITY) samples.poll()
            }
        }
        activity.window.addOnFrameMetricsAvailableListener(l, frameCallbackHandler)
        listener = l
        watcher = activity.window
        installed.set(true)
        Log.i(TAG, "已挂载帧监控：${activity.javaClass.simpleName}（回调线程=${CALLBACK_THREAD_NAME}）")
    }

    fun stop() {
        val w = watcher
        val l = listener
        if (w != null && l != null) {
            runCatching { w.removeOnFrameMetricsAvailableListener(l) }
        }
        listener = null
        watcher = null
        installed.set(false)
    }

    // ─────────────────────────────────────────────
    // 回调线程：专用受治理线程 + Looper
    // ─────────────────────────────────────────────

    private const val CALLBACK_THREAD_NAME = "app-frame-metrics"

    /**
     * 给 FrameMetrics 回调用的**专用 Looper 线程**。
     *
     * ─── 为什么不给主线程的 Handler ───
     *
     * 官方文档对 `addOnFrameMetricsAvailableListener` 的 handler 参数有一条
     * 明确警告：**回调必须快速返回，否则会影响该线程**。若传主线程 Handler，
     * 就等于把"每帧一次的额外工作"塞进主线程的帧预算里 ——
     * **监控工具本身成了卡顿来源**，而且会污染我们正在测量的数据。
     *
     * 所以用一个常驻的专用 Looper 线程（走收口层的 `looperThread`，
     * 这正是「收口层必须提供独占线程出口」的场景之一）。
     * 它带来的成本：**多一个常驻线程**。这是需要权衡的 ——
     * 若项目对线程数极度敏感，可以退化成"回调塞进已有的 bg Looper"，
     * 但**不能**退化成主线程。
     */
    private val frameCallbackHandler: Handler by lazy {
        val looper = com.interview.thread.ThreadPools.looperThread(
            name = "frame-metrics",
            priority = Process.THREAD_PRIORITY_BACKGROUND,
        )
        Handler(looper)
    }

    // ─────────────────────────────────────────────
    // 聚合 / 上报
    // ─────────────────────────────────────────────

    /**
     * 汇总并上报。**由调用方在 bg 泳道驱动**（例如每 30s 一次），
     * 不要在帧回调里做这件事。
     */
    fun flushAndReport(tag: String = "全局") {
        val list = samples.toList()
        val total = totalFrames.get()
        val jank = jankFrames.get()
        if (total == 0L) return

        val durations = list.map { it.totalMs }.sorted()
        val p50 = percentile(durations, 50)
        val p90 = percentile(durations, 90)
        val p99 = percentile(durations, 99)
        val worst = durations.lastOrNull() ?: 0
        // 归因：jank 帧里"最耗时的那一段"分布，直接回答"该优化谁"
        val segmentHist = list.map { it.dominantSegment().first }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }

        val report = buildString {
            appendLine("【卡顿报告｜$tag】")
            appendLine("判定依据：${if (Build.VERSION.SDK_INT >= 31) "FrameMetrics.DEADLINE（系统给的帧预算，自适应刷新率）" else "TOTAL_DURATION vs 16.6ms（API<31 降级，跨版本不可直接比较）"}")
            appendLine("总帧数 $total，jank(>预算×$JANK_FACTOR) $jank 帧，占比 ${"%.1f".format(jank * 100.0 / total)}%")
            val unspec = unspecifiedFrames.get()
            if (unspec > 0) {
                appendLine("⚠️ 另有 $unspec 帧的 TOTAL_DURATION 为「未指定」（哨兵值），已**排除在统计之外** ——")
                appendLine("   它们若不排除，会把 P99/max 带偏到荒谬量级（实测见过 `9221320829010ms`）。")
            }
            appendLine("jank 帧耗时分布：P50=${p50}ms P90=${p90}ms P99=${p99}ms max=${worst}ms")
            appendLine()
            appendLine("── jank 帧的耗时归因（哪一段在拖后腿）──")
            if (segmentHist.isEmpty()) {
                appendLine("  （本轮无 jank 帧）")
            } else {
                segmentHist.take(8).forEach { (seg, n) ->
                    appendLine("  ${seg.padEnd(24)} $n 帧")
                }
            }
            appendLine()
            appendLine("── 最慢的 3 帧 ──")
            list.sortedByDescending { it.totalMs }.take(3).forEach { appendLine("  ${it.describe()}") }
            appendLine()
            appendLine("⚠️ 能力边界（必须知道，否则会误判）：")
            appendLine("  · 只覆盖 **app 侧**。系统合成侧（SurfaceFlinger/Buffer Stuffing）的 jank")
            appendLine("    不在 FrameMetrics 里 —— 所以这里的 jank 数 ≤ Perfetto FrameTimeline 的 jank 数。")
            appendLine("  · **含首帧**（FIRST_DRAW_FRAME），冷启动的布局/图片解码耗时会体现在这里，")
            appendLine("    不应混进「滚动流畅度」的统计口径。")
            appendLine("  · 没有 Window 的时段（纯后台）无数据。")
        }
        StabilityReporter.forceReport(
            StabilityReporter.Event(
                kind = StabilityReporter.Kind.JANK,
                timestamp = System.currentTimeMillis(),
                name = "jank-summary:$tag",
                valueMs = worst,
                threadName = "main",
                isMainThread = true,
                isForeground = StabilityReporter.isForeground(),
                pid = Process.myPid(),
                detail = report,
            )
        )
        Log.w(TAG, report)
        // 采样缓冲滚动清理：flush 后清空自己的样本窗口，
        // 但 total/jank 计数**保留**（累计值用于算比例，别在这里 reset）
        samples.clear()
    }

    /**
     * 把 jank 明细也写一份给 `drainTo`（真正的上报出口），
     * 与 [flushAndReport] 的区别：这个是**单帧粒度**，用于平台侧做趋势/聚类。
     */
    fun reportWorstFrames(limit: Int = 5) {
        samples.sortedByDescending { it.totalMs }.take(limit).forEach { s ->
            StabilityReporter.report(
                StabilityReporter.Event(
                    kind = StabilityReporter.Kind.JANK,
                    timestamp = System.currentTimeMillis(),
                    name = "frame:${s.dominantSegment().first}",
                    valueMs = s.totalMs,
                    threadName = "main",
                    isMainThread = true,
                    isForeground = StabilityReporter.isForeground(),
                    pid = Process.myPid(),
                    detail = s.describe(),
                )
            )
        }
    }

    /** 重置累计计数（新一轮测量 / Demo 用） */
    fun reset() {
        samples.clear()
        totalFrames.set(0)
        jankFrames.set(0)
        unspecifiedFrames.set(0)
        slowSegment.set(0)
    }

    fun describe(): String = buildString {
        val total = totalFrames.get()
        val jank = jankFrames.get()
        appendLine("── 帧监控状态 ──")
        appendLine("已挂载：${if (installed.get()) "是（回调线程 $CALLBACK_THREAD_NAME）" else "否（需在 Activity 里调 watch()）"}")
        appendLine("累计帧数：$total，jank：$jank" +
                if (total > 0) "（${"%.1f".format(jank * 100.0 / total)}%）" else "")
        appendLine("未指定 TOTAL_DURATION 的帧：${unspecifiedFrames.get()}（已排除在统计外）")
        appendLine("缓冲样本：${samples.size}/$SAMPLE_CAPACITY（只存 jank 帧）")
        appendLine(
            "⚠️ 上方的「已挂载」是**最后一个** window；FrameMetrics 是 per-Window 的，" +
                    "Activity 切换后需要重新 watch（本项目在 Demo 里手动切换）。"
        )
    }

    private fun percentile(sorted: List<Long>, p: Int): Long =
        if (sorted.isEmpty()) 0L else sorted[(sorted.size * p / 100).coerceAtMost(sorted.size - 1)]
}
