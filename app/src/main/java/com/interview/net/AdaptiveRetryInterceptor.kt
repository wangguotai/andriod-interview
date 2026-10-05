package com.interview.net

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 第 3 步「策略层」—— 自适应超时 + 幂等重试 + 指数退避抖动。
 *
 * ─── 这一层要纠正的两个常见错误 ───
 *
 * 错误 A：**一刀切超时**。写死 10s 的问题在于——
 *   强网下 10s 早已是「该失败的失败了」，白等；弱网下 10s 又不够，
 *   一次慢请求就把 net 泳道（core=4）的一条通道占满。
 *   正确做法是用**实时 RTT 估算**反推：[adaptiveTimeoutMillis]。
 *
 * 错误 B：**无脑重试**。重试非幂等请求会重复下单/重复扣款；
 *   不加退避的重试在抖动时形成「重试风暴」，把刚恢复的链路再次打垮。
 *   正确做法：[isRetryable] 只放幂等方法，[backoffMillis] 指数退避 + 抖动。
 *
 * ─── 为什么重试要放在 Application Interceptor ───
 *
 * 它能看到**完整的一次 Call**（含建连失败），是重试语义最自然的落点。
 * OkHttp 自带的 RetryAndFollowUpInterceptor 也在这一层，但它只处理
 * 「可恢复的连接问题」，不做退避、不做幂等判断、不含业务 5xx 语义。
 * 本类补上这些，同时把 `retryOnConnectionFailure(true)` 保留给连接级恢复。
 *
 * ⚠️ 与泳道背压的关系：重试会**延长单个任务占用 net 泳道线程的时间**。
 * 若不加总预算，弱网下 3 次重试 × 30s 会把泳道拖满，进而触发
 * [com.interview.thread.ThreadPools] 的配额拒绝。所以本类同时受
 * [maxAttempts] 与「per-call 超时」双重约束，且对退避睡眠也设了上限。
 */
