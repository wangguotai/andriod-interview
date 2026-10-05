package com.interview.thread

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Time: 2026/9/27
 * Author: wgt
 * Description: 第 1 步「规范层」—— 全 App 统一线程池收口
 *
 * ─── 设计：一个治理入口，四条隔离泳道 ───
 *
 * 为什么不「全 App 共用一个 IO 池」：
 *
 * 1. 队头阻塞：FIFO 队列里，排在前面的慢任务会拖住后面所有快任务。
 *    前面堵 4 个 30s 的文件扫描，后面 50ms 的配置请求一起等 30s。
 * 2. 下游资源异构：网络受带宽与 RTT 约束、磁盘受尾延迟（写放大/GC）约束、
 *    SQLite 是单写者。用一个并发数服务三种资源，对每种都是错的。
 * 3. 全局故障面：一个 SDK 失控提交慢任务，全 App 的 IO 一起瘫痪 ——
 *    等于在另一个层面重建了「线程滥用」本身。
 *
 * 所以：**治理能力（命名/配额/监控）收在入口层，执行能力按下游资源分道**。
 * 这正是四层方案里「收口」的本意 —— 收的是治理，不是物理池。
 *
 * ─── 泳道大小的依据（要区分「推论」「惯例」「待实测」）───
 *
 * 不是「核数 × (1 + W/C)」—— 那是服务器追求 CPU 利用率最大化的公式，
 * 移动端的目标函数是「延迟 + 功耗」，故意不这么算。
 *
 * 定值问题不是「最多能开多少」，而是：
 *   在排队延迟（P99）不劣化的前提下，让下游资源恰好跑满的**最小**并发。
 *
 *  net = 4 / 8
 *    ① RTT 受限的小请求：吞吐 λ = n / (RTT + S/B)，W 几乎不随 n 变化，
 *       所以 n=1→4 是**线性收益**（100ms RTT 的接口，4 并发把 4 次串行的
 *       400ms 压到 ~100ms）。「加并发只是均分带宽」只对**带宽受限**的大文件
 *       传输成立 —— 它给的是**上界**，给不出 4 这个值。
 *    ② 上界由三件事压下来：per-host 连接池（OkHttp 默认 maxIdleConnections=5，
 *       HTTP/1.1 下超出连接数的请求只能串行）、服务端风控（单 host 并发过高
 *       会被 CDN/WAF 判异常）、每线程栈开销（泳道是跨域名的**全局**并发）。
 *    ③ 射频 race-to-sleep：传输结束后射频不立刻回 IDLE，RRC inactivity 的
 *       tail 是**秒级**高功耗窗口。但 tail 的**次数**取决于「请求间隔 vs
 *       timer」，不取决于并行度 —— 并行只是把窗口从 Σw 缩到 max(w)。
 *       真正治 tail 的是 **batching**（攒起来一次打完），不是加并发。
 *    ④ 「对齐 OkHttp maxRequestsPerHost=5」是**惯例**，不是移动端推论，
 *       而且它是 **per-host** 的。取 4 属于同量级对齐，别包装成推导。
 *
 *    ⚠️ 泳道并发 4 ≠ 网络并发 4。net 泳道的 4 个线程把请求交给 OkHttp，
 *       OkHttp 自己还有 Dispatcher（maxRequests=64 / maxRequestsPerHost=5）
 *       与连接池。两层是**叠乘**关系，不是同一层。
 *
 *  disk = 2 / 4
 *    ① 旧注释写「UFS/eMMC 队列深度低」**不准确** —— 它把两种相反的形态并列了：
 *         eMMC 4.4/4.5  单命令队列，主机侧无真正并行提交        → 依据成立
 *         eMMC 5.1+     引入 CQ（队列深 32），入门机实现普遍很浅
 *         UFS           SCSI-derived CQ，**理论队列深 32**，4K 随机读 IOPS
 *                       从 QD1 到 QD32 有数倍提升              → 依据不成立
 *    ② 那 2 还站得住吗？站得住，但理由在**软件栈**，不在设备 QD：
 *         · 文件系统元数据路径（open/stat/readdir）在 dentry/inode 锁上串行，
 *           这一段与块层并行度无关；
 *         · 应用层磁盘任务大量是 CPU 密集（解析/解码），加线程只是抢 CPU；
 *         · 随机**写**推高闪存 GC 与写放大，**P99 恶化远快于 P50**。
 *    ③ 所以 disk 限流的首要目的是**保尾延迟**，与 net 同源；标定必须用
 *       **随机读写混测** —— 顺序读会给出假的高拐点。
 *
 *  db   SQLite 单写者（WAL 下亦然），线程数 >1 纯粹在 DB 锁上排队。
 *  bg   后台预取/上报，低优先级 + 大配额，可牺牲延迟换吞吐。
 *
 * ─── 依据分级（完整表见 ../thread/README.md，别把这些混为一谈）───
 *   硬（可推导）  RTT 受限下 n=1→4 的线性收益；带宽受限下 ~2-3 后吞吐不增
 *   硬（有文献）  射频 tail 的收益来自**批次数**减少，支持 batching > 加并发
 *   硬（前提成立）eMMC 4.4/4.5 无有效并行提交
 *   惯例          OkHttp maxRequestsPerHost=5（且是 per-host）
 *   ❌ 与事实相反  「UFS 队列深度低」
 *   待实测        core=4/2、max=8/4 的具体取值
 *
 * ─── 队列容量与 max 的关系（本轮修正）───
 * ThreadPoolExecutor 的语义是「先填 core → 再入队 → 队列满才扩到 max」。
 * 旧配置 queueCapacity=32 时，net 要扩到 8 需已在途 4+32=36 个任务 ——
 * 那时 waitP99 早已爆掉，max 只在**过载区**生效。这与「修正 1：让扩容条件
 * 真正可达」自相矛盾（只是从「永不可达」变成「可达但无意义」）。
 * 故把队列收窄到与 max 匹配：net 8 / disk 4 / db 16 / bg 32，
 * 语义变成「稳态 core，超载借 max 并更早背压」。
 *
 * 精确值应由**并发扫描实测**标定（见 [LaneCalibration]）：扫 core=1..16，
 * 取「吞吐曲线平台起点」与「waitP99 触及 SLO 的交点」中**较小者**。
 * ⚠️ Little's Law 的 L=λW 给的是稳态在途数，**不能直接当并发配置** ——
 *    你要的是满足 P99 的最小 n，这是个需要实测的拐点问题。
 *    net 泳道的 W 本身依赖 n（带宽共享 + 服务端限流），是自反馈系统，无静态解；
 *    且必须在**低端机 + 弱网**这一最坏组合下复测（eMMC 机型的 λ 与 W 同时变差）。
 */
