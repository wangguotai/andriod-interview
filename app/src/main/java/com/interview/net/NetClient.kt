package com.interview.net

import android.content.Context
import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 全 App 网络层收口 —— 唯一的 OkHttpClient 工厂。
 *
 * ─── 先讲「为什么要收口」 ───
 *
 * 本仓库重构前有两套各自为政的网络客户端：
 *   · [com.interview.image.ImageDownloader] 在 companion 里自建 OkHttpClient
 *   · [com.interview.retrofit.Common] 用 Retrofit 默认客户端（等于又一个 OkHttpClient）
 *
 * 后果和线程池「每个页面 new 一个池」一模一样：**连接无法复用、DNS/连接池
 * 各建各的、度量各测各的**。网络优化的收益恰恰来自全局共享 ——
 * 你无法对一个「每处都是新连接」的 App 谈连接复用率。
 *
 * 所以本类是**唯一**允许 `OkHttpClient.Builder()` 出现的地方。
 * 与 [com.interview.thread.ThreadPools] 的分工：
 *   ThreadPools  = 谁在什么线程上执行（泳道治理）
 *   NetClient    = 用什么配置、连去哪、怎么度量（网络治理）
 * 两者是叠乘关系，不是替代关系（见 ThreadPools 类注释的 ⚠️）。
 *
 * ─── 装配顺序（顺序有意义，不是随便排的）───
 *
 *   Dns ─▶ EventListener ─▶ Application Interceptor(重试/自适应超时) ─▶ 网络
 *
 * · Dns 在连接层，必须最先装配，否则建连前的解析不受控。
 * · EventListener 挂在 client 上，对所有 Call 生效，是度量的唯一入口。
 * · 重试拦截器用 **addInterceptor**（应用层）而非 addNetworkInterceptor：
 *   网络层拦截器跑在「单次网络往返」内，看不到建连失败，也不该重试整个 Call。
 *   这是重试放错层最常见的错误，会导致「重试次数 = 连接尝试次数」的诡异结果。
 */
object NetClient {

    private const val TAG = "NetClient"

    /**
     * 全 App 唯一的 DNS 实现（含 TTL 缓存 + 坏 IP 熔断）。
     *
     * 之所以做成可访问的单例而不是藏在 [build] 内部：DNS 的缓存/熔断状态
     * 需要被实验页观测，而且必须**跨 client 共享**（API 档与下载档复用同一份缓存，
     * 否则同域名解析两次，缓存形同虚设）。
     */
    val dns: InterviewDns by lazy {
        InterviewDns(
            // 上游解析服务器。
            // ⚠️ 诚实标注：这里用的是公共 DNS（阿里 / 114DNS）。
            // 生产环境应替换为**自建/HTTPDNS 服务**的地址，才能获得就近调度
            // 与防劫持；公共 DNS 只是让本 demo 不依赖私有服务即可跑通。
            servers = listOf(
                InetSocketAddress("223.5.5.5", 53),   // 阿里公共 DNS
                InetSocketAddress("114.114.114.114", 53),
            ),
        )
    }

    /**
     * 供 Application 调用：启动网络质量感知。
     * 幂等；必须在第一次请求之前调用（否则冷启动首个请求拿不到网络类型）。
     */
    fun init(context: Context) {
        NetworkQuality.start(context)
    }

