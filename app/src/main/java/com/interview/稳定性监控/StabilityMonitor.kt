package com.interview.稳定性监控

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.interview.thread.ThreadPools

/**
 * Time: 2026/10/7
 * Author: wgt
 * Description: 稳定性监控总装 —— 把三条链路（Crash / ANR / 卡顿）装进 Application 生命周期
 *
 * ══════════════════════════════════════════════════════════════════════
 * 一、安装顺序是本类的核心内容（顺序错了会漏数据或制造 ANR）
 * ══════════════════════════════════════════════════════════════════════
 *
 * ```
 *   attachBaseContext
 *     └─ ① 装 UncaughtExceptionHandler   ← 必须最早！
 *          Application 自己的构造/attach 也可能崩（REASON_INITIALIZATION_FAILURE），
 *          晚装一秒就漏一秒。此时**不能**碰任何依赖 Context 的资源。
 *
 *   onCreate
 *     ├─ ② CrashJournal.beginSession()   ← 先读上次状态，再标 running（顺序不能反）
 *     ├─ ③ recoverPending()              ← 补报上批遗嘱（同步、极短，只读文件）
 *     ├─ ④ ExitInfo 回捞                 ← **必须丢到后台泳道**！
 *     │     getHistoricalProcessExitReasons + 读 trace 可能耗时上百毫秒，
 *     │     在 onCreate 里同步做 = 直接吃启动 ANR 预算（而且是冷启动最脆弱的时刻）
 *     ├─ ⑤ 装 ANR 探针（哨兵 + Looper 旁听）← 只注册，不起重活
 *     └─ ⑥ 起主线程采样器               ← 常驻低优先级线程
 *
 *   首帧之后（onActivityResumed）
 *     └─ ⑦ JankMonitor.watch(activity)  ← FrameMetrics 是 per-Window 的，
 *          只能等窗口存在了再挂；挂在 onCreate 会拿到 null 或漏掉首帧
 * ```
 *
 * ⚠️ **本类刻意不在 `Application` 里注册 ActivityLifecycleCallbacks 去自动 watch
 *    每个 Activity**。原因：FrameMetrics 的回调是**每帧一次**，
 *    而"监控工具变成卡顿源"最常见的形态就是"在生命周期回调里做了重活"。
 *    本项目让**需要帧数据的页面自己调** [watchJank]（Demo 页里就一处），
 *    这样监控的开关与作用域都是显式的、可审计的。
 *    生产环境若要全量挂，正确做法是**按采样率挑选页面**（如 1/50 的会话才开帧监控），
 *    而不是"所有页面全开"。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 二、一个必须解释的取舍：为什么把 ExitInfo 回捞放后台泳道
 * ══════════════════════════════════════════════════════════════════════
 *
 * 回捞这件事的两个属性互相矛盾：
 *   · **必须每次启动都做**（系统的环形缓冲会覆盖，见 `ExitInfoCollector` 注释）；
 *   · **读 trace 可能很慢**（系统侧组装 + 跨 binder 传几 MB）。
 *
 * 所以既不能省（会丢证据），也不能放主线程（会吃启动预算）。
 * → 放 `ThreadPools.background`（core=1、低优先级泳道）。
 *   选 background 而不是 disk：这不是磁盘任务，且它的优先级本就应该**低于**
 *   用户可见的启动工作（它属于"事后分析"，晚 200ms 没有任何影响）。
 *
 * ⚠️ 但它有一个真实代价：**若进程在回捞完成前就被杀，这次回捞白做。**
 *    对 "启动即崩" 的场景，我们靠的是 CrashJournal 的遗嘱（同步落盘），
 *    不依赖回捞 —— 这也是为什么两套机制都要有。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 三、采样率：线上必须做，而且是**分级的**
 * ══════════════════════════════════════════════════════════════════════
 *
 * | 数据 | 采样策略 | 依据 |
 * |---|---|---|
 * | 崩溃 | **必报**（100%） | 稀有且每一条都值钱 |
 * | ANR（ExitInfo / 哨兵） | **必报** | 同上 |
 * | 帧级 jank 明细 | 1/50 会话 | 量大、同质 |
 * | 卡顿聚合报告 | 1/5 | 聚合后信息密度高 |
 * | 主线程采样直方图 | 1/20 会话 | 体积大，靠聚合 |
 *
 * ⚠️ 本 Demo 为了「点一下就能看到数据」把采样率设成 1/1 或 1/5。
 *    **生产环境的默认值应当是上表的数字**，且必须能在服务端远程下发
 *    （线上卡顿风暴时，客户端自己不知道"此刻该降采样"）。
 */
