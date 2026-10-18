package com.interview.稳定性监控

import com.interview.net.NetClient
import com.interview.net.NetConfig
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Time: 2026/10/16
 * Author: wgt
 * Description: [CrashUploader] 的**集成测试** —— 用真实 OkHttp 栈 + MockWebServer 验证
 * 「返回 true 才清账」这条语义真的成立。
 *
 * ─── 为什么不能只做纯逻辑单测 ───
 *
 * 本类的每条硬约束都长在**真实 HTTP 语义**上：
 *   · 2xx 才算成功（不是"没抛异常"就算）；
 *   · 5xx 要重试、4xx 不许重试（重试判定错了就是"白烧流量"或"该重试的没重试"）；
 *   · token 进请求头、**不进** URL。
 * 这些用假 client 盖不住，必须让真 OkHttp 发一次真请求到本地 server。
 *
 * ⚠️ 单测里 `BuildConfig.CRASH_UPLOAD_URL` 是**空串**（它来自不入库的
 *    local.properties），所以测试全部走可注入的 [CrashUploader.uploadWith]，
 *    而不是 [CrashUploader.upload]。这正是把"读配置"与"发送语义"分开的原因 ——
 *    否则测试要么去改 BuildConfig（把测试与环境绑死），要么根本写不出来。
 *
 * ⚠️ 退避睡眠注入成 `{}`：5xx 用例要跑满 3 次尝试，真 sleep 会白等 2s。
 */
class CrashUploaderTest {

    private lateinit var server: MockWebServer

    /**
     * 显式用 127.0.0.1（而非 localhost）：本机 localhost 会先解析到 IPv6 ::1，
     * 而 MockWebServer 绑在 IPv4 回环，直接用 server.url() 会在建连阶段报
     * ConnectException，看着像被测代码的错。这是测试宿主自己的坑。
     *
     * 也正因为是 IP 字面量，[com.interview.net.InterviewDns] 会走"字面量直通"，
     * 不发 UDP 查询 —— 宿主 JVM 上也能真跑通 DNS 层。
     */
    private val host = "127.0.0.1"

    /** 真实走 [NetClient.build]：证明上报链路确实复用全局网络治理，而不是自建 client。 */
    private val client: OkHttpClient by lazy {
        NetClient.build(NetConfig(maxAttempts = 1, adaptiveTimeout = false))
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun endpoint(): String = server.url("/crash").newBuilder().host(host).build().toString()

    private fun oneEvent() = StabilityReporter.Event(
        kind = StabilityReporter.Kind.CRASH,
        timestamp = 1_700_000_000_000L,
        name = "java.lang.IllegalStateException",
        valueMs = 0,
        threadName = "main",
        isMainThread = true,
        isForeground = true,
        pid = 4242,
        detail = "boom",
        stack = "at com.example.Boom.kaboom(Boom.kt:1)",
    )

    /**
     * 统一注入点：真实 endpoint / 真 token / 真 client，**退避睡眠换成空实现**。
     * 这样重试次数（循环结构）与真实路径完全一致，但 5xx 用例不必白等 2s。
     */
    private fun upload(batch: List<StabilityReporter.Event>, token: String = "s3cr3t-token"): Boolean =
        CrashUploader.uploadWith(
            batch = batch,
            endpoint = endpoint(),
            token = token,
            client = client,
            sleep = {},
        )

    // ─────────────────────────────────────────────
    // 1. 2xx → true（服务端确认收到，调用方据此清账）
    // ─────────────────────────────────────────────

    @Test
    fun `2xx 返回 true，且请求体带上事件字段与批级元信息`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val ok = upload(listOf(oneEvent()))

        assertTrue("2xx 必须返回 true（否则崩溃遗嘱永远清不掉）", ok)
        assertEquals("成功不应重试", 1, server.requestCount)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/crash", recorded.path)
        // token 必须走请求头，绝不能进 URL
        assertEquals("Bearer s3cr3t-token", recorded.getHeader("Authorization"))

        val body = recorded.body.readUtf8()
        // 事件字段全覆盖
        assertTrue(body.contains("\"kind\":\"CRASH\""))
        assertTrue(body.contains("\"timestamp\":1700000000000"))
        assertTrue(body.contains("\"name\":\"java.lang.IllegalStateException\""))
        assertTrue(body.contains("\"threadName\":\"main\""))
        assertTrue(body.contains("\"isMainThread\":true"))
        assertTrue(body.contains("\"isForeground\":true"))
        assertTrue(body.contains("\"pid\":4242"))
        assertTrue(body.contains("\"detail\":\"boom\""))
        assertTrue(body.contains("Boom.kaboom"))
        // 批级元信息
        assertTrue("应带 sessionId", body.contains("\"sessionId\""))
        assertTrue("应带 batchIndex", body.contains("\"batchIndex\""))
        assertTrue("应带 events 数组", body.contains("\"events\""))
    }

