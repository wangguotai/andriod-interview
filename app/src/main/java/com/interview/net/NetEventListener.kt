package com.interview.net

import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 度量层的采集端 —— 用 OkHttp 原生 [EventListener] 拆解请求阶段耗时。
 *
 * ─── 为什么用 EventListener 而不是自己写 Interceptor ───
 *
 * 这是很多人会做错的一步。用 Interceptor 加 `System.nanoTime()` 只能测到
 * **应用拦截器链内**的时间，它天然包含不了 DNS、TCP、TLS —— 而弱网优化
 * 要定位的恰恰是这三段。更要命的是：Interceptor 测的「首包」是整段
 * 已缓冲完成的时间，不是 TTFB。
 *
 * [EventListener] 是 OkHttp 埋在网络栈**内部**的钩子，每个阶段边界都会回调：
 *
 *   dnsStart/End ─ TCP connectStart/End ─ secureConnect(TLS) ─
 *   requestHeaders ─ responseHeaders ─ responseBody ─ callEnd
 *
 * 这就是文章开头那张「一次请求耗时拆解图」在 OkHttp 里的**唯一准确实现方式**。
 *
 * ─── 生命周期（重要）───
 *
 * OkHttp 为**每个 Call 创建一个 EventListener 实例**（见 [Factory.newCall]），
 * 所以实例字段天然是 per-call 的，不需要 map 去关联、不会串数据。
 * 千万别做成单例再自己维护 call→状态 的映射 —— 那是在重新发明一个更差的轮子。
 *
 * ─── 线程纪律 ───
 *
 * 回调发生在 OkHttp 自己的线程（连接池/Dispatcher），**不在本仓库的泳道内**。
 * 这是 OkHttp 内置线程，无法也不必收编；但本类必须做到：
 * 极轻、无阻塞、无异常外泄（一次回调抛异常会让整个 Call 崩溃）。
 * 所以所有落库动作都在 [onCallEnd] 里一次性完成。
 */