object ThreadPools {

    private const val TAG = "ThreadPools"
    private const val DEMO_CALLER = "ThreadPools.demo"

    // ─────────────────────────────────────────────
    // ThreadFactory
    // ─────────────────────────────────────────────

    /**
     * 统一 ThreadFactory：
     * - 语义化命名：便于线程快照、Hook 回溯、线上问题定位
     * - daemon：不阻止 JVM 退出（Android 进程被回收时）
     * - 优先级：**单一路径**，只用 [Process.setThreadPriority]，避免两层各设一次
     *
     * ─── 为什么不再同时设 Thread.priority（实测结论，非推测）───
     *
     * Android 上 `Thread.setPriority` 与 `Process.setThreadPriority` 落到的是
     * 同一个内核 nice，只是取值空间不同。实测映射（API 36 模拟器）：
     *
     *   Thread.setPriority(1..10)  →  nice 19,16,13,10,0,-2,-4,-5,-6,-8
     *   Process.THREAD_PRIORITY_BACKGROUND(10) → nice 10
     *
     * 也就是说 `Thread.setPriority(4)` 与 `setThreadPriority(BACKGROUND)` 等价
     * （都是 nice=10），同时设两遍是重复劳动，还会让 `Thread.getPriority()`
     * 报出一个与内核不一致的数（实测：走 Process 路径时 getPriority() 仍是 5）。
     *
     * 选 Process 路径的原因：java 层够不到 niceness 的极端区间 ——
     * java 最低只到 nice=-8，而 URGENT_AUDIO 需要 nice=-19。
     * 统一用 Process 常量，语义更清晰、覆盖更全。
     *
     * ⚠️ 若日后需要niceness < -8（音频/实时），继续用本路径即可；
     *    千万不要退回 Thread.setPriority —— 它表达不了。
     */
    class NamedThreadFactory(
        private val prefix: String,
        private val daemon: Boolean = true,
        private val androidPriority: Int = Process.THREAD_PRIORITY_DEFAULT,
    ) : ThreadFactory {
        private val counter = AtomicInteger(0)

        override fun newThread(r: Runnable): Thread =
            Thread({
                // ⚠️ setThreadPriority 作用于「当前线程」，且必须在目标线程内调用：
                // 在 newThread() 里直接调用只会改到创建者线程。
                runCatching { Process.setThreadPriority(androidPriority) }
                r.run()
            }, "$prefix-${counter.incrementAndGet()}").apply {
                isDaemon = daemon
            }
    }