    /**
     * 构造一个装配好全部优化的 OkHttpClient。
     *
     * 默认返回**共享单例**（[shared]）；只有实验页需要对照不同参数时才自建，
     * 且要显式传 [config] —— 自建会各带一份连接池，这本身就是实验要量的东西。
     *
     * @param rustTransport 非 null 时，在**最后**追加 [RustTransportInterceptor]，
     *   让满足路由判据的请求改走 Rust HTTP/3。传 null（默认）则完全不接入 ——
     *   [shared]/[downloads] 因此保持既有行为，接入新传输**零回归风险**。
     */
    fun build(
        config: NetConfig = NetConfig.DEFAULT,
        rustTransport: RustTransportConfig? = null,
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .dns(dns)
            .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(config.writeTimeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(config.baseCallTimeoutMillis, TimeUnit.MILLISECONDS)
            // 保留 OkHttp 自身的连接级恢复（它比应用层重试更轻量），
            // 应用层的 [AdaptiveRetryInterceptor] 负责退避与幂等语义，两者互补。
            .retryOnConnectionFailure(true)
            .connectionPool(
                okhttp3.ConnectionPool(config.maxIdleConnections, config.keepAliveMinutes, TimeUnit.MINUTES)
            )
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = config.maxRequests
                    maxRequestsPerHost = config.maxRequestsPerHost
                }
            )
            // 度量：per-call 实例由 Factory 创建，并把建连失败回报给 DNS 熔断表。
            .eventListenerFactory(
                NetEventListener.Factory { address ->
                    runCatching {
                        InetAddress.getByName(address.hostString)?.let { dns.reportFailure(it) }
                    }
                }
            )
            // 策略：应用层重试（含自适应超时）。
            .addInterceptor(AdaptiveRetryInterceptor(config))
            // 传输：Rust HTTP/3 —— 必须在**最后**追加，才能保留上面所有拦截器。
            // （放前面会让重试/自适应超时看不到这些请求，静默失效。）
            .apply {
                rustTransport?.let { addInterceptor(RustTransportInterceptor(config = it)) }
            }
            .build()
    }

    /**
     * 全局共享客户端（API 档）。
     * 懒加载 + 线程安全（Kotlin `by lazy` 默认 SYNCHRONIZED）。
     */
    val shared: OkHttpClient by lazy {
        Log.i(TAG, "构造共享 OkHttpClient（DNS + EventListener + 自适应重试）")
        build(NetConfig.DEFAULT)
    }

    /**
     * 大文件下载客户端（下载档）。
     *
     * 与 [shared] 分开是**刻意的设计**，不是重复建设：
     *   · 超时档位不同（8s 自适应 vs 60s 固定）——见 [NetConfig] 类注释
     *   · 重试策略不同（3 次 vs 1 次）——大文件重下不划算
     *   · 域名不同，本就不共享连接池
     *
     * 但两者**共享同一个 DNS 与 EventListener**，所以 DNS 缓存、坏 IP 熔断、
     * 分阶段度量是全 App 一致的。这是「收口」的真正含义：
     * 收的是**治理与观测**，执行参数按下游资源分档 —— 与 ThreadPools
     * 「治理收口、执行分道」是同一个设计哲学。
     */
    val downloads: OkHttpClient by lazy {
        Log.i(TAG, "构造下载 OkHttpClient（下载档：长超时 / 不重试）")
        build(NetConfig.DOWNLOAD)
    }


    /** 当前 DNS 实现（供实验页展示缓存/熔断状态）。 */
    fun dnsDebugState(): String = dns.debugState()

    /**
     * 构造一个**接入 Rust HTTP/3** 的实验客户端。
     *
     * 与 [build] 的关系：这是唯一「官方」的生产式装配入口，把 [RustTransportConfig]
     * 传下去即可。刻意不做成默认 —— 默认关闭是本仓库对新传输通道的一贯态度
     * （见 [RustTransportConfig] 注释）：装上代码 ≠ 开始接管流量。
     *
     * ⚠️ 必须在 net 泳道线程上使用该 client（Rust 传输是阻塞式 JNI 调用）。
     */
    fun buildWithRustTransport(
        config: NetConfig = NetConfig.DEFAULT,
        rustTransport: RustTransportConfig = RustTransportConfig(enabled = true, hostAllowlist = setOf("api.github.com")),
    ): OkHttpClient = build(config, rustTransport)

    /**
     * 构建期对该 URL 的路由判定（供实验页展示「为什么走/不走 Rust」）。
     * 纯逻辑，不发起请求，不读网络状态以外的任何东西。
     */
    fun explainRoute(url: String, method: String = "GET"): String {
        val parsed = runCatching { url.toHttpUrl() }.getOrNull()
            ?: return "URL 非法：$url"
        val q = networkQuality()
        val cfg = RustTransportConfig(
            enabled = true,
            hostAllowlist = setOf(parsed.host),
        )
        val decision = decideRustRoute(
            url = parsed,
            method = method,
            hasProxy = false,
            bodySize = 0,
            level = q.level,
            onMainThread = false,
            config = cfg,
        )
        return when (decision) {
            is RouteDecision.Route -> "✅ 走 Rust HTTP/3（level=${q.level}）"
            is RouteDecision.Skip -> "➡ 走 OkHttp：${decision.reason}（level=${q.level}）"
        }
    }

    /**
     * 全局网络质量快照。UI 与降级策略统一从这里读，不直接碰 NetworkQuality，
     * 保证未来换实现时只有一个改动点。
     */
    fun networkQuality(): NetworkQuality.Snapshot = NetworkQuality.currentQuality()
}
