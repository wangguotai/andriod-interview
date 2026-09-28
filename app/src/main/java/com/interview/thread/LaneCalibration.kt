package com.interview.thread

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Time: 2026/9/28
 * Author: wgt
 * Description: 泳道并发标定 —— 把 ThreadPools 注释里的「待实测」换成真实拐点。
 *
 * ─── 为什么要做这件事 ───
 *
 * `net = 4`、`disk = 2` 是**保守起点**，不是推导结果。Little's Law 的
 * `L = λW` 给的是稳态在途数，**不能直接当并发配置**：你要的是「满足 P99 SLO
 * 的最小 n」，这是个必须测量的拐点问题（且 net 泳道的 W 本身依赖 n）。
 *
 * 所以本类扫 n = 1,2,4,8,16，对每种负载记录「吞吐 / P50 / P99」，
 * 人工读取两个拐点：**吞吐曲线的平台起点** 与 **P99 开始陡升处**。
 *
 * ─── 测量模型：闭循环（closed-loop），不是开循环提交 ───
 *
 * ⚠️ 第一版用「一次性提交 48 个任务给 core==max 的 SynchronousQueue 池」，
 *    结果是 n=1 那档完成 1 个、拒绝 47 个 —— 测出来的「吞吐」其实是在数拒绝数，
 *    全是废数据。原因是开循环提交 + 无缓冲队列：线程忙时任务当场被拒，
 *    样本根本没跑。
 *
 * 正确做法是闭循环：**固定 n 个 worker，各自循环从共享计数器抢任务**，
 * 直到总量耗尽。
 *   · 并发严格等于 n，无队列、无拒绝、无样本丢失
 *   · 墙钟就是真实耗时，吞吐 = 总操作数 / 墙钟
 *   · 每**个操作**单独记延迟 → 可直接看「资源服务时间被争用推高了多少」
 * 这是存储/网络基准测试的标准做法（fio、wrk 的固定并发模式同理）。
 *
 * ─── 诚实边界（别把结论用过头）───
 *
 * 1. **page cache 兜底**：读会命中页缓存，没真正压到闪存 FTL。所以 disk 曲线
 *    测的是**软件栈**（read 路径、文件系统元数据、线程调度），是设备真实争用的
 *    **下界**。要测闪存本身需绕过页缓存（本 Demo 做不到）。
 * 2. **一根设备一个结论**：本机若是 UFS，它恰恰是「能吃高队列深度」的那类，
 *    曲线会偏平；换 eMMC 低端机必须重测（λ 与 W 会同时变差）。
 * 3. **合成负载 ≠ 生产负载**：真实链路要按调用方采样重做。
 * 4. **net 用 sleep 替身**：只验「RTT 受限型任务并发是否线性收益」，
 *    不含带宽竞争与服务端限流。真实 net 泳道必须用弱网实测。
 */
object LaneCalibration {

    private const val TAG = "LaneCalib"

    /** 被测资源类型 */
    enum class Workload(val label: String, val unit: String) {
        /** 磁盘随机 4K 读写混合 —— **会被 page cache 兜住**，测的是软件栈 */
        DISK_RANDOM_4K("disk 随机4K 读写混合(缓存态)", "IO"),

        /** 磁盘随机 4K + O_SYNC 写 —— 强制落盘，逼近真实闪存服务时间 */
        DISK_RANDOM_4K_SYNC("disk 随机4K O_SYNC写(落盘)", "IO"),

        /** 磁盘顺序读（带预读）—— 用来暴露「顺序读给出的假高拐点」 */
        DISK_SEQ_READ("disk 顺序读(带预读)", "1MB块"),

        /** 纯等待，模拟 RTT 受限的 API 小请求 —— 预期线性上升到很晚才饱和 */
        NET_RTT("net RTT受限(纯等待)", "请求"),

        /** CPU 密集，模拟解析/解码 —— 拐点应出现在核数附近 */
        CPU_PARSE("cpu 计算(模拟解析)", "批次"),
    }

    /** 每档并发下的一个测量点 */
    data class Point(
        val concurrency: Int,
        val totalOps: Int,
        val wallMillis: Long,
        val throughput: Double,
        val meanMicros: Double,
        val p50Micros: Long,
        val p99Micros: Long,
    )

    // ─────────────────────────────────────────────
    // 负载实现：一次「操作」= 一次可计时的最小工作单元
    // ─────────────────────────────────────────────

    private const val RANDOM_FILE_SIZE = 4L * 1024 * 1024
    private const val SEQ_FILE_SIZE = 32L * 1024 * 1024
    private const val SEQ_SLOTS = 16
    private const val IO_UNIT = 4096
    private const val SEQ_CHUNK = 1L * 1024 * 1024

