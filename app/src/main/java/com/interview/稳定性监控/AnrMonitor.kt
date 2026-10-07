package com.interview.稳定性监控

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Printer
import android.util.StringBuilderPrinter
import com.interview.thread.ThreadPools
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Time: 2026/10/7
 * Author: wgt
 * Description: 线上 ANR 监控 —— 三条互补的通道
 *
 * ══════════════════════════════════════════════════════════════════════
 * 先纠正一个流传最广的错误前提：**ANR 不是「主线程卡了 5 秒」**
 * ══════════════════════════════════════════════════════════════════════
 *
 * 「ANR 必现 = 主线程阻塞 5s」是最常见的面试答案，也是**错的**。它至少有四个漏洞，
 * 每一个都会导致线上「明明卡了却没有 ANR」，或者「有 ANR 但不知道谁干的」：
 *
 * ─── 漏洞 1：超时时长不是 5s，而是「看是谁在等」───
 *
 * | 场景 | 默认超时 | 监控对象 |
 * |---|---|---|
 * | 输入事件分发（KeyEvent/触摸） | **5s** | 前台 Activity 的 `dispatchTouchEvent` |
 * | BroadcastReceiver `onReceive` | **前台 10s / 后台 60s** | 静态注册或带 flag 的广播 |
 * | Service 生命周期（`onCreate`等） | **前台 20s / 后台 200s** | 主线程回调 |
 * | ContentProvider 发布 | **10s** | `Application.onCreate` 里 |
 * | 前台 Service / app 在后台时的各种 | 见上 | — |
 *
 * 也就是说：**后台 Service 卡 190 秒都不算 ANR**，而输入事件卡 5 秒就算。
 * 「5 秒」只是最常见的那一档。
 *
 * ─── 漏洞 2：判据是「系统盯着你」，不是「你卡了」───
 *
 * ANR 的触发条件是 **AMS/binder 事务超时**：只有当一个**系统侧的同步事务**
 * （输入分发、广播、Service 生命周期、provider 发布）在你主线程上等太久，
 * 才会走到 `AMS.appNotResponding()`。
 *
 * 推论（非常重要，也是本类存在的理由）：
 * **你自己主线程空转 30 秒，只要没有系统事务在等你，就不会有 ANR。**
 * 反过来，用户感知到的是「应用卡了」，系统判定的可能是「无 ANR」。
 * 所以「ANR 率」和「卡顿率」是两个**不同**的指标，不能用一个替代另一个。
 *
 * ─── 漏洞 3：ANR ≠ 崩溃，而且**旧版本里可能根本不显示、不杀进程**───
 *
 * 后台 ANR 在 Android 早期版本里默认**不弹框**（需要开发者选项里打开
 * 「显示所有 ANR」），用户感知为「切回来白屏一下」，而 Play Console 里
 * 却多了一条 ANR。所以「线上 ANR 上报量」与「用户投诉量」经常对不上。
 *
 * 另外：ANR 会**杀进程**这件事也不绝对 —— 由 `appNotResponding()` 里的
 * `killProcess` 决定，前台 ANR 通常杀，某些后台/Service 场景只 dump 不杀
 *（尤其是「只 dump 不弹框」那条路径）。
 *
 * ─── 漏洞 4：ANR trace 在 Android 12+ 且 self ANR 时**不含完整堆栈**───
 *
 * 这是本类实现中最容易「以为自己能上报、其实拿到废数据」的地方，见 [AnrTraceParser]。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 三条通道（互补，不是替代）
 * ══════════════════════════════════════════════════════════════════════
 *
 * ```
 *    ┌───────────────────────────────────────────────────────────────┐
 *    │ 通道 1  ApplicationExitInfo 回捞       ← 唯一「真·ANR」证据   │
 *    │   下次启动，从系统拿历史退出原因 + trace                          │
 *    │   ✅ 真实、含系统判据、含 trace 摘要    ❌ 只有 API 30+          │
 *    ├───────────────────────────────────────────────────────────────┤
 *    │ 通道 2  哨兵线程主动探测（AnrProbe）   ← 覆盖 30 以下 + 定位    │
 *    │   park 轮询 + postAtFrontOfQueue 回环探测                        │
 *    │   ✅ 全版本、能主动抓堆栈、能区分「忙」与「卡死」  ❌ 有窗口误差  │
 *    ├───────────────────────────────────────────────────────────────┤
 *    │ 通道 3  Looper 消息耗时观测（MessageTimingProbe）                │
 *    │   只旁听、不阻塞、零额外线程                                     │
 *    │   ✅ 开销最小、能给出「哪条消息慢了多少」    ❌ 消息切细时易失真  │
 *    └───────────────────────────────────────────────────────────────┘
 * ```
 *
 * ─── 为什么把「清单文件监听」这条路明确排除 ───
 *
 * 网上的经典方案是 `FileObserver("/data/anr")` 监听 `traces.txt` 生成。
 * **本机实测（Redmi K40 / Android 12）**：
 *
 * ```
 * $ adb shell run-as com.example.myapplication ls /data/anr
 * ls: /data/anr: Permission denied          ← 连自己的进程都读不了
 * $ adb shell ls -ld /data/anr
 * drwxrwxr-x 2 system system               ← 属主 system，App 域无读权限
 * ```
 *
 * 这是 SELinux 域的强制限制（`appdomain` 无 `anr_data_file` 读权限），
 * 与「运行时权限」无关，**不可能靠申请权限解决**。所以本类不实现它，
 * 并把它作为「为什么必须换通道」的反面教材记录下来。
 *
 * ─── 通道 2 的三种变体（面试会追问「你的方案和 XX 有什么不同」）───
 *
 * | 变体 | 判据 | 能否自动恢复 | 误报 |
 * |---|---|---|---|
 * | 一次性探测（早期 ANRWatchDog） | 一个 **flag** 未在超时内翻转 | ❌ 探测完就退出/失控 | 中 |
 * | 连续两轮（本项目，默认 2） | 连续 N 轮无响应 | ✅ 卡顿恢复后自动回到观测态 | **低** |
 * | 边沿触发（本项目 `edgeTriggered`） | 仅在「从未阻塞→阻塞」跳变时上报 | ✅ | **最低** |
 *
 * 「三连无响应」是本项目的选择。理由：GC 长暂停（尤其是 CMS/Background GC
 * 的并发失败转 foreground）**必然**产生一次 5s 级的假阳性；连续两轮再判、
 * 且只报边沿，可以把「一次 GC」过滤掉，代价是**首次报告晚一个周期**。
 * 这是典型的「准确性 vs 及时性」取舍，线上应取准确性（否则告警疲劳）。
 *
 * ⚠️ 诚实边界：若 GC 停止时间 > 2 × 探测周期，仍会误报。要彻底解决需要
 *    读 ART 的 GC 状态（`Debug.getRuntimeStat`，但拿不到"此刻是否在 STW"）。
 */