    // ─────────────────────────────────────────────
    // 泳道
    // ─────────────────────────────────────────────

    /**
     * 泳道：一条隔离的执行通道，同时是「治理入口」。
     *
     * 承担：语义化命名、有界队列背压、调用方配额、可观测指标。
     */
    class Lane internal constructor(
        val name: String,
        private val coreSize: Int,
        private val maxSize: Int,
        private val keepAliveSeconds: Long,
        val queueCapacity: Int,
        /** 内核调度优先级（Process.THREAD_PRIORITY_*）。唯一优先级路径，见 NamedThreadFactory 注释 */
        androidPriority: Int,
        /** 单个调用方允许的在途任务上限，防止一个模块吃满整条泳道 */
        private val quotaPerCaller: Int,
    ) {
        private val queue = LinkedBlockingQueue<Runnable>(queueCapacity)

        private val executor: ThreadPoolExecutor = ThreadPoolExecutor(
            coreSize, maxSize, keepAliveSeconds, TimeUnit.SECONDS,
            queue,
            NamedThreadFactory("app-$name", androidPriority = androidPriority),
        ).apply {
            allowCoreThreadTimeOut(true)
            // ⚠️ 不用 CallerRunsPolicy：泳道可以被主线程提交，饱和时让主线程
            // 去跑 IO 任务 = 直接 ANR。改用 Abort，把背压显式抛回调用方，
            // 由它决定丢弃 / 降级 / 上报。
            rejectedExecutionHandler = ThreadPoolExecutor.AbortPolicy()
        }

        private val submittedCount = AtomicLong(0)
        private val rejectedByQueue = AtomicLong(0)
        private val rejectedByQuota = AtomicLong(0)
        private val errorCount = AtomicLong(0)
        private val waitSamples = ConcurrentLinkedQueue<Long>()
        private val inflight = ConcurrentHashMap<String, AtomicInteger>()

        data class Metrics(
            val name: String,
            val core: Int,
            val max: Int,
            val poolSize: Int,
            val active: Int,
            val queueDepth: Int,
            val queueCapacity: Int,
            val submitted: Long,
            val rejectedByQueue: Long,
            val rejectedByQuota: Long,
            val errors: Long,
            /** 任务从入队到开始执行的 P99 等待耗时（毫秒） */
            val waitP99Millis: Long,
        ) {
            val queueText: String
                get() = if (queueCapacity == Int.MAX_VALUE) "无界" else "$queueDepth/$queueCapacity"
        }

        fun metrics(): Metrics {
            val sorted = waitSamples.toList().sorted()
            val p99 = if (sorted.isEmpty()) {
                0L
            } else {
                sorted[(sorted.size * 99 / 100).coerceAtMost(sorted.size - 1)]
            }
            return Metrics(
                name = name,
                core = coreSize,
                max = maxSize,
                poolSize = executor.poolSize,
                active = executor.activeCount,
                queueDepth = queue.size,
                queueCapacity = queueCapacity,
                submitted = submittedCount.get(),
                rejectedByQueue = rejectedByQueue.get(),
                rejectedByQuota = rejectedByQuota.get(),
                errors = errorCount.get(),
                waitP99Millis = p99 / 1_000_000,
            )
        }

        /**
         * 提交任务。返回 false 表示被拒绝（配额超限或队列已满），调用方需自行降级。
         *
         * @param caller 调用方标识（模块/SDK 名），用于配额与归因
         */
        fun execute(caller: String, task: Runnable): Boolean {
            // 1) 调用方配额：挡住「一个模块吃满一条泳道」
            val counter = inflight.computeIfAbsent(caller) { AtomicInteger() }
            if (counter.get() >= quotaPerCaller) {
                rejectedByQuota.incrementAndGet()
                ThreadErrorReporter.onRejected(name, caller, "配额超限 ${counter.get()}/$quotaPerCaller")
                return false
            }
            counter.incrementAndGet()
            submittedCount.incrementAndGet()
            val enqueuedAt = System.nanoTime()
            return try {
                executor.execute {
                    recordWait(System.nanoTime() - enqueuedAt)
                    try {
                        // ⚠️ 异常必须在这里被接住并统一上报。
                        // 若放任它抛出去：execute() 路径会走到 worker 线程的
                        // UncaughtExceptionHandler，而 Android 默认 handler 是
                        // KillApplicationHandler —— 直接杀进程。
                        task.run()
                    } catch (t: Throwable) {
                        errorCount.incrementAndGet()
                        ThreadErrorReporter.onTaskError(name, caller, t)
                        if (ThreadErrorReporter.failFast) throw t
                    } finally {
                        counter.decrementAndGet()
                    }
                }
                true
            } catch (e: RejectedExecutionException) {
                // 2) 队列满：有界队列带来的背压，在此显式返回给调用方
                counter.decrementAndGet()
                rejectedByQueue.incrementAndGet()
                ThreadErrorReporter.onRejected(name, caller, "队列已满 $queueCapacity")
                false
            }
        }

        fun queueDepth(): Int = queue.size

        private fun recordWait(nanos: Long) {
            waitSamples.add(nanos)
            while (waitSamples.size > WAIT_SAMPLE_LIMIT) waitSamples.poll()
        }

        private companion object {
            const val WAIT_SAMPLE_LIMIT = 256
        }
    }

