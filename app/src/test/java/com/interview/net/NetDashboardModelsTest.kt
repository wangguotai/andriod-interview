package com.interview.net

import com.interview.net.dashboard.DashboardModels
import com.interview.net.dashboard.SimulatedNetwork
import com.interview.net.dashboard.StageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 仪表盘的**纯逻辑**测试 —— 图表会骗人，所以变换层必须有断言。
 *
 * 自定义 View 的绘制没法在 JVM 上断言（也不想引 Robolectric），但真正容易出错的
 * 不是「画得漂不漂亮」，而是**变换**：阶段怎么拆、未发生的段怎么处理、
 * 分位怎么算、模拟样本有没有被静默混入真实。这些全在纯 Kotlin 里，可秒级钉住。
 */
class NetDashboardModelsTest {

    private fun rec(
        total: Long,
        dns: Long = -1,
        connect: Long = -1,
        tls: Long = -1,
        ttfb: Long = -1,
        ok: Boolean = true,
        reused: Boolean = false,
        cached: Boolean = false,
        simulated: Boolean = false,
    ) = NetMetrics.Record(
        host = "h", method = "GET", code = if (ok) 200 else -1, ok = ok, protocol = "HTTP_2",
        reusedConnection = reused, fromCache = cached, responseBytes = 1,
        stage = NetMetrics.Stage(dns, connect, tls, ttfb, total),
        errorMessage = null, simulated = simulated,
    )

    // ─────────────────────────────────────────
    // 阶段堆叠
    // ─────────────────────────────────────────

    @Test
    fun `未发生的阶段保留 -1，绝不当成 0 挤进堆叠`() {
        // 连接复用：dns/tcp/tls 全是 -1（没发生），只有首包与传输。
        val slices = DashboardModels.stageSlices(rec(total = 120, ttfb = 100).stage)
        assertEquals(-1L, slices.first { it.kind == StageKind.DNS }.millis)
        assertEquals(-1L, slices.first { it.kind == StageKind.CONNECT }.millis)
        assertEquals(-1L, slices.first { it.kind == StageKind.TLS }.millis)
        assertEquals(100L, slices.first { it.kind == StageKind.TTFT }.millis)
    }

    @Test
    fun `传输余量等于 total 减去已知各段，且负数截断为 0`() {
        val s = rec(total = 300, dns = 10, connect = 20, tls = 30, ttfb = 100).stage
        val transfer = DashboardModels.stageSlices(s).first { it.kind == StageKind.TRANSFER }.millis
        assertEquals("300 - (10+20+30+100) = 140", 140L, transfer)
    }

    @Test
    fun `total 小于已知各段时余量截断为 0（不产生负宽度）`() {
        // 理论不该发生，但各段独立取整确实可能造出 total 略小于之和。
        val s = rec(total = 50, dns = 20, connect = 20, tls = 20, ttfb = 20).stage
        assertEquals(0L, DashboardModels.stageSlices(s).first { it.kind == StageKind.TRANSFER }.millis)
    }

    @Test
    fun `对不上的时间要能被识别出来，而不是悄悄吞掉`() {
        // 四段之和刚好等于 total → 没有对不上的时间。
        val accounted = rec(total = 40, dns = 10, connect = 10, tls = 10, ttfb = 10).stage
        assertFalse(DashboardModels.hasUnaccountedTime(accounted))
        // 只测到 DNS，其余 90ms 不知去向 → 必须被标出来。
        val gap = rec(total = 100, dns = 10).stage
        assertTrue(DashboardModels.hasUnaccountedTime(gap))
    }

    // ─────────────────────────────────────────
    // 时序与分位
    // ─────────────────────────────────────────

    @Test
    fun `时序按旧到新排列，且保留 simulated 标记`() {
        // 入参是「新→旧」（NetMetrics.recent 的契约）
        val newestFirst = listOf(rec(30, simulated = true), rec(20), rec(10))
        val series = DashboardModels.series(newestFirst)
        assertEquals(listOf(10L, 20L, 30L), series.map { it.totalMillis })
        assertTrue("最后一颗（最新的那条）应是模拟样本", series.last().simulated)
        assertFalse(series.first().simulated)
    }

    @Test
    fun `分位沿用 NetMetrics 的最近邻语义，不在此处重新发明`() {
        // 直接用既有实现，确认仪表盘读的是同一份分位（避免两处算法漂移）。
        val sorted = listOf(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L)
        assertEquals(50L, NetMetrics.percentile(sorted, 50))
        assertEquals(90L, NetMetrics.percentile(sorted, 90))
        assertEquals(100L, NetMetrics.percentile(sorted, 99))
    }

    // ─────────────────────────────────────────
    // 比率
    // ─────────────────────────────────────────

    @Test
    fun `无样本时比率是 -1 而不是 0（缺数据≠0）`() {
        val r = DashboardModels.rates(emptyList())
        assertEquals(-1.0, r.successRate, 0.0)
        assertEquals("无样本", DashboardModels.pct(r.successRate))
    }

