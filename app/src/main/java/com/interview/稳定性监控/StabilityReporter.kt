package com.interview.稳定性监控

import com.interview.thread.ThreadErrorReporter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Time: 2026/10/7
 * Author: wgt
 * Description: 稳定性监控的**统一上报出口** —— 三类数据（Crash / ANR / 卡顿）共用一条上报管道
 *
 * ─── 为什么先做「上报出口」而不是先做采集 ───
 *
 * 崩溃平台的 SDK 已经是商品；真正决定这套东西能不能长在项目里的，是**收敛点**。
 * 采集源会越加越多（UncaughtExceptionHandler、ApplicationExitInfo、FrameMetrics、
 * Looper Printer…），如果每加一个采集源就顺手 `Log.e` + 直接调平台 SDK，
 * 结果必然是：限流各写一遍、字段各不相同、线上出事时**不知道该信哪一份**。
 *
 * 所以本类是「先定契约，再接采集」：
 *
 * ```
 *   采集源（多个）           本类（唯一出口）              平台（可替换）
 *   ─────────────           ──────────────              ─────────────
 *   CrashGuard        ┐
 *   AnrProbe          ├──▶  Event(归一化字段)  ──▶  sink(可插拔) ──▶ 自研/三方 APM
 *   ExitInfoCollector ┤         │                                  
 *   JankMonitor       ┘         └──▶ 本地环形缓冲（离线也能看）
 * ```
 *
 * ─── 三条设计约束（都来自线上真实教训，不是审美）───
 *
 * **1. 唯一出口 + 采样率在出口处统一决定。**
 *    「在哪采、采多少」是**容量规划**问题，必须集中。分散的下场是各源互不知情，
 *    一次卡顿风暴把 quota 打满，真正的崩溃反而被采样掉（崩溃是**必报**的，
 *    卡顿是**可采样**的 —— 两者优先级不同，只有集中才知道该牺牲谁）。
 *
 * **2. 上报本身不能引发 ANR / 卡顿。**
 *    本类所有 `report*` 方法都是**非阻塞**的：只做内存入队（环形缓冲大小有界），
 *    真正的序列化与发送交给 [drainTo]（调用方从 bg 泳道驱动）。
 *    ⚠️ 这条不是洁癖：崩溃发生在主线程时，任何在 handler 里做 IO 的实现都会
 *       把「崩溃上报」变成「崩溃 + ANR」。采集体量 vs 主线程预算的矛盾
 *       在整个稳定性监控里反复出现，这里是最先要处理的一处。
 *
 * **3. 事件必须能**自证**：每个事件带 `kind / 时间 / 进程pid / 线程名 / 是否主线程 /
 *    是否前台`。** 少了「是否前台」，线上永远分不清「启动即崩」和「后台被限流」；
 *    少了 pid，「进程重启后的历史事件」去重就无从谈起。
 *
 * ─── 诚实边界 ───
 *
 * · 环形缓冲只在**内存**里，进程被杀（含 ANR 被系统杀）时缓冲区随之消失。
 *   所以真正需要跨进程存活的只有两类：崩溃（[CrashJournal] 落盘）与
 *   ANR/退出原因（下次启动由 `ApplicationExitInfo` 回捞）。卡顿事件**允许丢**。
 * · `sink` 默认只打日志。接三方平台时注入即可 —— 本仓库不内置任何网络上报，
 *   避免在 demo 里出现真实的 host/token（那属于「演示项泄露成生产配置」）。
 */
object StabilityReporter {

    private const val TAG = "Stability"

    /** 事件种类。⚠️ 顺序即优先级：崩溃 > ANR > 卡顿（采样时按此顺序保高丢低） */
    enum class Kind {
        /** Java / 后台线程未捕获异常 */
        CRASH,

        /** 原生崩溃（由 ApplicationExitInfo 回捞，Engine 侧只能看到"上次死了"）*/
        CRASH_NATIVE,

        /** 主线程无响应。注意：**TRIGGERED 与 ANR_WINDOW 是两回事**，见 AnrMonitor */
        ANR,