    // ─────────────────────────────────────────────
    // 四条泳道：执行隔离，互不拖累
    // ─────────────────────────────────────────────

    /** 网络：RTT 受限下并发有线性收益，上界由 per-host 连接池与风控压住 */
    val network: Lane by lazy {
        Lane(
            name = "net", coreSize = 4, maxSize = 8, keepAliveSeconds = 60,
            // 队列与 max 匹配：core 4 填满后，再有 8 个待处理就扩到 max=8；
            // 取 32 会让扩容要等到在途 36，max 形同虚设（详见类注释）
            queueCapacity = 8, androidPriority = Process.THREAD_PRIORITY_BACKGROUND, quotaPerCaller = 16,
        )
    }

    /** 磁盘：瓶颈在文件系统元数据锁与闪存写放大，限流首要目的是保 P99 */
    val disk: Lane by lazy {
        Lane(
            name = "disk", coreSize = 2, maxSize = 4, keepAliveSeconds = 60,
            queueCapacity = 4, androidPriority = Process.THREAD_PRIORITY_BACKGROUND, quotaPerCaller = 8,
        )
    }

    /** DB 写：SQLite 单写者，>1 只是在锁上排队 */
    val dbWrite: Lane by lazy {
        Lane(
            name = "db", coreSize = 1, maxSize = 1, keepAliveSeconds = 30,
            queueCapacity = 16, androidPriority = Process.THREAD_PRIORITY_BACKGROUND, quotaPerCaller = 64,
        )
    }

    /** 后台：预取/清理/上报，低优先级，可牺牲延迟 */
    val background: Lane by lazy {
        Lane(
            name = "bg", coreSize = 1, maxSize = 2, keepAliveSeconds = 30,
            queueCapacity = 32, androidPriority = Process.THREAD_PRIORITY_LOWEST, quotaPerCaller = 128,
        )
    }

    // ─────────────────────────────────────────────
    // 非 IO 型池
    // ─────────────────────────────────────────────

    private const val CPU_QUEUE_CAPACITY = 64

