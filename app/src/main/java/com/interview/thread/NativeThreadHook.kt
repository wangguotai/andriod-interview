package com.interview.thread

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Time: 2026/9/27
 * Author: wgt
 * Description: 第 4 步「兜底」—— Native Hook pthread_create 的 Java 侧入口
 *
 * 这是唯一能捕获**所有**线程创建（含三方 SDK）的手段，因为
 * `Thread.start() → nativeCreate → pthread_create` 是绕不过去的路径。
 *
 * ─── 能力边界（曾写错，实测后修正）───
 *
 * 旧注释说「拿不到创建者的 Java 堆栈，因为新线程尚未 attach」——
 * 这个推理是错的。`hooked_pthread_create` 执行在**调用者线程**上，
 * 而调用者通常就是那个已 attach 的 Java 线程。
 *
 * 所以真实能力是：
 *
 * | 调用者 | 能否溯源 | 拿到什么 |
 * |---|---|---|
 * | Java 线程 | ✅ 能 | `Thread.currentThread().stackTrace` = 谁建的线程 |
 * | 纯 native 线程 | ❌ 不能 | 只有线程名；它本来就没有 Java 栈 |
 *
 * 这个区分本身极有价值：可直接回答「这个线程是 Java 代码建的，
 * 还是某个 SDK 的 native 代码建的」。
 *
 * ─── 为什么堆栈在 Java 侧抓，而不是 native ───
 *
 * 回调本身就在调用者线程上执行，Java 侧一句 `stackTrace` 就够；
 * 在 native 侧用 JNI 拼 StackTraceElement 要查 4 个类、循环取数组、
 * 逐帧转字符串 —— 代码量 10 倍且每帧一次 JNI 往返。
 * 「能不跨语言就别跨语言」是 JNI 编程的基本判断。
 *
 * ─── 方案选型 ───
 * 本项目采用 GOT Hook（兼容性优，可上线）。
 * 若作为**线下排查工具**，可换成 Inline Hook 以获得更广的覆盖范围。
 */
object NativeThreadHook {

    private const val TAG = "ThreadHook"
    private const val LIB_NAME = "threadhook"

    /** 采样上限：超过后只计数不抓栈 —— getStackTrace 有明显开销，不能每次全抓 */
    private const val STACK_SAMPLE_LIMIT = 50

    /** 是否已加载 native 库（设备/ABI 不支持时为 false，退化为不可用） */
    private var libraryLoaded = false

    /** Native 层观测到的线程创建总次数 */
    val nativeCreatedCount = AtomicInteger(0)

    /** 其中由 Java 线程创建的次数（能拿到创建者堆栈） */
    val fromJavaCount = AtomicInteger(0)

    /** 其中由纯 native 线程创建的次数（无 Java 栈可溯源） */
    val fromNativeCount = AtomicInteger(0)

    /** 创建者分布：调用点（class.method:line）→ 次数。这是溯源的核心产出。 */
    private val creationSites = ConcurrentHashMap<String, AtomicInteger>()

    /** 堆栈里要跳过的框架帧：这些是「建线程」机制本身，不是业务调用点 */
    private val SKIP_FRAMES = listOf(
        "java.lang.Thread",
        "java.lang.Throwable",
        "java.util.concurrent",
        "com.interview.thread.NativeThreadHook",
        "dalvik.system.VMStack",
        "com.android.internal.os",
    )

    /** 按次数降序取创建者分布 */
    fun creationSitesSnapshot(limit: Int = 15): List<Pair<String, Int>> =
        creationSites.entries
            .sortedByDescending { it.value.get() }
            .take(limit)
            .map { it.key to it.value.get() }

    fun reset() {
        nativeCreatedCount.set(0)
        fromJavaCount.set(0)
        fromNativeCount.set(0)
        creationSites.clear()
    }

    /**
     * 安装 Hook。
     * @return true 表示安装成功；false 表示当前环境不支持（不抛异常，优雅降级）
     */
    fun install(): Boolean {
        if (!loadLibrary()) {
            Log.w(TAG, "native 库不可用，Hook 跳过（功能优雅降级）")
            return false
        }
        return try {
            installNative()
            Log.i(TAG, "pthread_create Hook 安装成功")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Hook 安装失败", e)
            false
        }
    }

    fun uninstall() {
        if (!libraryLoaded) return
        runCatching { uninstallNative() }
    }