    // ─────────────────────────────────────────────
    // 2. 5xx → false，且发生重试
    // ─────────────────────────────────────────────

    @Test
    fun `5xx 返回 false 且重试到上限`() {
        // 三次尝试都要有响应：1 + MAX_RETRIES(2) = 3
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("busy")) }

        val ok = upload(listOf(oneEvent()), token = "")

        assertFalse("5xx 必须返回 false，让事件保留", ok)
        assertEquals("5xx 应重试到上限（共 3 次尝试）", 3, server.requestCount)
    }

    // ─────────────────────────────────────────────
    // 3. 401 → false，且**不**重试
    // ─────────────────────────────────────────────

    @Test
    fun `401 返回 false 且不重试`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("unauthorized"))

        val ok = upload(listOf(oneEvent()), token = "bad-token")

        assertFalse("401 必须返回 false", ok)
        assertEquals("4xx 重试没有意义，绝不能重试", 1, server.requestCount)
    }

    @Test
    fun `413 同样不重试 —— 包太大，重发还是太大`() {
        server.enqueue(MockResponse().setResponseCode(413).setBody("payload too large"))

        val ok = upload(listOf(oneEvent()), token = "")

        assertFalse(ok)
        assertEquals(1, server.requestCount)
    }

    // ─────────────────────────────────────────────
    // 4. 空批次 / 未配置：语义边界
    // ─────────────────────────────────────────────

    @Test
    fun `空批次返回 true 且不发请求 —— 空 tick 不是一次提交`() {
        val ok = upload(emptyList())
        assertTrue("空批次视为「没什么可发」，返回 true 且不发请求", ok)
        assertEquals(0, server.requestCount)
    }

    // ─────────────────────────────────────────────
    // 5. 纯逻辑：endpoint 归一化与重试判定
    // ─────────────────────────────────────────────

    @Test
    fun `未配置或非法 endpoint 归一化为 null`() {
        assertNull("空串 = 未配置", CrashUploader.normalizeEndpoint(""))
        assertNull("null = 未配置", CrashUploader.normalizeEndpoint(null))
        assertNull("空白 = 未配置", CrashUploader.normalizeEndpoint("   "))
        assertNull("非 http(s) 的 scheme 不接受", CrashUploader.normalizeEndpoint("ftp://h/x"))
        assertNull("不是 URL 的垃圾串不接受", CrashUploader.normalizeEndpoint("not-a-url"))
        assertNotNull(CrashUploader.normalizeEndpoint("https://host/api/v1/crash"))
        assertEquals(
            "http 也应接受（自建内网 endpoint 常见）",
            "http://10.0.0.2:8080/api/v1/crash",
            CrashUploader.normalizeEndpoint("  http://10.0.0.2:8080/api/v1/crash  "),
        )
    }

    @Test
    fun `只有 5xx 被判为可重试`() {
        assertTrue(CrashUploader.shouldRetryStatus(500))
        assertTrue(CrashUploader.shouldRetryStatus(502))
        assertTrue(CrashUploader.shouldRetryStatus(503))
        assertTrue(CrashUploader.shouldRetryStatus(599))
        assertFalse("4xx 一律不重试", CrashUploader.shouldRetryStatus(400))
        assertFalse(CrashUploader.shouldRetryStatus(401))
        assertFalse(CrashUploader.shouldRetryStatus(403))
        assertFalse(CrashUploader.shouldRetryStatus(413))
        assertFalse("2xx 不重试", CrashUploader.shouldRetryStatus(200))
        assertFalse(CrashUploader.shouldRetryStatus(302))
    }

    // ─────────────────────────────────────────────
    // 6. describe() 不得回显 token
    // ─────────────────────────────────────────────

    @Test
    fun `describe 绝不回显 token`() {
        val text = CrashUploader.describe()
        assertFalse("describe 不能把 token 打出来", text.contains("s3cr3t-token"))
        assertTrue("应包含读数小标题", text.contains("崩溃上报出口"))
    }
}
