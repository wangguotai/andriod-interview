package com.interview.稳定性监控

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.interview.稳定性监控.StabilityReporter

/**
 * 通道 4（SIGQUIT 拦截）的**纯 JVM 单测**。
 *
 * ⚠️ 为什么只测这一层：`AnrSigquitHook` 在宿主 JVM 上不可用
 *    （`System.loadLibrary` 必失败、`Looper` 返回 null），
 *    所以要测得**把判定逻辑挤成纯函数**——这也是 [AnrSigquitChannel.decide]
 *    被设计成接受 `sampledCondition` 参数而不是自己去查系统的原因。
 *
 * ⚠️ 测的到底是什么：不是「Android 会不会发 SIGQUIT」（那要靠真机实验），
 *    而是**我们收到信号后的判定纪律**——不确认就绝不报 ANR。
 *    这条纪律错了，代价是平台的 ANR 率被污染（假阳性），
 *    或者反过来把真 ANR 当误报丢掉（假阴性）。两者都比"不监控"更糟：
 *    前者让告警系统失灵，后者让人以为问题不存在。
 */
class AnrSigquitChannelTest {

    private fun payload(senderPid: Int, code: Int = -1, selfPid: Int = 12345) =
        AnrSigquitChannel.Payload(
            senderPid = senderPid,
            senderUid = 10000,
            code = code,
            selfPidFromNative = selfPid,
        )

    // ─────────────────────────────────────────────
    // 载荷解析
    // ─────────────────────────────────────────────

    @Test
    fun `解析正常载荷`() {
        val p = AnrSigquitChannel.parsePayload("1234:10000:-1:12345")
        assertNotNull(p)
        assertEquals(1234, p!!.senderPid)
        assertEquals(10000, p.senderUid)
        assertEquals(-1, p.code)
        assertEquals(12345, p.selfPidFromNative)
    }

    @Test
    fun `解析失败时不猜值`() {
        // 段数不对 → null（宁可显示「不可用」，也不显示瞎猜的现场）
        assertNull(AnrSigquitChannel.parsePayload("1234:10000:-1"))
        assertNull(AnrSigquitChannel.parsePayload("1234:10000:-1:12345:extra"))
        assertNull(AnrSigquitChannel.parsePayload(""))
        assertNull(AnrSigquitChannel.parsePayload(null))
        // 非数字 → null
        assertNull(AnrSigquitChannel.parsePayload("a:b:c:d"))
        // 空格容错（native 侧不会产生，但解析不该因此崩）
        assertNotNull(AnrSigquitChannel.parsePayload(" 1 : 2 : -1 : 3 "))
    }

    @Test
    fun `si_code 只做线索不做判据`() {
        // 0 = kill 发的，-1 = sigqueue 发的。两者在 ANR 流程里都合法
        //（高版本 sigqueue、某些低版本 kill），所以文案要如实展示、不能被当成判据。
        assertTrue(payload(9, code = 0).codeText().contains("SI_USER"))
        assertTrue(payload(9, code = -1).codeText().contains("SI_QUEUE"))
        assertEquals("SI_?", payload(9, code = 42).codeText())
    }

    // ─────────────────────────────────────────────
    // 二次判定：四种形态
    // ─────────────────────────────────────────────

    @Test
    fun `自报信号直接否掉 —— 不需要查系统`() {
        // senderPid == selfPid：调试器/热修/测试工具给自己发 SIGQUIT 的典型形态
        val d = AnrSigquitChannel.decide(
            payload = payload(senderPid = 12345, selfPid = 12345),
            selfPid = 12345,
            sampledCondition = AnrSigquitChannel.PROCESS_ERROR_NOT_RESPONDING, // 即使系统态是 ANR
        )
        assertFalse("自己是发送者时必须否掉，否则每条 kill -3 都会变成假 ANR", d.confirmed)
        assertTrue(d.reason.contains("自己"))
    }

    @Test
    fun `系统态 NOT_RESPONDING 才确认 ANR`() {
        val d = AnrSigquitChannel.decide(
            payload = payload(senderPid = 999),
            selfPid = 12345,
            sampledCondition = AnrSigquitChannel.PROCESS_ERROR_NOT_RESPONDING,
        )
        assertTrue(d.confirmed)
        assertTrue(d.reason.contains("NOT_RESPONDING"))
    }

    @Test
    fun `没有系统错误态则判为误报`() {
        // 最常见的真实形态：**别的进程** ANR，我们只是被顺带 dump 的那一批之一
        val d = AnrSigquitChannel.decide(
            payload = payload(senderPid = 999),
            selfPid = 12345,
            sampledCondition = null,
        )
        assertFalse("隔离了「别的进程 ANR 时被顺带 dump」这一类误报", d.confirmed)
        // 判定理由必须把「漏报」也写出来 —— 不能让读者以为"非 ANR"就是安全结论
        assertTrue("必须同时说明漏报缺口", d.reason.contains("SilentAnr"))
        assertTrue(d.reason.contains("漏报"))
    }

    @Test
    fun `有错误态但不是 NOT_RESPONDING 仍不算 ANR`() {
        // ProcessErrorStateInfo 里还有 CRASH(1) 等其它 condition，
        // 「有错误态」不等于「无响应」——这是最容易写错的一处。
        val d = AnrSigquitChannel.decide(
            payload = payload(senderPid = 999),
            selfPid = 12345,
            sampledCondition = 1, // CRASH
        )
        assertFalse(d.confirmed)
        assertTrue(d.reason.contains("不是 NOT_RESPONDING"))
    }

