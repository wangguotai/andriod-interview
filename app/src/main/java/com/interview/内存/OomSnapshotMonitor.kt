package com.interview.内存

import android.content.Context
import android.os.Build
import android.util.Log
import com.interview.oomdump.OomSnapshotNative
import com.interview.稳定性监控.StabilityReporter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Time: 2026/10/17
 * Author: wgt
 * Description: **JVM OOM 现场取证** —— 发生 OutOfMemoryError 时 fork 一个子进程去 dump
 * 这份"冻结的堆快照"，父进程该活活、该死继续死。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 它要解决的那个具体问题
 * ══════════════════════════════════════════════════════════════════════
 *
 * 现有通道（[HeapDumpGate] / `Debug.dumpHprofData`）在**正常水位**下好用，
 * 但在 OOM 现场有一个致命缺陷：**dump 自己也要内存**。
 *
 * ```
 *   Debug.dumpHprofData()
 *     └─ art::hprof::DumpHeap
 *          · stop-the-world（冻结进程）
 *          · 自己还要 malloc 几十 MB 缓冲
 *   ⇒ 偏偏在最需要它的时候（堆已经满了）最容易失败，
 *     失败方式还很难看：在 dump 里再抛一次 OOM，原始异常被吃掉。
 * ```
 *
 * 本方案换一个思路（fork 快照）：
 *
 * ```
 *   OOM 抛出
 *     ├─ ① 父进程 SuspendAll（其它线程停在挂起点，让锁处于"无人持有"）
 *     ├─ ② fork() ── 子进程**只复制页表**（COW），不复制内存
 *     │             ⇒ 子进程看到的就是那一刻的原样
 *     ├─ ③ 父进程立刻 ResumeAll（挂起窗口只覆盖 fork，实测 ~15ms）
 *     │      父进程从这里就可以照常崩溃/被杀了
 *     └─ ④ 子进程慢慢 dump 到文件 → fsync → _exit
 *           父进程被 LMK 杀掉也不影响它（子进程自成进程组，
 *           且**刻意不设** PR_SET_PDEATHSIG —— 见 native 文件头【4】）
 * ```
 *
 * ══════════════════════════════════════════════════════════════════════
 * 两条触发通道（**不能只押"真 OOM 那一刻"**）
 * ══════════════════════════════════════════════════════════════════════
 *
 * | 通道 | 何时 | 为什么必须有 |
 * |---|---|---|
 * | [Trigger.REAL_OOM] | 真抛 OutOfMemoryError | 最准，现场是真的"活不下去"的那一刻 |
 * | [Trigger.WATERMARK] | 水位越级（HIGH/CRITICAL） | OOM 那一刻 fork 可能直接 ENOMEM —— 要有B 计划 |
 *
 * 实测（Redmi K40 / Android 12，堆上限 256MB）：把堆压到 **255MB** 再 fork，
 * 产出 **285MB** hprof、**1160ms**、exit(0) —— 成功。但这是**一个成功的数据点，
 * 不是"永远能成"的保证**：fork 要复制页表、子进程还要写缓冲，
 * 内存彻底耗尽时照样可能失败。所以水位通道不是多余的。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 三个**必须知道**的能力边界（别把这套当"万能 OOM 监控"）
 * ══════════════════════════════════════════════════════════════════════
 *
 * ① **Native OOM 抓不到。** `OutOfMemoryError` 只是 **Java 堆**分配失败。
 *    native malloc 失败、fd 耗尽、线程数超限这些"OOM"根本不抛 Java 异常
 *    （线程创建失败那条恰好抛 OutOfMemoryError，是本通道能捞到的边界情形）。
 *    native 那半边看 [com.interview.memtrace.MemTraceNative] 与 SmapsReader。
 *
 * ② **不是所有 ROM/ART 版本都能用。** 本方案依赖 libart 里几个具体符号
 *    （`art::hprof::DumpHeap` 的签名随版本变过）。换 ROM 后第一件事是看
 *    [describe]，它会显示符号命中情况；没命中就是**明确不可用**，
 *    会退回 [HeapDumpGate] 的老路，不会静默失败。
 *
 * ③ **dump 文件可能超出自研解析器的上限。** 实测 OOM 快照 **285MB**，
 *    而 [MemoryHeapAnalyzer.MAX_DUMP_BYTES] 是 **64MB** ——
 *    OOM 快照天生比常规 dump 大（堆满 vs 堆半满）。
 *    所以这里**先落盘、能解析则解析、超限就只登记元数据**，
 *    取证文件的完整性优先于本次能不能解析。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 与 [HeapDumpGate] 的分工（不要合并成一个）
 * ══════════════════════════════════════════════════════════════════════
 *
 * | | [HeapDumpGate] | 本类 |
 * |---|---|---|
 * | 何时用 | 想**看清某一刻**的堆（按钮触发、水位差分） | **OOM 现场**，进程下一秒可能就没了 |
 * | dump 在哪跑 | 本进程（会冻结） | **子进程**（父进程只挂起 ~15ms） |
 * | 父进程死了会怎样 | 文件残缺 | 子进程继续写完 |
 * | 代价 | 冻结 0.5~2s | 动 ART 同步原语（唯一"会改运行时状态"的采集通道） |
 */
