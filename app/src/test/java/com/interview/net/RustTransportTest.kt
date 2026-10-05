package com.interview.net

import com.interview.net.nativebridge.NetLabBridge
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: Rust HTTP/3 接入（A 阶段）的**纯逻辑**单测 —— 宿主 JVM 秒级，无需设备。
 *
 * 覆盖三块「会算错但不会崩」的逻辑：
 *   1. [decideRustRoute] 路由判定的每条分支与优先级；
 *   2. [NetLabBridge.parseWire] 线格式解析（跨语言重复实现，最易漂移）；
 *   3. 头部行格式编解码。
 *
 * 其中线格式解析用了**由 Rust 编码器生成的黄金十六进制串**
 * （`cargo run -p netlab --example wire_golden`）—— 这是把「Kotlin 与 Rust 对
 * 同一份格式的理解一致」变成可断言的证据，否则两端各写一份、靠人眼看注释同步，
 * 迟早在某个字段上漂移，而且**不会报错**，只会解析出错误的值。
 */
class RustTransportTest {

    // ─────────────────────────────────────────
    // 1. 路由判定
    // ─────────────────────────────────────────

    private fun route(
        url: String = "https://api.github.com/zen",
        method: String = "GET",
        hasProxy: Boolean = false,
        bodySize: Long = 0,
        level: NetworkLevel = NetworkLevel.WEAK,
        onMainThread: Boolean = false,
        config: RustTransportConfig = RustTransportConfig(
            enabled = true,
            hostAllowlist = setOf("api.github.com"),
        ),
    ): RouteDecision = decideRustRoute(
        url = url.toHttpUrl(),
        method = method,
        hasProxy = hasProxy,
        bodySize = bodySize,
        level = level,
        onMainThread = onMainThread,
        config = config,
    )

    private fun skipReason(d: RouteDecision): RouteReason {
        assertTrue("期望 Skip，实际 $d", d is RouteDecision.Skip)
        return (d as RouteDecision.Skip).reason
    }

    @Test
    fun `默认配置关闭 —— 装上代码不等于开始接管流量`() {
        val d = decideRustRoute(
            url = "https://api.github.com/zen".toHttpUrl(),
            method = "GET", hasProxy = false, bodySize = 0,
            level = NetworkLevel.WEAK, onMainThread = false,
            config = RustTransportConfig(), // 默认
        )
        assertEquals(RouteReason.DISABLED, skipReason(d))
    }

    @Test
    fun `命中判据时才走 Rust`() {
        val d = route()
        assertTrue(d is RouteDecision.Route)
        assertEquals(RouteReason.ELIGIBLE, (d as RouteDecision.Route).reason)
    }

    @Test
    fun `安全类判据优先于机会类判据`() {
        // 主线程 + 非 https + 非白名单全部同时满足时，必须报最先命中的安全理由，
        // 而不是「host 不在白名单」。优先级错了会让排查者看到误导性的原因。
        val d = route(
            url = "http://evil.example.com/x",
            onMainThread = true,
            config = RustTransportConfig(enabled = true, hostAllowlist = setOf("evil.example.com")),
        )
        assertEquals("主线程必须优先于一切（否则会把 ANR 风险藏起来）", RouteReason.MAIN_THREAD, skipReason(d))

        // 去掉主线程后，明文应优先于白名单/网络档位。
        val d2 = route(
            url = "http://evil.example.com/x",
            config = RustTransportConfig(enabled = true, hostAllowlist = setOf("evil.example.com")),
        )
        assertEquals(RouteReason.NOT_HTTPS, skipReason(d2))
    }

    @Test
    fun `明文 http 一律拒走 Rust`() {
        assertEquals(RouteReason.NOT_HTTPS, skipReason(route(url = "http://api.github.com/zen")))
    }

    @Test
    fun `需要代理时明确拒绝，而不是静默直连`() {
        assertEquals(RouteReason.HAS_PROXY, skipReason(route(hasProxy = true)))
    }

    @Test
    fun `方法白名单与 Rust 侧一致`() {
        listOf("GET", "HEAD", "PUT", "DELETE").forEach {
            assertTrue("$it 应放行", route(method = it) is RouteDecision.Route)
        }
        listOf("POST", "PATCH", "OPTIONS", "TRACE").forEach {
            assertEquals("$it 不在 v1 白名单", RouteReason.METHOD_NOT_ALLOWED, skipReason(route(method = it)))
        }
    }

