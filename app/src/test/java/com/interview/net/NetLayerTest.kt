package com.interview.net

import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 网络层**纯逻辑**单测 —— 宿主 JVM 上跑，秒级，无需设备。
 *
 * ─── 为什么这层单测存在感很强 ───
 *
 * 本仓库对 native 代码的纪律是「算法与 JNI 分离，cargo test 秒级反馈」。
 * 网络层这里做的是同一件事：把「打分 / 退避 / 分位 / 幂等 / DNS 报文」
 * 这些**会算错但不会崩**的逻辑抽成纯函数，用单测钉死；
 * 剩下真正需要设备的（EventListener 的真实回调时序、连接复用）
 * 交给 NetLabActivity 真机实验。
 *
 * 这个划分不是形式主义：弱网优化最容易出错的地方恰恰不是「请求发不出去」，
 * 而是「策略算错了却不报错」——比如重试把非幂等的 POST 重发了两遍，
 * 上线看指标一切正常，直到用户被重复下单。
 * 这些 bug 只有靠这种秒级单测才拦得住。
 */
class NetLayerTest {

    // ─────────────────────────────────────────
    // 1. 网络质量打分
    // ─────────────────────────────────────────

    @Test
    fun `无网必定判 OFFLINE，且优先于一切其它信号`() {
        val (level, _) = NetworkScoring.evaluate(
            hasNetwork = false,
            transport = "WIFI",
            smoothRttMillis = 20,      // 即使 RTT 很好
            successRate = 1.0,
            sampleCount = 100,
        )
        assertEquals(NetworkLevel.OFFLINE, level)
    }

    @Test
    fun `假连接检测 —— 系统说有网但请求全失败，必须判 WEAK`() {
        val (level, _) = NetworkScoring.evaluate(
            hasNetwork = true,
            transport = "WIFI",        // 满格 WiFi
            smoothRttMillis = 30,
            successRate = 0.0,         // 但一次都没成功
            sampleCount = 10,
        )
        assertEquals(NetworkLevel.WEAK, level)
    }

    @Test
    fun `样本不足时 WiFi 乐观为 GOOD，蜂窝保守为 MARGINAL`() {
        val wifi = NetworkScoring.evaluate(true, "WIFI", -1, -1.0, 0).first
        val cell = NetworkScoring.evaluate(true, "CELLULAR", -1, -1.0, 0).first
        assertEquals(NetworkLevel.GOOD, wifi)
        assertEquals(NetworkLevel.MARGINAL, cell)
    }

    @Test
    fun `样本足够时的 RTT 分档边界`() {
        // 99ms → GOOD
        assertEquals(
            NetworkLevel.GOOD,
            NetworkScoring.evaluate(true, "WIFI", 99, 1.0, 10).first,
        )
        // 100ms（含）→ MARGINAL
        assertEquals(
            NetworkLevel.MARGINAL,
            NetworkScoring.evaluate(true, "WIFI", 100, 1.0, 10).first,
        )
        // 300ms（含）→ WEAK
        assertEquals(
            NetworkLevel.WEAK,
            NetworkScoring.evaluate(true, "WIFI", 300, 1.0, 10).first,
        )
    }

    @Test
    fun `成功率跌破阈值时，即使 RTT 很好也判 WEAK`() {
        val (level, _) = NetworkScoring.evaluate(
            hasNetwork = true,
            transport = "WIFI",
            smoothRttMillis = 40,
            successRate = 0.5, // 低于 0.6
            sampleCount = 10,
        )
        assertEquals(NetworkLevel.WEAK, level)
    }

    // ─────────────────────────────────────────
    // 2. 自适应超时 / 退避（Algorithm 部分不依赖 Android）
    // ─────────────────────────────────────────

    @Test
    fun `指数退避随尝试次数增长，且带抖动落在期望区间`() {
        val config = NetConfig(retryBaseDelayMillis = 300)
        // 抖动系数 ∈ [0.5, 1.5)，故第 n 次退避 ∈ [exp*0.5, exp*1.5)
        assertInJitterRange(300L, asRetry(config), 1)
        assertInJitterRange(600L, asRetry(config), 2)
        assertInJitterRange(1200L, asRetry(config), 3)
    }

    @Test
    fun `退避有上限，不会因尝试次数多而无限增长`() {
        val config = NetConfig(retryBaseDelayMillis = 300)
        val retry = asRetry(config)
        // 第 10 次：2^9 * 300 = 153600，必须被压到上限（2000ms）附近
        val delay = retry.backoffMillis(10)
        assertTrue("退避 ${delay}ms 超过上限*1.5", delay <= 2000L * 3 / 2 + 1)
    }