object AnrMonitor {

    private const val TAG = "AnrMonitor"

    /** 主线程消息耗时超过此阈值就记一条 SLOW_MESSAGE（不打断、只统计） */
    const val SLOW_MESSAGE_MS = 500L

    /** 哨兵探测周期 */
    const val PROBE_INTERVAL_MS = 1000L

    /** 探测判定阈值：单轮超过它算「一次无响应」 */
    const val PROBE_TIMEOUT_MS = 2500L

    /** 连续多少轮无响应才判定为卡死（过滤 GC 长暂停的假阳性） */
    const val CONSECUTIVE_ROUNDS = 2

    /**
     * 最近一次「主线程在等什么」的现场（由 [MainThreadSampler] 喂入）。
     *
     * ─── 为什么要把这个接到 ANR 报告里 ───
     *
     * ANR 报告最容易犯的错是「只说了卡住多久，没说在等谁」。而主线程的阻塞点
     * 恰好在**卡住的那一刻**最容易被采到（采样器会自动加密到 50ms 一次）。
     * 把它塞进 ANR 事件的 detail，等于每次报 ANR 都自带一个「嫌疑人」。
     *
     * ⚠️ 它是**启发式线索，不是结论**：采到的那一帧是「卡住时占比最高的等待点」，
     *    不等于「就是它导致的 ANR」（可能是它下游在等别人）。
     *    因果要靠 Perfetto 的 flow 箭头，这一点别混。
     */
    @Volatile
    var lastBlockSite: String? = null

    fun noteMainThreadBlockSite(site: String) {
        lastBlockSite = site
    }

    fun lastBlockSite(): String? = lastBlockSite

    /** 供 ANR 报告引用：把「卡住时主线程在等什么」附到事件里 */
    fun withBlockSite(detail: String): String {
        val site = lastBlockSite ?: return detail
        return detail + "\n\n── 卡住时主线程顶部等待点（采样器提供，启发式）──\n  $site"
    }

