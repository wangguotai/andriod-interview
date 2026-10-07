package com.interview.稳定性监控

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * [AnrTraceParser] 的纯 JVM 单测。
 *
 * ⚠️ 为什么这个测试必须存在：trace 解析器运行在**回捞路径**上，
 *    它一旦抛异常或误判格式，代价是「证据永久丢失」——而证据只在
 *    系统那个有界环形缓冲里存在，下次启动就被覆盖了。
 *    所以这里要钉死三种格式的分支行为。
 *
 * ⚠️ 测试数据说明：下面的文本 trace 是按系统真实格式**手写**的最小样本
 *    （字段名、`- waiting to lock ... held by thread N` 语法都对齐真实输出），
 *    **不是**从设备抓的原始文件。真实文件的完整样本见文档里的实测摘要。
 */
class AnrTraceParserTest {

    // ─────────────────────────────────────────────
    // 分支一：文本 ANR trace
    // ─────────────────────────────────────────────

    private val textTrace = """
        ----- pid 12345 at 2026-10-07 12:00:00 -----
        Cmd line: com.example.myapplication
        Build fingerprint: 'Redmi/alioth/alioth:12/...'
        ----- end 12345 -----

        "main" prio=5 tid=1 Native
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72bdcce8 self=0xb4000074e65ecc00
          | sysTid=12345 nice=-10 cgrp=default sched=0/0 handle=0x74e7c434f8
          | state=S schedstat=( 850170453 76107254 590 ) utm=74 stm=10 core=4 HZ=100
          | held mutexes=
          native: #00 pc 0000000000098f1c  /apex/com.android.art/lib64/libart.so
          at android.os.MessageQueue.nativePollOnce(Native method)
          at android.os.MessageQueue.next(MessageQueue.java:335)
          - waiting to lock <0x05c43aca> (a java.lang.Object) held by thread 12
          at android.os.Looper.loop(Looper.java:245)
          at android.app.ActivityThread.main(ActivityThread.java:8134)

        "pool-3-thread-1" prio=5 tid=12 Waiting
          | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72bdcce8 self=0xb4000074e65ecc00
          | held mutexes= "mutator lock"(shared held)
          at java.lang.Object.wait(Native method)
          at com.example.Demo.waitForIt(Demo.java:42)
        """.trimIndent()

    @Test
    fun `文本 trace 应判为非二进制且能解析出线程堆栈`() {
        val r = AnrTraceParser.parse(textTrace.toByteArray(StandardCharsets.UTF_8))

        assertFalse("文本 trace 不应被误判为二进制", r.isBinary)
        assertTrue("应确实解析到线程堆栈", r.hasThreadStacks)
        assertEquals("应解析出 pid", 12345, r.pid)

        val main = r.threads.firstOrNull { it.isMain }
        assertNotNull("必须能定位到 main 线程", main)
        assertEquals("main", main!!.name)
        assertTrue("main 线程应带帧", main.frames.isNotEmpty())
        // 成名的坑：main 线程状态藏在属性行里（Native / Waiting / Sleeping…）
        assertTrue("属性行应包含线程状态标记", main.attrs.contains("Native"))
    }

    @Test
    fun `文本 trace 应解析出锁等待的 holder tid`() {
        val r = AnrTraceParser.parse(textTrace.toByteArray(StandardCharsets.UTF_8))
        val main = r.threads.first { it.isMain }

        assertNotNull("应解析出等待的锁地址", main.waitingLock)
        assertEquals("holder 应指向 thread 12", 12, main.holderTid)
        assertTrue(
            "应生成「谁在等谁」的死锁链（main → t12）",
            r.deadlockChain.any { it.contains("main") || it.contains("12") },
        )
    }

    // ─────────────────────────────────────────────
    // 分支二：二进制（protobuf，native 崩溃）
    // ─────────────────────────────────────────────

    @Test
    fun `二进制 trace 应判为二进制且不误报堆栈`() {
        // protobuf 的典型开头：不可打印字节占比极高
        val binary = byteArrayOf(
            0x08, 0x01, 0x12, 0x04, 0x6E, 0x61, 0x74, 0x69, // 含少量可打印
            0x1A, 0x80.toByte(), 0xFF.toByte(), 0x00, 0x7F, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x03, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        val r = AnrTraceParser.parse(binary)

        assertTrue("应判为二进制 proto", r.isBinary)
        assertFalse("二进制不应声称解析到线程堆栈", r.hasThreadStacks)
        assertTrue("摘要应说明这是二进制/原生崩溃", r.summary.isNotEmpty())
    }

    // ─────────────────────────────────────────────
    // 分支三：被裁剪的 trace（只有头，没有线程块）
    // ─────────────────────────────────────────────

    @Test
    fun `被裁剪的 trace 应有头但无堆栈`() {
        // 实测过：ApplicationExitInfo 给的子集常被裁剪 —— 连 subject/线程块都没有
        val stripped = """
            ----- pid 12345 at 2026-10-07 12:00:00 -----
            Cmd line: com.example.myapplication
            ----- end 12345 -----
        """.trimIndent()

        val r = AnrTraceParser.parse(stripped.toByteArray(StandardCharsets.UTF_8))

        assertFalse(r.isBinary)
        assertEquals(12345, r.pid)
        assertFalse("没有线程块时不应声称有堆栈", r.hasThreadStacks)
        assertNull("拿不到 subject 就返回 null，不要编造", r.subject)
    }

    // ─────────────────────────────────────────────
    // 边界：绝不能抛异常（回捞路径上崩了 = 证据永久丢失）
    // ─────────────────────────────────────────────

    @Test
    fun `空输入与垃圾输入都不能抛异常`() {
        val empty = AnrTraceParser.parse(ByteArray(0))
        assertFalse(empty.isBinary)
        assertTrue("空输入摘要应说明 0 字节", empty.summary.contains("0"))

        val garbage = ByteArray(64) { it.toByte() }   // 递增字节，既非文本也非合法 proto
        val r = AnrTraceParser.parse(garbage)
        assertTrue("垃圾输入也必须返回 Result 而不是抛异常", r.summary.isNotEmpty())
    }

    @Test
    fun `超大 trace 的帧数应被截断`() {
        val head = """
            ----- pid 999 at 2026-10-07 12:00:00 -----
            Cmd line: com.example.myapplication
            ----- end 999 -----

            "main" prio=5 tid=1 Runnable
              | held mutexes=
        """.trimIndent()
        val frames = (1..2000).joinToString("\n") { "  at fake.Clazz.method$it(Clazz.java:$it)" }
        val r = AnrTraceParser.parse("$head\n$frames".toByteArray(StandardCharsets.UTF_8))

        val main = r.threads.first { it.isMain }
        assertTrue("main 线程帧数应被截断（避免全量上报）", main.frames.size <= 30)
    }
}