object StabilityMonitor {

    private const val TAG = "StabilityMonitor"

    /** 本机是否开启了全部探针（Demo 用；生产应由 remote config 决定） */
    @Volatile
    private var started = false

    /** 会话内是否允许采集 jank 明细（生产环境这里是 1/50 的抽签结果） */
    @Volatile
    var jankDetailEnabled: Boolean = true

    @Volatile
    private var appContext: Context? = null

    /** 上一次回捞结果（Demo 展示用；真实场景直接上传后丢弃） */
    @Volatile
    var lastExitInfo: List<AnrMonitor.ExitInfoCollector.Item> = emptyList()
        private set

    @Volatile
    var lastSession: CrashJournal.SessionInfo? = null
        private set

    /**
     * `attachBaseContext` 阶段：**只装异常兜底**。
     *
     * ⚠️ 此刻不能碰 `filesDir` 之外的资源，也不能依赖任何尚未初始化的单例。
     *    所以 `CrashMonitor.install` 里对 Context 的使用被限制为
     *    `applicationContext.filesDir`（这是 attachBaseContext 之后就可用的）。
     */
    fun installEarly(app: Application) {
        CrashMonitor.install(app, swallowBackground = true)
        StabilityReporter.bindThreadGovernance()
        // ⚠️ 刻意**不**在这里给 sink 赋一个"打 logcat"的实现：
        //    `drainTo` 本身就会 logcat，两者叠加会让同一条事件打印两遍
        //    （实测过的现象）。sink 的定位是**接三方平台**，本仓库不内置 ——
        //    既避免重复日志，也避免在 demo 里出现真实 host/token
        //    （那属于"演示项泄露成生产配置"）。
        //    接平台时在这里注入：StabilityReporter.sink = { platform.send(it) }
    }

    /**
     * `onCreate` 阶段：会话标记 → 补报 → 起探针 → 后台回捞。
     */
    fun installOnCreate(app: Application) {
        if (started) return
        started = true
        appContext = app.applicationContext

        // ② 会话标记（先读后写，见 CrashJournal 注释）
        lastSession = CrashJournal.beginSession(app)
        Log.i(TAG, "会话状态：${lastSession?.describe()}")

        // ③ 补报上批崩溃遗嘱（同步，但只是读一个小文件 + 人手可数的条数）
        val recovered = CrashMonitor.recoverPending(app)
        if (recovered.isNotEmpty()) {
            Log.w(TAG, "补报 ${recovered.size} 条上次的崩溃遗嘱")
        }

        // ⑤ ANR 探针（哨兵 + Looper 旁听）
        AnrMonitor.installProbes()

        // ⑥ 主线程采样器
        MainThreadSampler.start()

        // ④ ExitInfo 回捞 —— 后台泳道，绝不阻塞启动
        val accepted = ThreadPools.background.execute("stability.exitinfo") {
            lastExitInfo = AnrMonitor.ExitInfoCollector.collect(app, readTrace = true)
            val newOnes = lastExitInfo.count { it.isNew }
            Log.i(TAG, "ExitInfo 回捞完成：共 ${lastExitInfo.size} 条，新增 $newOnes 条")
        }
        if (!accepted) {
            // 泳道饱和也不放弃：这是"证据"，丢了就没了。原地在**本线程**执行是不可接受的
            //（onCreate 里做重活 = ANR），所以退化到 disk 泳道再试一次。
            ThreadPools.disk.execute("stability.exitinfo.retry") {
                lastExitInfo = AnrMonitor.ExitInfoCollector.collect(app, readTrace = false)
            }
        }
    }