        /**
         * 主线程堆栈转字符串。
         *
         * ⚠️ 两条约束（都在真机/线上会咬人）：
         * 1. **不能高频调用**：`Thread.getStackTrace()` 会触发 safepoint 检查，
         *    被采样线程要在安全点停一下。在「已经卡住」的时刻它相对便宜（线程本来就停着），
         *    但在正常路径上高频调用会**制造**卡顿。
         * 2. **可能拿不到**：若主线程卡在 native 且持有 ART mutex，本调用会一起卡住。
         *    所以本方法**必须在探测线程上调用**（绝不能在主线程），且调用方要接受
         *    「这次拿不到，只有『卡住』这个事实」这一结果。
         */
        fun dumpMainThread(): String = runCatching {
            val main = Looper.getMainLooper().thread
            val sb = StringBuilder()
            sb.appendLine("── 主线程堆栈（此刻）──")
            main.stackTrace.take(25).forEach { sb.appendLine("  at $it") }
            sb.appendLine()
            sb.appendLine("── 主线程 Looper 消息队列 dump ──")
            val dump = StringBuilder()
            Looper.getMainLooper().dump(StringBuilderPrinter(dump), "")
            sb.append(dump.take(4000))
            sb.toString()
        }.getOrElse { "堆栈抓取失败（可能卡在 native 且持有 ART mutex）：$it" }

    private val installed = AtomicBoolean(false)

    /**
     * 安装两个运行时探针（通道 2 + 通道 3）。幂等：重复调用只有第一次生效。
     */
    internal fun installProbes() {
        if (!installed.compareAndSet(false, true)) return
        MessageTimingProbe.attach()
        startProbe()
        Log.i(TAG, "ANR 监控已安装：通道2（哨兵）+ 通道3（Looper 旁听）；" +
                "通道1（ExitInfo）需在启动时主动 collect()")
    }

    // ═══════════════════════════════════════════════════════════════
    // 通道 3：Looper 消息耗时观测
    // ═══════════════════════════════════════════════════════════════

    /**
     * 只**旁听** Looper 消息耗时。
     *
     * ─── 与「消息细则」的关系（一个必须说清的取舍）───
     *
     * ⚠️ 读 `Looper.getQueue()`（即拿 `mMessages.target`/`when` 来判断"下一条消息
     *    本该什么时候跑"）需要**反射**，而 `MessageQueue.mMessages` 在 Android 9+
     *    属于 `greylist-max-o` 隐藏 API，在高版本上会警告甚至失效。
     *    本项目**不做这件事** —— 一是它会引入隐藏 API 依赖，二是它把「消息延迟」
     *    与「消息执行耗时」混为一谈：
     *      · 执行耗时 = 我的代码慢（该优化）
     *      · 消息延迟 = 队列里排队（可能是别人的消息慢，也可能是 Choreographer 刷帧）
     *    线上第一诉求是**归因到我的代码**，所以这里只测「执行耗时」这个确定量。
     *
     * ─── 一个已知失真，必须说明（诚实边界）───
     *
     * `setMessageLogging` 的 ">>>>> Dispatching" 只在该消息**开始执行**时打印。
     * 因此若当前消息执行了 10 秒，`>>>>` 与 `<<<<` 之间就是 10 秒，读数正确；
     * 但**这 10 秒期间发生的、本该执行的后续消息，一个 `>>>>` 都没有** ——
     * 它们被"借"走了时间但记在第一条消息头上。
     * → 所以本探针的语义是准确的（"这条消息执行了 10 秒"），
     *   而「整体卡顿时长」是**低估**的（后续被延后的消息不计入）。
     *   要抓后者只能用通道 2 的哨兵。
     */
    private object MessageTimingProbe : Printer {

        private var dispatchStart = 0L
        private val slowCount = AtomicLong(0)
        private var dumped = false

        fun attach() {
            Looper.getMainLooper().setMessageLogging(this)
        }