        /** 帧超预算。可采样 */
        JANK,

        /** Looper 单条消息超阈值。可采样 */
        SLOW_MESSAGE,

        /** 主线程卡顿时的采样堆栈（由看门狗主动抓取）*/
        MAIN_THREAD_STUCK,

        /**
         * 内存水位升高（NORMAL→HIGH）。**只在越级时产生**，不是每次采样。
         * 见 `com.interview.内存.MemoryMonitor` 类注释 §二。
         */
        MEMORY_HIGH,

        /** 内存水位到临界（软上限 85%，或 committed 贴到硬上限 95%） */
        MEMORY_CRITICAL,

        /**
         * 系统下发的内存压力（`onTrimMemory` 的**压力轴**级别，含 onLowMemory）。
         *
         * ⚠️ 与 ANR 的区别：这一条**不是我们算的**，是系统告诉我们的，
         * 所以它是"为什么这个 App 在特定机型上被杀"的唯一客户端证据。
         */
        MEMORY_TRIM,

        /** 周期性的内存趋势聚合报告（有采样率，默认 1/5 会话） */
        MEMORY_TREND,
    }

    /** 归一化事件。字段刻意保持扁平 —— 便于直接映射到 APM 的宽表 */
    data class Event(
        val kind: Kind,
        /** 事件发生时刻（epoch millis） */
        val timestamp: Long,
        /** 归因标识：崩溃是异常类名，ANR 是触发场景，卡顿是页面/阶段 */
        val name: String,
        /** 量纲由 kind 决定：卡顿=耗时ms，ANR=阻塞ms，崩溃=0 */
        val valueMs: Long = 0,
        val threadName: String = "",
        val isMainThread: Boolean = false,
        val isForeground: Boolean = false,
        val pid: Int = 0,
        /** 附加载荷（堆栈 / trace 摘要 / 帧分布），已做截断 */
        val detail: String = "",
        /** 完整堆栈（仅崩溃类事件有）。⚠️ 只在崩溃路径上填，别给卡顿事件填 —— 体积大 */
        val stack: String = "",
    ) {
        fun oneLine(): String =
            "[${kind.name}] ${fmt.format(Date(timestamp))} pid=$pid " +
                    "name=$name value=${valueMs}ms main=$isMainThread fg=$isForeground " +
                    "thread=$threadName"
    }

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // ─────────────────────────────────────────────
    // 出口
    // ─────────────────────────────────────────────

    /**
     * 可插拔上报出口。接三方平台（Bugly/Sentry/Firebase…）时注入。
     * 签名收到的是**已归一化**的事件，采集源不需要知道平台的字段格式。
     */
    @Volatile
    var sink: ((Event) -> Unit)? = null

    /** 卡顿类事件的采样率（1/N）。崩溃与 ANR 恒为 1，不受此值影响 */
    @Volatile
    var jankSampleRate: Int = 5

    /** 是否已安装（由 StabilityMonitor.install 统一置位，防止重复安装） */
    @Volatile
    internal var installed = false

    private val totalByKind = java.util.concurrent.ConcurrentHashMap<Kind, AtomicLong>()

    /**
     * 有界环形缓冲：满了丢最旧的。**绝不无界增长**——那本身就是内存泄漏
     *
     * ⚠️ 容量从 200 提到 320，直接原因是**新增了内存事件**：
     *    内存监控会产生 MEMORY_TREND（每次几百~几千字符），
     *    它与崩溃/ANR 抢同一个缓冲。缓冲满时丢的是**最旧的**——
     *    如果内存趋势把缓冲刷满，最坏的后果是**丢掉一条崩溃**。
     *
     * ⇒ 所以除了扩容，还加了**分类配额**（见 [record]）：任何一类事件最多占
     *    [PER_KIND_RING_CAPACITY] 条，保证"量大的那一类"吃不掉整个缓冲。
     *    这是 `INTERVIEW-线上ANR监控方案.md` §2.2 那条「分类配额」在**客户端内存里**
     *    的对应实现 —— 服务端有配额，客户端内的缓冲同样要有。
     */
    private const val RING_CAPACITY = 320