class AdaptiveRetryInterceptor(
    private val config: NetConfig,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // ── 自适应超时 ──
        // 在 Interceptor 里改 Call 的超时是 per-call 的，不影响连接池其他请求。
        // 用 NetMetrics 的实时 RTT（而非系统类型）来定，才可能反映真实弱网。
        // 大文件下载档会关掉它（瓶颈是带宽不是 RTT，见 NetConfig）。
        if (config.adaptiveTimeout) {
            val timeout = adaptiveTimeoutMillis()
            chain.call().timeout().timeout(timeout, java.util.concurrent.TimeUnit.MILLISECONDS)
        }

        if (!isRetryable(request)) {
            return chain.proceed(request)
        }

        var lastResponse: Response? = null
        var lastError: IOException? = null

        for (attempt in 1..config.maxAttempts) {
            // 每次重试前先看是否已被取消：用户离开页面后不该继续耗流量。
            if (chain.call().isCanceled()) {
                throw IOException("Call 已被取消，停止重试")
            }

            try {
                val response = chain.proceed(request)

                // 只有服务端「临时性」错误才重试。4xx 是客户端问题，
                // 重试 100 次也是 400；重试它纯属浪费流量。
                if (!shouldRetryResponse(response)) {
                    return response
                }

                response.close() // 必须关，否则连接无法归还连接池
                lastResponse = response
                Log.w(
                    TAG,
                    "第 $attempt/${config.maxAttempts} 次失败（HTTP ${response.code}）" +
                        "，${if (attempt < config.maxAttempts) "准备重试" else "放弃"}"
                )
            } catch (e: IOException) {
                lastError = e
                Log.w(
                    TAG,
                    "第 $attempt/${config.maxAttempts} 次网络异常：${e.javaClass.simpleName}: ${e.message}" +
                        "，${if (attempt < config.maxAttempts) "准备重试" else "放弃"}"
                )
            }

            if (attempt < config.maxAttempts) {
                sleepQuietly(backoffMillis(attempt))
            }
        }

        // 全部尝试失败：优先把最后一次响应返回给上层（保留状态码语义），
        // 没有响应才抛异常。
        lastResponse?.let { return it }
        throw lastError ?: IOException("重试耗尽且无响应")
    }

    // ─────────────────────────────────────────
    // 自适应超时
    // ─────────────────────────────────────────

    /**
     * 用实时 RTT 反推超时预算：
     *
     *   timeout = max(基础超时, k × 平滑RTT)
     *
     * 为什么要乘系数 k=3：单次 RTT 的中位数不能代表最坏一次。
     * 一段可用链路里，P99 往返通常是中位数的 2~3 倍（排队/重传），
     * 取 3 倍能覆盖多数正常波动，又不至于像「固定 30s」那样永远等下去。
     *
     * ⚠️ 这是**启发式**，不是推导：系数 3 与分档阈值一样属于经验值。
     * 更严谨的做法是用 [NetMetrics] 的 P99 RTT 直接当预算，
     * 但样本少时 P99 噪声大，故此处用「中位数 × 3」这种更稳的估计。
     *
     * 无样本（冷启动）时退回 [NetConfig.baseCallTimeoutMillis]。
     */
    internal fun adaptiveTimeoutMillis(): Long {
        val q = NetworkQuality.currentQuality()
        if (q.smoothRttMillis < 0) return config.baseCallTimeoutMillis
        val estimate = q.smoothRttMillis * RTT_TIMEOUT_FACTOR
        return estimate.coerceIn(
            config.baseCallTimeoutMillis,
            config.maxCallTimeoutMillis,
        )
    }

    // ─────────────────────────────────────────
    // 重试判定
    // ─────────────────────────────────────────

    /**
     * 幂等判定。**这是防止「重试造成数据损坏」的唯一闸门**。
     *
     * 语义来自 HTTP 规范（RFC 7231 §4.2.2），不是拍脑袋：
     *   GET / HEAD / OPTIONS / TRACE  → safe，永远可重试
     *   PUT / DELETE                  → idempotent，可重试
     *   POST / PATCH                  → **非幂等**，默认不重试
     *
     * POST 的例外：调用方显式带上 `Idempotency-Key` 头时视为可重试 ——
     * 这是 Stripe 等支付接口的通行约定，服务端据此去重。
     * 这比「一律不重试 POST」更实用，又比「无条件重试 POST」安全。
     */
    internal fun isRetryable(request: okhttp3.Request): Boolean {
        val method = request.method.uppercase()
        if (method in SAFE_METHODS) return true
        if (method in IDEMPOTENT_METHODS) return true
        return request.header(HEADER_IDEMPOTENCY_KEY) != null
    }

    /** 5xx 里只有「网关/过载类」值得重试；500 是代码 bug，重试无益。 */
    internal fun shouldRetryResponse(response: Response): Boolean =
        response.code in RETRYABLE_CODES

    /**
     * 指数退避 + 全抖动。
     *
     *   base × 2^(attempt-1)  →  300ms, 600ms, 1200ms …
     *   再乘 [0.5, 1.5] 的随机抖动
     *
     * 抖动为什么必要：若大量客户端在同一时刻失败（服务端重启、CDN 抖动），
     * 无抖动的退避会让它们**同步**重试，形成周期性冲击波，被重试的流量
     * 比原故障还大。随机化把冲击摊平成均匀流量。
     *
     * ⚠️ 退避要计入 callTimeout 预算。这里刻意把单次退避上限压到 2s，
     * 避免「退避本身」把 per-call 预算吃光。
     */
    internal fun backoffMillis(attempt: Int): Long {
        val exp = config.retryBaseDelayMillis shl (attempt - 1)
        val capped = exp.coerceAtMost(MAX_BACKOFF_MILLIS)
        val jitter = 0.5 + ThreadLocalRandom.current().nextDouble() // [0.5, 1.5)
        return (capped * jitter).toLong().coerceAtLeast(1L)
    }

    private fun sleepQuietly(millis: Long) {
        runCatching { Thread.sleep(millis) }
            .onFailure { /* 被中断：交给下一轮 isCanceled 判断 */ }
    }

    companion object {
        private const val TAG = "NetRetry"

        /** 平滑 RTT → 超时的系数。见 [adaptiveTimeoutMillis] 的取舍说明。 */
        private const val RTT_TIMEOUT_FACTOR = 3L

        private const val MAX_BACKOFF_MILLIS = 2_000L
        private const val HEADER_IDEMPOTENCY_KEY = "Idempotency-Key"

        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS", "TRACE")
        private val IDEMPOTENT_METHODS = setOf("PUT", "DELETE")

        /** 502/503/504 = 网关类临时故障；408 = 请求超时；429 = 限流（值得退避后重试）。 */
        private val RETRYABLE_CODES = setOf(408, 429, 502, 503, 504)
    }
}