    /** 防止 JIT 把空转循环消掉 */
    @Volatile
    private var sink: Double = 0.0

    private class Env(val ctx: Context) {
        val randomFile: File by lazy {
            File(ctx.cacheDir, "calib-random.bin").also { ensureSize(it, RANDOM_FILE_SIZE) }
        }
        val seqFile: File by lazy {
            File(ctx.cacheDir, "calib-seq.bin").also { ensureSize(it, SEQ_FILE_SIZE) }
        }
        val outDir: File by lazy {
            val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            File(base, "LaneCalibration").apply { mkdirs() }
        }

        private fun ensureSize(f: File, size: Long) {
            if (f.exists() && f.length() >= size) return
            f.parentFile?.mkdirs()
            RandomAccessFile(f, "rw").use { it.setLength(size) }
        }
    }

    /**
     * 执行一个任务（含若干次操作），每次操作结束回调其耗时（纳秒）。
     * 返回本次任务产生的操作数。
     */
    private fun runTask(
        workload: Workload,
        env: Env,
        taskIdx: Int,
        onOp: (Long) -> Unit,
    ): Int = when (workload) {

        Workload.NET_RTT -> {
            var ops = 0
            repeat(NET_OPS_PER_TASK) {
                val t = System.nanoTime()
                Thread.sleep(NET_SLEEP_MILLIS)
                onOp(System.nanoTime() - t)
                ops++
            }
            ops
        }

        Workload.CPU_PARSE -> {
            var ops = 0
            repeat(CPU_OPS_PER_TASK) {
                val t = System.nanoTime()
                val deadline = t + CPU_OP_MICROS * 1000
                var acc = taskIdx + 1.000001
                while (System.nanoTime() < deadline) {
                    repeat(512) { acc += sqrt(acc) }
                    acc %= 1e9
                }
                sink = acc
                onOp(System.nanoTime() - t)
                ops++
            }
            ops
        }

        Workload.DISK_RANDOM_4K -> {
            var ops = 0
            val pages = (RANDOM_FILE_SIZE / IO_UNIT).toInt()
            val rnd = Random(taskIdx * 31L + 7L)
            val buf = ByteArray(IO_UNIT)
            RandomAccessFile(env.randomFile, "rw").use { raf ->
                repeat(DISK_RANDOM_OPS_PER_TASK) {
                    val off = rnd.nextInt(pages) * IO_UNIT.toLong()
                    val t = System.nanoTime()
                    raf.seek(off)
                    raf.readFully(buf)
                    // 写 1KB：制造写路径，逼近真实随机写放大
                    raf.seek(off)
                    raf.write(buf, 0, 1024)
                    onOp(System.nanoTime() - t)
                    ops++
                }
            }
            ops
        }

        /**
         * 随机 4K + FileChannel.force(true)：强制刷盘，穿透 page cache。
         *
         * 这是**唯一能反映设备真实服务时间**的档位 —— 其余读路径全在缓存里。
         * ⚠️ 代价是慢 2~3 个数量级，所以每任务操作数砍到 1/4。
         */
        Workload.DISK_RANDOM_4K_SYNC -> {
            var ops = 0
            val pages = (RANDOM_FILE_SIZE / IO_UNIT).toInt()
            val rnd = Random(taskIdx * 31L + 11L)
            val buf = ByteArray(IO_UNIT)
            RandomAccessFile(env.randomFile, "rw").use { raf ->
                val ch = raf.channel
                repeat(DISK_SYNC_OPS_PER_TASK) {
                    val off = rnd.nextInt(pages) * IO_UNIT.toLong()
                    val t = System.nanoTime()
                    raf.seek(off)
                    raf.readFully(buf)
                    raf.seek(off)
                    raf.write(buf, 0, 1024)
                    // force 作用于整个 channel，代价高但最接近「真实随机写落盘」
                    ch.force(true)
                    onOp(System.nanoTime() - t)
                    ops++
                }
            }
            ops
        }

        Workload.DISK_SEQ_READ -> {
            var ops = 0
            val start = (taskIdx % SEQ_SLOTS) * SEQ_CHUNK
            val buf = ByteArray(IO_UNIT)
            RandomAccessFile(env.seqFile, "r").use { raf ->
                repeat(SEQ_OPS_PER_TASK) {
                    val t = System.nanoTime()
                    raf.seek(start)
                    var remaining = SEQ_CHUNK
                    while (remaining > 0) {
                        val n = raf.read(buf, 0, minOf(IO_UNIT.toLong(), remaining).toInt())
                        if (n <= 0) break
                        remaining -= n
                    }
                    onOp(System.nanoTime() - t)
                    ops++
                }
            }
            ops
        }
    }

