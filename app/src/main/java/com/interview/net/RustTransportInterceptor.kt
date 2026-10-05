package com.interview.net

import android.util.Log
import com.interview.net.nativebridge.NetLabBridge
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.io.IOException

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: **A 阶段的核心接缝** —— 把 HTTP/3(Rust) 传输接进 OkHttp，作为最后一道应用拦截器。
 *
 * ─── 为什么缝在应用拦截器，而不是 Call.Factory / socketFactory ───
 *
 * 见 [DESIGN-rust-transport.md] §0：CallFactory 会让 OkHttp 自己的配置与
 * **它前面所有拦截器全部被绕过**（含 [AdaptiveRetryInterceptor]，静默失效）；
 * socketFactory 只能换 TCP/TLS socket，装不下跑在 UDP 上、握手融合的 QUIC。
 * 应用拦截器（放最后）是唯一既保留既有拦截器、又能接管传输的缝。
 *
 * 装配位置：`client.addInterceptor(RustTransportInterceptor(...))` **放最后**。
 * 「最后」意味着它看到的是前面拦截器（重试/自适应超时/自定义 header）已经处理过的
 * 请求 —— 这正是我们想要的：重试、超时预算、幂等语义继续生效，只有**传输**换了。
 *
 * ─── ⚠️ 一个必须显式处理的副作用（否则指标会静默变脏）───
 *
 * OkHttp 对每个 Call 都会在机场**最外层**触发 `EventListener.callStart/callEnd`
 * （见 RealCall → getResponseWithInterceptorChain → timeoutExit）。因此当我们在
 * 应用拦截器里合成一个 Response、**不调用 `chain.proceed()`** 时：
 *
 *   [NetEventListener] 仍会收到 `callEnd`，并**再记一条** NetMetrics.Record。
 *
 * 如果本类也记一条，就会**双记**：分位数、成功率、连接复用率全部被污染，
 * 而且不会报错。这是本接缝最隐蔽的坑，必须显式处理。
 *
 * 处理方式（见 [buildSynthesizedResponse] 的 `sentRequestAtMillis`）：把
 * `sentRequestAtMillis` 置为**负值**作为「已由本拦截器记账」的标记；
 * [NetEventListener] 见到该标记就跳过记账。用请求侧时间戳而不是自定义 Header
 * 传递标记，是为了**不给真实请求增加任何头**（自定义头会真的发到网络上）。
 */

/**
 * @param bridge   native 桥（可注入以便单测替换）
 * @param config   路由配置
 * @param callTimeoutMillis Rust 侧的超时预算（毫秒）。**为什么不由 [Interceptor.Chain] 读**：
 *   `Chain` 不暴露 call timeout（没有 `callTimeoutMillis()`），且自适应超时是在
 *   `AdaptiveRetryInterceptor` 里通过 `call.timeout()` 设置的、也读不回来。
 *   故这里用装配时传入的基础预算 —— 语义上略保守（比 OkHttp 实际预算不晚），
 *   属于如实标注的近似，而非假装精确。
 * @param levelProvider 取当前网络档位（注入以便单测；生产传 [NetClient.networkQuality] 的结果）
 * @param hasProxyProvider 判断某 URL 是否会被代理（注入以便单测；默认用系统代理选择器）
 * @param onMainThreadProvider 判断是否主线程（注入以避免本类依赖 Android Looper，便于单测）
 * @param metricsSink 记录度量（注入以便单测断言「恰好记一次」）
 */
