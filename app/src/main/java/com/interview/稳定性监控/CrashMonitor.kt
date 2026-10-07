package com.interview.稳定性监控

import android.content.Context
import android.os.Process
import android.util.Log
import com.interview.thread.ThreadErrorReporter
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Time: 2026/10/7
 * Author: wgt
 * Description: 线上 Crash 监控 —— 「什么算崩」「怎么不丢」「怎么不谎报」
 *
 * ══════════════════════════════════════════════════════════════════════
 * 一、Android 上「进程死亡」有 4 条互不相通的出口，任何一条没接住都是静默失败
 * ══════════════════════════════════════════════════════════════════════
 *
 * ```
 *  ① 主线程 Java 异常      → UncaughtExceptionHandler → KillApplicationHandler → 必崩
 *  ② 子线程 Java 异常      → 同上（**同一套 handler**）。⚠️ 关键：它同样会杀进程，
 *                            所以"后台线程异常"常被误判成"随机崩溃/机型问题"
 *  ③ 线程池任务异常        → 分三条路（execute/submit/被拒），见 thread/README.md
 *                            submit() 的异常被吞进 Future —— 不 get() 就永远不知道
 *  ④ Native 崩溃           → 信号（SIGSEGV/SIGABRT…）→ debuggerd → tombstone。
 *                            Java 侧完全看不到，只能靠墓碑/ExitInfo 回捞
 * ```
 *
 * 本类负责 ①②④ 的「接住」与「落盘」，③ 由已有的 `ThreadErrorReporter` 负责，
 * 两者通过 [StabilityReporter.bindThreadGovernance] 汇到同一条出口。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 二、必须纠正的两个平台细节（本仓库已在真机/模拟器实测过）
 * ══════════════════════════════════════════════════════════════════════
 *
 * **细节 1：装了 handler 之后，logcat 里照样有 `FATAL EXCEPTION`，但进程可能没死。**
 *
 * Android 的 handler 链是：
 * ```
 *   RuntimeInit$KillApplicationHandler.uncaughtException()
 *        ├─ 1. 打印 FATAL EXCEPTION（Clog_e）
 *        ├─ 2. ehandler = thread.getUncaughtExceptionHandler(); ehandler.uncaughtException()
 *        │        ↑ 你的 handler 在这里被调用
 *        └─ 3. Process.killProcess(myPid())
 * ```
 * 它在**第 1 步就把 FATAL 打出来了**，然后才把控制权交给你。
 * → **`FATAL EXCEPTION` 行 ≠ 进程一定死了。判断是否真崩只看 PID。**
 *   （本仓库 thread/README.md 有对照表：无兜底 → PID 消失；有兜底 → PID 不变。）
 *
 * **细节 2：`Future.get()` 的异常不是你该「吞掉」的那个。**
 *
 * 常见写法是在 handler 里把一切异常吞掉「保证 App 存活」。**这是有害的**：
 * 吞掉主线程异常会留下**状态不一致的 App**（Activity 栈、生命周期、单例状态
 * 都停在半途），用户看到的是"点了没反应/白屏"，比崩溃更难查、更伤口碑。
 * → 本类的策略（与 `thread/CrashGuard` 一致）：
 *    · **主线程异常：上报后仍交给原 handler（必须崩）**；
 *    · **非主线程异常：上报后吞掉**（该任务失败，但进程可继续服务）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 三、为什么「上报了」这件事本身不可信 —— 遗嘱模式
 * ══════════════════════════════════════════════════════════════════════
 *
 * 崩溃上报是异步的。主线程崩溃时留给 handler 的时间窗口极短（几十~几百毫秒），
 * 后台线程大概率来不及完成序列化 + 网络发送。所以：
 *
 * ```
 *   handler 第一动作：CrashJournal.writePending(...)  ← 同步落盘 + fsync
 *   若最终进程存活（非主线程崩），标记 acknowledge
 *   下次启动：recoverPending() 把没走完的补报
 * ```
 *
 * 这条设计的价值在于**它不依赖网络、不依赖平台 SDK**，任何机型都能保证
 * 「至少不会丢」——这也是线上崩溃监控的第一性要求。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 四、Native 崩溃：App 内**接不住**，别假装能接
 * ══════════════════════════════════════════════════════════════════════
 *
 * 可以在 native 侧注册 `signal handler`（SIGSEGV 等）自己抓，但在 App 内做这件事
 * 有三个硬伤，本项目**不做**：
 *  1. **信号处理器运行在崩溃现场，栈可能已经损坏**，能在里面安全调用的函数极少；
 *  2. ART 自己就装了 handler（用于把 native crash 转成 Java 异常的地方）——
 *     抢装/链式调用容易把系统的崩溃收集（debuggerd/tombstone）搞坏，
 *     反而**丢掉平台侧证据**；
 *  3. Android 10+ 有 **crash_dump / debuggerd** 这套统一机制，
 *     自己抓的质量不如系统，且拿不到符号化所需的全部上下文。
 *
 * → 正确做法是**回捞**：`ApplicationExitInfo(REASON_CRASH_NATIVE)` 拿 proto trace
 *   （见 `AnrMonitor.ExitInfoCollector`），原始字节原样上传，平台侧符号化。
 *   在 App 内自己实现 proto 解析 + 符号化是负收益。
 */