object OomSnapshotMonitor {

    private const val TAG = "OomSnapshot"

    /** 快照文件所在目录（应用私有）；OOM 快照比常规 dump 大得多，保留份数刻意更少 */
    private const val DIR_NAME = "oom-snapshots"

    /**
     * 磁盘保留份数。
     *
     * ⚠️ 实测单份 285MB —— 这是本模块**最现实的成本**。留 2 份 = 570MB，
     *    在低存储设备上已经会触发系统清理。生产上应当配合"上报成功后立即删"，
     *    这里保留 2 份是教学场景下"能对比两次 OOM"的取舍。
     *
     *     第三种入库方式：**真 OOM 快照挤掉预防性快照**。用完上面两个名额后，
     *     水位快照本来就是"次优解"；真 OOM 现场的价值更高，见 [doSnapshot]。
     */
    private const val KEEP_DUMPS = 2

    /** 手动按钮用这个 scene 名；它跳过"最小间隔"节流（点一次就该拍一次） */
    internal const val MANUAL_SCENE = "手动按钮"

    /** 最小触发间隔：OOM 可能连环抛（多个线程一起挂），不能让它们各 fork 一次 */
    @Volatile
    var minIntervalMs: Long = 5 * 60 * 1000L

    /** 是否启用。关掉后 OOM 仍会被记录成事件，但不做 fork 快照 */
    @Volatile
    var enabled: Boolean = true

    /**
     * 水位触发阈值（相对 `Runtime.maxMemory()` 的比例）。
     *
     * 依据：本仓库 [MemoryMonitor] 的 CRITICAL 判定是**软上限 85%**，
     * 这里用同一个口径 —— 两处阈值不一致会出现"水位报了 CRITICAL 但快照没触发"，
     * 那种不一致比阈值本身松紧更要命。
     */
    @Volatile
    var watermarkRatio: Double = 0.85

    private val running = AtomicBoolean(false)
    private val installed = AtomicBoolean(false)
    private val totalOomSeen = AtomicLong(0)
    private val totalSnapshotOk = AtomicLong(0)
    private val totalSnapshotFail = AtomicLong(0)
    private val lastTriggerAt = AtomicLong(0)

    /** 上一次结果（页面展示用） */
    @Volatile
    var lastSnapshot: SnapshotRecord? = null
        private set

    /** 触发来源 */
    enum class Trigger {
        /** 真的抛了 OutOfMemoryError */
        REAL_OOM,

        /** 水位越过高阈值（**预防性**快照；因为真 OOM 那一刻 fork 未必还能成） */
        WATERMARK,
    }

