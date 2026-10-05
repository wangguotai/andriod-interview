package com.interview.net

import com.interview.net.nativebridge.NetLabBridge
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: [RustTransportInterceptor] 的**集成测试** —— 用真实 OkHttp 栈验证
 * 「纯逻辑单测证明不了」的那部分。
 *
 * ─── 为什么必须有这一层 ───
 *
 * 本接缝最隐蔽的风险不在路由算错，而在**账记错**：应用拦截器合成 Response 后，
 * OkHttp 仍会在最外层触发 `EventListener.callEnd`，于是「合成者记一条 + EventListener
 * 再记一条」= 双记，指标被静默污染、不报错。这条路径依赖 OkHttp **真实的拦截器链
 * 与事件时序**，假 Listener 盖不住，只有真的跑一遍 OkHttpClient 才能证明护栏有效。
 *
 * ─── 为什么要跑 HTTPS ───
 * 路由判定会**拒绝明文 http**（安全红线），所以用明文 MockWebServer 根本走不到
 * 被测路径，会一路退回 OkHttp。故这里用 okhttp-tls 自签证书起一个 HTTPS server，
 * 并把该证书加进测试 client 的信任库。（真实路径走的是我们的 Rust 桥，不连它。）
 *
 * 用一个**假 Rust 桥**（[FakeRustBridge]）注入：不发真网络请求、不需要设备与 .so。
 */
class RustTransportInterceptorIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var trust: HandshakeCertificates

    /**
     * 显式用 127.0.0.1 而不是 `localhost`：本机 `localhost` 会解析到 IPv6 `::1`，
     * 而 MockWebServer 绑定在 IPv4 回环上 —— 直接用 `server.url()`（host=localhost）
     * 会在连接阶段报 ConnectException，且**看起来像被测代码的错**。测试宿主自己踩的坑。
     */
    private val host = "127.0.0.1"

    @Before
    fun setUp() {
        val cert = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName(host)
            .commonName("localhost")
            .build()
        // 必须 `heldCertificate(cert)`：这样 HandshakeCertificates 才有**私钥**，
        // MockWebServer 才能把它作为服务端证书呈现。只 addTrustedCertificate 的话
        // 服务端没有密钥 → 握手直接失败（且报的是 handshake_failure，看着像别的问题）。
        // 同一个 HandshakeCertificates 同时用于：server 端呈现 + client 端信任。
        trust = HandshakeCertificates.Builder()
            .heldCertificate(cert)
            .addTrustedCertificate(cert.certificate)
            .build()
        server = MockWebServer()
        server.useHttps(trust.sslSocketFactory(), false)
        server.start()
    }

    /** 构造指向 mock server、但 host 固定为 127.0.0.1 的 URL。 */
    private fun url(path: String) = server.url(path).newBuilder().host(host).build()

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** 可编程的假 Rust 桥：不发网络请求，返回预设结果，并记录调用次数。 */
    private class FakeRustBridge(
        private val produce: (String) -> NetLabBridge.FetchResult,
    ) : RustTransportInterceptor.RustFetch {
        val calls = AtomicInteger(0)
        val seenUrls = mutableListOf<String>()
        override fun fetch(
            url: String,
            method: String,
            headers: List<Pair<String, String>>,
            body: ByteArray?,
            timeoutMillis: Long,
            cancelHandle: NetLabBridge.CancelTokenHandle?,
        ): NetLabBridge.FetchResult {
            calls.incrementAndGet()
            seenUrls += url
            return produce(url)
        }
    }

    private fun okResponse(body: String, headers: List<Pair<String, String>> = emptyList()) =
        NetLabBridge.FetchResult.Success(
            NetLabBridge.NativeResponse(
                status = 200,
                protocol = "HTTP_3",
                reusedConnection = false,
                connectMillis = 12,
                firstByteMillis = 40,
                totalMillis = 55,
                headers = headers,
                body = body.toByteArray(Charsets.UTF_8),
                peerSpkiSha256 = null,
            )
        )

    private fun rustConfig(enabled: Boolean = true, level: NetworkLevel = NetworkLevel.WEAK) =
        RustTransportConfig(
            enabled = enabled,
            hostAllowlist = setOf(host),
            triggerLevels = setOf(NetworkLevel.WEAK),
        ).let { it to level }

    private fun clientWith(
        bridge: RustTransportInterceptor.RustFetch,
        recorded: MutableList<NetMetrics.Record>,
        level: NetworkLevel = NetworkLevel.WEAK,
    ): OkHttpClient {
        val (cfg, lvl) = rustConfig(level = level)
        return OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .eventListenerFactory(NetEventListener.Factory())
            .addInterceptor(AdaptiveRetryInterceptor(NetConfig()))
            .addInterceptor(
                RustTransportInterceptor(
                    bridge = bridge,
                    config = cfg,
                    callTimeoutMillis = 5_000,
                    levelProvider = { lvl },
                    hasProxyProvider = { false },
                    onMainThreadProvider = { false },
                    metricsSink = { recorded += it },
                )
            )
            .build()
    }

    private fun request() = Request.Builder()
        .url(url("/zen"))
        .header("User-Agent", "netlab-test")
        .build()

    // ─────────────────────────────────────────

    @Test
    fun `命中路由时合成 Response，协议标记为 QUIC，状态码与 body 正确`() {
        // 排一个 599：若请求真的走了 chain.proceed，就会拿到 599 而不是 200。
        server.enqueue(MockResponse().setBody("should-not-be-used").setResponseCode(599))
        val bridge = FakeRustBridge { okResponse("hello-from-rust") }
        val recorded = mutableListOf<NetMetrics.Record>()

        clientWith(bridge, recorded).newCall(request()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals(Protocol.QUIC, resp.protocol)
            assertEquals("hello-from-rust", resp.body?.string())
        }
        assertEquals("应恰好调用一次 Rust 桥", 1, bridge.calls.get())
        assertEquals("不应走 OkHttp 网络层", 0, server.requestCount)
    }

    @Test
    fun `命中路由时 NetMetrics 恰好记一条，且带 HTTP_3 与阶段耗时`() {
        val bridge = FakeRustBridge { okResponse("x") }
        val recorded = mutableListOf<NetMetrics.Record>()

        clientWith(bridge, recorded).newCall(request()).execute().use { it.body?.string() }

        assertEquals("合成响应的那次必须恰好记一条（多记即双记污染）", 1, recorded.size)
        val r = recorded.single()
        assertEquals("HTTP_3", r.protocol)
        assertEquals(200, r.code)
        assertTrue(r.ok)
        assertEquals(12L, r.stage.connectMillis)
        assertEquals(40L, r.stage.firstByteMillis)
        assertEquals(55L, r.stage.totalMillis)
        // QUIC 无独立 TLS 段 → -1（未测到），不是 0
        assertEquals(-1L, r.stage.tlsMillis)
    }

    /**
     * ── 本文件存在的核心理由 ──
     *
     * 只让 OkHttp 自己的 [NetEventListener] 面对「应用拦截器合成的响应」，验证
     * 它**确实不会再记**（防双记护栏生效）——否则会留下一个 code=-1、协议为 `?`、
     * 阶段全空的脏样本，把分位数与成功率悄悄拉偏。
     *
     * 判据用真实的全局 [NetMetrics]：调用前后总条数不得变化，且窗口内不得出现
     * 「code=-1 且协议=?」的合成脏样本。
     */
    @Test
    fun `防双记护栏 —— 应用拦截器合成响应时 EventListener 不得再记一条`() {
        val (cfg, lvl) = rustConfig()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .eventListenerFactory(NetEventListener.Factory())
            .addInterceptor(
                RustTransportInterceptor(
                    bridge = FakeRustBridge { okResponse("rust") },
                    config = cfg,
                    levelProvider = { lvl },
                    hasProxyProvider = { false },
                    onMainThreadProvider = { false },
                    // 这个测试**故意不**接 metricsSink：模拟「只有 EventListener 在记」
                    // 的处境。若护栏失效，EventListener 就会写进真实的 NetMetrics。
                    metricsSink = { /* 丢弃 */ },
                )
            )
            .build()

        val before = NetMetrics.totalRecorded()
        client.newCall(request()).execute().use { assertEquals(200, it.code) }
        val after = NetMetrics.totalRecorded()

        assertEquals("合成响应不得让 EventListener 额外记账", before, after)
        assertTrue(
            "窗口内不得出现 code=-1、协议为 ? 的合成脏样本",
            NetMetrics.recent(50).none { it.code == -1 && it.protocol == "?" },
        )
    }

    @Test
    fun `未命中路由时不碰 Rust，照常走 OkHttp`() {
        server.enqueue(MockResponse().setBody("okhttp").setResponseCode(200))
        val bridge = FakeRustBridge { okResponse("rust") }
        val recorded = mutableListOf<NetMetrics.Record>()

        // GOOD 档位不在触发集合里 → 必须走 OkHttp。
        clientWith(bridge, recorded, level = NetworkLevel.GOOD)
            .newCall(request()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("okhttp", resp.body?.string())
                assertEquals(Protocol.HTTP_2, resp.protocol) // MockWebServer 默认 h2
            }
        assertEquals("不应调用 Rust 桥", 0, bridge.calls.get())
        assertEquals("应真的走了 OkHttp 网络层", 1, server.requestCount)
    }

    @Test
    fun `Rust 失败时抛 IOException，且失败链不额外记一条合成记录`() {
        val bridge = FakeRustBridge {
            NetLabBridge.FetchResult.Failure(code = 11, message = "connect: handshake failed")
        }
        val recorded = mutableListOf<NetMetrics.Record>()

        var threw = false
        try {
            clientWith(bridge, recorded).newCall(request()).execute()
        } catch (e: IOException) {
            threw = true
            assertTrue("失败信息应可诊断", e.message!!.contains("11"))
        }
        assertTrue("Rust 失败必须上抛 IOException（交给既有失败链）", threw)
        assertEquals("失败由 OkHttp 失败链处理，本拦截器不额外记一条", 0, recorded.size)
        assertEquals("失败时也不应回退去发真实网络请求", 0, server.requestCount)
    }

    @Test
    fun `pin 不匹配的失败必须可识别为安全事件（不可当普通网络抖动重试）`() {
        val bridge = FakeRustBridge {
            NetLabBridge.FetchResult.Failure(
                code = 11,
                message = "connect: SPKI-PIN-MISMATCH: 叶证书未命中任何固定指纹",
            )
        }
        val recorded = mutableListOf<NetMetrics.Record>()
        try {
            clientWith(bridge, recorded).newCall(request()).execute()
            throw AssertionError("应抛出 IOException")
        } catch (e: IOException) {
            assertTrue(
                "pin 失败必须带上明确字样，否则会被误当作网络抖动重试",
                e.message!!.contains("安全事件"),
            )
        }
    }

    @Test
    fun `未配 pin 的普通连接失败不得被误判为安全事件`() {
        val bridge = FakeRustBridge {
            NetLabBridge.FetchResult.Failure(code = 11, message = "connect: handshake failed: 证书已过期")
        }
        val recorded = mutableListOf<NetMetrics.Record>()
        try {
            clientWith(bridge, recorded).newCall(request()).execute()
            throw AssertionError("应抛出 IOException")
        } catch (e: IOException) {
            assertFalse(
                "普通链校验失败不是 pin 事件，混淆会让日志误导排查方向",
                e.message!!.contains("安全事件"),
            )
        }
    }

    @Test
    fun `重复响应头（Set-Cookie）在合成 Response 里全部保留`() {
        val bridge = FakeRustBridge {
            okResponse(
                "x",
                headers = listOf(
                    "Set-Cookie" to "a=1; Path=/",
                    "Set-Cookie" to "b=2; Path=/",
                ),
            )
        }
        val recorded = mutableListOf<NetMetrics.Record>()
        clientWith(bridge, recorded).newCall(request()).execute().use { resp ->
            assertEquals("重复头被合并会静默丢 cookie", listOf("a=1; Path=/", "b=2; Path=/"), resp.headers.values("Set-Cookie"))
        }
    }

    @Test
    fun `switch 关闭时完全不接入，OkHttp 行为不变`() {
        server.enqueue(MockResponse().setBody("plain").setResponseCode(200))
        val bridge = FakeRustBridge { okResponse("rust") }
        val recorded = mutableListOf<NetMetrics.Record>()

        clientWith(bridge, recorded, level = NetworkLevel.WEAK).let { _ ->
            // enabled=false 的配置：即使档位/白名单都命中，也必须走 OkHttp。
            val (_, lvl) = rustConfig()
            val disabled = OkHttpClient.Builder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                .eventListenerFactory(NetEventListener.Factory())
                .addInterceptor(
                    RustTransportInterceptor(
                        bridge = bridge,
                        config = RustTransportConfig(enabled = false, hostAllowlist = setOf(host)),
                        levelProvider = { lvl },
                        hasProxyProvider = { false },
                        onMainThreadProvider = { false },
                        metricsSink = { recorded += it },
                    )
                )
                .build()
            disabled.newCall(request()).execute().use { resp ->
                assertEquals("default off：装上代码不等于开始接管流量", "plain", resp.body?.string())
            }
        }
        assertEquals(0, bridge.calls.get())
    }

    @Test
    fun `native 不可用时显式失败，不得静默返回假数据`() {
        val bridge = FakeRustBridge { NetLabBridge.FetchResult.Failure(-1, "native 不可用") }
        val recorded = mutableListOf<NetMetrics.Record>()
        var threw = false
        try {
            clientWith(bridge, recorded).newCall(request()).execute()
        } catch (e: IOException) {
            threw = true
        }
        assertTrue("native 不可用必须显式失败，不得静默返回假数据", threw)
    }
}