    /** 单类事件在环形缓冲里的上限（保证高优先级类别永远有位置） */
    private const val PER_KIND_RING_CAPACITY = 96

    /** 模拟提交的镜像批数上限（够看最近几次即可；见 [uploadLog]） */
    private const val UPLOAD_LOG_CAPACITY = 10

    private val ring = ConcurrentLinkedDeque<Event>()
    private val ringCountByKind = java.util.concurrent.ConcurrentHashMap<Kind, AtomicLong>()

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    /**
     * 进程是否处于前台。
     *
     * ⚠️ **不是** `Process.getImportance()` —— 那是 `@hide` 的（我用 `javap` 核过
     *    android-34 的 android.jar，`Process` 里只有 `myPid/myTid/myUid` 等，
     *    没有 `getImportance`）。线上调用隐藏 API 在 Android 9+ 会吃
     *    `greylist` 警告，9 以后还可能直接抛。用公开的
     *    `ActivityManager.getMyMemoryState(RunningAppProcessInfo)`（API 16+）。
     *
     * ⚠️ 另一个坑：**Android 11+ 会给 importance 加一个相对偏移**（`IMPORTANCE_RELATIVE_OFFSET`
     *    = 1000），使「本进程相对其他进程」的排序在整机内可比。也就是说同一个
     *    前台进程，API 30 以下读到 100、API 30+ 可能读到 1100。
     *    若直接拿 `<= 125` 判前台，**在 Android 11+ 上会把前台误判成后台** ——
     *    这是个静默错误：采样率、ANR 归因都会跟着错，但没有任何报错。
     *    这里用 `% 1000` 归一化，两条路径都成立。
     *
     * 备注：采样决策只需要粗粒度，不值得为此上 ActivityLifecycleCallbacks 的
     * 引用计数（那反而引入生命周期泄漏风险）。
     */
    fun isForeground(): Boolean = runCatching {
        val info = android.app.ActivityManager.RunningAppProcessInfo()
        android.app.ActivityManager.getMyMemoryState(info)
        val base = info.importance % IMPORTANCE_RELATIVE_OFFSET
        base <= IMPORTANCE_FOREGROUND_SERVICE
    }.getOrDefault(false)

    /** ActivityManager.IMPORTANCE_FOREGROUND_SERVICE = 125（公开常量） */
    private const val IMPORTANCE_FOREGROUND_SERVICE = 125

    /** ActivityManager.IMPORTANCE_RELATIVE_OFFSET = 1000（API 30+ 起加到 importance 上） */
    private const val IMPORTANCE_RELATIVE_OFFSET = 1000

    // ─────────────────────────────────────────────
    // 上报入口（全部非阻塞）
    // ─────────────────────────────────────────────

    /**
     * 上报一个事件。
     *
     * ⚠️ **本方法可能从主线程调用**（崩溃 handler 里就是主线程），
     *    所以这里只允许「入队 + 计数」两种操作，任何 IO / 序列化都要挪到 [drainTo]。
     */
    fun report(event: Event) {
        val kind = event.kind
        // 采样：只对 JANK / SLOW_MESSAGE 生效。崩溃与 ANR **必报**——
        // 它们本身就是稀有事件，采样只会让线上"少掉几个崩溃"，得不偿失。
        if (kind == Kind.JANK || kind == Kind.SLOW_MESSAGE) {
            val rate = jankSampleRate.coerceAtLeast(1)
            if (rate > 1 && counter.incrementAndGet() % rate != 0L) return
        }
        record(event)
    }

    private val counter = AtomicLong(0)