    /** 一次快照的登记信息 */
    data class SnapshotRecord(
        val trigger: Trigger,
        val ok: Boolean,
        val file: File?,
        val sizeBytes: Long,
        val totalMs: Long,
        /** 父进程被挂起的时长 —— 这是本方案对 App 的**直接影响面**，必须暴露 */
        val suspendMs: Long,
        val flags: Int,
        val nativeHow: String,
        val error: String?,
        /** 解析结果；超限/失败时为 null（**不代表快照无效**） */
        val histogram: MemoryHeapAnalyzer.Histogram?,
        val parseError: String?,
        /** 触发时的堆水位（快照"为什么是现在"的上下文） */
        val usedBytes: Long,
        val maxBytes: Long,
        val snapshotAt: Long,
    ) {
        fun describe(): String = buildString {
            appendLine("═══ OOM 快照（${trigger.name}）═══")
            appendLine("结果：${if (ok) "✅ 成功" else "❌ 失败"}  触发时刻水位：${MemoryMetrics.fmt(usedBytes)} / ${MemoryMetrics.fmt(maxBytes)}")
            appendLine("文件：${file?.absolutePath ?: "（无）"}")
            appendLine("大小：${MemoryMetrics.fmt(sizeBytes)}")
            appendLine("耗时：合计 ${totalMs}ms（其中**父进程被挂起 ${suspendMs}ms**）")
            appendLine("native：$nativeHow  flags=0x${Integer.toHexString(flags)}")
            error?.let { appendLine("⚠️ 失败原因：$it") }
            if (histogram != null) {
                appendLine()
                appendLine("已解析：${histogram.classCount} 个类 / ${MemoryMetrics.fmt(histogram.totalBytes)}")
                appendLine("Top5：")
                histogram.top(5).forEach { stat -> appendLine("  ${stat.describe()}") }
            } else if (parseError != null) {
                appendLine()
                appendLine("⚠️ 未解析：$parseError")
                appendLine("   注意：**这不代表快照无效** —— 文件是完整的（shark/MAT 都能打开），")
                appendLine("   只是端内解析被有意推迟了（OOM 时刻堆是满的，见 OomSnapshotMonitor.doSnapshot 的注释）。")
            }
            appendLine()
            appendLine("⚠️ 边界：本通道只覆盖 **Java 堆** OOM；native OOM 不抛 Java 异常，抓不到。")
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 安装
    // ─────────────────────────────────────────────────────────────────

    /**
     * 安装 OOM 快照能力。**幂等**。
     *
     * 做两件事：
     *   1. 在**本进程**装一个 OOM 监听（不能靠 UncaughtExceptionHandler ——
     *      那个只在异常真把线程打死时才被调；很多 OOM 是被 catch 住处理的）；
     *   2. 探测 native 能力（[OomSnapshotNative.probe]），不可用就**如实降级**。
     *
     * @param onWatermark 是否允许"水位触发"这条预防性通道。它需要在
     *        [MemoryMonitor] 的越级回调里调 [snapshotOnWatermark]；
     *        传 false 表示只做真 OOM 通道。
     */
    fun install(@Suppress("UNUSED_PARAMETER") allowWatermark: Boolean = true): Boolean {
        if (!installed.compareAndSet(false, true)) return true
        val probe = OomSnapshotNative.probe()
        if (probe == null) {
            Log.w(TAG, "native 不可用（${OomSnapshotNative.loadError}）：OOM 快照通道关闭，" +
                    "OOM 仍会被登记成事件")
        } else if (!probe.usable) {
            Log.w(TAG, "native 符号未命中，OOM 快照通道不可用：\n${probe.describe()}")
        } else {
            Log.i(TAG, "OOM 快照通道已就绪：\n${probe.describe()}")
        }
        return true
    }

    fun uninstall() {
        installed.set(false)
    }

    fun isAvailable(): Boolean = OomSnapshotNative.isUsable()

    // ─────────────────────────────────────────────────────────────────
    // 通道 1：真 OOM
    // ─────────────────────────────────────────────────────────────────

    /**
     * 记录一次 OutOfMemoryError —— **应当在每个 `catch (e: OutOfMemoryError)` 里调**，
     * 也应当在业务层的全局兜底里调。
     *
     * ⚠️ 为什么必须有这么一个"显式入口"，而不是只装一个全局 hook：
     *    OutOfMemoryError 大多数时候是被**业务代码 catch 住**并降级处理的
     *    （"这个图片加载不了就算了"），根本不会走到任何全局 handler。
     *    只在全局 handler 里埋点，线上看到的 OOM 率会**严重偏低**。
     *    本仓库 [com.interview.thread.ThreadDefense] 就是这么 catch 的。
     *
     * 本方法**不阻塞**：真正的 fork+dump 会扔到后台泳道（OOM 线程此刻最不该被拖住）。
     *
     * @return true = 已受理（进入去重闸门并提交后台任务）；false = 被闸门挡住（不重复取证）
     */
    fun onOutOfMemoryError(context: Context, error: Throwable?, scene: String): Boolean {
        totalOomSeen.incrementAndGet()

        // ① 无论能不能 dump，**OOM 事件本身必须进上报管道** ——
        //    这是"线上 OOM 率"的分母，别让它的存在取决于 native 是否可用。
        reportEvent(
            kind = StabilityReporter.Kind.MEMORY_CRITICAL,
            name = "OOM:$scene",
            detail = buildString {
                appendLine("异常：${error?.javaClass?.name}: ${error?.message}")
                appendLine("水位：${MemoryMetrics.fmt(usedBytes())} / ${MemoryMetrics.fmt(maxBytes())}")
                appendLine("快照能力：${if (isAvailable()) "可用" else "不可用（${OomSnapshotNative.loadError ?: "符号未命中"}）"}")
            },
        )

        if (!isAvailable()) {
            Log.w(TAG, "OOM 已登记，但快照通道不可用（scene=$scene）")
            return false
        }
        return requestSnapshot(context, Trigger.REAL_OOM, scene)
    }

    /**
     * 通道 2：水位越级时**预防性**快照。
     *
     * 为什么需要它：真 OOM 那一刻 fork 有可能直接 ENOMEM（页表 + 子进程写缓冲都要内存）。
     * 在水位 85% 时先拍一张，等于给"真拍不出来的那一次"留个参照。
     *
     * ⚠️ 代价必须写清楚：**会挂起父进程全部线程 ~15ms**（fork 窗口），
     *    并且产出一个几百 MB 的文件。所以它只在**越级时**触发，不是每次采样。
     */
    fun snapshotOnWatermark(context: Context, scene: String, ratio: Double): Boolean {
        if (ratio < watermarkRatio) return false
        if (!isAvailable()) return false
        return requestSnapshot(context, Trigger.WATERMARK, scene, bypassInterval = scene == MANUAL_SCENE)
    }

    // ─────────────────────────────────────────────────────────────────
    // 去重闸门 + 后台执行
    // ─────────────────────────────────────────────────────────────────

    /**
     * @param bypassInterval 是否跳过"最小间隔"这道节流。
     *
     * ⚠️ 这里有一个**必须区分**的语义，第一版没区分，被实测抓到：
     *    「预防性水位快照」和「真 OOM 快照」不该共用同一道节流。
     *    实测场景：手动拍了一张（预防性），14s 后真的 OOM 了，结果被
     *    "最小间隔 300s"挡住 —— **最该留下的那一次现场被丢了**。
     *
     *    规则应该是：
     *      · 真 OOM（REAL_OOM）→ **无视间隔**：它是更强的信号，
     *        任何此前的预防性快照都不能成为"不拍它"的理由。
     *      · 预防性（WATERMARK，非手动）→ 受间隔节流：它是"有总比没有好"的补充，
     *        越级抖动时连着拍只会白占磁盘。
     *
     *    但**并发闸门（running CAS）对两者都生效** —— 绝不允许两次 dump 并行，
     *    那会同时挂起父进程两次、抢磁盘 IO。间隔可以放宽，互斥不能。
     */
    private fun requestSnapshot(
        context: Context,
        trigger: Trigger,
        scene: String,
        bypassInterval: Boolean = trigger == Trigger.REAL_OOM,
    ): Boolean {
        if (!enabled) return false

        val now = System.currentTimeMillis()
        val last = lastTriggerAt.get()
        if (!bypassInterval && last != 0L && now - last < minIntervalMs) {
            Log.w(TAG, "跳过快照（${(now - last) / 1000}s 前刚拍过，最小间隔 ${minIntervalMs / 1000}s）")
            return false
        }
        if (bypassInterval && last != 0L && now - last < minIntervalMs) {
            // 真 OOM 无视间隔，但这件事必须留下痕迹：否则"为什么不按间隔走"无从解释
            Log.w(TAG, "真 OOM 无视最小间隔（距上次仅 ${(now - last) / 1000}s）—— 真现场优先")
        }
        // CAS 去重：OOM 常常多线程连环抛，必须保证同一波只 fork 一次。
        // ⚠️ 这个闸门是**跨线程**的 —— 用 running 而不只是时间戳：
        //    时间戳在"第一次还没写完"时挡不住第二批。
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "跳过快照（已有一次在进行中）")
            return false
        }
        lastTriggerAt.set(now)

        val used = usedBytes()
        val max = maxBytes()

        // ⚠️【用**专用线程**，不走共享泳道】—— 这是一个实测出来的坑，不是洁癖。
        //
        //    第一版用 ThreadPools.background（coreSize=1）。页面按钮提交一个"等结果"的任务
        //    后，那个线程会被占用，而 OOM 取证任务排在它**后面**，于是：
        //    受理=true（闸门过了），但 doSnapshot 一行日志都没打 —— 任务在队列里饿死。
        //    后果极具迷惑性：日志显示"已受理"，现场却没有文件，像 dump 失败又像没执行。
        //
        //    OOM 取证在**语义上**就要求这条路不能和任何东西共享：
        //      · 共享泳道可能正忙（一次 OOM 常伴随多线程连环抛，大家都在抢池子）；
        //      · 池子的优先级是 LOWEST，取证要的是"尽快落盘"，不是"让路给别人"；
        //      · 最坏情况是池子已被占满且全部阻塞 —— 那取证就永远发不出去。
        //    所以这里自己起一条线程（镜像 KOOM 的做法：dump 走独立线程，不进业务池）。
        var accepted = true
        val worker = Thread({
            try {
                doSnapshot(context, trigger, scene, used, max)
            } catch (t: Throwable) {
                // 取证失败必须可见；但也绝不能把异常抛回调用方（调用方可能正处在 OOM 路径上）
                Log.e(TAG, "OOM 快照失败（trigger=$trigger scene=$scene）", t)
                totalSnapshotFail.incrementAndGet()
                lastSnapshot = SnapshotRecord(
                    trigger = trigger, ok = false, file = null, sizeBytes = -1, totalMs = -1,
                    suspendMs = -1, flags = OomSnapshotNative.Flags.DEFAULT,
                    nativeHow = "exception", error = "${t.javaClass.simpleName}: ${t.message}",
                    histogram = null, parseError = null, usedBytes = used, maxBytes = max,
                    snapshotAt = now,
                )
            } finally {
                running.set(false)
            }
        }, "oom-snapshot")
        // 不设成 daemon：它可能比 Activity 活得久（父进程先死，子进程仍要写完快照）；
        // 但这个 Thread 只是**父进程侧**的调度，真正的 dump 在 native fork 出的子进程里。
        worker.priority = Thread.MIN_PRIORITY
        try {
            worker.start()
        } catch (t: Throwable) {
            Log.e(TAG, "OOM 快照线程启动失败", t)
            running.set(false)
            lastTriggerAt.set(0L)
            accepted = false
        }
        return accepted
    }

