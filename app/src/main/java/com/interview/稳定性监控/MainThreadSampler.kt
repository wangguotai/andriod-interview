package com.interview.稳定性监控

import android.os.Debug
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.interview.thread.ThreadPools
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Time: 2026/10/7
 * Author: wgt
 * Description: 主线程「谁慢」的采样器 —— 补上「卡住了，但不知道卡在哪」这个最大的缺口
 *
 * ══════════════════════════════════════════════════════════════════════
 * 为什么 ANR 哨兵 + 帧监控还**不够**
 * ══════════════════════════════════════════════════════════════════════
 *
 * 前两者都能精确回答「有多卡」：
 *   · 哨兵：主线程 2.5 秒没响应；
 *   · FrameMetrics：这一帧 78ms，最耗时段是 layout/measure。
 *
 * 但它们都只在**已经卡了之后**才抓到一次现场。而线上真正要回答的是：
 *
 * > **这个版本里，主线程时间到底花在哪些代码上？**
 *
 * 这个问题是**统计问题**，不是"抓现场"问题。答案是采样：
 * 周期性抓主线程堆栈，把大量样本按「栈顶方法」聚类，
 * 出现频率最高的那几帧就是**时间的主要去处**（这就是"poor man's profiler"，
 * 也是 debuggerd / simpleperf 之外最便宜的手段）。
 *
 * ─── 与「高精度方法耗时插桩（ASM，滴滴 Booster / 微信 Matrix）」的取舍 ───
 *
 * | 手段 | 精度 | 开销 | 能否上生产全量 |
 * |---|---|---|---|
 * | 采样堆栈（本类） | 统计意义（±几 %） | 极低（几十 µs / 次） | ✅ |
 * | ASM 插桩每个方法出入 | 精确到调用 | 高（尤其冷启动 + 大类） | ⚠️ 需灰度/开关 |
 *
 * 本项目选采样，理由：**采样的误差是可解释的**（样本数足够时就是时间占比），
 * 而插桩的开销是**不可预测的**（取决于方法数量与调用频率，且它本身会改变被测对象）。
 * 而且采样没有"编译期依赖"—— 三方 SDK 的代码也能采到（插桩做不到，见
 * `thread/README.md` 关于 `InstrumentationScope.PROJECT` 的实测说明）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 三条必须处理的细节（不做就得到废数据）
 * ══════════════════════════════════════════════════════════════════════
 *
 * **细节 1：网络/IO 上的栈必须能识别，否则会得出"SocketInputStream 是 CPU 热点"这种笑话。**
 * 采样到的栈顶若是 `SocketInputStream.socketRead0`（native）或
 * `Object.wait` / `Unsafe.park` / `LockSupport.park` / `Thread.sleep`，
 * 这条样本的语义是**等待**，不是**计算**。混在一起统计会让"谁最耗时"完全失真。
 * 本类把样本分成 [SampleKind.CPU] / [SampleKind.WAIT] / [SampleKind.LOCK]，
 * **分别聚合并分开汇报** —— 这一点是很多自研采样器的连续错处。
 *
 * **细节 2：抓堆栈本身有成本，且会与目标线程竞争。**
 * `Thread.getStackTrace()` 需要目标线程到达 safepoint。抓得太频繁（如 10ms 一次）
 * 会让主线程频繁停点，**制造出它本想测量的卡顿**。
 * 本项目的默认值：**空闲时 200ms 一次，卡顿时加密到 50ms**（自适应）。
 * 判断"卡顿"复用 [AnrMonitor] 的探测结果，避免自己再造一套。
 *
 * **细节 3：采样必须能关。**
 * 任何持续开销的功能都要有开关与预算上限。本类有：
 *   · [maxSamples] 硬上限（内存有界，绝不无界增长）；
 *   · [samplingEnabled] 运行期开关；
 *   · 只保留**归一化后的字符串**（方法签名），不保留 StackTraceElement 数组
 *     （后者每帧几十字节 × 每样本上百帧 × 上千样本 = 真实的几 MB 内存）。
 *
 * ─── 诚实边界 ───
 *
 * 1. **采样率与误差**：N 个样本的统计误差约 `1/√N`。1000 个样本 ≈ ±3%。
 *    要判断"某方法占 2% 还是 5%"需要上万样本 —— 所以线上要**长时间累积**，
 *    不要拿一次 30 秒的采样下结论。
 * 2. **无法区分"自己算"与"等别人算后自己算"**：采样只给栈，不给因果。
 *    要因果（"这 40ms 是被 binder 对端拖的"）必须靠 Perfetto 的 flow 箭头。
 * 3. **inlined 方法会被抹平**：AOT/JIT 内联后栈上可能看不到原本的方法名。
 */