    @Test
    fun `方法大小写不敏感`() {
        assertTrue(route(method = "get") is RouteDecision.Route)
        assertTrue(route(method = "Delete") is RouteDecision.Route)
    }

    @Test
    fun `body 超上限不走 Rust，边界值本身放行`() {
        val cfg = RustTransportConfig(enabled = true, hostAllowlist = setOf("api.github.com"), maxRequestBodyBytes = 100)
        assertTrue(route(bodySize = 100, config = cfg) is RouteDecision.Route)
        assertEquals(RouteReason.BODY_TOO_LARGE, skipReason(route(bodySize = 101, config = cfg)))
    }

    @Test
    fun `未配白名单即不生效`() {
        // 空白名单 + 未放开任意 host → 任何 host 都不走。这是「未配置=不生效」的安全默认。
        assertEquals(
            RouteReason.HOST_NOT_ALLOWED,
            skipReason(route(config = RustTransportConfig(enabled = true, hostAllowlist = emptySet()))),
        )
    }

    @Test
    fun `allowAnyHost 可放开全部 host`() {
        val cfg = RustTransportConfig(enabled = true, allowAnyHost = true)
        assertTrue(route(url = "https://anything.example.com/", config = cfg) is RouteDecision.Route)
    }

    @Test
    fun `网络档位未达触发条件则不走`() {
        assertEquals(RouteReason.QUALITY_NOT_TRIGGERED, skipReason(route(level = NetworkLevel.GOOD)))
        // 默认只在 WEAK 触发
        assertTrue(route(level = NetworkLevel.WEAK) is RouteDecision.Route)
    }

    // ─────────────────────────────────────────
    // 2. 线格式解析（与 Rust `wire::encode` 对拍）
    // ─────────────────────────────────────────