    private fun doSnapshot(context: Context, trigger: Trigger, scene: String, used: Long, max: Long) {
        val dir = File(context.filesDir, DIR_NAME).apply { if (!exists() && !mkdirs()) Log.w(TAG, "mkdirs 失败：$absolutePath") }
        val file = File(dir, "oom-${trigger.name.lowercase()}-${System.currentTimeMillis()}.hprof")

        Log.w(TAG, "开始 fork 快照：trigger=$trigger scene=$scene 路径=${file.absolutePath}")

        val result = OomSnapshotNative.forkDump(
            path = file.absolutePath,
            timeoutMs = DUMP_TIMEOUT_MS,
            flags = OomSnapshotNative.Flags.DEFAULT,
        )

        // ── 解析：能解析则解析，超限就只登记元数据 ──
        // ⚠️ 顺序很重要：**先确保文件落盘，再尝试解析**。解析失败绝不能影响文件保留 ——
        //    拿到一份几百 MB 的原始快照，远比"因为解析器上限而把文件删了"有价值。
        // ── 解析：**默认不做**。这是一个被实测纠正掉的设计，值得留档 ──
        //
        // 第一版在 doSnapshot 里顺手就把 hprof 解析了。真 OOM 实测的结果是：
        //
        //   E OomSnapshot: 解析 OOM 快照失败
        //   ⚠️ 未解析：OutOfMemoryError: Failed to allocate a 1048592 byte allocation
        //              with 92176 free bytes and 90KB until OOM
        //
        // 也就是说：**解析器自己 OOM 了**。原因很直白 —— 此刻父进程的堆还是满的
        // （实测 255.95/256MB），而解析 285MB 的 hprof 要建几万条类名/字符串映射，
        // 这个分配必然失败。
        //
        // 更值得警惕的是它的**腐蚀性**：一次失败无关紧要，但每来一次 OOM 就在
        // "已经很紧张"的堆上再砸一次大分配，等于让观测行为加重被观测的问题。
        //
        // 所以这里的选择是：**只落盘、不解析**，把解析留给"内存已经恢复"的时刻
        // （见 [analyzeLastSnapshot]）。这也和 [HeapDumpGate] 的纪律一致 ——
        // 重活必须在显式动作下发生，不在崩溃路径上顺手做。
        var parseError: String? = null
        if (result.ok && file.length() > 0) {
            parseError = "未在端上解析（OOM 时刻堆已满，端内解析会自己 OOM —— " +
                    "点「解析最近快照」在内存恢复后再解析，或用 shark/MAT 离线分析）"
        }
        val histogram: MemoryHeapAnalyzer.Histogram? = null

        val record = SnapshotRecord(
            trigger = trigger,
            ok = result.ok,
            file = if (result.ok) file else null,
            sizeBytes = result.sizeBytes,
            totalMs = result.totalMs,
            suspendMs = result.suspendMs,
            flags = result.flags,
            nativeHow = result.how,
            error = result.error,
            histogram = histogram,
            parseError = parseError,
            usedBytes = used,
            maxBytes = max,
            snapshotAt = System.currentTimeMillis(),
        )
        lastSnapshot = record

        if (result.ok) {
            totalSnapshotOk.incrementAndGet()
            Log.e(TAG, "OOM 快照成功：\n${record.describe()}")
            // ⚠️ 真 OOM 快照优先保留（protect = 它自己），预防性快照可以被它挤掉。
            //    理由：这两个文件的**分析价值不对等** —— 水位快照是"有总比没有好"，
            //    真 OOM 快照是"唯一能解释这次崩溃的证据"。如果它们公平竞争 2 个名额，
            //    两次水位快照就能把真 OOM 的现场挤出去。见 cleanupOldDumps 的注释。
            cleanupOldDumps(dir, KEEP_DUMPS, protect = if (trigger == Trigger.REAL_OOM) file else null)
            reportEvent(
                kind = StabilityReporter.Kind.MEMORY_CRITICAL,
                name = "OOM快照:${trigger.name}:$scene",
                detail = buildString {
                    appendLine("文件：${file.name}（${MemoryMetrics.fmt(result.sizeBytes)}）")
                    appendLine("耗时：合计 ${result.totalMs}ms，**父进程挂起 ${result.suspendMs}ms**")
                    appendLine("native：${result.how} flags=0x${Integer.toHexString(result.flags)}")
                    histogram?.let {
                        appendLine("Top5：")
                        it.top(5).forEach { stat -> appendLine("  ${stat.describe()}") }
                    }
                    parseError?.let { appendLine("⚠️ 未解析：$it") }
                },
            )
        } else {
            totalSnapshotFail.incrementAndGet()
            Log.e(TAG, "OOM 快照失败：\n${record.describe()}")
            reportEvent(
                kind = StabilityReporter.Kind.MEMORY_CRITICAL,
                name = "OOM快照失败:${trigger.name}:$scene",
                detail = "how=${result.how} err=${result.error}",
            )
        }
    }