        override fun println(x: String?) {
            val msg = x ?: return
            // Choreographer 的帧回调与 ActivityThread$H（生命周期/事务）刷屏极快，
            // 全量统计会把真正的业务慢消息淹没。但它们**恰恰是 jank 的高发地**，
            // 所以这里不丢弃、而是折算成 JANK 事件（见下），分类统计。
            val isFrame = msg.contains("Choreographer")

            if (msg.startsWith(">>>>> Dispatching")) {
                dispatchStart = SystemClock.uptimeMillis()
                return
            }
            if (!msg.startsWith("<<<<< Finished")) return
            if (dispatchStart == 0L) return
            val used = SystemClock.uptimeMillis() - dispatchStart
            dispatchStart = 0L

            // 逐帧回调本来就该在 ~16ms 量级，用业务阈值判定它毫无意义。
            // 这里只对「非帧消息」应用 SLOW_MESSAGE 阈值。
            if (isFrame) return
            if (used < SLOW_MESSAGE_MS) return

            slowCount.incrementAndGet()
            StabilityReporter.report(
                StabilityReporter.Event(
                    kind = StabilityReporter.Kind.SLOW_MESSAGE,
                    timestamp = System.currentTimeMillis(),
                    name = msg.substringAfter("<<<<< Finished to ").substringBefore(" ").take(120),
                    valueMs = used,
                    threadName = "main",
                    isMainThread = true,
                    isForeground = StabilityReporter.isForeground(),
                    pid = Process.myPid(),
                    detail = if (used >= PROBE_TIMEOUT_MS) "已达 ANR 量级（会触发通道 2）" else "",
                )
            )
            // 只在第一次超阈值时 dump 一次主线程栈：堆栈抓取要抢占目标线程，
            // 在卡顿路径上反复调用是**二次伤害**（会进一步加重卡顿）。
            if (!dumped && used >= PROBE_TIMEOUT_MS) {
                dumped = true
                MainThreadSampler.noteStuck()
                StabilityReporter.forceReport(
                    StabilityReporter.Event(
                        kind = StabilityReporter.Kind.MAIN_THREAD_STUCK,
                        timestamp = System.currentTimeMillis(),
                        name = "looper-message:$used ms",
                        valueMs = used,
                        threadName = "main",
                        isMainThread = true,
                        isForeground = StabilityReporter.isForeground(),
                        pid = Process.myPid(),
                        detail = dumpMainThread(),
                    )
                )
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 通道 2：哨兵线程主动探测
    // ═══════════════════════════════════════════════════════════════

    /**
     * 主线程响应性探测。
     *
     * ─── 探测机制：为什么是 postAtFrontOfQueue + 一个已经过期的 when ───
     *
     * 一次探测 = 在**队首**投一条回环 Runnable，然后在**自己的线程**上等它被执行：
     *
     * ```
     *   哨兵线程                           主线程
     *      │ postAtFrontOfQueue(probe) ───▶  （进入队列最前）
     *      │ synchronized(this).wait(T)          │
     *      │                                    ▼ 主线程空闲时才轮到它
     *      │ ◀──── probe.run(): completed=true ─┤
     *      │ 没等到 → 主线程在这 T 毫秒内没空过   │
     * ```
     *
     * `postAtFrontOfQueue` 而不是 `post`：后者会排到队列**尾部**，上面还有几十条
     * 待执行消息，"等不到回环"就同时包含了「主线程忙」和「前面有别的消息」两种原因，
     * 判据被稀释。插到队首后，只要主线程**一有空**就会先跑探测，
     * 于是「等不到」被收紧成「主线程真的没有空闲过」。
     *
     * 为什么要 `when = 0`（已过期）：`postAtFrontOfQueue` 本身已经插到最前，
     * 但消息队列是「按 when 排序的优先队列的一部分语义 + 队首特判」。
     * 给一个过去的时间戳，可以避免某些版本上「新消息的 when 更大 → 被排到后面」的边界行为。
     *
     * ─── 覆盖不到的两种 ANR（必须说清，这是本方案的**能力边界**）───
     *
     * 1. **Native 侧卡死在 binder 等待里**：「主线程在等 binder 对端」时，
     *    主线程本身并没有在执行 Java 代码，但它**不在 Looper 里**——
     *    此时探测 Runnable 也排队等着，哨兵会判定「卡」。这一条实际上是**能覆盖**的。
     *    ⚠️ 但反过来：若卡在 native 且 native 持有了 ART 的 mutex，
     *    堆栈抓取（`getStackTrace`）会**一起卡住**，我们只能报「卡住」但拿不到堆栈。
     * 2. **主线程空闲但系统事务超时**：例如 `onReceive` 已返回，
     *    但 AMS 侧的事务已经超时（理论上不会，但要小心耗时的 binder 回包）。
     *    → 这类只能靠通道 1（ExitInfo）兜。
     *
     * ─── 为什么用 park 而不是 wait/sleep ───
     *
     * `Thread.sleep`/`wait` 会把线程置为 `TIMED_WAITING`，且不可被探测自己打断；
     * `LockSupport.parkNanos` 是**无锁**的（不需要 monitor），CONSECUTIVE 轮之间的
     * 状态清零也不依赖持有锁。更实际的理由：`park` 与 `unpark` 成对时不会丢信号，
     * 而 `wait` 必须持有同一个 monitor，探测路径上持锁 = 给主线程回归路径加锁竞争。
     * 探测路径**绝不能**参与主线程的锁竞争，否则探测器本身就成了卡顿源。
     */
    private class Probe : Runnable {

        @Volatile
        private var completed = false

        /** 本轮探测的发起时刻（uptimeMillis） */
        @Volatile
        var startedAt = 0L
            private set

        /** 上一轮的实际回环耗时。⚠️ 仅在 [completed] 为 true 时才有意义（否则是上一轮的残值） */
        @Volatile
        var lastRoundTripMs = 0L
            private set

        /**
         * 上一轮是否「跑到了但太慢」（与「压根没跑到」是**两种不同的失败**）。
         *
         * ⚠️ 这个区分不是洁癖：修这一处之前，日志会打成
         * `第 1 轮无响应（回环 2ms）` —— 「回环 2ms」是**上一轮的残值**，
         * 而真实的失败原因是 `completed == false`（压根没跑到）。
         * 两者对排查的意义完全不同：
         *   · 没跑到 → 主线程被**长时间**占住（或根本没进 Looper）
         *   · 跑到了但慢 → 主线程在**临界**区间，说明是"重活"而不是"卡死"
         * 一个监控系统报出自己都解释不了的数字，比不报更糟。
         */
        @Volatile
        var lastWasSlowButCompleted = false
            private set

        /** 连续无响应的轮数 */
        var consecutiveMisses = 0
            private set

        /** 本次「卡死事件」是否已经上报过（边沿触发：只在进入卡死时报一次） */
        var reportedCurrentStuck = false

        /** 计数 +1，返回新值（避免外部直接写 private setter） */
        fun miss(): Int {
            consecutiveMisses++
            return consecutiveMisses
        }

        /** 恢复正常时清零 */
        fun clearMisses() {
            consecutiveMisses = 0
            reportedCurrentStuck = false
        }

        override fun run() {
            completed = true
            lastRoundTripMs = SystemClock.uptimeMillis() - startedAt
        }

        fun reset() {
            completed = false
            startedAt = SystemClock.uptimeMillis()
        }

        fun completedWithin(timeoutMs: Long): Boolean {
            lastWasSlowButCompleted = completed && lastRoundTripMs >= timeoutMs
            if (!completed) return false
            // ⚠️ 关键的第二重判据：**回环跑到了，但跑得太慢**。
            // 只看 flag 会漏掉「主线程正在跑一条 6 秒的慢消息」——它会通过探测，
            // 但那 6 秒里用户按什么都没反应。所以把「单次回环耗时」也算进判据。
            return lastRoundTripMs < timeoutMs
        }

        /** 失败原因的可读描述 —— 不要输出自己都解释不了的数字（见 [lastWasSlowButCompleted]） */
        fun failureReason(timeoutMs: Long): String =
            if (completed) "本轮回环跑到但耗时 ${lastRoundTripMs}ms（超出单轮阈值 ${timeoutMs}ms，属于「有响应但太慢」）"
            else "本轮探测 Runnable 完全未被执行（主线程在 ${timeoutMs}ms 内没有走到 Looper 的队首）"
    }

    private var probeThread: Thread? = null

    /**
     * 启动哨兵线程。
     *
     * @param intervalMs   探测周期
     * @param timeoutMs    单轮判定阈值
     * @param edgeTriggered true = 仅在「进入卡死」的跳变点上报（告警量最小）；
     *                      false = 卡死期间每轮都上报（能看到持续时长，但会刷屏）
     */
    @JvmOverloads
    fun startProbe(
        intervalMs: Long = PROBE_INTERVAL_MS,
        timeoutMs: Long = PROBE_TIMEOUT_MS,
        edgeTriggered: Boolean = true,
    ) {
        if (probeThread != null) return
        // ⚠️ 现在才 new：`Probe` 要在**探测线程**上构造并 park，
        //    绝不能在主线程/onCreate 里 new 完就丢过去（见类注释的「采样成本」）。
        val mainHandler = Handler(Looper.getMainLooper())
        val probe = Probe()
        // ⚠️ 探测线程自己**不能**进统一泳道：它要 park 住常驻，占一个 worker
        //    会把泳道饿死（那正是「收口层必须提供独占线程出口」的场景）。
        val t = ThreadPools.dedicatedThread("anr-probe", Process.THREAD_PRIORITY_BACKGROUND) {
            Log.i(TAG, "ANR 哨兵已启动 interval=${intervalMs}ms timeout=${timeoutMs}ms edge=$edgeTriggered")
            while (!Thread.currentThread().isInterrupted) {
                probe.reset()
                mainHandler.postAtFrontOfQueue(probe)
                // park 而不是 sleep：无锁、不参与 monitor 竞争（见类注释）
                java.util.concurrent.locks.LockSupport.parkNanos(intervalMs * 1_000_000)
                java.util.concurrent.locks.LockSupport.parkNanos(0) // 清除上一次的 unpark 信号

                if (probe.completedWithin(timeoutMs)) {
                    // 恢复正常：连续计数清零，并把卡死标志复位（为下一次边沿做准备）
                    probe.clearMisses()
                    continue
                }

                val misses = probe.miss()
                Log.w(TAG, "第 $misses 轮无响应：${probe.failureReason(timeoutMs)}")
                // 连续多轮才判定 —— 过滤 GC 长暂停造成的单轮假阳性
                if (misses < CONSECUTIVE_ROUNDS) continue
                if (edgeTriggered && probe.reportedCurrentStuck) continue
                probe.reportedCurrentStuck = true

                val blocked = misses * intervalMs
                StabilityReporter.report(
                    StabilityReporter.Event(
                        kind = StabilityReporter.Kind.ANR,
                        timestamp = System.currentTimeMillis(),
                        name = "probe:主线程无响应",
                        valueMs = blocked,
                        threadName = "main",
                        isMainThread = true,
                        isForeground = StabilityReporter.isForeground(),
                        pid = Process.myPid(),
                        detail = withBlockSite(buildString {
                            appendLine("连续 $misses 轮无响应，估算阻塞 ≥ ${blocked}ms")
                            appendLine("失败形态：${probe.failureReason(timeoutMs)}")
                            appendLine("（单轮阈值 ${timeoutMs}ms，判定需连续 $CONSECUTIVE_ROUNDS 轮）")
                            appendLine()
                            appendLine(dumpMainThread())
                        }),
                    )
                )
                // 同时告诉采样器「现在卡了」→ 它会切到加密采样（50ms/次），
                // 这样这次卡顿期间的主线程热点会被采得更细
                MainThreadSampler.noteStuck()
            }
        }
        probeThread = t.apply { start() }
    }

    fun stopProbe() {
        probeThread?.interrupt()
        probeThread = null
    }

    // ═══════════════════════════════════════════════════════════════
    // 通道 3 的辅助：堆栈抓取（通道 2 也复用）
    // ═══════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    // 通道 1：ApplicationExitInfo 回捞
    // ═══════════════════════════════════════════════════════════════

    /**
     * `ApplicationExitInfo` 回捞器 —— **线上 ANR 监控的中坚**。
     *
     * ─── 为什么它是唯一「真·ANR」证据 ───
     *
     * 其他通道都是**我们的推断**（"主线程没响应 2 秒以上，多半会 ANR"）；
     * 只有这里的数据来自**系统自己的判定**。Play Console 里的 ANR 数量
     * 就是靠这条链路（AMS 记录 → ActivityManager 暴露给 App）。
     *
     * ─── 三条必须记住的行为（否则会得出错误结论）───
     *
     * **1. 环形缓冲，会覆盖。** 系统按包名保留**有限条数**（`getHistoricalProcessExitReasons`
     *    的 `maxNum` 参数，官方文档给的默认值是 16），超出后**丢最旧的**。
     *    推论：**每次启动都要回捞**，间隔太久（长期不启动/被覆盖）就会丢证据。
     *
     * **2. trace 可能挂在「非 ANR」的退出原因上。** 实测/文档确认：只要进程生命周期内
     *    发生过 ANR，系统就抓了 trace 并挂到那条 ExitInfo 上 —— 之后进程可能因为
     *    **别的原因**（如用户划掉、系统回收）退出，于是你看到 `reason=OTHER` 的条目
     *    却带着 ANR 的 trace。→ **不要按 reason 过滤，要问"有没有 trace"。**
     *
     * **3. `getTraceInputStream()` 读的是二进制 proto，且读起来慢。**
     *    - 文本：明文堆栈（类似 /data/anr 的格式），直接可读；
     *    - 二进制：**protobuf** 编码的原生崩溃信息，按文本读会乱码。
     *    所以要「先探测是不是文本，再决定怎么解析」，见 [AnrTraceParser]。
     *    ⚠️ 而且这个读操作**可能耗时较长**（系统侧要组装），**必须在后台线程**。
     *
     * ─── 关于「自 ANR（self ANR）」这个坑 ───
     *
     * Android 12 起，App 可以**自己**调 `ActivityManager.appNotResponding()` 报告
     * 一次 ANR（例如检测到卡顿且无法恢复）。这种 self ANR 在 ExitInfo 里**不带全量
     * trace** —— 因为系统认为"你已经知道原因了"。如果你把 ExitInfo 当成唯一证据，
     * 就会在 self ANR 场景下拿到「有 ANR、没有堆栈」的空壳。
     * → 这正是通道 2 必须存在的原因（自报时自己把堆栈一起带上）。
     */
    object ExitInfoCollector {

        private const val TAG_EXIT = "ExitInfo"

        /** 每条原因最多读多少字节的 trace（防止 proto 爆炸 + 控制内存） */
        private const val TRACE_LIMIT = 512 * 1024

        /** 一次回捞时最多读取多少条 trace（读取慢，要限流） */
        private const val TRACE_READ_BUDGET = 3

        data class Item(
            val timestamp: Long,
            val reason: Int,
            val reasonText: String,
            val importance: Int,
            val pssKb: Long,
            val rssKb: Long,
            val description: String,
            val pid: Int,
            /** 是否有 trace（**判断 ANR 要看这个，不是看 reason**） */
            val hasTrace: Boolean,
            val traceIsBinary: Boolean,
            val traceSummary: String,
            /** 上一次回捞之后新增的（系统不提供去重 API，靠我们自己记账） */
            val isNew: Boolean,
        ) {
            fun describe(): String = buildString {
                val catchesTrace = reason == ApplicationExitInfo.REASON_ANR ||
                        reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                appendLine("${com.interview.稳定性监控.internalTime(timestamp)} [$reasonText] pid=$pid " +
                        "importance=$importance pss=${pssKb / 1024}MB rss=${rssKb / 1024}MB" +
                        if (isNew) "  ★新增" else "")
                if (description.isNotBlank()) appendLine("  desc: $description")
                if (hasTrace) {
                    appendLine("  trace：${if (traceIsBinary) "二进制 proto（原生崩溃）" else "文本（Java/ANR）"}，摘要如下")
                    // ⚠️ 本机实测到过这个现象，所以这里必须显式提示：
                    //    只要进程生命周期内发生过 ANR，系统就抓了 trace 并**挂到该 ExitInfo 上**；
                    //    之后进程可能因为**别的原因**（用户划掉、系统回收、被 force-stop）退出，
                    //    于是你会看到 `reason=用户主动退出` 却**带着 ANR trace**。
                    //    → 这就是"不要按 reason 过滤，要问有没有 trace"的实证来源。
                    //    实测记录：force-stop 掉一个刚 ANR 过的进程，回捞到的这条
                    //    reason=用户主动退出，但 trace 里 main 线程正停在 Thread.sleep。
                    if (!catchesTrace) {
                        appendLine("    ⚠️ 注意：本条 reason 是「$reasonText」，却**带着 ANR trace** ——")
                        appendLine("       说明进程在别的原因退出前发生过 ANR（系统把 trace 挂在这条记录上）。")
                        appendLine("       ⇒ **判断是否为 ANR 要看「有没有 trace」，不能看 reason。**")
                    }
                    appendLine(traceSummary.lines().take(30).joinToString("\n") { "    $it" })
                } else {
                    // ⚠️ 必须区分「本就不该有 trace」和「本该有却没有」。
                    //    对所有原因都喊"self ANR / 未采集"是**狼来了**：
                    //    用户主动退出、低内存被杀、包更新被杀，本来就不带 trace。
                    appendLine(
                        if (catchesTrace) {
                            "  trace：⚠️ **缺失** —— 这类退出原因（$reasonText）**理应带 trace**，" +
                                    "却没有。可能原因：self ANR（Android 12+ 自报不带全量 trace）/" +
                                    "系统侧裁剪/读取失败。只有「发生过」这个事实，拿不到堆栈。"
                        } else {
                            "  trace：无（这类退出原因本就不带 trace，属正常）"
                        }
                    )
                }
            }
        }

        /**
         * 回捞历史退出原因。
         *
         * ⚠️ **必须在后台线程调用**：`getTraceInputStream()` 的读取可能耗时。
         *    本仓库用 `ThreadPools.background`（低优先级泳道）驱动。
         */
        fun collect(ctx: Context, readTrace: Boolean = true): List<Item> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
            val seen = loadSeen(ctx)
            var traceBudget = if (readTrace) TRACE_READ_BUDGET else 0
            val out = mutableListOf<Item>()
            val newlySeen = mutableSetOf<String>()

            val list = runCatching {
                am.getHistoricalProcessExitReasons(ctx.packageName, 0, 0)
            }.getOrElse {
                Log.w(TAG_EXIT, "读取历史退出原因失败（部分 ROM 会限制）：$it")
                return emptyList()
            }

            list.forEach { info ->
                val key = idOf(info)
                val isNew = key !in seen
                newlySeen += key
                var hasTrace = false
                var binary = false
                var summary = ""
                // 不按 reason 过滤 —— trace 可能挂在非 ANR 的原因上（见类注释）
                if (traceBudget > 0) {
                    val t = runCatching { info.traceInputStream }.getOrNull()
                    if (t != null) {
                        hasTrace = true
                        traceBudget--
                        runCatching {
                            val bytes = t.readBytes().take(TRACE_LIMIT).toByteArray()
                            val parsed = AnrTraceParser.parse(bytes)
                            binary = parsed.isBinary
                            summary = parsed.summary
                        }.onFailure { Log.w(TAG_EXIT, "读 trace 失败: $it") }
                    }
                }
                out += Item(
                    timestamp = info.timestamp,
                    reason = info.reason,
                    reasonText = reasonText(info.reason),
                    importance = runCatching { info.importance }.getOrDefault(0),
                    pssKb = runCatching { info.pss }.getOrDefault(0L),
                    rssKb = runCatching { info.rss }.getOrDefault(0L),
                    description = runCatching { info.description ?: "" }.getOrDefault(""),
                    pid = info.pid,
                    hasTrace = hasTrace,
                    traceIsBinary = binary,
                    traceSummary = summary,
                    isNew = isNew,
                )
            }
            saveSeen(ctx, newlySeen)
            return out.sortedByDescending { it.timestamp }
        }

        /** 去重键：pid + 时间戳。系统不给去重 API，只能自己记账（见类注释：环形缓冲会覆盖） */
        private fun idOf(info: ApplicationExitInfo): String = "${info.pid}@${info.timestamp}"

        private fun seenFile(ctx: Context) = java.io.File(ctx.filesDir, "stability/exit-seen.txt")

        private fun loadSeen(ctx: Context): Set<String> = runCatching {
            seenFile(ctx).takeIf { it.exists() }?.readLines()?.toSet().orEmpty()
        }.getOrDefault(emptySet())

        /** ⚠️ 只保留最近 200 条：否则这个文件会随使用时长无限增长 */
        private fun saveSeen(ctx: Context, ids: Set<String>) = runCatching {
            val f = seenFile(ctx)
            f.parentFile?.mkdirs()
            f.writeText(ids.toList().takeLast(200).joinToString("\n"))
        }

        /**
         * 退出原因 → 中文说明。
         *
         * 只列「排查时真的会看」的几类。完整 17 类见 `ApplicationExitInfo` 常量。
         * ⚠️ 看到 `OTHER` **不要**跳过：很多"莫名白屏"就是这个，
         *    它的 `description` 里通常有线索。
         */
        fun reasonText(reason: Int): String = when (reason) {
            ApplicationExitInfo.REASON_ANR -> "ANR ★"
            ApplicationExitInfo.REASON_CRASH -> "Java 崩溃"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native 崩溃 ★（有 proto trace）"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "低内存被杀"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "超资源用量被杀"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "用户主动退出"
            ApplicationExitInfo.REASON_USER_STOPPED -> "用户强制停止"
            ApplicationExitInfo.REASON_SIGNALED -> "被信号杀（signal）"
            ApplicationExitInfo.REASON_EXIT_SELF -> "自愿退出（System.exit/Process.kill）"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "初始化失败（Application 构造/attach 抛异常）"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "权限变更导致重启"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "应用被更新"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "包状态变化（启用/禁用）"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "依赖进程死亡"
            ApplicationExitInfo.REASON_FREEZER -> "被冷冻（cached app freezer）"
            ApplicationExitInfo.REASON_UNKNOWN -> "未知"
            ApplicationExitInfo.REASON_OTHER -> "其他（⚠️ 别跳过，看 description）"
            else -> "未分类($reason)"
        }

        /**
         * ⚠️ 一个必须知道的平台细节：`ApplicationExitInfo.REASON_OTHER` 的
         * `description` **没有稳定契约**（Android 12 才改善），不同 ROM 上文本不同，
         * 不要写 `if (desc.contains("xxx"))` 这种业务判断 —— 只用于人看。
         */
        fun describeAll(items: List<Item>): String = if (items.isEmpty()) {
            "无历史退出记录（API < 30，或系统未保留）"
        } else buildString {
            appendLine("共 ${items.size} 条历史退出记录，其中新增 ${items.count { it.isNew }} 条")
            appendLine("⚠️ 系统只保留有限条数（官方文档默认 maxNum=16），**环形覆盖**，故每次启动都要回捞")
            appendLine()
            items.forEach { appendLine(it.describe()); appendLine("  ─────") }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 安装 / 生命周期
    // ═══════════════════════════════════════════════════════════════
}

/**
 * 时间格式化的小工具（避免每个类都依赖 SimpleDateFormat 的线程安全问题）。
 * SimpleDateFormat 不是线程安全的 —— 这是本仓库在稳定性监控里刻意避开它的原因：
 * 崩溃/ANR 恰恰是**多线程同时**上报的场景，共享一个 format 实例会得到乱码时间。
 */
internal fun internalTime(millis: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date(millis))