    private fun record(event: Event) {
        totalByKind.computeIfAbsent(event.kind) { AtomicLong() }.incrementAndGet()
        ring.addLast(event)
        ringCountByKind.computeIfAbsent(event.kind) { AtomicLong() }.incrementAndGet()
        while (ring.size > RING_CAPACITY) {
            val dropped = ring.pollFirst() ?: break
            ringCountByKind[dropped.kind]?.decrementAndGet()
        }
        // 分类配额：某一类占满上限时，**从该类的头部**淘汰最旧的一条。
        // ⚠️ 必须从**自己这一类**里淘汰，而不是从环形缓冲头部 ——
        //    后者会误伤别的类别（正是我们要防的"量大的吃掉量小的"）。
        val mine = ringCountByKind[event.kind]?.get() ?: 0L
        if (mine > PER_KIND_RING_CAPACITY) {
            val it = ring.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.kind == event.kind) {
                    it.remove()
                    ringCountByKind[event.kind]?.decrementAndGet()
                    break
                }
            }
        }
        // ⚠️ 这里**只做入队 + 计数**。不调 sink、不打 logcat、不做任何 IO，
        //    **也不在这里触发发送**。
        //
        // 理由一（正确性）：`record` 会被崩溃 handler **在主线程或刚出错的线程上**调用。
        //   此处任何"顺手做点事"（序列化、写文件、发网络）都会把
        //   「一次崩溃」放大成「崩溃 + 卡顿/超时」，甚至在异常路径上再抛一次。
        //
        // 理由二（实测教训）：曾经在这里挂过一个"合并式自动 flush"，
        //   本意是让崩溃/ANR 尽快可见。但它带一个**硬编码的
        //   `upload = { true }`**（demo 没有真上传出口），于是语义变成了
        //   「记下 → 立刻宣称发送成功 → 从环形缓冲里删掉」：
        //     · 事件在几毫秒内就被清空 → 实验页的「事件流水」永远是空的
        //     · 「模拟上报」按钮恒报 0 条（要发的东西已经被"发"掉了）
        //     · 批量化彻底失效（等于一条一发）
        //   ⇒ **"尽快发送"必须由真正的 upload 出口（生产是定时器）来实现，
        //     不能在采集点上用一个假的成功回执来"模拟"。**
        //
        // 发送是**别人的职责**：由 [StabilityMonitor.flush] 驱动
        // （崩溃遗嘱另有 `CrashJournal` 兜底到下次启动，不会丢）。
    }

    /** 强制记录（绕过采样）。用于「手动触发」这一类必须留下证据的事件 */
    fun forceReport(event: Event) = record(event)

    // ─────────────────────────────────────────────
    // 读出 / 对接
    // ─────────────────────────────────────────────

    fun snapshot(limit: Int = 50): List<Event> = ring.toList().takeLast(limit)

    /**
     * **模拟提交的记录**（最近一次 `drainTo` 送出去的那一批）。
     *
     * 为什么需要它：`drainTo` 在成功后会**把事件从环形缓冲里移除**（不然会重复上报），
     * 于是"刚提交了什么"在 `snapshot()` 里**立刻消失** —— 实验页上表现为
     * "点完上报，事件列表空了"，看起来像丢了。
     * 这里留一份**只读镜像**（同样有界，只保留最后 [UPLOAD_LOG_CAPACITY] 批），
     * 用来回答"**这个模拟出口到底收到了什么**"——这是"模拟提交"能被验证的前提。
     */
    private val uploadLog = ArrayDeque<List<Event>>()

    /** 最近一次上报批次的条数（0 表示还没提交过） */
    @Volatile
    var lastUploadSize: Int = 0
        private set

    /** 累计已提交事件数（用于证明"提交确实发生了、且不是重复上报"） */
    private val uploadedTotal = AtomicLong(0)

    fun uploadedCount(): Long = uploadedTotal.get()

    /** 最近若干次提交的批次（新的在后）。只在实验页/排查时读。 */
    fun uploadLog(): List<List<Event>> = synchronized(uploadLog) { uploadLog.toList() }

    fun clearUploadLog() {
        synchronized(uploadLog) { uploadLog.clear() }
        lastUploadSize = 0
    }

    fun countOf(kind: Kind): Long = totalByKind[kind]?.get() ?: 0L

    fun reset() {
        ring.clear()
        ringCountByKind.clear()
        totalByKind.clear()
        counter.set(0)
    }

    /**
     * 把环形缓冲交给平台。**由调用方在后台线程驱动**（例如 bg 泳道定时调用），
     * 这样 UI 线程与崩溃 handler 都不承担序列化成本。
     *
     * 语义：sink/日志在这里统一执行；只有当 [upload] 返回 true（表示本轮**已发送**）
     * 才会从缓冲里移除事件。返回 false / 抛异常时事件保留，下次再报。
     *
     * ⚠️ 调用方应在此之后决定是否清账崩溃遗嘱（见 `StabilityMonitor.flush`）——
     *    清账的前提是**这一步真的成功**，不能凭空 acknowledge。
     *
     * @param upload 真正的发送实现。返回 true 表示发送成功。
     */
    fun drainTo(upload: (List<Event>) -> Boolean): Boolean {
        val batch = ring.toList()
        if (batch.isEmpty()) return true
        // 先观察（sink + logcat），再发送。两者都在**调用线程**上，不在崩溃路径上。
        batch.forEach { event ->
            runCatching { sink?.invoke(event) }
                .onFailure { android.util.Log.w(TAG, "sink 抛异常，已忽略", it) }
            logToLogcat(event)
        }
        val ok = runCatching { upload(batch) }.getOrDefault(false)
        if (ok) {
            batch.forEach {
                if (ring.remove(it)) ringCountByKind[it.kind]?.decrementAndGet()
            }
            // 记下这一批（只读镜像，见 uploadLog 注释）——否则"提交了什么"看不了
            synchronized(uploadLog) {
                uploadLog.addLast(batch)
                while (uploadLog.size > UPLOAD_LOG_CAPACITY) uploadLog.removeFirst()
            }
            lastUploadSize = batch.size
            uploadedTotal.addAndGet(batch.size.toLong())
        }
        return ok
    }

    /**
     * 接上线程治理里那个「可插拔 sink」。
     *
     * 这是两个子系统的接缝：线程池任务的异常（`ThreadErrorReporter`）与全局崩溃
     * （`CrashGuard`）本来就有各自的收集器，这里把它们**汇入同一条出口**，
     * 避免线上出现「线程池异常看不见」的老问题（`submit()` 吞异常那类）。
     */
    fun bindThreadGovernance() {
        ThreadErrorReporter.sink = { site, throwable ->
            report(
                Event(
                    kind = Kind.CRASH,
                    timestamp = System.currentTimeMillis(),
                    name = "task-error:${throwable?.javaClass?.simpleName ?: "Rejected"}",
                    threadName = Thread.currentThread().name,
                    isMainThread = android.os.Looper.myLooper() == android.os.Looper.getMainLooper(),
                    isForeground = isForeground(),
                    pid = android.os.Process.myPid(),
                    detail = "$site | ${throwable?.message ?: ""}".take(512),
                )
            )
        }
    }

    // ─────────────────────────────────────────────
    // 概览（Demo 展示用）
    // ─────────────────────────────────────────────

    fun describe(): String = buildString {
        appendLine("── 统一上报出口 ──")
        appendLine("采样率：JANK/SLOW_MESSAGE 1/$jankSampleRate；CRASH/ANR/内存水位 必报")
        appendLine("环形缓冲：${ring.size}/$RING_CAPACITY（有界，溢出丢最旧；单类上限 $PER_KIND_RING_CAPACITY）")
        appendLine("sink：${if (sink == null) "未接入（仅本地环形缓冲 + logcat）" else "已接入"}")
        appendLine()
        appendLine("── 分类计数 ──")
        Kind.entries.forEach { k ->
            appendLine("  %-18s %d".format(k.name, countOf(k)))
        }
    }

    /** 让事件也能进 logcat，方便 `adb logcat -s Stability` 观察 */
    fun logToLogcat(event: Event) {
        when (event.kind) {
            Kind.CRASH, Kind.CRASH_NATIVE, Kind.ANR, Kind.MEMORY_CRITICAL -> android.util.Log.e(TAG, event.oneLine())
            else -> android.util.Log.w(TAG, event.oneLine())
        }
    }

    /** 主线程包装：确保任何 UI 更新回到主线程（Demo 侧使用） */
    internal fun onMain(block: () -> Unit) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}