object MainThreadSampler {

    private const val TAG = "MainThreadSampler"

    /** 样本类型 —— 见类注释细节 1。**必须分开统计**，否则结论会失真 */
    enum class SampleKind {
        /** 真正在算：栈顶是业务/框架的计算帧 */
        CPU,

        /** 在等 IO / 等网络 / 主动 sleep：栈顶是阻塞原语 */
        WAIT,

        /** 在等锁 */
        LOCK,
    }

    /** 聚合粒度：栈顶方法（归一化后）。value = 出现次数 */
    private val cpuHistogram = ConcurrentHashMap<String, AtomicLong>()
    private val waitHistogram = ConcurrentHashMap<String, AtomicLong>()
    private val lockHistogram = ConcurrentHashMap<String, AtomicLong>()

    private val totalSamples = AtomicLong(0)
    private val maxSamples = AtomicLong(20_000)

    @Volatile
    var samplingEnabled: Boolean = true

    private val running = AtomicBoolean(false)
    private var samplerThread: Thread? = null

    /** 最近一次「卡顿」的判定时间（由 AnrMonitor 的探测结果驱动，见 [noteStuck]） */
    private val lastStuckAt = AtomicLong(0)

    /** 空闲采样间隔 */
    private const val INTERVAL_IDLE_MS = 200L

    /** 卡顿时加密采样间隔 */
    private const val INTERVAL_STUCK_MS = 50L

    /** 卡顿窗口：判定后这段时间内保持加密采样 */
    private const val STUCK_WINDOW_MS = 3000L

    /** 栈最多看多少帧（够定位即可；深栈只增加成本与内存） */
    private const val MAX_DEPTH = 40

    private val WAIT_TOP = setOf(
        "java.net.SocketInputStream.socketRead0",
        "java.net.SocketInputStream.read",
        "sun.nio.ch.IOUtil.read",
        "android.os.MessageQueue.nativePollOnce",
        "java.lang.Thread.sleep",
        "android.os.SystemClock.sleep",
        "libcore.io.Linux.read",
        "libcore.io.Linux.write",
        "android.database.sqlite.SQLiteConnection.nativeExecute",
        "android.database.sqlite.SQLiteConnection.nativeExecuteForChangedRowCount",
        "android.database.sqlite.SQLiteSession.acquireConnection",
        "android.graphics.BitmapFactory.nativeDecodeStream",
    )

    private val LOCK_TOP = setOf(
        "java.lang.Object.wait",
        "java.util.concurrent.locks.LockSupport.park",
        "java.util.concurrent.locks.LockSupport.parkNanos",
        "java.util.concurrent.locks.LockSupport.parkUntil",
        "java.util.concurrent.CountDownLatch.await",
    )

    /** 由 ANR 探测/慢消息探针调用，用于把采样率切到"加密"档 */
    fun noteStuck() {
        lastStuckAt.set(SystemClock.elapsedRealtime())
    }