    /**
     * 有界清理。
     *
     * ⚠️ 快照单份可达数百 MB，所以这里不是"顺手清一下"而是**必要的成本控制**：
     *    没有它，设备上几次 OOM 就能吃掉几个 G。
     *
     * @param protect 额外指定"必须保住"的一份（真 OOM 现场）。它就占用一个名额，
     *                其余名额按时间新→旧填。这样"两次水位快照挤掉真 OOM 现场"
     *                这种情况在结构上就不可能发生。
     */
    fun cleanupOldDumps(dir: File, keep: Int = KEEP_DUMPS, protect: File? = null): Int {
        if (!dir.exists()) return 0
        val files = dir.listFiles { f -> f.name.endsWith(".hprof") }
            ?.sortedByDescending { it.lastModified() } ?: return 0
        if (keep <= 0) {
            // keep=0 是"给我全删"（UI 的清理按钮），此时 protect 也不豁免
            var removed = 0
            files.forEach { if (it.delete()) removed++ }
            if (removed > 0) Log.w(TAG, "清理了 $removed 份快照（keep=0，全清）")
            return removed
        }
        val keepSet = LinkedHashSet<String>()
        if (protect != null && protect.exists()) keepSet.add(protect.name)
        for (f in files) {
            if (keepSet.size >= keep) break
            keepSet.add(f.name)
        }
        var removed = 0
        files.filter { it.name !in keepSet }.forEach { if (it.delete()) removed++ }
        if (removed > 0) Log.w(TAG, "清理了 $removed 份旧快照（保留 ${keepSet.size} 份）")
        return removed
    }

