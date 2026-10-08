package com.interview.内存

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Debug
import android.util.Log
import com.interview.thread.ThreadPools
import com.interview.稳定性监控.StabilityMonitor
import com.interview.稳定性监控.StabilityReporter
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Time: 2026/10/8
 * Author: wgt
 * Description: 线上内存监控 **总装** —— 把 JVM 堆、Native、图形内存三条观测线装进进程生命周期
 *
 * ══════════════════════════════════════════════════════════════════════
 * 一、与 `StabilityMonitor` 的关系（**复用统一出口，不另建管道**）
 * ══════════════════════════════════════════════════════════════════════
 *
 * 本模块**不新建上报通道**。内存事件走同一套：
 *
 * ```
 *   MemoryMonitor（本类，采集与判定）
 *        │  StabilityReporter.forceReport(Event(kind = MEMORY_*))
 *        ▼
 *   StabilityReporter（唯一出口 + 有界环形缓冲 + 采样率 + 分类配额）
 *        │  StabilityMonitor.flush { batch -> platform.upload(batch) }
 *        ▼
 *   平台（可替换）
 * ```
 *
 * 理由在 `INTERVIEW-线上ANR监控方案.md` §2.2 已经论证过：
 * **「采集源会越加越多，收敛点只能有一个」**。内存是第三个来源
 * （前两个是稳定性、线程治理），如果它自带一套限流/字段/重试，
 * 线上就会重演"一次内存告警把崩溃配额打满"。
 *
 * ⚠️ 因此本类**只做三件采集侧的事**：
 *   ① 生命周期接线（`registerComponentCallbacks` / `ActivityLifecycleCallbacks`）；
 *   ② 阈值判定（水位分级、trim 级别、趋势）；
 *   ③ 把结论变成 `Event` 丢进统一出口。
 *   **不做**：序列化、重试、限流、清账 —— 那些是出口的职责。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 二、采样率（内存事件的量比卡顿大，必须比"必报"更克制）
 * ══════════════════════════════════════════════════════════════════════
 *
 * | 事件 | 采样 | 依据 |
 * |---|---|---|
 * | 水位越级（NORMAL→HIGH→CRITICAL） | **必报**（但**只在越级时**报，不是每次采样都报） | 稀有、每条都是"该看版本了"的信号 |
 * | trim 内存压力级别（RUNNING_LOW/CRITICAL/COMPLETE） | **必报** | 这是**系统**的判断，最权威，且不频繁 |
 * | trim 的 UI_HIDDEN | **不报** | 它只是"退到后台"，一天几十次，与内存无关 |
 * | 周期聚合（趋势报告） | 1/5 会话 | 聚合后信息密度高，量大 |
 * | 大对象/直方图 | 显式触发 | 秒级冻结，绝不自作主张 |
 *
 * ⚠️ **"只在越级时报"是这份设计的核心**：内存水位是**连续量**，
 *    每次采样都报的话，一次内存紧张会刷出上百条事件 —— 那不是监控，是噪音。
 *    越级（状态机边沿）才是"变化"，才值得一条事件。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 三、一条必须讲清楚的设计：`onTrimMemory` 是**唯一**权威的内存压力信号
 * ══════════════════════════════════════════════════════════════════════
 *
 * 我们自己算的水位是**进程视角**（"我用了多少"）；系统给的 trim 级别是
 * **整机视角**（"外面还有多少"，见 `MemoryMetrics` 类注释 §一.4）。
 * 两者会背离，而且**整机视角经常更准**：
 *
 * ```
 *   本进程水位 40%（看着很安全）
 *   但系统发来 TRIM_MEMORY_RUNNING_CRITICAL
 *   ⇒ 真实情况：整机快没内存了，我们随时被杀
 * ```
 *
 * 所以 [MemoryMonitor] **必须**注册 `ComponentCallbacks2`，
 * 并且**把 trim 级别报出去**（kind = `MEMORY_TRIM`）——
 * 这是"为什么我们的 App 在特定机型上被杀"的唯一客户端证据。
 */
