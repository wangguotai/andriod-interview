package com.example.myapplication

import androidx.test.ext.junit.runners.AndroidJUnit4
import android.util.Log
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 网络泳道并发标定实验。
 *
 * ─── 要回答的问题 ───
 *
 * 「网络泳道经常排队」的**定量成因**是什么，core 该设多大？
 *
 * ─── 为什么用「到达率扫描」而不是一次性突发 ───
 *
 * 网络请求是**持续到达**的（每秒多少个 QPS），不是一次性丢 48 个。
 * 持续到达下有 Little's Law：
 *
 *     L = λ × W          （在途任务 = 到达率 × 单任务停留时间）
 *
 * 池的稳态吞吐上限 = core / W。所以：
 *   · λ ≤ core/W  → 系统能跟上，队列不积压，无排队
 *   · λ >  core/W  → 每秒『多出』(λ - core/W) 个任务，队列单调增长 → 排队爆炸
 *
 * 也就是说，**决定 core 的是 λ（QPS）与 W（RTT）的乘积**，不是拍脑袋。
 * 本实验扫描 λ，把这个转折点标出来。
 *
 * ⚠️ 诚实边界：W 用 sleep 替身，模拟「RTT 受限」而不含带宽竞争与服务端限流。
 *    真实链路的 W 要在弱网下实测，但**转折点公式不变**。
 */
@RunWith(AndroidJUnit4::class)
class NetworkLaneConcurrencyTest {

    private data class Cfg(val label: String, val core: Int, val max: Int, val qcap: Int) // qcap<0 = Synchronous

    private data class Result(
        val label: String,
        val qps: Int,
        val rttMs: Long,
        val submitted: Int,
        val rejected: Int,
        val waitP50: Long,
        val waitP99: Long,
        val waitMax: Long,
        val capacityQps: Double,
    )

    private fun build(c: Cfg): ThreadPoolExecutor {
        val q = if (c.qcap < 0) SynchronousQueue<Runnable>() else LinkedBlockingQueue<Runnable>(c.qcap)
        return ThreadPoolExecutor(c.core, c.max, 60L, TimeUnit.SECONDS, q, ThreadPoolExecutor.AbortPolicy())
    }

    /**
     * 以 [qps] 的速率持续投放 [seconds] 秒、每次 [rttMs] 的任务，测排队等待。
     */
    private fun arrivalScan(c: Cfg, rttMs: Long, qps: Int, seconds: Int = 2): Result {
        val ex = build(c)
        val waits = ConcurrentLinkedQueue<Long>()
        val submitted = AtomicInteger(0)
        val rejected = AtomicInteger(0)
        val intervalMs = 1000.0 / qps
        val total = (qps * seconds)

        try {
            val t0 = System.nanoTime()
            for (i in 0 until total) {
                // 按到达率节流；用 nanos 累加避免累积漂移
                val targetNs = t0 + (i * intervalMs * 1_000_000).toLong()
                val now = System.nanoTime()
                if (targetNs > now) {
                    val sleepMs = (targetNs - now) / 1_000_000
                    if (sleepMs > 0) Thread.sleep(sleepMs)
                }
                val enq = System.nanoTime()
                submitted.incrementAndGet()
                try {
                    ex.execute {
                        waits.add(System.nanoTime() - enq)
                        Thread.sleep(rttMs)
                    }
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    rejected.incrementAndGet()
                }
            }
            // 等剩余任务跑完
            ex.shutdown()
            ex.awaitTermination(30, TimeUnit.SECONDS)

            val sorted = waits.toList().sorted()
            fun pct(p: Int): Long =
                if (sorted.isEmpty()) -1
                else sorted[(sorted.size.toLong() * p / 100).toInt().coerceAtMost(sorted.size - 1)] / 1_000_000

            return Result(
                c.label, qps, rttMs, submitted.get(), rejected.get(),
                pct(50), pct(99), if (sorted.isEmpty()) -1 else sorted.last() / 1_000_000,
                c.core * 1000.0 / rttMs,
            )
        } finally {
            ex.shutdownNow()
        }
    }

    /**
     * 实验：固定 RTT=100ms，扫描到达率。
     * 预期：λ 越过 core/W 处，等待从 ~0 陡然上升。
     */
    @Test
    fun scanArrivalRate() {
        val rtt = 100L
        val qpsLevels = listOf(20, 40, 60, 80, 160)
        val configs = listOf(
            Cfg("当前 core=4 max=8 LBQ(8)", 4, 8, 8),
            Cfg("候选 core=8 max=8 LBQ(8)", 8, 8, 8),
            Cfg("候选 core=8 max=8 Sync", 8, 8, -1),
        )

        val sb = StringBuilder("\n===== 到达率扫描（RTT=${rtt}ms，持续 2s）=====\n")
        sb.appendLine("理论容量 = core / W；『QPS/容量』>1 即必然排队\n")
        for (c in configs) {
            sb.appendLine("── ${c.label}（理论容量 %.0f QPS）──".format(c.core * 1000.0 / rtt))
            sb.appendLine("  QPS   QPS/容量   提交  拒绝    P50等待   P99等待   最大等待")
            for (q in qpsLevels) {
                val r = arrivalScan(c, rtt, q)
                sb.appendLine(
                    "  %-5d %-9.2f %-6d %-5d  %-8d %-9d %d".format(
                        q, q / r.capacityQps, r.submitted, r.rejected,
                        r.waitP50, r.waitP99, r.waitMax,
                    )
                )
                Log.i("NetLaneExp", "${c.label} qps=$q 拒绝=${r.rejected} P50=${r.waitP50}ms P99=${r.waitP99}ms")
            }
            sb.appendLine()
        }
        Log.i("NetLaneExp", sb.toString())
    }

    /**
     * 实验：直接验证线上配置 [com.interview.thread.ThreadPools.network] 本身。
     * 合成 executor 证明的是「这个结构性缺陷存在」，本测证明「线上配置已修正」。
     */
    @Test
    fun verifyRealNetworkLane() {
        val lane = com.interview.thread.ThreadPools.network
        val before = lane.metrics()
        val gate = CountDownLatch(1)
        val started = AtomicInteger(0)
        val allStarted = CountDownLatch(before.core)
        // 占满 core：验证真实并发上限
        repeat(before.core) {
            lane.execute("verify") {
                started.incrementAndGet()
                allStarted.countDown()
                gate.await(3, TimeUnit.SECONDS)
            }
        }
        allStarted.await(2, TimeUnit.SECONDS)
        val reported = lane.metrics()
        gate.countDown()

        Log.i(
            "NetLaneExp",
            "===== 线上 net 泳道 =====\n" +
                    "  配置 core=${before.core} max=${before.max} queue=${before.queueCapacity}\n" +
                    "  闸门测试：能同时启动 ${started.get()} 个（应等于 core=${before.core}）\n" +
                    "  实测活跃=${reported.active} poolSize=${reported.poolSize}\n" +
                    "  core==max ? ${before.core == before.max}"
        )
        org.junit.Assert.assertEquals(
            "net 泳道应能真正跑满 core 个并发", before.core, started.get(),
        )
        org.junit.Assert.assertEquals("修后 core 应等于 max（不再靠队列满才扩容）", before.core, before.max)
    }
}