    @Test
    fun `判定不依赖 code —— sigqueue 与 kill 都要能确认`() {
        // 同一次真 ANR，在不同 ROM 上 si_code 可能是 SI_QUEUE 也可能是 SI_USER，
        // 判定结果必须一致（否则跨版本口径就崩了）。
        val queue = AnrSigquitChannel.decide(
            payload = payload(senderPid = 999, code = -1),
            selfPid = 12345,
            sampledCondition = AnrSigquitChannel.PROCESS_ERROR_NOT_RESPONDING,
        )
        val user = AnrSigquitChannel.decide(
            payload = payload(senderPid = 999, code = 0),
            selfPid = 12345,
            sampledCondition = AnrSigquitChannel.PROCESS_ERROR_NOT_RESPONDING,
        )
        assertEquals(queue.confirmed, user.confirmed)
        assertTrue(queue.confirmed)
        // 但现场描述要保留 code，便于事后核对"这条是 kill 发的还是 sigqueue 发的"
        assertTrue(queue.payload.describe().contains("SI_QUEUE"))
        assertTrue(user.payload.describe().contains("SI_USER"))
    }

    // ─────────────────────────────────────────────
    // 边沿去重（真机实测逼出来的：一次 ANR 来 4 条 SIGQUIT）
    // ─────────────────────────────────────────────

    /** 造一个「已确认 ANR」的输入：sender 是别人 + 系统态为 NOT_RESPONDING */
    private fun callConfirmedAnr() {
        AnrSigquitChannel.conditionSampler = { AnrSigquitChannel.PROCESS_ERROR_NOT_RESPONDING }
        AnrSigquitChannel.onSigquit("1901:1000:-1:${android.os.Process.myPid()}")
    }

    private fun anrEventCount(): Int =
        StabilityReporter.snapshot(200).count { it.kind == StabilityReporter.Kind.ANR }

    @Test
    fun `一次 ANR 的多条 SIGQUIT 只上报一条 —— 但后续信号仍会被看见`() {
        AnrSigquitChannel.resetAudit()
        val before = anrEventCount()
        val decisions = mutableListOf<AnrSigquitChannel.Decision>()
        AnrSigquitChannel.decisionSink = { decisions.add(it) }

        // 真机实测形态：system_server 在几秒内连发多条（Redmi K40 上是 4 条）。
        // 注意：第 1 条会**等满窗口**（判定确认），后续条命中复用窗口立即返回。
        repeat(4) { callConfirmedAnr() }

        assertEquals(
            "一次 ANR 必须只产生 1 条 Kind.ANR；否则 ANR 率会被放大成假的",
            before + 1,
            anrEventCount(),
        )
        assertTrue("至少首条信号要走到判定，链路才算活", decisions.isNotEmpty())
        assertTrue("首条必须判为 ANR", decisions.first().confirmed)
        AnrSigquitChannel.decisionSink = null
    }

    @Test
    fun `复用窗口不得吞掉自报信号 —— 它必须被判为误报而不是被复用`() {
        AnrSigquitChannel.resetAudit()
        val decisions = mutableListOf<AnrSigquitChannel.Decision>()
        AnrSigquitChannel.decisionSink = { decisions.add(it) }

        // 先制造一次真 ANR（进入复用窗口）
        callConfirmedAnr()
        // 紧接着一条自报信号：若被"复用"吞掉，就会把一条真实的误报证据藏起来
        AnrSigquitChannel.onSigquit("${android.os.Process.myPid()}:1000:0:${android.os.Process.myPid()}")

        val selfSent = decisions.last()
        assertFalse("自报信号必须走到 decide 并被否掉，不能走复用分支", selfSent.confirmed)
        assertTrue(selfSent.reason.contains("自己"))
        AnrSigquitChannel.decisionSink = null
    }

    @Test
    fun `去重不跨 ANR —— 重置后可以再次上报`() {
        AnrSigquitChannel.resetAudit()
        callConfirmedAnr()
        val afterFirst = anrEventCount()

        // resetAudit 清掉窗口（实验页「清空」按钮的语义）
        AnrSigquitChannel.resetAudit()
        callConfirmedAnr()

        assertEquals("清空之后必须能重新上报（否则第二次 ANR 会被永久吞掉）",
            afterFirst + 1, anrEventCount())
    }

    // ─────────────────────────────────────────────
    // 诊断读数解析
    // ─────────────────────────────────────────────

    @Test
    fun `诊断读数解析`() {
        val s = com.interview.anrsignal.AnrSigquitHook.Stats.parse("3:3:0:1234:1234:1")
        assertNotNull(s)
        assertEquals(3L, s!!.hit)
        assertEquals(3L, s.rethrow)
        assertEquals(0L, s.rethrowFail)
        assertEquals(1234, s.catcherTid)
        assertTrue(s.active)
    }

    @Test
    fun `catcherTid 为 -1 时必须给出明确告警`() {
        // 这个状态（找不到 SignalCatcher）不是小事：只剩拦截没有转发，
        // 系统要等到 20s 超时才继续。读数必须把后果说清楚，而不是只显示一个 -1。
        val s = com.interview.anrsignal.AnrSigquitHook.Stats.parse("-1:-1:0:-1:-1:1")!!
        assertEquals(-1, s.catcherTid)
        val text = s.describe()
        assertTrue(text.contains("未找到 SignalCatcher"))
        assertTrue("必须说明后果（拖慢系统取证）", text.contains("20s"))
        assertTrue("必须给出处置建议", text.contains("uninstall"))
    }

    @Test
    fun `读数解析失败返回 null`() {
        assertNull(com.interview.anrsignal.AnrSigquitHook.Stats.parse("1:2:3"))
        assertNull(com.interview.anrsignal.AnrSigquitHook.Stats.parse(""))
        assertNull(com.interview.anrsignal.AnrSigquitHook.Stats.parse(null))
        assertNull(com.interview.anrsignal.AnrSigquitHook.Stats.parse("1:2:3:4:5:x"))
    }
}