class NetEventListener private constructor(
    private val callStartNs: Long,
    /**
     * 建连失败回报钩子。把「哪个 IP 连不上」这一只有建连侧才看得到的信息
     * 送回 [InterviewDns] 的坏 IP 熔断表。
     *
     * 为什么用回调而不是让本类持有 InterviewDns：保持单向依赖——
     * 度量层不该依赖某个具体的 DNS 实现，否则换 DNS 策略时要动度量代码。
     */
    private val onConnectFailed: ((InetSocketAddress) -> Unit)?,
) : EventListener() {

    /**
     * 每个 Call 由 OkHttp 调 [Factory.newCall] 创建新实例。
     * 这里返回新对象而不是复用，正是为了让「per-call 状态」不需额外同步。
     */
    class Factory(
        private val onConnectFailed: ((InetSocketAddress) -> Unit)? = null,
    ) : EventListener.Factory {
        override fun create(call: Call): EventListener =
            NetEventListener(System.nanoTime(), onConnectFailed)
    }

    // ─── per-call 状态（无需同步：仅单 Call 的回调线程访问）───

    private var dnsStartNs = -1L
    private var dnsMillis = -1L

    private var connectStartNs = -1L
    private var tcpMillis = -1L

    private var tlsStartNs = -1L
    private var tlsMillis = -1L

    private var requestSentNs = -1L
    private var serverWaitMillis = -1L

    private var connectAttempted = false
    private var reused = false
    private var fromCache = false
    private var responseBytes = 0L
    private var lastResponse: Response? = null
    private var lastProtocol: Protocol? = null

    // ─────────────────────────────────────────
    // DNS
    // ─────────────────────────────────────────

    override fun dnsStart(call: Call, domainName: String) {
        dnsStartNs = System.nanoTime()
    }

    override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
        if (dnsStartNs > 0) dnsMillis = millisSince(dnsStartNs)
    }

    // ─────────────────────────────────────────
    // TCP 建连
    // ─────────────────────────────────────────

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        connectAttempted = true
        connectStartNs = System.nanoTime()
    }

    /**
     * TLS 开始 = TCP 完成。
     *
     * ⚠️ OkHttp 里 `connectEnd` 是**整个连接（含 TLS）完成**才回调，
     * 所以「纯 TCP 握手耗时」必须在 `secureConnectStart` 这个边界截断，
     * 否则会把 TLS 时间算进 TCP。这是本类最容易写错的一点。
     */
    override fun secureConnectStart(call: Call) {
        if (connectStartNs > 0) tcpMillis = millisSince(connectStartNs)
        tlsStartNs = System.nanoTime()
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        if (tlsStartNs > 0) tlsMillis = millisSince(tlsStartNs)
    }

    override fun connectEnd(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
    ) {
        // 明文 http（无 TLS）时，TCP 耗时在 connectEnd 才结算。
        if (tlsStartNs < 0 && connectStartNs > 0 && tcpMillis < 0) {
            tcpMillis = millisSince(connectStartNs)
        }
    }

    /**
     * 单次建连失败（OkHttp 会对返回列表里的每个 IP 依次尝试）。
     *
     * 这是坏 IP 熔断**唯一精确的信号源**：`callFailed` 只知道「整个 Call 挂了」，
     * 不知道挂在哪个地址上；而 nextConnectionSpec/多 IP 的情况下，
     * 失败地址与最终结果没有一一对应关系。用这个回调才能把失败归因到具体 IP。
     */
    override fun connectFailed(
        call: Call,
        inetSocketAddress: InetSocketAddress,
        proxy: Proxy,
        protocol: Protocol?,
        ioe: IOException,
    ) {
        runCatching { onConnectFailed?.invoke(inetSocketAddress) }
    }

    override fun connectionAcquired(call: Call, connection: Connection) {
        // 本次 Call 完全没有触发 connectStart = 复用了连接池里的连接。
        // 这是「连接复用率」这个指标唯一的准确来源。
        reused = !connectAttempted
    }

    // ─────────────────────────────────────────
    // 请求 / 响应
    // ─────────────────────────────────────────

    override fun requestHeadersEnd(call: Call, request: Request) {
        requestSentNs = System.nanoTime()
    }

    override fun responseHeadersEnd(call: Call, response: Response) {
        if (requestSentNs > 0) serverWaitMillis = millisSince(requestSentNs)
        lastResponse = response
        lastProtocol = response.protocol
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        responseBytes += byteCount
    }

    override fun cacheHit(call: Call, response: Response) {
        fromCache = true
        lastResponse = response
        lastProtocol = response.protocol
    }

    // ─────────────────────────────────────────
    // 收口：一次请求结束
    // ─────────────────────────────────────────

    override fun callEnd(call: Call) {
        emit(call, ok = true, error = null)
    }

    override fun callFailed(call: Call, ioe: IOException) {
        emit(call, ok = false, error = ioe)
    }

    /**
     * 组装并落库。放在最后一次性做，避免在每个回调里重复触发统计与 UI 刷新。
     * 整体包在 runCatching 里：EventMonitor 抛异常不能反过来搞崩用户的请求。
     */
    private fun emit(call: Call, ok: Boolean, error: IOException?) {
        runCatching {
            val request = runCatching { call.request() }.getOrNull()
            val response = lastResponse
            val code = response?.code ?: -1
            // 「业务成功」比「HTTP 层没抛异常」更严：4xx/5xx 也算失败，
            // 否则 502 会被算进成功率里，弱网下最容易出现的就是 5xx。
            val businessOk = ok && code in 200..299

            NetMetrics.record(
                NetMetrics.Record(
                    host = request?.url?.host ?: "unknown",
                    method = request?.method ?: "?",
                    code = code,
                    ok = businessOk,
                    protocol = (lastProtocol ?: response?.protocol)?.toString() ?: "?",
                    reusedConnection = reused,
                    fromCache = fromCache,
                    responseBytes = responseBytes,
                    stage = NetMetrics.Stage(
                        dnsMillis = dnsMillis,
                        connectMillis = tcpMillis,
                        tlsMillis = tlsMillis,
                        firstByteMillis = serverWaitMillis,
                        totalMillis = millisSince(callStartNs),
                    ),
                    errorMessage = error?.let { it::class.java.simpleName + ": " + it.message },
                ),
            )
        }
    }

    private fun millisSince(startNs: Long): Long =
        (System.nanoTime() - startNs) / 1_000_000
}
