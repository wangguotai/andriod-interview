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
        ReportDriver.stop()
        CrashJournal.endSession(app)
    }

    // ─────────────────────────────────────────────
    // 上报驱动（⚠️ 这里是"实验版 → 线上版"的那一步）
    // ─────────────────────────────────────────────

    /**
     * 统一的**上报驱动**：把 [flush] 挂到"该发的时候"。
     *
     * ══════════════════════════════════════════════════════════════════
     * 为什么必须有它（以及它为什么本该是最先写的一行）
     * ══════════════════════════════════════════════════════════════════
     *
     * 在加这个类之前，全仓**没有任何**定时器/`WorkManager`/`AlarmManager` 调用 `flush`——
     * 唯一会 `flush` 的是实验页那个「模拟上报」按钮。后果是：
     *
     * ```
     *   采集 → 判定 → 环形缓冲 → ✗ 停在这里
     * ```
     *
     * 也就是**"三通道都装了"的表象掩盖了"数据永远到不了平台"**。
     * 这与 `INTERVIEW-线上ANR监控方案.md` §1 审出的「周期性上报驱动不存在」
     * 是**同一个缺口** —— 因为内存模块复用了同一个出口，所以补一次两边都通。
     *
     * ══════════════════════════════════════════════════════════════════
     * 三条纪律（照抄 `CrashJournal` / 采样器的既有判断）
     * ══════════════════════════════════════════════════════════════════
     *
     * 1. **不在主线程跑**：`drainTo` 要遍历事件、跑 sink、可能序列化 ——
     *    丢到 `ThreadPools.scheduled`（该线程池是治理过的：命名、优先级、
     *    拒绝策略、队列深度可观测），**绝不** `new Thread` / `Executors` /
     *    `HandlerThread`（ASM 插件 + lint 会直接拦）。
     * 2. **周期 + 进后台各一次**：周期保证"一直不开后台也能上"；
     *    进后台那一次是**关键**——移动端大量会话是"退到后台后再也没回来"，
     *    不在这时发，这一整个会话的数据就随进程回收一起没了。
     * 3. **默认关**：与 SIGQUIT 通道、native 归因探针同一纪律。
     *    自动周期上报会真实消耗资源（唤醒 + IO），必须显式开启。
     *    实验页有开关，开的那个瞬间起效、关掉立刻停。
     *
     * ══════════════════════════════════════════════════════════════════
     * ⚠️ 本仓库的 upload 是**模拟提交**（刻意如此）
     * ══════════════════════════════════════════════════════════════════
     *
     * 真实上传需要 host/token，把那些写进开仓库的 demo 属于
     * "演示项泄露成生产配置"。所以这里注入一个**明确标注为模拟**的 upload：
     * 它**不联网**，只把批次记进 [StabilityReporter.uploadLog] 并打一行 logcat，
     * 供实验页当场查看"这次提交了什么"（这是它能被验证的前提）。
     * 接真实平台时替换这一个 lambda 即可：`upload = { platform.send(it) }`。
     */
    object ReportDriver {

        private const val TAG_DRIVER = "Stability"

        /** 周期上报间隔。⚠️ 生产口径应可远程下发（见 INTERVIEW §4.1） */
        const val DEFAULT_INTERVAL_MS = 30_000L

        private val running = java.util.concurrent.atomic.AtomicBoolean(false)
        private var future: java.util.concurrent.ScheduledFuture<*>? = null

        fun isRunning(): Boolean = running.get()

        /** 累计成功提交的批次数（与 `uploadedCount` 配合，能证明"发过、且没重复发"） */
        private val batches = java.util.concurrent.atomic.AtomicLong(0)
        fun batchCount(): Long = batches.get()

        /**
         * 打开驱动。重复调用是安全的（幂等）。
         *
         * @param intervalMs 周期；测试/演示可传小值（如 3s）快速看到效果
         */
        fun start(intervalMs: Long = DEFAULT_INTERVAL_MS) {
            if (!running.compareAndSet(false, true)) return
            val period = intervalMs.coerceAtLeast(1_000L)
            future = ThreadPools.scheduled.scheduleWithFixedDelay(
                { tick("周期") }, period, period, java.util.concurrent.TimeUnit.MILLISECONDS
            )
            Log.i(TAG_DRIVER, "上报驱动已开：每 ${period / 1000}s 一次 + 每次进后台一次（模拟提交，不联网）")
        }

        fun stop() {
            if (!running.compareAndSet(true, false)) return
            future?.cancel(false)
            future = null
            Log.i(TAG_DRIVER, "上报驱动已关")
        }

        /**
         * 触发一次上报（驱动内部用；实验页的"立即上报"按钮也走这里，
         * 保证"手动"和"周期"走的是**同一段代码**，不会一个能清账另一个不能）。
         */
        fun flushNow(reason: String): Boolean =
            flush { batch -> simulatedUpload(batch, reason) }

        private fun tick(reason: String) {
            runCatching { flushNow(reason) }
                .onFailure { Log.w(TAG_DRIVER, "上报驱动本轮失败（下轮继续，事件不会丢）", it) }
        }

        /**
         * 进后台时触发一次（由 `MemoryMonitor.onTrimMemory(UI_HIDDEN)` 调用）。
         *
         * ⚠️ 驱动**没开**时这里必须 no-op：否则"默认关"就是假话 ——
         *    用户以为关掉了，但每次退后台仍在偷偷上报。
         *    "关"的语义必须是**一条都不发**，不是"少发一条"。
         */
        fun onEnterBackground() {
            if (!running.get()) return
            tick("进后台")
        }

        /**
         * **模拟提交**：不联网。
         *
         * ⚠️ 返回 true 的语义是"已发送成功"——只有 true 才会清账（从环形缓冲移除）。
         *    这里返回 true 是**故意的**：好让"上报→清账→模拟出口里能看到"这条链路
         *    在实验页上完整可验证。**接真实平台时必须换成真实的成败**，
         *    否则会出现"发送失败但事件被清掉"的数据丢失。
         */
        private fun simulatedUpload(batch: List<StabilityReporter.Event>, reason: String): Boolean {
            // ⚠️ **空批次不算一次提交**。这里必须提前返回，别去动 `batches` 计数器 ——
            //    否则每 5s 的定时器哪怕一条事件都没有也会把计数往上加，
            //    于是"已提交 N 批次"变成在数**定时器滴答**而不是在数**数据**。
            //    （实测踩过：第一条真实事件的日志显示成"批次#2"，
            //    因为前两次空 tick 已经加过数了。计数器一旦失去意义，
            //    它就不能再用来回答"到底发出去了没有"。）
            if (batch.isEmpty()) return true
            val n = batches.incrementAndGet()   // 先自增再打印，编号与真实批次对齐
            val byKind = batch.groupingBy { it.kind.name }.eachCount()
            Log.i(
                TAG_DRIVER,
                "[模拟提交:$reason] 批次#$n：${batch.size} 条 | " +
                    byKind.entries.joinToString(" ") { "${it.key}=${it.value}" }
            )
            return true
        }
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