    @Test
    fun `比率按窗口计算且互相独立`() {
        val records = listOf(
            rec(10, ok = true, reused = true, cached = true),
            rec(10, ok = true, reused = true),
            rec(10, ok = false, reused = false),
            rec(10, ok = true, reused = false),
        )
        val r = DashboardModels.rates(records)
        assertEquals(0.75, r.successRate, 1e-9)
        assertEquals(0.5, r.reuseRate, 1e-9)
        assertEquals(0.25, r.cacheRate, 1e-9)
    }

    // ─────────────────────────────────────────
    // 模拟器
    // ─────────────────────────────────────────

    @Test
    fun `模拟器同种子可复现（演示要能重放同一条曲线）`() {
        val a = SimulatedNetwork.generate(SimulatedNetwork.Preset.WEAK, 30, seed = 42)
        val b = SimulatedNetwork.generate(SimulatedNetwork.Preset.WEAK, 30, seed = 42)
        assertEquals(a.map { it.stage.totalMillis }, b.map { it.stage.totalMillis })
    }

    @Test
    fun `模拟器反映档位差异：高丢包的尾部显著高于良好`() {
        val good = SimulatedNetwork.generate(SimulatedNetwork.Preset.GOOD, 100, seed = 1)
        val lossy = SimulatedNetwork.generate(SimulatedNetwork.Preset.LOSSY, 100, seed = 1)
        val goodP99 = NetMetrics.percentile(good.map { it.stage.totalMillis }.sorted(), 99)
        val lossyP99 = NetMetrics.percentile(lossy.map { it.stage.totalMillis }.sorted(), 99)
        assertTrue("高丢包 P99($lossyP99) 应远高于良好 P99($goodP99)", lossyP99 > goodP99 * 2)
    }

    @Test
    fun `模拟器前两次强制不复用 —— 冷启动就 100% 复用一眼是假数据`() {
        val batch = SimulatedNetwork.generate(SimulatedNetwork.Preset.WEAK, 10, seed = 3, reuseRate = 1.0)
        assertFalse("第 1 条必须建连", batch[0].reusedConnection)
        assertFalse("第 2 条必须建连", batch[1].reusedConnection)
        assertTrue("第 3 条起才允许复用", batch[2].reusedConnection)
    }

    @Test
    fun `复用样本的 dns、建连、TLS 必须是 -1 而非 0`() {
        val batch = SimulatedNetwork.generate(SimulatedNetwork.Preset.WEAK, 20, seed = 5)
        val reused = batch.first { it.reusedConnection }
        assertEquals(-1L, reused.stage.dnsMillis)
        assertEquals(-1L, reused.stage.connectMillis)
        assertEquals(-1L, reused.stage.tlsMillis)
    }

    @Test
    fun `缓存命中样本除 total 外各阶段全为 -1`() {
        // 构造到出现 cache 为止（缓存概率 8%，多生几条必然覆盖）
        val batch = SimulatedNetwork.generate(SimulatedNetwork.Preset.GOOD, 200, seed = 11)
        val cached = batch.firstOrNull { it.fromCache }
        assertNotNull("应至少产生一条缓存命中样本", cached)
        cached!!.let {
            assertEquals(-1L, it.stage.dnsMillis)
            assertEquals(-1L, it.stage.connectMillis)
            assertEquals(-1L, it.stage.tlsMillis)
            assertEquals(-1L, it.stage.firstByteMillis)
            assertTrue(it.stage.totalMillis >= 0)
        }
    }

    // ─────────────────────────────────────────
    // 事件折叠
    // ─────────────────────────────────────────

    @Test
    fun `相邻同级快照不产生事件（否则切网回调会刷屏）`() {
        val snap = NetworkQuality.Snapshot(
            level = NetworkLevel.WEAK, score = 1, transport = "WIFI", metered = false,
            smoothRttMillis = 300, successRate = 0.5, sampleCount = 8, detail = "d",
        )
        assertNotNull("首次出现应产生事件", DashboardModels.levelEvent(null, 1, snap, 0L))
        assertNull("同级重复必须被折叠", DashboardModels.levelEvent(NetworkLevel.WEAK, 2, snap, 0L))
        assertNotNull(
            "档位变化应产生事件",
            DashboardModels.levelEvent(NetworkLevel.GOOD, 3, snap, 0L),
        )
    }

    @Test
    fun `分位摘要可对任意子集计算，口径与 summary 同源`() {
        val real = listOf(rec(10), rec(20), rec(30))
        val sim = listOf(rec(9000, simulated = true))
        val all = real + sim
        // 整窗 P99 会被模拟样本拉高；只算真实样本则不会 —— 这正是"只看真实"开关的意义。
        assertTrue(NetMetrics.summarize(all).p99TotalMillis > 1000)
        assertTrue(NetMetrics.summarize(real).p99TotalMillis <= 30)
    }

    @Test
    fun `模拟样本不回喂 NetworkQuality（否则注入会污染真实档位判定）`() {
        // currentQuality() 是纯内存读，不需要 Context（appContext 只在 start/refresh 用）。
        val before = NetworkQuality.currentQuality().sampleCount
        repeat(5) { NetMetrics.record(rec(5000, ok = false, simulated = true)) }
        val after = NetworkQuality.currentQuality().sampleCount
        assertEquals("模拟样本不得改变感知层样本数", before, after)
    }
}
