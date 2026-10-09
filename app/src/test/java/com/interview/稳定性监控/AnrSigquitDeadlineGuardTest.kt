package com.interview.稳定性监控

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 轮询上界的安全性测试 —— **独立成一个文件**，因为它与其它判定测试的代价特征完全不同：
 * 它真的会跑满整个窗口（80 次 × 250ms ≈ 20 秒），而这正是我们要证明的「有界」。
 * 混进主测试类会让每次全量单测都慢 20 秒。
 * （仓库里已有先例：`RustTransportInterceptorIntegrationTest` 跑 35s。）
 *
 * ⚠️ 这条测试的由来是一个**真实的死循环风险**：
 *    轮询条件最初只写了 `while (… && SystemClock.uptimeMillis() < deadline)`。
 *    在设备上这没问题（uptimeMillis 单调增长），但宿主 JVM 单测里
 *    `android.os.SystemClock.uptimeMillis()` 因为 `isReturnDefaultValues = true`
 *    **恒为 0** —— deadline 永远追不上，循环永不退出。
 *    换句话说：**一个只在测试环境暴露、却会挂住整个 JVM 的问题**。
 *    修法是加轮数上界（见 `AnrSigquitChannel` 的 `maxPolls`）。
 */
class AnrSigquitDeadlineGuardTest {

    /** ⚠️ 本测试会改全局 `conditionSampler`，必须还原 —— 否则会污染同 JVM 里的其它测试类 */
    @After
    fun restoreSampler() {
        AnrSigquitChannel.conditionSampler = { pid -> AnrSigquitChannel.systemConditionOf(pid) }
        AnrSigquitChannel.resetAudit()
    }

    /**
     * 条件永远读不到 NOT_RESPONDING（模拟「等不到 flag」）。
     * 必须**有界地**返回，不能挂住。
     */
    @Test(timeout = 60_000)
    fun `等不到 flag 时必须有界退出（不得死循环）`() {
        AnrSigquitChannel.resetAudit()
        AnrSigquitChannel.conditionSampler = { null }   // 永远读不到

        val started = System.currentTimeMillis()
        // sender 必须不等于自己，否则会走「自报信号」的快路径而不进入轮询
        AnrSigquitChannel.onSigquit("1901:1000:-1:-999")
        val cost = System.currentTimeMillis() - started

        // 这条日志在测试输出里能看到，便于人工核对实际代价
        println("无线索轮询耗时 ${cost}ms（期望 ≈ 20s，即窗口上界）")
        assertEquals(
            "窗口上界应为 20s / 250ms = 80 次（改它即改动了「等 flag」的耐心上限）",
            80,
            (AnrSigquitChannel.POLL_WINDOW_MS / AnrSigquitChannel.POLL_INTERVAL_MS).toInt(),
        )
    }
}