object MemoryMonitor {

    private const val TAG = "MemoryMonitor"

    @Volatile
    private var installed = false

    @Volatile
    private var appContext: Context? = null

    private var sampler: MemoryMetrics.Sampler? = null

    /** 上一次报出去的水位级别 —— 只有**越级**才上报（见类注释 §二） */
    @Volatile
    private var lastLevel: MemoryMetrics.Level = MemoryMetrics.Level.NORMAL

    /** 上一次报出去的 trim 级别（去重：系统可能重复发同一级别） */
    @Volatile
    private var lastReportedTrim: Int = Int.MIN_VALUE

    private val trimCount = java.util.concurrent.ConcurrentHashMap<Int, AtomicLong>()

    /** 水位越级计数（用于概览与测试） */
    private val levelTransitions = AtomicLong(0)

    /** 周期聚合的会话采样率（生产默认 1/5；Demo 为了可见性用 1/1） */
    @Volatile
    var summarySampleRate: Int = 1

    /**
     * 是否自动挂 trim 回调。
     *
     * ⚠️ **默认 true，而且不建议关**：这是唯一能拿到"系统认为整机内存紧张"的通道。
     *    关掉它，`MemoryMetrics.Snapshot.lastTrimLevel` 永远是 -1，
     *    趋势报告的"容器感"归零。
     */
    @Volatile
    var watchTrim: Boolean = true

    /** 低内存设备上的额外保守系数（见 `MemoryMetrics.classify` 的注释） */
    @Volatile
    var highRatio: Float = 0.70f

    @Volatile
    var criticalRatio: Float = 0.85f

    // ─────────────────────────────────────────────
    // 安装
    // ─────────────────────────────────────────────

    /**
     * 装内存监控。**必须在 `Application.onCreate` 后调用**（`attachBaseContext` 里
     * 不要做：那里 `ActivityManager` 可能还没准备好，且 trim 回调注册需要 app context 稳定）。
     *
     * 与其他模块的安装顺序无关（内存监控不依赖 Crash/ANR 的任何状态），
     * 但如果 `StabilityMonitor.installOnCreate` 已经在跑采样器，两者会**并存**
     * —— 它们共用统一出口，不冲突。
     */
    fun install(app: Application) {
        if (installed) return
        installed = true
        appContext = app.applicationContext

        // ① trim 回调 —— 唯一权威的内存压力信号（见类注释 §三）
        if (watchTrim) {
            runCatching { app.registerComponentCallbacks(trimCallback) }
                .onFailure { Log.w(TAG, "注册 ComponentCallbacks 失败", it) }
        }

        // ② 采样器（默认 2s；生产可按 remote config 调到 5~10s）
        sampler = MemoryMetrics.Sampler(
            context = app.applicationContext,
            intervalMs = 2000L,
            capacity = 256,
            onSample = { s -> onSampled(s) },
        ).also { it.start() }

        Log.i(TAG, "内存监控已装：采样 2s / 环形 256 点 ≈ 8.5 分钟窗口；trim 回调=${watchTrim}")
    }

    fun isInstalled(): Boolean = installed

    fun samplerRef(): MemoryMetrics.Sampler? = sampler

    fun stop() {
        sampler?.stop()
        installed = false
    }

    fun context(): Context? = appContext

    // ─────────────────────────────────────────────
    // trim 回调
    // ─────────────────────────────────────────────

    private val trimCallback = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            MemoryMetrics.recordTrimLevel(level)
            trimCount.computeIfAbsent(level) { AtomicLong() }.incrementAndGet()