object CrashMonitor {

    private const val TAG = "CrashMonitor"

    private val installed = AtomicBoolean(false)
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /** 我们**自己**安装的那个 handler，用于"临时卸下/装回"（见 [uninstall] / [reinstall]） */
    @Volatile
    private var ourHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var appContext: Context? = null

    /**
     * 安装全局崩溃捕获。
     *
     * @param swallowBackground true（推荐）：非主线程异常上报后吞掉，App 存活；
     *                          false：一律交回原 handler（保持"崩得干脆"）
     */
    @JvmOverloads
    fun install(ctx: Context, swallowBackground: Boolean = true) {
        appContext = ctx.applicationContext
        if (!installed.compareAndSet(false, true)) return

        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Log.i(TAG, "安装前默认 handler = ${previousHandler?.javaClass?.name}")

        val handler = object : Thread.UncaughtExceptionHandler {
            override fun uncaughtException(thread: Thread, e: Throwable) {
                val isMain = thread === android.os.Looper.getMainLooper().thread
                val stack = stackToString(e)
                val event = StabilityReporter.Event(
                    kind = StabilityReporter.Kind.CRASH,
                    timestamp = System.currentTimeMillis(),
                    name = e.javaClass.name,
                    threadName = thread.name,
                    isMainThread = isMain,
                    isForeground = StabilityReporter.isForeground(),
                    pid = Process.myPid(),
                    detail = buildString {
                        appendLine("message: ${e.message}")
                        appendLine("mainThread=$isMain  threadPriority=${runCatching { thread.priority }.getOrDefault(-1)}")
                        appendLine("线程状态：${runCatching { thread.state }.getOrDefault(Thread.State.RUNNABLE)}")
                        appendLine("（后台线程崩溃同样会杀进程 —— 默认 handler 是 KillApplicationHandler，")
                        appendLine("  这正是「随机崩溃」最常见的真身）")
                    },
                    // 事件里的 detail 已截断，完整堆栈交给遗嘱
                    stack = stack.take(2048),
                )

                // ① 先落盘：这是唯一「不依赖网络、不依赖进程存活」的一步。
                //    ⚠️ 必须在任何可能的阻塞操作（上报/网络）之前 —— 主线程崩溃时
                //    留给我们的窗口可能只有几十毫秒。
                StabilityReporter.forceReport(event)
                appContext?.let { CrashJournal.writePending(it, event, stack) }

                // ② 决定生死
                if (isMain || !swallowBackground) {
                    // 主线程异常**必须崩**。吞掉只会留下状态不一致的 App（见类注释细节 2）。
                    // ⚠️ 刻意**不动遗嘱** —— 本进程马上要死，留一条"未确认"记录，
                    //    由下次启动补报（这就是遗嘱机制存在的意义）。
                    Log.e(TAG, "主线程异常，交回原 handler（进程将结束）：${e.javaClass.name}")
                    runCatching { previousHandler?.uncaughtException(thread, e) }
                        .onFailure { Process.killProcess(Process.myPid()) }
                } else {
                    // 非主线程：异常已入环形缓冲 + 已落盘遗嘱，吞掉。
                    // ⚠️⚠️ 这里**不做任何发送/序列化** —— 本方法的调用点是
                    //      `UncaughtExceptionHandler`，在一个刚刚出错的线程上。
                    //      任何在此处的 IO（网络/文件）都会把"一次崩溃"放大成
                    //      "崩溃 + ANR/超时"，而且可能再抛一次。
                    //      发送是**别人的职责**：由 [StabilityMonitor.flush] 在后台周期驱动。
                    //      ⚠️ 同样**不 acknowledge** —— 遗嘱只应由"发送成功"来清账，
                    //      不能由崩溃 handler 自己说"我报完了"（那是自证，不可信）。
                    Log.w(TAG, "后台线程异常已捕获，等待上报出口发送：${thread.name}")
                }
            }
        }
        ourHandler = handler
        Thread.setDefaultUncaughtExceptionHandler(handler)
        Log.i(TAG, "崩溃捕获已安装（swallowBackground=$swallowBackground）")
    }

