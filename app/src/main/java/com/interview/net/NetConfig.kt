package com.interview.net

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 网络层的集中配置。所有可调参数收口在此，**不散落在各 Interceptor 里**。
 *
 * 为什么值得单独抽一个 data class：
 *   1. 弱网调优的本质是**改参数**（超时、重试次数、退避基数）。
 *      参数散在多个类里时，一次实验要改 5 个文件，A/B 对比根本做不干净。
 *   2. 便于把「取值依据」贴在参数旁边 —— 本仓库的纪律是：
 *      每个数要么是推导，要么标成经验值/待实测，不留裸魔术数。
 *
 * ─── 为什么分 API 与 Download 两档，而不是一个万能配置 ───
 *
 * 这两类请求的**瓶颈资源不同**，用同一套超时对两种都是错的：
 *
 *   API（JSON 小包）  → **RTT 受限**。耗时 ≈ 握手 RTT + 服务端处理。
 *                        超时应对 RTT 敏感，RTT 涨了就该放宽，但不需要很大绝对值。
 *   Download（大文件）→ **带宽受限**。耗时 ≈ 体积 / 带宽，与 RTT 关系很弱。
 *                        超时必须给足（几十秒量级），且**不该重试**——
 *                        弱网下从头重下一遍 3MB 比「让它慢慢传完」更糟；
 *                        正确工具是 Range 断点续传（见文末 TODO）。
 *
 * 这正是文章里「请求/响应」一节要区分的东西。混在一个配置里，
 * 要么 API 在慢链路上被过小的超时误杀，要么大图下载把超时放到离谱。
 *
 * ⚠️ 两档 = 两个 OkHttpClient = 两份连接池，这是**有意的**，不是重构前那种
 * 「没人知道为什么会有两份」的重复。两者域名不同（api.github.com vs picsum），
 * 本来也不会共享连接。
 */
data class NetConfig(
    // ── 超时 ──
    /**
     * per-call 超时**下限**（也是强网下的实际预算）。
     *
     * 取 8s 的依据：一次小 API 调用即使服务端处理偏慢（几百 ms~数秒），
     * 8s 也足够；再长只会让「服务端已经挂了」的请求白占泳道。
     * 属于**经验值**，需用 [NetMetrics] 的 P99 分布回调。
     */
    val baseCallTimeoutMillis: Long = 8_000,

    /**
     * per-call 超时**上限**。自适应估算再大也不越过它。
     * 与 [com.interview.thread.ThreadPools] 的 net 泳道直接相关：
     * 泳道 core=4，一个 Call 占线程多久就少一条通道多久。
     * 60s 是「宁可失败也别把泳道焊死」的上限，**经验值**。
     */
    val maxCallTimeoutMillis: Long = 60_000,

    val connectTimeoutMillis: Long = 10_000,
    val readTimeoutMillis: Long = 20_000,
    val writeTimeoutMillis: Long = 20_000,

    // ── 重试 ──
    /** 总尝试次数（含首次）。1~3 是移动端常见区间，再多收益锐减且拖泳道。 */
    val maxAttempts: Int = 3,

    /** 指数退避基数：300ms → 600ms → 1200ms（再乘抖动）。 */
    val retryBaseDelayMillis: Long = 300,

    // ── 连接池 ──
    /**
     * 空闲连接上限与保活时长。
     * 保活 5 分钟对应文章里的 **NAT 超时**问题：移动网络 NAT 通常
     * 30s~5min 回收空闲映射，保活过长连接其实已失效（表现为复用连接后
     * 首次请求超时，比新建连接还慢）。5min 内复用有效，更久交给 OkHttp
     * 连接池的健康检查剔除。
     */
    val maxIdleConnections: Int = 5,
    val keepAliveMinutes: Long = 5,

    // ── Dispatcher ──
    val maxRequests: Int = 64,
    /**
     * 单 host 并发。**默认 5 是 OkHttp 的约定，不是移动端推导**。
     * 保持 5 不放大：放大单 host 并发会同时抬高服务端风控概率与
     * 弱网下的连接争抢（每连接独立的拥塞窗口互相挤压）。
     */
    val maxRequestsPerHost: Int = 5,

    /**
     * 是否启用「自适应超时」（用实时 RTT 抬高 call 超时）。
     * 大文件下载档建议关闭 —— 它的瓶颈是体积不是 RTT。
     */
    val adaptiveTimeout: Boolean = true,
) {
    companion object {
        /** API 档：RTT 受限的小请求，超时自适应、允许重试。 */
        val DEFAULT = NetConfig()

        /**
         * 下载档：带宽受限的大传输。
         *   · 超时给足（base 60s / 上限 180s）：弱网下一张 3MB 原图可能要几十秒，
         *     这不是「慢」，是物理带宽决定的正常时间，不该被超时判死。
         *   · maxAttempts=1：从头重下比等它传完更浪费流量（见类注释）。
         *   · 关闭自适应：RTT 与传输耗时无关。
         */
        val DOWNLOAD = NetConfig(
            baseCallTimeoutMillis = 60_000,
            maxCallTimeoutMillis = 180_000,
            readTimeoutMillis = 30_000,
            writeTimeoutMillis = 30_000,
            maxAttempts = 1,
            maxIdleConnections = 5,
            adaptiveTimeout = false,
        )

        /**
         * 弱网实验档：更短超时、更多重试，用于实验页做**对照**。
         * 不要设成全局默认 —— 它的价值在于「和默认档对比出差异」。
         */
        val WEAK_NETWORK_EXPERIMENT = NetConfig(
            baseCallTimeoutMillis = 5_000,
            maxCallTimeoutMillis = 20_000,
            maxAttempts = 3,
            retryBaseDelayMillis = 200,
        )
    }
}