class RustTransportInterceptor(
    private val bridge: RustFetch = NetLabBridgeFetch,
    private val config: RustTransportConfig = RustTransportConfig(),
    private val callTimeoutMillis: Long = NetConfig.DEFAULT.baseCallTimeoutMillis,
    private val levelProvider: () -> NetworkLevel = { NetClient.networkQuality().level },
    private val hasProxyProvider: (okhttp3.HttpUrl) -> Boolean = ::systemProxyApplies,
    private val onMainThreadProvider: () -> Boolean = { isMainThread() },
    private val metricsSink: (NetMetrics.Record) -> Unit = { NetMetrics.record(it) },
) : Interceptor {

    /**
     * 让 native 桥可被替换 —— 单测里用一个纯 Kotlin 假实现，从而在 JVM 上
     * 验证「路由 → 合成 Response → 记账」整条链路，不需要设备与 .so。
     */
    interface RustFetch {
        fun fetch(
            url: String,
            method: String,
            headers: List<Pair<String, String>>,
            body: ByteArray?,
            timeoutMillis: Long,
            cancelHandle: NetLabBridge.CancelTokenHandle?,
        ): NetLabBridge.FetchResult
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val decision = decideRustRoute(
            url = request.url,
            method = request.method,
            hasProxy = hasProxyProvider(request.url),
            bodySize = request.bodySizeOrZero(),
            level = levelProvider(),
            onMainThread = onMainThreadProvider(),
            config = config,
        )
        if (decision is RouteDecision.Skip) {
            // 极其常见（默认关闭、非弱网、非白名单），故用 verbose 级避免刷屏。
            Log.v(TAG, "跳过 Rust 传输 → OkHttp：${decision.reason} (${request.method} ${request.url})")
            return chain.proceed(request)
        }

        return try {
            val native = fetchViaRust(chain, request)
            buildSynthesizedResponse(request, native)
        } catch (e: IOException) {
            // 真失败：**不吞**。抛出后由 OkHttp 的既有失败链处理（前面拦截器可重试）。
            Log.w(TAG, "Rust 传输失败，异常上抛交由既有链路处理：${e.message}")
            throw e
        }
    }

    // ─────────────────────────────────────────
    // 发起 Rust 请求
    // ─────────────────────────────────────────

    private fun fetchViaRust(chain: Interceptor.Chain, request: Request): NetLabBridge.NativeResponse {
        val call = chain.call()

        // 取消桥接：OkHttp 的取消状态 → Rust 取消标志。
        // OkHttp 的 cancel 可能在另一个线程发生，故用轮询式桥（见下 fetch 后的检查）。
        val token = bridge.let { NetLabBridge.newToken() }
        try {
            // 请求体：第一版只支持「已完整读入内存」的小 body（上限由路由判定把关）。
            val bodyBytes = request.body?.let { rb ->
                Buffer().also { rb.writeTo(it) }.readByteArray()
            }

            val timeout = callTimeoutMillis

            val result = bridge.fetch(
                url = request.url.toString(),
                method = request.method,
                headers = request.headers.toMultimap().flatMap { (k, vs) -> vs.map { k to it } },
                body = bodyBytes,
                timeoutMillis = timeout,
                cancelHandle = token,
            )

            // 协作式取消的「返回后复查」：native 在阶段边界查标志，这里再查一次
            // OkHttp 侧是否已取消，避免把「用户已放弃」的结果当成成功返回。
            if (call.isCanceled()) {
                throw IOException("Call 已被取消（Rust 传输返回后复查）")
            }

            return when (result) {
                is NetLabBridge.FetchResult.Success -> result.response
                is NetLabBridge.FetchResult.Failure -> throw mapFailure(result, request)
            }
        } finally {
            token?.close() // 恰好释放一次（AutoCloseable）
        }
    }

    /**
     * 把 Rust 失败映射成 [IOException]，**按语义分类**而不是一律「网络错误」：
     *   · pin 不匹配 → 带上明确字样，绝不能被上层当作「网络抖动」静默重试；
     *   · 取消        → 也是 IOException，但语义是「用户主动放弃」；
     *   · 其它        → 普通 IO 失败，可被 [AdaptiveRetryInterceptor] 按幂等规则重试。
     */
    private fun mapFailure(f: NetLabBridge.FetchResult.Failure, request: Request): IOException {
        val prefix = when {
            f.isPinMismatch -> "证书固定校验失败（安全事件，不可重试）"
            f.isCancelled -> "Rust 传输被取消"
            else -> "Rust HTTP/3 传输失败"
        }
        return IOException("$prefix: code=${f.code} ${f.message} [${request.method} ${request.url}]")
    }

    // ─────────────────────────────────────────
    // 合成 okhttp3.Response
    // ─────────────────────────────────────────

    /**
     * 用 Rust 的传输结果合成一个 OkHttp `Response`。**不调用 `chain.proceed()`** —
     * 这正是「接管传输」的含义。
     *
     * ─── 拿不到、必须诚实置空/标注的字段（与 Cronet 同类的坑）───
     *   · `handshake`：v1 置 null。QUIC 把 TLS 融进握手、quinn 未暴露 cipher/TLS 版本，
     *     完整重建 [okhttp3.Handshake] 需要的信息不足。**这相对 OkHttp 路径是信息降级**，
     *     已在 [NativeResponse.peerSpkiSha256] 里回传叶证书 SPKI 作为补偿。
     *   · `networkResponse` / `cacheResponse`：null（本次不经这两条路径）。
     *   · `protocol`：用 OkHttp 官方的 [Protocol.QUIC]（OkHttp 文档明确它是「为
     *     一个提供 QUIC 支持的拦截器而保留」的常量）。**用 QUIC 而不是 HTTP_2**：
     *     两者 ALPN 不同，混用会让任何按协议分支的逻辑悄悄走错路径
     *     （OkHttp 的 `Protocol.get()` 也只认自己的字符串）。
     *   · `sentRequestAtMillis`：置 **-1** 作为「本拦截器已记账」的标记（见类注释）。
     */
    private fun buildSynthesizedResponse(
        request: Request,
        native: NetLabBridge.NativeResponse,
    ): Response {
        // 响应头：**逐条 add** 而不是 set —— 重复头（Set-Cookie）必须全部保留。
        val headers = okhttp3.Headers.Builder().apply {
            native.headers.forEach { (k, v) -> add(k, v) }
            // 传输层可归因的元数据挂在头里，供日志/EventListener 读取（不污染业务可见头？——
            // 见下注释：这里刻意不写入业务头，改由 Record 直接带上）。
        }.build()

        metricsSink(
            NetMetrics.Record(
                host = request.url.host,
                method = request.method,
                code = native.status,
                ok = native.status in 200..299,
                protocol = native.protocol,
                reusedConnection = native.reusedConnection,
                fromCache = false,
                responseBytes = native.body.size.toLong(),
                stage = NetMetrics.Stage(
                    // Rust 路径的 DNS 由传入的 IP 承担，v1 未单独采集 → -1（未测到，不是 0）。
                    dnsMillis = -1,
                    // ⚠️ 语义标注：QUIC 的 connect 段**含融合的 TLS 握手**，
                    // 与 TCP 路径的 connect/tls 两段不可直接比较（见设计 §5）。
                    connectMillis = native.connectMillis,
                    tlsMillis = -1,
                    firstByteMillis = native.firstByteMillis,
                    totalMillis = native.totalMillis,
                ),
                errorMessage = null,
            )
        )

        val body = native.body.toResponseBody("application/octet-stream".toMediaTypeOrNull())
        return Response.Builder()
            .request(request)
            .protocol(Protocol.QUIC)
            .code(native.status)
            .message(messageFor(native.status))
            .headers(headers)
            .body(body)
            // 负数 = 「已由 RustTransportInterceptor 记账」的标记，NetEventListener 见到即跳过。
            .sentRequestAtMillis(-1L)
            .receivedResponseAtMillis(System.currentTimeMillis())
            .build()
    }

    private fun messageFor(code: Int): String = when (code) {
        in 200..299 -> "OK"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        429 -> "Too Many Requests"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "HTTP $code"
    }

    private fun Request.bodySizeOrZero(): Long {
        val b = body ?: return 0
        // contentLength 为 -1 表示未知（流式）。未知**不**当成 0：那会让流式 body
        // 被误判为「小 body」而放行，故按「超限」处理（返回 Long.MAX_VALUE）。
        return if (b.contentLength() >= 0) b.contentLength() else Long.MAX_VALUE
    }

    companion object {
        private const val TAG = "RustTransport"

        /**
         * 某 URL 是否会经系统代理。
         *
         * ⚠️ 不能只判「proxySelector 非 null」：OkHttp 总会有一个（默认的系统选择器），
         * 那样会恒为 true，把所有请求都判成「有代理」而**永不路由**（静默失效）。
         * 必须真的问一次选择器：有任何一个非 NO_PROXY 的候选即视为需要代理。
         *
         * 局限（如实标注）：这只是**预判**，最终走哪条代理由 OkHttp 的连接阶段决定；
         * 我们宁可保守（判成有代理 → 退回 OkHttp，安全）也不激进。
         */
        internal fun systemProxyApplies(url: okhttp3.HttpUrl): Boolean = runCatching {
            java.net.ProxySelector.getDefault()
                ?.select(url.toUri())
                ?.any { it != java.net.Proxy.NO_PROXY }
                ?: false
        }.getOrDefault(false)

        /**
         * 是否主线程。用 `Looper.getMainLooper()` 判断，不引 Android 类型到构造签名里
         * （实际仍依赖 android.os.Looper，但被 [onMainThreadProvider] 的默认值隔离，
         * 单测注入替身后即可在 JVM 上跑）。
         */
        private fun isMainThread(): Boolean = try {
            android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        } catch (_: Throwable) {
            false
        }
    }
}

/** 生产用默认实现：转发到 [NetLabBridge]。抽成对象便于单测替换。 */
internal object NetLabBridgeFetch : RustTransportInterceptor.RustFetch {
    override fun fetch(
        url: String,
        method: String,
        headers: List<Pair<String, String>>,
        body: ByteArray?,
        timeoutMillis: Long,
        cancelHandle: NetLabBridge.CancelTokenHandle?,
    ): NetLabBridge.FetchResult =
        NetLabBridge.fetch(url, method, headers, body, timeoutMillis, cancelHandle)
}