    // ─────────────────────────────────────────────
    // 临时卸下 / 装回（供对照实验使用）
    // ─────────────────────────────────────────────

    /**
     * ⚠️ **仅供实验**：临时把默认 handler 还原成"我们安装之前的那个"，
     * 让异常重新按平台默认行为**杀掉进程**。
     *
     * ─── 为什么需要这个开关（真实起因）───
     *
     * `thread/ThreadGovernanceActivity` 的「⚠无防护对照」实验，
     * 其**结论依赖**"后台线程抛异常 → 进程真的挂掉"。
     * 而稳定性监控在 `MyApplication.attachBaseContext` 就装了
     * `swallowBackground = true` 的全局 handler —— 实测（Redmi K40 / Android 12）
     * 那个实验因此**变成假绿**：点击前后 PID 都是 28026，进程根本没死。
     *
     * 与其把监控弱化（`swallowBackground = false`，那会牺牲线上能力），
     * 不如给实验一个**用完立即还原**的开关 —— 让"监控"和"对照"各归其位。
     *
     * @return 是否真的卸下了（未安装过则返回 false，调用方应据此提示"无防护对照不成立"）
     */
    fun uninstallForExperiment(): Boolean {
        if (!installed.get()) return false
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        Log.w(TAG, "⚠️ 实验性卸下崩溃 handler：异常将按平台默认行为杀进程" +
                "（原先为 ${previousHandler?.javaClass?.name}）")
        return true
    }

    /**
     * 与 [uninstallForExperiment] 成对：把**我们自己**的 handler 装回去。
     *
     * ⚠️ 注意这里装回的是 `ourHandler`（我们那个会被重启进程的版本），
     *    **不是** `previousHandler` —— 否则一次实验就永久失去了监控能力。
     */
    fun reinstallForExperiment(): Boolean {
        val ours = ourHandler ?: return false
        Thread.setDefaultUncaughtExceptionHandler(ours)
        Log.i(TAG, "实验结束，崩溃 handler 已装回")
        return true
    }

    /**
     * 启动时补报「上次没走完」的崩溃。
     *
     * ⚠️ 必须在**任何耗时初始化之前**调用。这里只把遗嘱读进内存环形缓冲，
     *    **不清账** —— 清账的唯一时机是 [StabilityMonitor.flush] 里的
     *    `drainTo` 返回 true（"真的发出去了"）。
     *
     * ─── 为什么这一处曾经写错（值得记住）───
     *
     * 最初的设计是"启动时读遗嘱 → 交给 sink → 成功后 acknowledge"。
     * 问题是 `sink` 的语义是**本地记录**（logcat/环形缓冲），不是"发送成功"。
     * 于是"启动即崩"的场景下：读 → 假装报了 → 清账 → 再崩 → 下次启动什么都没有。
     * **遗嘱被自己吃掉了。**
     *
     * 现在的语义是干净的：`recoverPending` 只负责"把未确认记录搬进内存"，
     * 清账交给真正发送成功的那一步。
     */
    fun recoverPending(ctx: Context): List<CrashJournal.Record> {
        val pending = CrashJournal.recoverPending(ctx)
        if (pending.isEmpty()) return emptyList()
        Log.w(TAG, "发现 ${pending.size} 条上次未确认的崩溃遗嘱（待上报出口发送后才清账）")
        pending.forEach { rec ->
            StabilityReporter.forceReport(
                StabilityReporter.Event(
                    kind = runCatching { StabilityReporter.Kind.valueOf(rec.kind) }
                        .getOrDefault(StabilityReporter.Kind.CRASH),
                    timestamp = rec.timestamp,
                    name = "${rec.name}（补报）",
                    threadName = rec.threadName,
                    isMainThread = rec.isMainThread,
                    isForeground = rec.isForeground,
                    pid = rec.pid,
                    detail = "来自遗嘱补报｜${rec.detail}",
                    stack = rec.stack,
                )
            )
        }
        return pending
    }

    /** 清账。⚠️ 只应由"发送成功"调用（见 [recoverPending] 的注释）。 */
    fun acknowledgeJournal(ctx: Context) = CrashJournal.acknowledge(ctx)

    /** 完整堆栈字符串。`PrintWriter` 包 `StringWriter` 是标准做法，且不涉及 IO */
    fun stackToString(e: Throwable): String = runCatching {
        val sw = StringWriter()
        PrintWriter(sw).use { e.printStackTrace(it) }
        // 链式异常（cause / suppressed）printStackTrace 已经递归包含，无需手动展开
        sw.toString()
    }.getOrDefault("<堆栈序列化失败>")
}