    // 每任务的操作数与单操作工作量（合计控制在「n=1 时约 1~2 秒」）
    private const val NET_OPS_PER_TASK = 2
    private const val NET_SLEEP_MILLIS = 20L
    private const val CPU_OPS_PER_TASK = 20
    private const val CPU_OP_MICROS = 5_000L
    private const val DISK_RANDOM_OPS_PER_TASK = 40
    private const val DISK_SYNC_OPS_PER_TASK = 12
    private const val SEQ_OPS_PER_TASK = 8

    // ─────────────────────────────────────────────
    // 闭循环测量
    // ─────────────────────────────────────────────

    /**
     * 固定 [concurrency] 个 worker 抢 [totalTasks] 个任务。
     * 并发严格等于 concurrency，无队列入队、无拒绝、无丢样本。
     */
    private fun measure(
        env: Env,
        workload: Workload,
        concurrency: Int,
        totalTasks: Int,
    ): Point {
        val nextTask = AtomicInteger(0)
        val opNanos = ConcurrentLinkedQueue<Long>()
        val totalOps = AtomicInteger(0)
        val factory = ThreadPools.NamedThreadFactory("app-calib-$concurrency")

        val workers = (0 until concurrency).map {
            factory.newThread {
                while (true) {
                    val idx = nextTask.getAndIncrement()
                    if (idx >= totalTasks) break
                    totalOps.addAndGet(runTask(workload, env, idx) { opNanos.add(it) })
                }
            }
        }

        val t0 = System.nanoTime()
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        val wallMillis = ((System.nanoTime() - t0) / 1_000_000).coerceAtLeast(1)

        val sorted = opNanos.toList().sorted()
        val ops = totalOps.get()
        return Point(
            concurrency = concurrency,
            totalOps = ops,
            wallMillis = wallMillis,
            throughput = ops * 1000.0 / wallMillis,
            meanMicros = if (sorted.isEmpty()) 0.0 else sorted.sum().toDouble() / sorted.size / 1000.0,
            p50Micros = percentile(sorted, 50),
            p99Micros = percentile(sorted, 99),
        )
    }

    private fun percentile(sortedNanos: List<Long>, p: Int): Long {
        if (sortedNanos.isEmpty()) return 0L
        val idx = (sortedNanos.size.toLong() * p / 100).toInt().coerceIn(0, sortedNanos.size - 1)
        return sortedNanos[idx] / 1000
    }

    // ─────────────────────────────────────────────
    // 扫描与报告
    // ─────────────────────────────────────────────