    /**
     * 挂帧监控。**由页面自己调用**（见类注释一：不自动化注册，理由）。
     * 重复调用同一 Activity 是安全的（内部去重）。
     */
    fun watchJank(activity: Activity) {
        if (!jankDetailEnabled) return
        JankMonitor.watch(activity)
    }

    /**
     * 需要**手动**上报一次聚合报告的时刻。生产环境由定时器（bg 泳道）驱动，
     * 例如每 30 秒：`ThreadPools.background.execute("stability.flush") { flush() }`。
     */
    fun flushJank(tag: String = "全局") {
        JankMonitor.flushAndReport(tag)
        if (jankDetailEnabled) JankMonitor.reportWorstFrames(3)
    }

    /**
     * 统一上报出口的常规驱动点：**发送 + 清账**。
     *
     * ⚠️ 这是"清账崩溃遗嘱"的唯一正确位置 —— 因为只有这里知道
     *    `drainTo` 是否**真的发送成功**。崩溃 handler 不能自证"我报完了"，
     *    启动时的 `recoverPending` 也不能（见 `CrashMonitor.recoverPending` 注释）。
     *
     * 生产环境应挂在定时器上（如每 30s / 每次进后台 / 每次冷启动后延迟一次），
     * 而不是"每个事件都立刻发"。
     */
    fun flush(upload: (List<StabilityReporter.Event>) -> Boolean): Boolean {
        val ok = StabilityReporter.drainTo(upload)
        if (ok) {
            context()?.let { CrashMonitor.acknowledgeJournal(it) }
        }
        return ok
    }

    /** 进程退出前的收尾（尽力而为，见 CrashJournal.endSession 的诚实边界） */
    fun onExit(app: Application) {
        CrashJournal.endSession(app)
    }

    /** Demo/测试用：把所有内存态统计清零 */
    fun resetAll() {
        StabilityReporter.reset()
        MainThreadSampler.reset()
        JankMonitor.reset()
    }

    fun context(): Context? = appContext

    // ─────────────────────────────────────────────
    // 汇总报告（Demo 与面试演示用）
    // ─────────────────────────────────────────────

    fun formatOverview(): String = buildString {
        appendLine("══════════ 稳定性监控总览 ══════════")
        appendLine("pid=${Process.myPid()}  前台=${StabilityReporter.isForeground()}")
        appendLine("SDK=${android.os.Build.VERSION.SDK_INT}  " +
                "ExitInfo 可用=${android.os.Build.VERSION.SDK_INT >= 30}")
        appendLine()
        appendLine("── 上次会话 ──")
        appendLine(lastSession?.describe() ?: "（未知）")
        appendLine()
        appendLine(StabilityReporter.describe())
        appendLine()
        appendLine(JankMonitor.describe())
        appendLine()
        appendLine("── ANR 监控 ──")
        appendLine("  通道1 ExitInfo：${if (android.os.Build.VERSION.SDK_INT >= 30) "已回捞 ${lastExitInfo.size} 条（新增 ${lastExitInfo.count { it.isNew }}）" else "不可用（API<30）"}")
        appendLine("  通道2 哨兵：运行中（每 1s 探测，连续 2 轮无响应判定）")
        appendLine("  通道3 Looper 旁听：已挂（阈值 500ms）")
        appendLine("  卡住时主线程等待点：${AnrMonitor.lastBlockSite() ?: "（尚未采到）"}")
        appendLine()
        appendLine("── 主线程采样 ──")
        appendLine("  样本数：${MainThreadSampler.total()}（上限 20000，有界）")
        appendLine("  采样间隔：空闲 200ms / 卡顿 50ms（自适应）")
        appendLine()
        appendLine("── 事件流水（最近 10 条）──")
        StabilityReporter.snapshot(10).forEach { appendLine("  ${it.oneLine()}") }
        appendLine()
        appendLine("⚠️ 生产环境必须做采样率分级（崩溃/ANR 必报，jank 明细 1/50、")
        appendLine("   采样直方图 1/20），且采样率应由服务端下发 —— 卡顿风暴时")
        appendLine("   客户端自己不知道「此刻该降采样」。本 Demo 为了可见性全开。")
    }
}