    // ─────────────────────────────────────────
    // 3. 幂等判定（防止重试造成重复写入）
    // ─────────────────────────────────────────

    @Test
    fun `GET HEAD 可重试，POST 默认不可重试`() {
        val retry = asRetry(NetConfig())
        assertTrue(retry.isRetryable(request("GET")))
        assertTrue(retry.isRetryable(request("HEAD")))
        assertTrue(retry.isRetryable(request("PUT")))
        assertTrue(retry.isRetryable(request("DELETE")))
        assertFalse("POST 非幂等，默认绝不可重试", retry.isRetryable(request("POST")))
        assertFalse(retry.isRetryable(request("PATCH")))
    }

    @Test
    fun `POST 带幂等键时可重试`() {
        val retry = asRetry(NetConfig())
        assertTrue(retry.isRetryable(request("POST", idempotencyKey = "abc-123")))
    }

    @Test
    fun `只有网关类状态码与限流值得重试，4xx 业务错误不重试`() {
        val retry = asRetry(NetConfig())
        listOf(408, 429, 502, 503, 504).forEach {
            val response = okhttp3.Response.Builder()
                .request(request("GET"))
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(it)
                .message("test")
                .build()
            assertTrue("HTTP $it 应可重试", retry.shouldRetryResponse(response))
        }
        listOf(400, 401, 404, 418, 500).forEach {
            val response = okhttp3.Response.Builder()
                .request(request("GET"))
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(it)
                .message("test")
                .build()
            assertFalse("HTTP $it 不该重试", retry.shouldRetryResponse(response))
        }
    }

    // ─────────────────────────────────────────
    // 4. 分位数与均值（度量层必须算对）
    // ─────────────────────────────────────────

    @Test
    fun `分位数取真实样本值而非插值`() {
        val data = listOf(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L, 90L, 100L)
        // 最近邻法：index = size*p/100，取该下标真实值
        assertEquals(10L, NetMetrics.percentile(data, 10))
        assertEquals(50L, NetMetrics.percentile(data, 50))
        assertEquals(90L, NetMetrics.percentile(data, 90))
        assertEquals(100L, NetMetrics.percentile(data, 99))
    }

    @Test
    fun `分位数在空样本时返回 -1，避免误读成 0ms`() {
        assertEquals(-1L, NetMetrics.percentile(emptyList(), 50))
    }

    @Test
    fun `均值忽略 -1（未发生的阶段），且全为 -1 时返回 -1`() {
        assertEquals(30L, NetMetrics.avgOf(listOf(10L, 20L, -1L, -1L, 60L)))
        assertEquals(-1L, NetMetrics.avgOf(listOf(-1L, -1L)))
        assertEquals(-1L, NetMetrics.avgOf(emptyList()))
    }

    // ─────────────────────────────────────────
    // 5. DNS 报文组包 / 解包
    // ─────────────────────────────────────────

    @Test
    fun `DNS 查询报文头与问题段符合 RFC1035`() {
        val query = DnsResolver(emptyList()).buildQuery("api.github.com", DnsResolver.TYPE_A)
        // QDCOUNT = 1
        assertEquals(1, ((query[4].toInt() and 0xFF) shl 8) or (query[5].toInt() and 0xFF))
        // ANCOUNT/NSCOUNT/ARCOUNT = 0
        for (i in 6..11) assertEquals(0, query[i].toInt())
        // flags: 标准查询 + 期望递归 = 0x0100
        assertEquals(0x01, query[2].toInt() and 0xFF)
        assertEquals(0x00, query[3].toInt() and 0xFF)
        // QNAME 编码：3"api" 6"github" 3"com" 0
        assertEquals(3, query[12].toInt())
        assertEquals('a'.code, query[13].toInt())
        // 报文末尾：QTYPE=A, QCLASS=IN
        val n = query.size
        assertEquals(0, query[n - 4].toInt())
        assertEquals(DnsResolver.TYPE_A, query[n - 3].toInt())
        assertEquals(0, query[n - 2].toInt())
        assertEquals(1, query[n - 1].toInt())
    }