    /**
     * 扫描驱动：独占一个受治理的线程。
     *
     * 为什么用 `ThreadPoolExecutor` 而非 `Executors.newSingleThreadExecutor`：
     * 后者虽等价，但绕过收口层、命名与优先级都不可控 —— 本仓库的 Lint 规则
     * 正是拦这个（`ExecutorsThreadPool`）。
     *
     * 队列用有界 `LinkedBlockingQueue(4)` 而非 `SynchronousQueue`：重复点按钮时
     * 多余任务排队等待，而不是直接拒绝。
     * ⚠️ 绝不能用 `CallerRunsPolicy` —— 调用方是 UI 线程，饱和时会让主线程
     * 去跑几十秒的扫描，直接 ANR（这正是 README 里批评过的写法）。
     */
    private val driver = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.SECONDS,
        LinkedBlockingQueue(4),
        ThreadPools.NamedThreadFactory("app-calib-driver"),
    )

    /** 异步入口：扫描几十秒，不要阻塞主线程 */
    fun runAllAsync(ctx: Context, onDone: (String) -> Unit) {
        val app = ctx.applicationContext
        driver.execute {
            val result = runCatching { runAll(app) }
            onDone(result.getOrElse { "扫描失败：${it.message}" })
        }
    }

    /**
     * 全量扫描。判读两条线：
     *   · 吞吐平台起点 —— 再加并发换不到吞吐
     *   · P99 陡升点 —— 服务时间被争用推高
     * 取两者中较小者为该泳道 core 的上界。
     */
    fun runAll(
        ctx: Context,
        levels: List<Int> = listOf(1, 2, 4, 8, 16),
        totalTasks: Int = 48,
    ): String {
        val env = Env(ctx)
        val cores = Runtime.getRuntime().availableProcessors()
        val device = "${Build.MODEL}(${Build.DEVICE}) / Android ${Build.VERSION.RELEASE} / " +
                "$cores 核 / ${Build.SUPPORTED_ABIS.firstOrNull()}"

        // 全局预热：JIT + 页缓存先稳下来，否则 n=1 那档会虚高
        measure(env, Workload.DISK_RANDOM_4K, 2, totalTasks / 3)
        measure(env, Workload.CPU_PARSE, 2, totalTasks / 3)

        val sb = StringBuilder()
        val csv = StringBuilder("workload,n,total_ops,wall_ms,throughput_ops_per_s,mean_us,p50_us,p99_us\n")

        sb.appendLine("===== 泳道并发标定（闭循环）=====")
        sb.appendLine("设备：$device")
        sb.appendLine("参数：每档 $totalTasks 个任务；并发 n = worker 数（无队列、无拒绝）")
        sb.appendLine("读数：吞吐 = 总操作数/墙钟；P50/P99 是**单次操作**的服务时间")
        sb.appendLine()

        for (workload in Workload.values()) {
            val points = levels.map { measure(env, workload, it, totalTasks) }
            Log.i(TAG, "扫描完成 ${workload.label}: " +
                    points.joinToString { "n=${it.concurrency} ${fmt(it.throughput)}${workload.unit}/s p99=${it.p99Micros}us" })

            sb.appendLine("── ${workload.label} ──")
            sb.appendLine("n     吞吐(${workload.unit}/s)  相对1x    P50(us)   P99(us)   墙钟(ms)")
            val base = points.first().throughput.takeIf { it > 0 }
            points.forEach { p ->
                val speedup = if (base == null) "-" else "%.2fx".format(Locale.US, p.throughput / base)
                sb.appendLine(
                    "%-5d %-16s %-8s %-9d %-9d %d".format(
                        Locale.US, p.concurrency, fmt(p.throughput), speedup,
                        p.p50Micros, p.p99Micros, p.wallMillis,
                    )
                )
                csv.appendLine(
                    "${workload.name},${p.concurrency},${p.totalOps},${p.wallMillis}," +
                            "${fmt(p.throughput)},${fmt(p.meanMicros)},${p.p50Micros},${p.p99Micros}"
                )
            }
            sb.appendLine("判读：${verdict(points, cores)}")
            sb.appendLine()
        }

        val csvFile = File(env.outDir, "calibration.csv")
        val txtFile = File(env.outDir, "calibration.txt")
        runCatching {
            csvFile.writeText(csv.toString())
            txtFile.writeText(sb.toString())
        }.onFailure { Log.w(TAG, "结果落盘失败：${it.message}") }

        sb.appendLine("CSV：${csvFile.absolutePath}")
        sb.appendLine()
        sb.appendLine("⚠️ page cache 会兜住读请求，disk 曲线是软件栈争用的**下界**；")
        sb.appendLine("   本机 ${Build.MODEL} 若为 UFS，它恰能吃高队列深度，曲线偏平 ——")
        sb.appendLine("   结论不能跨设备搬运，换 eMMC 低端机必须重测。")

        Log.i(TAG, sb.toString())
        return sb.toString()
    }

    /** 从曲线读出「吞吐平台起点」与「P99 劣化处」，给出保守建议值 */
    private fun verdict(points: List<Point>, cores: Int): String {
        if (points.size < 2) return "样本不足"

        // 吞吐平台：相邻档位增益首次低于 15%
        val knee = points.zipWithNext().firstOrNull { (prev, cur) ->
            prev.throughput <= 0 || cur.throughput / prev.throughput - 1.0 < 0.15
        }?.second

        // P99 劣化：超过首档 P99 的 1.5 倍（Flash 的尾延迟通常先恶化）
        val p99Base = points.first().p99Micros.coerceAtLeast(1)
        val degrade = points.firstOrNull { it.p99Micros > p99Base * 1.5 }

        val parts = mutableListOf<String>()
        parts += if (knee == null) "吞吐到 n=${points.last().concurrency} 仍在上升（需扩大扫描范围）"
        else "吞吐自 n=${knee.concurrency} 起增益 <15%"
        parts += if (degrade == null) "P99 未劣化超 1.5x"
        else "P99 自 n=${degrade.concurrency} 起劣化 ${"%.1f".format(Locale.US, degrade.p99Micros * 1.0 / p99Base)}x"

        val suggested = listOfNotNull(knee, degrade).minByOrNull { it.concurrency }
        parts += if (suggested == null) "→ 本设备未测到上界（核数 $cores）"
        else "→ 建议 core ≤ ${suggested.concurrency}"
        return parts.joinToString("；")
    }

    private fun fmt(v: Double): String = "%.1f".format(Locale.US, v)
}