    /**
     * 由 Rust 侧生成的黄金样本：
     *   `cargo run -p netlab --example wire_golden`
     * 内容：status=200 / proto=HTTP_3 / reused=false / connect_ms=42 / first_byte_ms=90 /
     *      total_ms=120 / body_bytes=5 / spki=d8a2… / 4 条头（含 2 条重复 set-cookie、
     *      1 条值含 ':'） / body = "hello"。
     *
     * **改动线格式必须同步重跑该命令并更新此常量**，否则此测试变红 —— 这正是它的价值。
     */
    private val goldenHex =
        "4f4b0a7374617475733d3230300a70726f746f3d485454505f330a7265757365643d66616c73650a" +
            "636f6e6e6563745f6d733d34320a66697273745f627974655f6d733d39300a746f74616c5f6d73" +
            "3d3132300a626f64795f62797465733d350a73706b693d64386132623438653136626233323162" +
            "643862663265393863636462336233386162313262313437616364613735633165666135353964" +
            "3066623139623636330a6864723d636f6e74656e742d747970653a206170706c69636174696f6e" +
            "2f6a736f6e0a6864723d7365742d636f6f6b69653a20613d313b20506174683d2f0a6864723d73" +
            "65742d636f6f6b69653a20623d323b20506174683d2f0a6864723d782d74726163653a20323032" +
            "362d31302d30355431323a30303a30305a0a0a68656c6c6f"

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }

    @Test
    fun `解析 Rust 生成的黄金样本 —— 字段全部对齐`() {
        val result = NetLabBridge.parseWire(hexToBytes(goldenHex))
        assertTrue("应解析成功，实际 $result", result is NetLabBridge.FetchResult.Success)
        val r = (result as NetLabBridge.FetchResult.Success).response

        assertEquals(200, r.status)
        assertEquals("HTTP_3", r.protocol)
        assertFalse(r.reusedConnection)
        assertEquals(42L, r.connectMillis)
        assertEquals(90L, r.firstByteMillis)
        assertEquals(120L, r.totalMillis)
        assertEquals(
            "d8a2b48e16bb321bd8bf2e98ccdb3b38ab12b147acda75c1efa559d0fb19b663",
            r.peerSpkiSha256,
        )
        assertEquals("hello", String(r.body, Charsets.UTF_8))
    }

    @Test
    fun `黄金样本里的重复 set-cookie 必须全部保留且有序`() {
        val r = (NetLabBridge.parseWire(hexToBytes(goldenHex)) as NetLabBridge.FetchResult.Success).response
        val cookies = r.headers.filter { it.first == "set-cookie" }.map { it.second }
        assertEquals(
            "重复头被合并会静默丢 cookie —— 必须全部保留",
            listOf("a=1; Path=/", "b=2; Path=/"),
            cookies,
        )
    }

    @Test
    fun `黄金样本里值含冒号的头不得被截断`() {
        val r = (NetLabBridge.parseWire(hexToBytes(goldenHex)) as NetLabBridge.FetchResult.Success).response
        assertEquals(
            "2026-10-05T12:00:00Z",
            r.headers.first { it.first == "x-trace" }.second,
        )
    }

    @Test
    fun `ERR 线格式解析出错误码与消息`() {
        val bytes = "ERR:4\nmsg=ProxyUnsupported\n\n".toByteArray()
        val result = NetLabBridge.parseWire(bytes)
        assertTrue(result is NetLabBridge.FetchResult.Failure)
        val f = result as NetLabBridge.FetchResult.Failure
        assertEquals(4, f.code)
        assertEquals("ProxyUnsupported", f.message)
        assertFalse(f.isCancelled)
        assertFalse(f.isPinMismatch)
    }

    @Test
    fun `取消与 pin 失败可被区分出来（语义不同，处理方式也不同）`() {
        val cancelled = NetLabBridge.parseWire("ERR:12\nmsg=cancelled\n\n".toByteArray())
        assertTrue((cancelled as NetLabBridge.FetchResult.Failure).isCancelled)

        val pin = NetLabBridge.parseWire(
            "ERR:11\nmsg=connect: SPKI-PIN-MISMATCH: 叶证书未命中\n\n".toByteArray()
        )
        assertTrue("pin 失败必须可识别为安全事件", (pin as NetLabBridge.FetchResult.Failure).isPinMismatch)
        assertFalse(pin.isCancelled)
    }

    @Test
    fun `body 长度不符必须失败，绝不猜着解析`() {
        // 声明 5 字节却只给 3 字节 —— 这是最危险的一类错（会读到错位 body）。
        val bad = "OK\nstatus=200\nproto=HTTP_3\nbody_bytes=5\n\nabc".toByteArray()
        assertTrue(NetLabBridge.parseWire(bad) is NetLabBridge.FetchResult.Failure)
    }

    @Test
    fun `缺少空行分隔必须失败`() {
        assertTrue(NetLabBridge.parseWire("OK\nstatus=200".toByteArray()) is NetLabBridge.FetchResult.Failure)
    }

    @Test
    fun `缺少必填 status 必须失败`() {
        assertTrue(NetLabBridge.parseWire("OK\nproto=HTTP_3\nbody_bytes=0\n\n".toByteArray()) is NetLabBridge.FetchResult.Failure)
    }

    @Test
    fun `可选计时段缺失时映射为 -1 而不是 0`() {
        // -1 = 未测到，0 = 「瞬间完成」。两者混淆会造出假的好看数字。
        val bytes = "OK\nstatus=200\nproto=HTTP_3\ntotal_ms=10\nbody_bytes=0\n\n".toByteArray()
        val r = (NetLabBridge.parseWire(bytes) as NetLabBridge.FetchResult.Success).response
        assertEquals(-1L, r.connectMillis)
        assertEquals(-1L, r.firstByteMillis)
        assertEquals(10L, r.totalMillis)
    }

    // ─────────────────────────────────────────
    // 3. 头部行格式编解码（与 Rust `wire::encode_header_lines` 对应）
    // ─────────────────────────────────────────

    @Test
    fun `头部行格式与 Rust 侧一致`() {
        val encoded = NetLabBridge.encodeHeaderLines(
            listOf("Accept" to "application/json", "X-Trace" to "abc")
        )
        assertEquals("Accept: application/json\r\nX-Trace: abc\r\n", String(encoded, Charsets.UTF_8))
    }

    @Test
    fun `空头部编码为空字节`() {
        assertEquals(0, NetLabBridge.encodeHeaderLines(emptyList()).size)
    }

    // ─────────────────────────────────────────
    // 4. NativeResponse 的值语义
    // ─────────────────────────────────────────

    @Test
    fun `含 ByteArray 的 data class 必须是值相等，否则单测会莫名其妙地假失败`() {
        fun make() = NetLabBridge.NativeResponse(
            status = 200, protocol = "HTTP_3", reusedConnection = false,
            connectMillis = 1, firstByteMillis = 2, totalMillis = 3,
            headers = listOf("a" to "b"), body = byteArrayOf(1, 2, 3), peerSpkiSha256 = null,
        )
        assertEquals(make(), make())
        assertEquals(make().hashCode(), make().hashCode())
        assertNotNull(make().body)
    }
}