    /**
     * 启动采样线程。
     *
     * ⚠️ 用收口层的 [ThreadPools.dedicatedThread]：它是常驻线程（数量 O(1)），
     *    且**绝不能**放进共享泳道 —— 采样器自己 park 住会占死一个 worker。
     */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = ThreadPools.dedicatedThread(
            name = "main-sampler",
            priority = Process.THREAD_PRIORITY_BACKGROUND,
        ) {
            Log.i(TAG, "主线程采样器已启动（空闲 ${INTERVAL_IDLE_MS}ms / 卡顿 ${INTERVAL_STUCK_MS}ms）")
            while (!Thread.currentThread().isInterrupted) {
                val interval = if (isInStuckWindow()) INTERVAL_STUCK_MS else INTERVAL_IDLE_MS
                java.util.concurrent.locks.LockSupport.parkNanos(interval * 1_000_000)
                java.util.concurrent.locks.LockSupport.parkNanos(0)
                if (!samplingEnabled) continue
                if (totalSamples.get() >= maxSamples.get()) continue
                runCatching { sampleOnce() }
            }
        }
        samplerThread = t.apply { start() }
    }

    fun stop() {
        running.set(false)
        samplerThread?.interrupt()
        samplerThread = null
    }

    private fun isInStuckWindow(): Boolean =
        SystemClock.elapsedRealtime() - lastStuckAt.get() < STUCK_WINDOW_MS

    /**
     * 抓一次主线程栈并归入直方图。
     *
     * ⚠️ 必须在**采样线程**上调用（目标线程是主线程）。抓取会触发主线程到 safepoint，
     *    这是本方案唯一的侵入性成本，也是必须控制采样率的原因。
     */
    private fun sampleOnce() {
        val main = Looper.getMainLooper().thread
        val stack = main.stackTrace
        if (stack.isEmpty()) return

        // 归一化：把 StackTraceElement 立刻转成字符串，避免持有数组（见类注释细节 3）
        val normalized = stack.take(MAX_DEPTH).map { "${it.className}.${it.methodName}" }
        val top = normalized.firstOrNull() ?: return

        val kind = classify(normalized)
        val key = buildString {
            append(top)
            // 把关键上下文附上，避免"同一个方法名来自不同调用点"被合并。
            // ⚠️ 只取前若干帧：附得太长会让 key 基数爆炸（每个调用点都成独立项），
            //    反而看不出热点。
            normalized.drop(1).take(4).forEach { append(" ← ").append(it) }
        }
        val target = when (kind) {
            SampleKind.CPU -> cpuHistogram
            SampleKind.WAIT -> waitHistogram
            SampleKind.LOCK -> lockHistogram
        }
        target.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        totalSamples.incrementAndGet()

        // 采样到了 WAIT/LOCK 的顶层栈，说明此刻主线程在等 —— 这对 ANR 很关键：
        // 顺手把「主线程在等什么」记到 ANR 侧，避免 ANR 报告里只有"卡住"没有"在等谁"
        if (kind != SampleKind.CPU) {
            AnrMonitor.noteMainThreadBlockSite(top)
        }
    }

    /**
     * 分类。
     *
     * 判据用**归一化后的方法名精确匹配**，不用 `contains` ——
     * `contains("read")` 会把 `RecyclerView.read...`、`BufferReader.read` 全部误判。
     * 这个坑很常见（"我的采样器说主线程全在 read"）。
     */
    private fun classify(stack: List<String>): SampleKind {
        val top = stack.first()
        // `nativePollOnce` 是 Looper 空闲的标志，不是"在干活"。
        // ⚠️ 它出现意味着采样落在**主线程空闲**的时刻，此时应当**不计入任何热点** ——
        //    否则它会成为绝对值最高的"热点"，把真实结论淹掉。
        //    本实现把它归为 WAIT，并在输出时**单独说明**它的含义（见 formatReport）。
        if (top in LOCK_TOP) return SampleKind.LOCK
        if (top in WAIT_TOP) return SampleKind.WAIT
        // 兜底：栈里出现已知的阻塞原语（非栈顶）也算等待
        if (stack.take(3).any { it in LOCK_TOP }) return SampleKind.LOCK
        if (stack.take(3).any { it in WAIT_TOP }) return SampleKind.WAIT
        return SampleKind.CPU
    }

    /** 是否处于「主线程空闲」（采样落在 nativePollOnce）。报告里要单独排除 */
    fun isIdleSampleKey(key: String): Boolean =
        key.startsWith("android.os.MessageQueue.nativePollOnce")

    data class Hot(val method: String, val count: Long, val pct: Double) {
        fun line(): String = "  ${"%5.1f".format(pct)}%  ×$count  $method"
    }

    /** 按类型取热点（降序） */
    fun hot(kind: SampleKind, limit: Int = 15): List<Hot> {
        val hist = when (kind) {
            SampleKind.CPU -> cpuHistogram
            SampleKind.WAIT -> waitHistogram
            SampleKind.LOCK -> lockHistogram
        }
        val total = hist.values.sumOf { it.get() }.coerceAtLeast(1)
        return hist.entries
            .map { Hot(it.key, it.value.get(), it.value.get() * 100.0 / total) }
            .sortedByDescending { it.count }
            .take(limit)
    }

    fun total(): Long = totalSamples.get()

    fun reset() {
        cpuHistogram.clear()
        waitHistogram.clear()
        lockHistogram.clear()
        totalSamples.set(0)
    }

    /**
     * 报告。
     *
     * ⚠️ 输出里必须**显式区分 CPU / WAIT / LOCK**，并给出各自的占比。
     *    把它们混在一起是自研采样器最常见的错误（见类注释细节 1）。
     */
    fun formatReport(): String {
        val total = totalSamples.get()
        if (total == 0L) return "尚无采样数据（调用 MainThreadSampler.start() 后等待累积）"
        val cpu = cpuHistogram.values.sumOf { it.get() }
        val wait = waitHistogram.values.sumOf { it.get() }
        val lock = lockHistogram.values.sumOf { it.get() }
        return buildString {
            appendLine("【主线程采样报告】样本 $total 个")
            appendLine("采样间隔：空闲 ${INTERVAL_IDLE_MS}ms / 卡顿 ${INTERVAL_STUCK_MS}ms（自适应）")
            appendLine()
            appendLine("── 样本类型分布（**必须分开看**）──")
            appendLine("  CPU（真在算）      ${pct(cpu, total)}   ← 优化对象")
            appendLine("  WAIT（等 IO/睡眠）  ${pct(wait, total)}   ← 问题在「等得久」，不是「算得多」")
            appendLine("  LOCK（等锁）        ${pct(lock, total)}   ← 找锁竞争，不是优化方法体")
            appendLine()
            appendLine("── CPU 热点 Top（栈顶方法 ← 最近 4 层调用点）──")
            hot(SampleKind.CPU).forEach { appendLine(it.line()) }
            if (cpuHistogram.isEmpty()) appendLine("  （无 CPU 样本）")
            appendLine()
            appendLine("── WAIT 热点 Top（主线程在等什么）──")
            hot(SampleKind.WAIT, 8).forEach { appendLine(it.line()) }
            if (waitHistogram.isEmpty()) appendLine("  （无 WAIT 样本）")
            appendLine()
            appendLine("── LOCK 热点 Top（主线程在等哪把锁）──")
            hot(SampleKind.LOCK, 8).forEach { appendLine(it.line()) }
            if (lockHistogram.isEmpty()) appendLine("  （无 LOCK 样本）")
            appendLine()
            appendLine("── 读法（三个常见误判）──")
            appendLine("  1. `MessageQueue.nativePollOnce` 高 ⇒ 主线程**空闲**，说明没采到卡顿，")
            appendLine("     不是「热点」。它出现在 WAIT 里是分类正确，但**不要**把它当优化对象。")
            appendLine("  2. 「等锁」占比高时，优化方法体毫无意义 —— 要去掉那把锁的粒度。")
            appendLine("  3. 样本数 < 1000 时 ±5% 以内的差异**没有统计意义**（误差 ≈ 1/√N）。")
            appendLine()
            appendLine("⚠️ inlined 方法在 AOT/JIT 后可能从栈上消失，栈顶会「上移」到调用者。")
        }
    }

    private fun pct(v: Long, total: Long): String = "%5.1f%%".format(v * 100.0 / total.coerceAtLeast(1))

    /** 是否在主线程空闲时采样（Demo 展示用：说明"没卡"时的采样长什么样） */
    fun idleSampleCount(): Long =
        cpuHistogram.entries.filter { isIdleSampleKey(it.key) }.sumOf { it.value.get() }
}