    /** CPU 密集型：计算。有界队列形成背压。 */
    val cpu: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            CORE_CPU, CORE_CPU, 30L, TimeUnit.SECONDS,
            LinkedBlockingQueue(CPU_QUEUE_CAPACITY),
            guardedFactory("cpu", "app-cpu", Process.THREAD_PRIORITY_DEFAULT),
        )
    }

    /** 串行任务：需要保证顺序的场景。 */
    val single: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(128),
            guardedFactory("single", "app-single", Process.THREAD_PRIORITY_DEFAULT),
        )
    }

    /** 定时 / 延时任务。 */
    val scheduled: ScheduledExecutorService by lazy {
        Executors.newScheduledThreadPool(
            2,
            guardedFactory("scheduled", "app-scheduled", Process.THREAD_PRIORITY_BACKGROUND),
        )
    }

    /** 收敛池：ASM 把 new Thread 收敛后的最终落点 */
    val converged: Lane by lazy {
        Lane(
            name = "converged", coreSize = 2, maxSize = 8, keepAliveSeconds = 60,
            queueCapacity = 32, androidPriority = Process.THREAD_PRIORITY_BACKGROUND,
            // caller 恒为 asm-converged，配额无隔离意义，给足即可
            quotaPerCaller = 1024,
        )
    }

    /**
     * 演示用：一个「不受控」的池，故意用默认 ThreadFactory + SynchronousQueue，
     * 用来在线程快照里对照出「命名率」差异。
     */
    val unnamed: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            0, 32, 60L, TimeUnit.SECONDS,
            SynchronousQueue(),
            Executors.defaultThreadFactory(), // ← 产出 pool-1-thread-N
        )
    }

    /**
     * ⚠️ 反面教材（仅供对照实验）：重构前的「全 App 单一 IO 池」。
     *
     * 它浓缩了旧设计的两处硬伤：
     * 1. `maximumPoolSize = 16` 是死配置 —— 队列无界（Integer.MAX_VALUE），
     *    execute() 第 3 步「队列满才扩容」永不成立，实际并发上限恒为 core = 4。
     * 2. 单一 FIFO 队列 → 慢任务队头阻塞快任务。
     *
     * 用 [demoLaneIsolation] 可把这两点量化出来。
     */
    val legacyShared: Lane by lazy {
        Lane(
            name = "legacy-io", coreSize = 4, maxSize = 16, keepAliveSeconds = 60,
            queueCapacity = Int.MAX_VALUE, // ← 无界，导致 max 永不生效
            androidPriority = Process.THREAD_PRIORITY_BACKGROUND,
            quotaPerCaller = Int.MAX_VALUE,
        )
    }

    private val CORE_CPU = maxOf(2, Runtime.getRuntime().availableProcessors() - 1)

    // ─────────────────────────────────────────────
    // 对照实验
    // ─────────────────────────────────────────────

    /**
     * 对照实验：慢任务队头阻塞。
     *
     * 同一批任务（4 个 800ms 磁盘任务 + 1 个 20ms 网络任务），分别在
     * 「单一池」和「泳道」下执行，测量网络任务从提交到开始执行的等待时间。
     */
    fun demoLaneIsolation(): String {
        val slowMillis = 800L
        val slowCount = 4
        val sb = StringBuilder()
        sb.appendLine("场景：先提交 $slowCount 个 ${slowMillis}ms 的磁盘任务，")
        sb.appendLine("      再提交 1 个 20ms 网络任务，观察网络任务的等待时间")
        sb.appendLine()

        val legacyWait = measureHeadOfLineBlocking(legacyShared, slowCount, slowMillis)
        sb.appendLine("A. 单一 IO 池（core=4 max=16 无界队列）")
        sb.appendLine("   网络任务等待 = ${legacyWait}ms  ← 被 $slowCount 个慢任务堵住")
        sb.appendLine("   注：max=16 不会救场，无界队列使扩容条件永不成立")
        sb.appendLine()

        val laneWait = measureHeadOfLineBlocking(null, slowCount, slowMillis)
        sb.appendLine("B. 泳道隔离（disk 泳道跑慢任务，net 泳道跑网络任务）")
        sb.appendLine("   网络任务等待 = ${laneWait}ms  ← 网络泳道空闲，立即执行")
        sb.appendLine()

        sb.appendLine("结论：并发上限相同（4 vs 4），但泳道把「无关任务互相拖累」消灭了。")
        Log.i(TAG, "泳道隔离对照：单一池=${legacyWait}ms 泳道=${laneWait}ms")
        return sb.toString()
    }

    /**
     * @param sharedLane 非空时用该池执行全部任务（模拟旧设计）；
     *                   为空时慢任务走 [disk]、快任务走 [network]（泳道隔离）
     */
    private fun measureHeadOfLineBlocking(
        sharedLane: Lane?,
        slowCount: Int,
        slowMillis: Long,
    ): Long {
        val start = SystemClock.elapsedRealtime()
        val netStartedAt = AtomicLong(-1)
        val latch = CountDownLatch(slowCount + 1)

        val slowTask = {
            Thread.sleep(slowMillis)
            latch.countDown()
        }
        repeat(slowCount) {
            if (sharedLane != null) sharedLane.execute(DEMO_CALLER, slowTask)
            else disk.execute(DEMO_CALLER, slowTask)
        }

        val netTask = Runnable {
            netStartedAt.set(SystemClock.elapsedRealtime() - start)
            Thread.sleep(20)
            latch.countDown()
        }
        if (sharedLane != null) sharedLane.execute(DEMO_CALLER, netTask)
        else network.execute(DEMO_CALLER, netTask)

        latch.await(10, TimeUnit.SECONDS)
        return netStartedAt.get().coerceAtLeast(0)
    }

    // ─────────────────────────────────────────────
    // 概览
    // ─────────────────────────────────────────────

    /** 供 Demo 展示：当前各池的配置与实时指标概览。 */
    fun describe(): String = buildString {
        appendLine("当前设备 CPU 核数：${Runtime.getRuntime().availableProcessors()}")
        appendLine()
        appendLine("── 泳道（按下游资源隔离）──")
        listOf(network, disk, dbWrite, background).forEach { appendLine(formatLane(it)) }
        appendLine()
        appendLine("── 其他池 ──")
        appendLine("cpu       core=${cpu.corePoolSize} max=${cpu.maximumPoolSize} 实际=${cpu.poolSize} 队列=${cpu.queue.size}/$CPU_QUEUE_CAPACITY")
        appendLine("single    实际=${single.poolSize} 队列=${single.queue.size}/128")
        appendLine("scheduled 实际=${(scheduled as ThreadPoolExecutor).poolSize}")
        appendLine("unnamed   实际=${unnamed.poolSize}（对照用，默认命名）")
        appendLine()
        appendLine("── 反面教材（重构前）──")
        appendLine(formatLane(legacyShared))
        appendLine()
        appendLine("说明：queueDepth 是「任务是否堆积」的直接证据；")
        appendLine("waitP99 是任务从入队到开始执行的等待耗时，比 poolSize 更能反映真实压力。")
    }

    private fun formatLane(lane: Lane): String {
        val m = lane.metrics()
        return "%-9s core=%d max=%d 实际=%d 活跃=%d 队列=%-11s 提交=%d 拒绝(队列/配额)=%d/%d 异常=%d waitP99=%dms"
            .format(
                m.name, m.core, m.max, m.poolSize, m.active, m.queueText,
                m.submitted, m.rejectedByQueue, m.rejectedByQuota, m.errors, m.waitP99Millis,
            )
    }

    // ─────────────────────────────────────────────
    // 统一异常捕获（非 Lane 池）
    // ─────────────────────────────────────────────

    /**
     * 把 Runnable 包成「异常不会逃逸」的版本。
     *
     * 为什么需要：`ThreadPoolExecutor.execute()` 提交的任务若抛异常，
     * 会走到该 worker 线程的 UncaughtExceptionHandler。Android 默认的是
     * KillApplicationHandler —— **直接杀进程**。
     *
     * `Lane` 内部已自带 try/catch；cpu / single 这类裸 ThreadPoolExecutor
     * 没有，所以用一个 ThreadFactory 统一包一层最省事且不会漏。
     *
     * @param lane 归因用的泳道名
     */
    private fun guardedFactory(lane: String, prefix: String, androidPriority: Int): ThreadFactory {
        val inner = NamedThreadFactory(prefix, androidPriority = androidPriority)
        return ThreadFactory { r ->
            inner.newThread {
                try {
                    r.run()
                } catch (t: Throwable) {
                    ThreadErrorReporter.onTaskError(lane, "direct-execute", t)
                    if (ThreadErrorReporter.failFast) throw t
                }
            }
        }
    }
}