    @Test
    fun `DNS 响应解析 —— 单条 A 记录`() {
        // 手写一个最小响应：header(12) + question(api.github.com A IN) + answer(A: 1.2.3.4)
        val response = buildAResponse("api.github.com", byteArrayOf(1, 2, 3, 4))
        val addrs = DnsResolver(emptyList()).parseAnswersForTest(response)
        assertEquals(1, addrs.size)
        assertEquals("1.2.3.4", addrs[0].hostAddress)
    }

    @Test
    fun `DNS 响应解析 —— 多条 A 记录全部收集`() {
        val response = buildAResponse(
            "example.com",
            byteArrayOf(10, 0, 0, 1),
            byteArrayOf(10, 0, 0, 2),
        )
        val addrs = DnsResolver(emptyList()).parseAnswersForTest(response)
        assertEquals(2, addrs.size)
        assertEquals("10.0.0.1", addrs[0].hostAddress)
        assertEquals("10.0.0.2", addrs[1].hostAddress)
    }

    // ─────────────────────────────────────────
    // 6. 字面量 IP 直通
    // ─────────────────────────────────────────

    @Test
    fun `识别 IPv4 与 IPv6 字面量，不误判普通域名`() {
        assertTrue(InterviewDns.isIpLiteral("1.2.3.4"))
        assertTrue(InterviewDns.isIpLiteral("255.255.255.255"))
        assertTrue(InterviewDns.isIpLiteral("::1"))
        assertTrue(InterviewDns.isIpLiteral("2408:8207::1"))
        assertFalse(InterviewDns.isIpLiteral("api.github.com"))
        assertFalse(InterviewDns.isIpLiteral("999.1.1.1")) // 越界不是 IP
        assertFalse(InterviewDns.isIpLiteral("1.2.3"))     // 段数不足
    }

    // ─────────────────────────────────────────
    // 辅助
    // ─────────────────────────────────────────

    private fun request(method: String, idempotencyKey: String? = null) =
        okhttp3.Request.Builder()
            .url("https://api.github.com/zen")
            .apply { idempotencyKey?.let { header("Idempotency-Key", it) } }
            .method(method, if (method in setOf("POST", "PUT", "PATCH")) "".toRequestBody() else null)
            .build()

    private fun asRetry(config: NetConfig) = AdaptiveRetryInterceptor(config)

    private fun assertInJitterRange(expectedBase: Long, retry: AdaptiveRetryInterceptor, attempt: Int) {
        // 抖动随机，故重复采样，断言全部落在 [base*0.5, base*1.5)
        repeat(50) {
            val d = retry.backoffMillis(attempt)
            assertTrue(
                "退避 ${d}ms 超出 [${expectedBase / 2}, ${expectedBase * 3 / 2})",
                d >= expectedBase / 2 && d < expectedBase * 3 / 2,
            )
        }
    }

    /**
     * 构造一个最小合法 DNS 响应报文（含 answer 段）。
     * 供解析测试用 —— 不依赖真实网络，因此可在 JVM 上稳定复现。
     */
    private fun buildAResponse(host: String, vararg ips: ByteArray): ByteArray {
        val header = ByteArray(12).apply {
            this[2] = 0x81.toByte() // QR=1, RD=1
            this[3] = 0x80.toByte() // RA=1, RCODE=0
            this[5] = 0x01          // QDCOUNT=1
            this[7] = ips.size.toByte() // ANCOUNT
        }
        val question = encodeName(host) + byteArrayOf(0, 1, 0, 1) // A + IN
        val answers = ips.map { ip ->
            val rr = ByteArray(12 + ip.size)
            var p = 0
            // NAME: 压缩指针指向 offset 12（问题段的 host 名）
            rr[p++] = 0xC0.toByte(); rr[p++] = 0x0C
            rr[p++] = 0; rr[p++] = 1        // TYPE = A
            rr[p++] = 0; rr[p++] = 1        // CLASS = IN
            rr[p++] = 0; rr[p++] = 0; rr[p++] = 0; rr[p++] = 60 // TTL = 60
            rr[p++] = 0; rr[p++] = ip.size.toByte() // RDLENGTH
            System.arraycopy(ip, 0, rr, p, ip.size)
            rr
        }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }
        return header + question + answers
    }

    private fun encodeName(host: String): ByteArray {
        val out = ArrayList<Byte>()
        host.split('.').forEach { label ->
            out.add(label.length.toByte())
            label.toByteArray(Charsets.US_ASCII).forEach { out.add(it) }
        }
        out.add(0)
        return out.toByteArray()
    }
}