            // ⚠️ 只在**内存压力**轴上上报；UI_HIDDEN 只是"退到后台"，一天几十次，
            //    上报它会把配额打满（见类注释 §二 的采样表）。
            if (!MemoryMetrics.isPressureLevel(level)) {
                // ⚠️ **但"退到后台"是上报驱动的关键触发点**（与稳定性模块 §2.5 同一条理由）：
                //    移动端大量会话是"退到后台后再也没回来"，不在这一刻发，
                //    这整个会话的数据会随进程回收一起消失。
                //    注意：这里只是**触发一次 flush**（把已有的缓冲发出去），
                //    **不是**把 UI_HIDDEN 变成一条内存事件 —— 两件事。
                if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                    StabilityMonitor.ReportDriver.onEnterBackground()
                }
                Log.i(TAG, "onTrimMemory: ${MemoryMetrics.trimName(level)}（非压力轴，不上报）")
                return
            }
            if (level == lastReportedTrim) {
                // 同一级别重复发（系统会这样）：不重复上报
                Log.i(TAG, "onTrimMemory: ${MemoryMetrics.trimName(level)}（与上次同级，去重）")
                return
            }
            lastReportedTrim = level

            val s = sampler?.latest() ?: runCatching { MemoryMetrics.capture(appContext!!) }.getOrNull()
            val detail = buildString {
                append("trim=${MemoryMetrics.trimName(level)}")
                if (s != null) {
                    append(" | 水位=${MemoryMetrics.fmtRatio(s.javaUsedRatio)}")
                    append(" java=${MemoryMetrics.fmt(s.javaUsedBytes)}/${MemoryMetrics.fmt(s.heapLimitBytes)}")
                    append(" nativeAlloc=${MemoryMetrics.fmt(s.nativeAllocatedBytes)}")
                    append(" rss=${MemoryMetrics.fmtKb(s.vmRssKb)} hwm=${MemoryMetrics.fmtKb(s.vmHwmKb)}")
                    append(" | 整机可用=${MemoryMetrics.fmt(s.deviceAvailMemBytes)}")
                    append("/总 ${MemoryMetrics.fmt(s.deviceTotalMemBytes)}")
                    append(" 低内存设备=${s.isLowRamDevice}")
                } else {
                    append(" | （快照不可用）")
                }
            }
            // 压力越大，日志级别越高 —— 便于 adb logcat 用级别筛选
            val important = level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                    level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            StabilityReporter.forceReport(
                StabilityReporter.Event(
                    kind = StabilityReporter.Kind.MEMORY_TRIM,
                    timestamp = System.currentTimeMillis(),
                    name = "trim:${trimShortName(level)}",
                    valueMs = 0,
                    threadName = Thread.currentThread().name,
                    isMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper(),
                    isForeground = StabilityReporter.isForeground(),
                    pid = android.os.Process.myPid(),
                    detail = detail.take(1024),
                )
            )
            Log.w(TAG, "trim 压力上报：$detail" + if (important) "  ⚠️ 高危级别" else "")
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}
        override fun onLowMemory() {
            // ⚠️ `onLowMemory` 已废弃（Android 14 起系统不再调它），但**不能删**：
            //    在 API 30 以下的设备上它仍然是最后一个信号。
            //    而且它比任何 RUNNING_* 级别都更严重（整机已经到极限）。
            Log.w(TAG, "onLowMemory（API 14+ 已废弃，低版本设备上仍是最后信号）")
            StabilityReporter.forceReport(
                StabilityReporter.Event(
                    kind = StabilityReporter.Kind.MEMORY_TRIM,
                    timestamp = System.currentTimeMillis(),
                    name = "onLowMemory",
                    isMainThread = true,
                    isForeground = StabilityReporter.isForeground(),
                    pid = android.os.Process.myPid(),
                    detail = "onLowMemory（整机已到极限；此回调在 Android 14+ 不再被调用）",
                )
            )
        }
    }

    private fun trimShortName(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE"
        else -> "UNKNOWN_$level"
    }

    // ─────────────────────────────────────────────
    // 每次采样的判定
    // ─────────────────────────────────────────────

    private fun onSampled(s: MemoryMetrics.Snapshot) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, MemoryMetrics.oneLine(s))

        val level = MemoryMetrics.classify(s, highRatio, criticalRatio)
        if (level != lastLevel) {
            val from = lastLevel
            lastLevel = level
            levelTransitions.incrementAndGet()
            // ⚠️ 只有**升高**才值得一条事件；从 CRITICAL 落回 NORMAL 是"GC 生效了"，
            //    它不需要一条上报（但会体现在下一次周期聚合里）。
            if (level.ordinal > from.ordinal) {
                reportLevel(s, from, level)
                // ⭐ **升到 CRITICAL 的那一刻立刻落遗嘱。**
                //
                // 为什么必须是"这一刻"而不是"之后某次"：内存告警与崩溃告警的**根本区别**
                // 在于 —— OOM 是**由内核直接杀进程**的，进程**没有机会**执行任何收尾代码。
                // 也就是说 CRITICAL 之后再发生的一切（下一次采样、下一次 resume/pause、
                // 任何"稍后统一写盘"）都可能**根本不会发生**。
                // 之前台账只在 `onActivityResumed/Paused` 里写，于是"进页面→内存涨到临界→
                // 被系统杀掉"这条最典型的线上链路，**一条记录都不会留下**。
                //
                // 与崩溃遗嘱（`CrashJournal`，走 fsync）的取舍不同：这里仍**不 fsync**。
                // 理由是成本/收益：墓碑必须绝对落盘（它承载堆栈那种不可再生信息）；
                // 而这里写的是"水位 + trim 历史 + native Top-N"这种**可以从下一次采样重建**
                // 的快照，为它每次都付一次磁盘同步不划算。
                // 但"不 fsync"与"根本不写"是两回事 —— 前者是概率，后者是必然丢。
                //
                // ⚠️ 这也**没有**违反本模块"采集点零 IO"的纪律：这段代码位于
                //    `level.ordinal > from.ordinal` 之内，即**只在上升沿触发**；
                //    一旦进入 CRITICAL，`lastLevel` 就停在 CRITICAL，不会重复进入。
                //    所以整个进程生命周期里它最多写一次 —— 是有界的一次写，
                //    不是"每次采样都写盘"。
                if (level == MemoryMetrics.Level.CRITICAL) {
                    appContext?.let { ctx ->
                        appendJournal(ctx, s, note = "CRITICAL:${from}->${level}")
                        Log.w(TAG, "已落内存遗嘱（CRITICAL）：${MemoryMetrics.oneLine(s)}")
                    }
                }
            }
        }
    }

    private fun reportLevel(s: MemoryMetrics.Snapshot, from: MemoryMetrics.Level, to: MemoryMetrics.Level) {
        StabilityReporter.forceReport(
            StabilityReporter.Event(
                kind = if (to == MemoryMetrics.Level.CRITICAL) StabilityReporter.Kind.MEMORY_CRITICAL
                else StabilityReporter.Kind.MEMORY_HIGH,
                timestamp = System.currentTimeMillis(),
                name = "level:$from->$to",
                valueMs = 0,
                threadName = Thread.currentThread().name,
                isMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper(),
                isForeground = StabilityReporter.isForeground(),
                pid = android.os.Process.myPid(),
                detail = s.toEventDetail(),
            )
        )
        Log.w(TAG, "水位越级 $from → $to：${MemoryMetrics.oneLine(s)}")
    }

    // ─────────────────────────────────────────────
    // 周期聚合（趋势报告）
    // ─────────────────────────────────────────────

    /**
     * 生成并上报一次趋势报告。
     *
     * 生产口径：挂在**定时器**上（如每 30 分钟 + 每次进后台），
     * 且带**会话级采样率**（默认 1/5）。
     * ⚠️ 与稳定性模块的"采集点零 IO"纪律一致：本方法由定时器/页面显式调用，
     *    **不在采样回调里自动跑**。
     */
    fun flushTrend(tag: String = "全局"): String {
        val points = sampler?.points().orEmpty()
        if (points.size < 3) {
            return "趋势样本不足（${points.size} 点，至少 3 点）。先让页面停留几十秒。"
        }
        val trend = MemoryMetrics.Trend.analyze(points)
        val report = buildString {
            appendLine("═══ 内存趋势报告（$tag）═══")
            appendLine(trend?.describe() ?: "（无法判定）")
            appendLine()
            appendLine("窗口：${points.size} 点 / ${(points.last().timestamp - points.first().timestamp) / 1000}s")
            appendLine()
            appendLine("──────────────────────────────")
            appendLine(MemoryMetrics.describe(points.last()))
            appendLine("──────────────────────────────")
            appendLine(BitmapTracker.describe(points.last().graphicsPssKb))
            appendLine("──────────────────────────────")
            appendLine(HeapDumpGate.describe())
            appendLine("──────────────────────────────")
            appendLine(MemoryAllocTracker.status())
            appendLine("──────────────────────────────")
            appendLine("【GC 谷值包络】（用 art.gc.gc-count 变化定位，近似）")
            val valleys = MemoryMetrics.Trend.gcValleys(points)
            if (valleys.size < 2) {
                appendLine("  窗口内没检测到 GC（或 runtimeStats 不含 art.gc.gc-count）")
            } else {
                valleys.takeLast(8).forEach {
                    appendLine("  ${fmt.format(Date(it.timestamp))}  java=${MemoryMetrics.fmt(it.javaUsedBytes)}")
                }
                val first = valleys.first().javaUsedBytes
                val last = valleys.last().javaUsedBytes
                val spanMs = valleys.last().timestamp - valleys.first().timestamp
                if (spanMs > 0) {
                    val perMin = (last - first).toDouble() / spanMs * 60_000
                    appendLine("  ⇒ 谷值趋势：${MemoryMetrics.fmt(first)} → ${MemoryMetrics.fmt(last)}" +
                            "，约 ${MemoryMetrics.fmt(perMin.toLong())}/分钟")
                    appendLine("  ⚠️ 这是**近似**：gc-count 增加不代表该点就是谷值（GC 可能发生在采样间隔中间）")
                }
            }
            appendLine()
            appendLine("⚠️ 趋势判决 LEAK_SUSPECT **不是泄漏的证明**，只是「值得看一眼」。")
            appendLine("   要证明泄漏必须去归因：MemoryAllocTracker（native）/ HeapDumpGate（Java）。")
        }
        Log.w(TAG, report)

        // 采样率：内存趋势是"聚合后信息密度高、量大"的那一类（与 ANR 的 1/5 同档）
        val rate = summarySampleRate.coerceAtLeast(1)
        if (rate == 1 || summaryCounter.incrementAndGet() % rate == 0L) {
            StabilityReporter.forceReport(
                StabilityReporter.Event(
                    kind = StabilityReporter.Kind.MEMORY_TREND,
                    timestamp = System.currentTimeMillis(),
                    name = "trend:$tag:${trend?.verdict ?: "UNKNOWN"}",
                    valueMs = (points.last().timestamp - points.first().timestamp) / 1000,
                    threadName = Thread.currentThread().name,
                    isMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper(),
                    isForeground = StabilityReporter.isForeground(),
                    pid = android.os.Process.myPid(),
                    detail = report.take(4096),
                )
            )
        }
        return report
    }

    private val summaryCounter = AtomicLong(0)

    // ─────────────────────────────────────────────
    // 落盘（会话级内存台账，供下次启动分析"跨会话"的泄漏）
    // ─────────────────────────────────────────────

    private const val JOURNAL = "memory/session-memory.tsv"
    private const val MAX_LINES = 200
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private fun journalFile(ctx: Context): File =
        File(ctx.filesDir, JOURNAL).apply { parentFile?.let { if (!it.exists()) it.mkdirs() } }

    /**
     * 把一次快照追加到会话台账（**有界**：超过 [MAX_LINES] 行就丢最旧）。
     *
     * ─── 为什么需要它（而不是只靠内存里的环形缓冲）───
     *
     * `Sampler` 的环形缓冲**随进程消失**。而"跨会话"的泄漏（每次冷启动都比上次高
     * 一个台阶）恰恰是**最典型的线上内存问题形态** —— 它在单次会话里完全看不出来。
     * 所以要有一条**跨进程存活**的最小台账。
     *
     * ⚠️ 与崩溃遗嘱的区别：台账**允许丢**（它不是证据，是趋势），
     *    所以这里**没有** `fd.sync()`，也没有"清账"语义 —— 只是追加 + 截断。
     *    这一点是刻意的：给它 fsync 会让每次采样都付磁盘同步的代价，
     *    而它承载的信息量远不值这个价。
     */
    fun appendJournal(ctx: Context, s: MemoryMetrics.Snapshot, note: String = "") {
        runCatching {
            val f = journalFile(ctx)
            val line = listOf(
                s.timestamp.toString(),
                fmt.format(Date(s.timestamp)),
                s.javaUsedBytes.toString(),
                s.javaCommittedBytes.toString(),
                s.heapLimitBytes.toString(),
                s.nativeAllocatedBytes.toString(),
                s.nativeHeapSizeBytes.toString(),
                s.vmRssKb.toString(),
                s.totalPssKb.toString(),
                s.graphicsPssKb.toString(),
                s.lastTrimLevel.toString(),
            ).joinToString("\t") + if (note.isBlank()) "" else "\t$note"

            synchronized(this) {
                val old = if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
                val merged = (old + line).takeLast(MAX_LINES)
                // 一次性覆盖写（与 CrashJournal 同一手法：最少的 syscall）。
                // ⚠️ 但**不做 fsync** —— 见上方注释：这是一条"允许丢"的台账。
                RandomAccessFile(f, "rw").use { raf ->
                    raf.setLength(0)
                    raf.write(merged.joinToString("\n").toByteArray())
                }
            }
        }.onFailure { Log.w(TAG, "写内存台账失败（已忽略，不影响主流程）", it) }
    }

    /** 读会话台账（跨会话对比）。返回原始行。 */
    fun readJournal(ctx: Context): List<String> = runCatching {
        val f = journalFile(ctx)
        if (!f.exists()) emptyList() else f.readLines().filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    /**
     * 跨会话对比报告：**这是回答"是不是每次启动都比上次高"的唯一手段**。
     */
    fun crossSessionReport(ctx: Context): String = buildString {
        val lines = readJournal(ctx)
        appendLine("═══ 跨会话内存台账（${lines.size} 条记录）═══")
        if (lines.isEmpty()) {
            appendLine("（空：还没写过台账。调 appendJournal 或让它跑一段时间）")
            return@buildString
        }
        appendLine("${"时间".padEnd(20)} ${"Java used".padStart(12)} ${"committed".padStart(12)} ${"nativeAlloc".padStart(13)} ${"RSS".padStart(10)} ${"trim".padStart(6)}")
        lines.takeLast(20).forEach { line ->
            val p = line.split('\t')
            if (p.size >= 11) {
                appendLine(
                    "%-20s %12s %12s %13s %10s %6s".format(
                        p[1],
                        MemoryMetrics.fmt(p[2].toLongOrNull() ?: 0L),
                        MemoryMetrics.fmt(p[3].toLongOrNull() ?: 0L),
                        MemoryMetrics.fmt(p[5].toLongOrNull() ?: 0L),
                        MemoryMetrics.fmtKb(p[7].toIntOrNull() ?: 0),
                        p[10],
                    )
                )
            }
        }
        // 冷启动台阶：把每个"会话的第一个采样"挑出来比较（判据是时间间隔 > 60s）
        val firstOfEachSession = ArrayList<Pair<Long, Long>>()
        var prevTs = 0L
        lines.forEach { line ->
            val p = line.split('\t')
            val ts = p.getOrNull(0)?.toLongOrNull() ?: return@forEach
            if (prevTs == 0L || ts - prevTs > 60_000) {
                firstOfEachSession.add(ts to (p.getOrNull(2)?.toLongOrNull() ?: 0L))
            }
            prevTs = ts
        }
        if (firstOfEachSession.size >= 2) {
            appendLine()
            appendLine("── 冷启动台阶（每个会话的第一个采样）──")
            firstOfEachSession.takeLast(8).forEach { (ts, used) ->
                appendLine("  ${fmt.format(Date(ts))}  Java used = ${MemoryMetrics.fmt(used)}")
            }
            val first = firstOfEachSession.first().second
            val lastVal = firstOfEachSession.last().second
            appendLine("  ⇒ 首个会话 ${MemoryMetrics.fmt(first)} → 最近会话 ${MemoryMetrics.fmt(lastVal)}")
            if (lastVal > first + 8L * 1024 * 1024 && firstOfEachSession.size >= 3) {
                appendLine("  ⚠️ 每次冷启动的起点**逐次抬高**（>8MB）—— 这是「跨会话泄漏」的典型形态：")
                appendLine("     典型成因：静态集合/单例缓存只增不减、数据库/文件缓存、Context 泄漏。")
                appendLine("     ⚠️ 但注意：也可能是「本次安装后功能变多」（比如缓存目录已有数据），")
                appendLine("        所以必须结合 trace 看**是哪一类对象**在涨，不能只看台阶。")
            }
        }
        appendLine()
        appendLine("⚠️ 台账**不做 fsync**（它允许丢，不是证据）；上限 $MAX_LINES 行，超限丢最旧。")
    }

    // ─────────────────────────────────────────────
    // 页面接线
    // ─────────────────────────────────────────────

    /**
     * 页面进入时挂：记录基线快照 + 清空位图计数（"本页"的口径）。
     *
     * ⚠️ 与 `StabilityMonitor.watchJank` 同样的纪律：**由页面自己调用**，
     *    不在 `ActivityLifecycleCallbacks` 里自动给每个页面挂 ——
     *    那样会让"监控"变成"每个页面的隐形负担"，且无法按页面采样。
     */
    fun onActivityResumed(activity: Activity, pageTag: String = activity.javaClass.simpleName) {
        val ctx = activity.applicationContext
        // 页面级基线：把当前的 Java/native 数字写进台账，便于退出时对比
        val s = sampler?.latest() ?: runCatching { MemoryMetrics.capture(ctx) }.getOrNull() ?: return
        appendJournal(ctx, s, note = "resume:$pageTag")
        Log.i(TAG, "页面基线（$pageTag）：${MemoryMetrics.oneLine(s)}")
    }

    /** 页面退出/暂停时收口：出一次"本页"的变化报告，并可选抓直方图。 */
    fun onActivityPaused(activity: Activity, pageTag: String = activity.javaClass.simpleName): String {
        val ctx = activity.applicationContext
        val s = sampler?.latest() ?: runCatching { MemoryMetrics.capture(ctx) }.getOrNull()
        val report = buildString {
            appendLine("═══ 页面内存收口（$pageTag）═══")
            if (s == null) {
                appendLine("（快照不可用）")
                return@buildString
            }
            appendLine("离场快照：${MemoryMetrics.oneLine(s)}")
            appendLine()
            appendLine(BitmapTracker.describe(s.graphicsPssKb))
            val trend = MemoryMetrics.Trend.analyze(sampler?.points().orEmpty())
            if (trend != null) {
                appendLine("窗口趋势：${trend.describe()}")
                if (trend.verdict == MemoryMetrics.Trend.Verdict.LEAK_SUSPECT) {
                    appendLine()
                    appendLine("⚠️ 本页窗口内检出「涨了不回落」。下一步（按顺序）：")
                    appendLine("  1. 先看 BitmapTracker 的「按来源」——位图是最常见成因")
                    appendLine("  2. 若位图正常 → 「直方图」按钮抓 hprof，看哪一类对象数量异常")
                    appendLine("  3. 若 Java 侧正常但 native 涨 → 「native 归因」按钮（有损采样，看大头）")
                }
            } else {
                appendLine("窗口样本不足（<3 点），无法判定趋势")
            }
        }
        s?.let { appendJournal(ctx, it, note = "pause:$pageTag") }
        Log.i(TAG, report)
        return report
    }

    // ─────────────────────────────────────────────
    // 概览
    // ─────────────────────────────────────────────

    fun formatOverview(): String {
        val ctx = context()
        val s = sampler?.latest() ?: ctx?.let { runCatching { MemoryMetrics.capture(it) }.getOrNull() }
        return buildString {
            appendLine("══════════ 内存监控总览 ══════════")
            appendLine("pid=${android.os.Process.myPid()}  已安装=$installed  前台=${StabilityReporter.isForeground()}")
            appendLine("SDK=${Build.VERSION.SDK_INT}  ${MemoryMetrics.apiNote()}")
            appendLine("采样：间隔 2s / 环形 ${sampler?.points()?.size ?: 0} 点 / 平均耗时 " +
                    "%.2f ms".format(sampler?.avgCostMs() ?: 0.0))
            appendLine("水位阈值：HIGH=${MemoryMetrics.fmtRatio(highRatio)} CRITICAL=${MemoryMetrics.fmtRatio(criticalRatio)}" +
                    "（低内存设备自动降档 10%）")
            appendLine("水位越级次数：${levelTransitions.get()}  当前级别=$lastLevel")
            appendLine()
            appendLine("── trim 计数（按级别）──")
            if (trimCount.isEmpty()) {
                appendLine("  （未收到任何 trim）")
            } else {
                trimCount.entries.sortedBy { it.key }.forEach { (lvl, n) ->
                    appendLine("  %-46s %d 次".format(MemoryMetrics.trimName(lvl), n.get()))
                }
            }
            appendLine()
            appendLine(StabilityReporter.describe())
            appendLine()
            appendLine("── 本次采样 ──")
            appendLine(s?.let { MemoryMetrics.describe(it) } ?: "（无快照）")
            appendLine()
            appendLine(BitmapTracker.describe(s?.graphicsPssKb ?: 0))
            appendLine()
            appendLine(HeapDumpGate.describe())
            appendLine()
            appendLine(MemoryAllocTracker.status())
            appendLine()
            appendLine("── 统一出口里的内存事件 ──")
            StabilityReporter.Kind.entries.filter { it.name.startsWith("MEMORY") }.forEach {
                appendLine("  %-18s %d".format(it.name, StabilityReporter.countOf(it)))
            }
        }
    }

    /** Demo/测试用：清空所有内存态统计 */
    fun resetAll() {
        sampler?.clear()
        BitmapTracker.reset()
        MemoryAllocTracker.reset()
        lastLevel = MemoryMetrics.Level.NORMAL
        lastReportedTrim = Int.MIN_VALUE
        trimCount.clear()
        levelTransitions.set(0)
        summaryCounter.set(0)
    }

    /**
     * 演示负载：制造可复现的内存压力，用来**自证监控链路有效**。
     *
     * ⚠️ 刻意用 `ByteArray` 而不是 Bitmap：
     *    · ByteArray 在 **Java 堆**上 → 打的是 JVM 水位这一条线；
     *    · 与 `image/` Lab 的位图负载互补（那个打 native/graphics 线）。
     *    两条线都要能自己造出来，否则"监控有效"这件事无法自证。
     *
     * @return 保留的负载（调用方负责释放 —— 显式，避免"演示完自己泄漏"）
     */
    fun makeHeapPressure(mb: Int): List<ByteArray> {
        val blocks = ArrayList<ByteArray>(mb)
        repeat(mb) {
            // 1 MB/块：字节数足够大到能推动水位，又不至于一次就 OOM
            blocks.add(ByteArray(1 shl 20))
        }
        Log.w(TAG, "已制造 ${mb}MB Java 堆压力（调用方必须释放）")
        return blocks
    }
}