    private fun loadLibrary(): Boolean {
        if (libraryLoaded) return true
        return try {
            System.loadLibrary(LIB_NAME)
            libraryLoaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "loadLibrary($LIB_NAME) 失败：${e.message}")
            false
        }
    }

    /**
     * Native 层回调入口。必须为 public static，供 JNI 调用。
     *
     * ⚠️ 本方法**执行在调用者的线程上**（不是新建的那个线程），
     * 这正是我们能读到创建者堆栈的原因。
     *
     * @param payload 形如 "java|" （来自 Java 线程）或 "native|some-thread"（来自 native 线程）
     */
    @JvmStatic
    fun onThreadCreatedFromNative(payload: String) {
        val count = nativeCreatedCount.incrementAndGet()
        val isFromJava = payload.startsWith("java|")
        if (isFromJava) fromJavaCount.incrementAndGet() else fromNativeCount.incrementAndGet()

        // Hook 点绝不能做重活。堆栈采样有明确上限，超出后只计数。
        if (count > STACK_SAMPLE_LIMIT) {
            if (count == STACK_SAMPLE_LIMIT + 1) {
                Log.d(TAG, "已采样 $STACK_SAMPLE_LIMIT 次，后续只计数不抓栈（保护线程创建性能）")
            }
            return
        }

        val site = if (isFromJava) {
            // 当前线程就是调用者，直接读自己的堆栈 —— 无需任何 JNI 往返
            Thread.currentThread().stackTrace
                .firstOrNull { frame ->
                    SKIP_FRAMES.none { prefix -> frame.className.startsWith(prefix) }
                }
                ?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
                ?: "unknown"
        } else {
            payload.removePrefix("native|").ifEmpty { "unnamed" }
        }

        creationSites.computeIfAbsent(site) { AtomicInteger() }.incrementAndGet()
        Log.d(TAG, "捕获线程创建 #$count [${if (isFromJava) "Java" else "Native"}] $site")
    }

    // ═══════════════════════════════════════════════════════════
    // 治理策略：决策留在 Java（可配置、可热更新、好测试），
    // 执行放在 native（只有那里能改 attr、能拒绝创建）。
    // ═══════════════════════════════════════════════════════════

    /** 治理动作 */
    enum class Action(val code: Int) {
        ALLOW(0),   // 放行
        DEMOTE(1),  // 放行但降级（压低优先级）
        REJECT(2),  // 拒绝创建
    }

    /** 按调用点前缀匹配的策略规则 */
    data class Rule(val callerPrefix: String, val action: Action)

    /**
     * 策略表。前缀匹配，**第一条命中即生效**，务必以空前缀的兜底规则结尾。
     *
     * ⚠️ 默认全部 ALLOW —— 治理机制自身的默认姿态必须是「不干预」，
     * 否则一旦策略写错会直接搞崩接入的 SDK。
     * 生产上的正确节奏：先只观测一段时间 → 用真实数据定策略 → 再逐步加码。
     */
    @Volatile
    var rules: List<Rule> = listOf(Rule("", Action.ALLOW))

    /** 是否真正执行策略（默认关：只观测不动手，便于先收集数据） */
    @Volatile
    var enforceEnabled: Boolean = false

    /** 决策次数统计 */
    val allowCount = AtomicInteger(0)
    val demoteCount = AtomicInteger(0)
    val rejectCount = AtomicInteger(0)

    /**
     * 演示「Hook 降级」。
     *
     * 实测结论（本 Demo 踩过并验证）：
     *  - ❌ 在 pthread 入口 setpriority 设 nice **无效**：ART 在 Java 线程
     *    run() 时会重新应用 Java 层 priority，把它覆盖回 0。
     *    探针证据：设置后立刻回读是 10，但业务真正跑起来时 /proc 里是 0。
     *  - ✅ 同一点改 pthread_attr 的栈大小**有效**：栈在 pthread_create 时
     *    就 mmap 定死，ART 改不了。这才是 native 层稳定可用的控制手段。
     *
     * 结论：治内存（栈）交给 native，治调度（优先级）必须留给 Java 层。
     */
    fun applyStackShrinkPolicy() {
        rules = listOf(
            Rule("com.interview.thread.ThreadMisuseScenarios", Action.DEMOTE),
            Rule("", Action.ALLOW),
        )
        enforceEnabled = true
        reset()
    }

    /**
     * 供 native 调用的策略查询回调。
     *
     * ⚠️ 本方法在 Hook 点被**同步**调用，必须极快返回。
     * 只做前缀匹配（几条规则），绝不在这里做 IO / 抓堆栈 / 打日志。
     *
     * @return 0=放行 1=降级 2=拒绝
     */
    @JvmStatic
    fun onThreadCreateRequested(callerSite: String): Int {
        if (!enforceEnabled) {
            allowCount.incrementAndGet()
            return Action.ALLOW.code
        }
        val action = rules.firstOrNull { callerSite.startsWith(it.callerPrefix) }?.action
            ?: Action.ALLOW
        when (action) {
            Action.ALLOW -> allowCount.incrementAndGet()
            Action.DEMOTE -> demoteCount.incrementAndGet()
            Action.REJECT -> rejectCount.incrementAndGet()
        }
        return action.code
    }

    private external fun installNative()
    private external fun uninstallNative()
}