    /**
     * 事后解析：把"最近一次快照"的直方图算出来。
     *
     * ⚠️ 与 [doSnapshot] 里"不解析"的决定是一对 —— 见那里的注释。
     *    这个方法**必须由调用方确认内存已经恢复**（例如用户点按钮时，
     *    距离上一次 OOM 已经过去一段时间）。它不做这道判断，因为
     *    "什么算恢复"取决于调用场景；但它会把失败如实回报。
     *
     * @return 解析结果；失败返回 null 并把原因写进 [lastSnapshot] 的 describe()
     */
    fun analyzeLastSnapshot(): MemoryHeapAnalyzer.Histogram? {
        val rec = lastSnapshot ?: return null
        val file = rec.file ?: return null
        if (!file.exists()) return null
        return runCatching {
            MemoryHeapAnalyzer.parse(file, MemoryHeapAnalyzer.MAX_ANALYZE_BYTES_FOR_OOM)
        }.onFailure {
            Log.e(TAG, "事后解析快照失败（file=${file.name}）", it)
        }.getOrNull()
    }

    /** 事后解析指定文件（列表页用：可能要看的是更早那一份） */
    fun analyze(file: File): MemoryHeapAnalyzer.Histogram? =
        runCatching { MemoryHeapAnalyzer.parse(file, MemoryHeapAnalyzer.MAX_ANALYZE_BYTES_FOR_OOM) }
            .onFailure { Log.e(TAG, "解析快照失败（file=${file.name}）", it) }
            .getOrNull()

    // ─────────────────────────────────────────────────────────────────
    // 读数
    // ─────────────────────────────────────────────────────────────────

    private fun usedBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun maxBytes(): Long = Runtime.getRuntime().maxMemory()

    fun snapshotDir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun listSnapshots(context: Context): List<File> =
        snapshotDir(context).listFiles { f -> f.name.endsWith(".hprof") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun totalDiskUsage(context: Context): Long = listSnapshots(context).sumOf { it.length() }

    /**
     * 诊断读数。**换 ROM 后第一个该看的就是这里**（尤其"符号命中"那一段）。
     */
    fun describe(context: Context): String = buildString {
        appendLine("── OOM 快照（fork 子进程 dump 冻结堆）──")
        appendLine("开关=$enabled  可用=${isAvailable()}  运行中=${running.get()}")
        appendLine("最小间隔=${minIntervalMs / 1000}s  水位触发阈值=${(watermarkRatio * 100).toInt()}%  超时=${DUMP_TIMEOUT_MS / 1000}s")
        appendLine("观测到 OOM：${totalOomSeen.get()} 次；快照成功 ${totalSnapshotOk.get()} / 失败 ${totalSnapshotFail.get()}")
        appendLine("水位：${MemoryMetrics.fmt(usedBytes())} / ${MemoryMetrics.fmt(maxBytes())}")
        appendLine()
        val probe = OomSnapshotNative.probe()
        when {
            probe == null -> {
                appendLine("⚠️ native 不可用：${OomSnapshotNative.loadError}")
                appendLine("   （本机 abiFilters 只有 arm64-v8a；32 位设备上这个 .so 不存在）")
            }
            else -> appendLine(probe.describe().trimEnd())
        }
        appendLine()
        val files = listSnapshots(context)
        appendLine("磁盘：${files.size} 份 / ${MemoryMetrics.fmt(totalDiskUsage(context))}（保留上限 $KEEP_DUMPS 份）")
        files.take(5).forEach { f -> appendLine("  ${f.name}  ${MemoryMetrics.fmt(f.length())}") }
        appendLine()
        appendLine("⚠️ 能力边界（诚实标注）：")
        appendLine("  · 只覆盖 **Java 堆** OOM；native OOM 不抛 Java 异常，本通道抓不到")
        appendLine("  · 依赖 libart 具体符号（DumpHeap 签名随 ART 版本变）—— 换 ROM 先看上面的符号命中")
        appendLine("  · 会挂起父进程全部线程（实测毫秒级），是唯一会改 ART 运行时状态的采集通道")
        appendLine("  · 实测真 OOM 快照 285MB，超过自研解析器 64MB 上限 ⇒ 先落盘、能解析则解析")
        lastSnapshot?.let {
            appendLine()
            appendLine("── 最近一次 ──")
            appendLine(it.describe().trimEnd())
        }
    }

    /** 重置计数（实验页用） */
    fun resetCounters() {
        totalOomSeen.set(0)
        totalSnapshotOk.set(0)
        totalSnapshotFail.set(0)
        lastTriggerAt.set(0)
        lastSnapshot = null
    }

    private fun reportEvent(kind: StabilityReporter.Kind, name: String, detail: String) {
        runCatching {
            StabilityReporter.report(
                StabilityReporter.Event(
                    kind = kind,
                    timestamp = System.currentTimeMillis(),
                    name = name,
                    valueMs = 0,
                    threadName = Thread.currentThread().name,
                    isMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper(),
                    isForeground = StabilityReporter.isForeground(),
                    pid = android.os.Process.myPid(),
                    detail = detail.take(4000),
                )
            )
        }.onFailure { Log.w(TAG, "上报事件失败（已忽略，不影响取证）", it) }
    }

    /**
     * 子进程写一份几百 MB 的快照要多久？
     *
     * 实测（64MB 堆）：808ms；**真 OOM（255MB 堆）：1160ms**。
     * 但线上设备慢得多、堆更大、还可能正在被 LMK 追着杀 —— 给足余量，
     * 同时**必须有界**：超时就 SIGKILL 并删掉半成品（半个 hprof 没有分析价值）。
     */
    private const val DUMP_TIMEOUT_MS = 60_000L

    /** 设备信息摘要（上报时带上，用于按机型/版本聚合"符号是否命中"） */
    fun deviceSummary(): String =
        "SDK=${Build.VERSION.SDK_INT} ABIS=${Build.SUPPORTED_ABIS.joinToString(",")}"
}
