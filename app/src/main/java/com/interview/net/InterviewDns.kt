package com.interview.net

import android.util.Log
import okhttp3.Dns
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 第 2 步「DNS 层」—— 缓存 + 多 IP 排序 + 坏 IP 熔断。
 *
 * ─── 这一层解决文章里的哪三件事 ───
 *
 *   1. DNS 解析慢 / 超时率高   → **TTL 缓存**（[resultCache]），命中即 0ms
 *   2. 调度不精准 / 单点故障   → **多 IP 返回 + 优选**（[orderByHealth]）
 *   3. 坏 IP（线路黑洞）反复命中 → **坏 IP 熔断**（[blacklist]）
 *
 * ─── 一个必须讲清楚的边界：本类不是 HTTPDNS ───
 *
 * 真实的 HTTPDNS（腾讯/阿里/自建）有两个本类**没有**的能力：
 *   · 走 HTTPS 向 HTTPDNS 服务请求解析 → 防 LocalDNS 劫持、防投毒
 *   · 服务端按用户 IP/运营商返回**就近**节点 → 精准调度
 *
 * 本类用的是「指定上游 DNS 服务器 + UDP 明文」（见 [DnsResolver] 的取舍说明），
 * 能拿到多 IP 与 TTL，但**不具备防劫持能力**。要做 DoH 需要 TLS + HTTP，
 * 那是另一个模块的量级。这里如实标注，不把 UDP 版包装成 HTTPDNS。
 *
 * ─── 为什么必须继承 OkHttp 的 Dns 接口 ───
 *
 * OkHttp 的一切（连接池、Happy Eyeballs、重试）都建立在 `Dns.lookup` 之上。
 * 在这一层做优化，才可能让**建连阶段**真正受益；在 Interceptor 里改 DNS
 * 是无效的——那时连接已经建好了。
 */
class InterviewDns(
    private val servers: List<java.net.InetSocketAddress>,
    /**
     * 是否允许在自定义解析失败时回退系统解析。
     * 默认 true：DNS 优化永远不该成为「把 App 搞挂」的单点。
     */
    private val fallbackToSystem: Boolean = true,
) : Dns {

    private val resolver = DnsResolver(servers)

    /** host(lowercase) -> 解析结果。 */
    private val resultCache = ConcurrentHashMap<String, CachedResult>()

    /**
     * 坏 IP 熔断表：addr.hostAddress -> 熔断到期时间戳（SystemClock.elapsedRealtime，0 表示未熔断）。
     *
     * 为什么需要它：DNS 返回的 IP 里**混着坏 IP**（黑洞路由/机房故障）是常态。
     * 若不加熔断，每次连接都会先试那个坏 IP，用户要为「一条线路的故障」
     * 付出整个连接超时（弱网下轻松 10s）。熔断把它降级成「一次失败后短期不再选」。
     */
    private val blacklist = ConcurrentHashMap<String, Long>()

    private data class CachedResult(
        val addresses: List<InetAddress>,
        val expiresAtMillis: Long,
    )

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.lowercase()

        // 0) 字面量 IP 直通：无需解析，也不该进缓存/熔断。
        //    很多 client 会先查一次「主机是不是 IP」，走系统路径时这一步开销极小；
        //    自定义路径若不显式处理，会把 "1.2.3.4" 拼成一个畸形 DNS 查询发出去。
        if (isIpLiteral(hostname)) {
            runCatching { return listOf(InetAddress.getByName(hostname)) }
        }

        // 1) 缓存命中（含 TTL）——命中时 DNS 耗时接近 0，这是弱网最直接收益。
        resultCache[host]?.let { cached ->
            if (now() < cached.expiresAtMillis) {
                return orderByHealth(cached.addresses)
            }
            resultCache.remove(host)
        }

        // 2) 真实解析：A + AAAA 并发拿，各自失败互不影响。
        val resolved = resolveAll(host)
        if (resolved.isEmpty()) {
            if (!fallbackToSystem) {
                throw UnknownHostException("自定义 DNS 无结果且禁止回退: $host")
            }
            Log.w(TAG, "自定义 DNS 无结果，回退系统解析: $host")
            // 系统解析兜底。它没有 TTL 缓存语义，所以给它一个**保守短缓存**，
            // 避免弱网下每次请求都重新走一遍系统 DNS。
            val system = InetAddress.getAllByName(hostname).toList()
            cacheResult(host, system, SYSTEM_FALLBACK_TTL_MILLIS)
            return orderByHealth(system)
        }

        cacheResult(host, resolved, DEFAULT_TTL_MILLIS)
        return orderByHealth(resolved)
    }

    private fun resolveAll(host: String): List<InetAddress> {
        val out = LinkedHashSet<InetAddress>()
        // IPv4 优先入列：多数服务端 v4 优化更成熟，且 v4 地址更稳定；
        // v6 作为备选排在后面，Happy Eyeballs 会在 v4 慢时自动去试。
        runCatching { resolver.resolve(host, DnsResolver.TYPE_A) }
            .onSuccess { out.addAll(it) }
        runCatching { resolver.resolve(host, DnsResolver.TYPE_AAAA) }
            .onSuccess { out.addAll(it) }
        return out.toList()
    }

    /**
     * 按健康度排序：未熔断的在前，熔断的沉底。
     *
     * ⚠️ 这里只做**排序**，不做过滤。原因：
     * OkHttp 按返回顺序依次尝试，把坏 IP 放最后已能避开绝大多数损失；
     * 而如果**全部** IP 都在熔断期就把列表清空，会让请求直接失败 ——
     * 熔断的目的是「少走弯路」，不是「拒绝服务」。这个边界很容易写反。
     */
    private fun orderByHealth(addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.size <= 1) return addresses
        val t = now()
        return addresses.sortedBy { addr -> if (isBlacklisted(addr, t)) 1 else 0 }
    }

    private fun isBlacklisted(addr: InetAddress, nowMillis: Long): Boolean {
        val until = blacklist[addr.hostAddress ?: return false] ?: return false
        return until > nowMillis
    }

    /**
     * 由上层在「某 IP 建连失败」时回报。
     *
     * 为什么要外部回报：Dns 只负责「给地址」，它**看不到**后续哪次连接成功了、
     * 哪个 IP 失败了。健康信息只有从建连侧才能拿到（见 [NetEventListener] /
     * [AdaptiveRetryInterceptor] 的调用点）。这是一个真实的模块边界，
     * 不能靠 Dns 内部猜。
     */
    fun reportFailure(address: InetAddress) {
        val key = address.hostAddress ?: return
        val penalty = computePenalty(key)
        blacklist[key] = now() + penalty
        Log.w(TAG, "熔断坏 IP $key，${penalty}ms 内不再优选（连续第 ${failureCount(key)} 次）")
    }

    fun reportSuccess(address: InetAddress) {
        val key = address.hostAddress ?: return
        blacklist.remove(key)
        failureCounts.remove(key)
    }

    /**
     * 熔断时长递增：1s → 2s → 4s … 上限 60s。
     * 不让它一次性熔断很久，是因为「坏 IP」可能是**瞬时的**（线路抖动），
     * 熔断过久会让本已恢复的线路长期不可用；递增式给了它快速回归的机会。
     */
    private val failureCounts = ConcurrentHashMap<String, AtomicInteger>()

    private fun computePenalty(key: String): Long {
        val n = failureCount(key).coerceAtMost(6)
        return (1000L shl n).coerceAtMost(MAX_PENALTY_MILLIS)
    }

    private fun failureCount(key: String): Int =
        failureCounts.computeIfAbsent(key) { AtomicInteger(0) }.incrementAndGet()

    private fun cacheResult(host: String, addresses: List<InetAddress>, ttlMillis: Long) {
        if (addresses.isEmpty()) return
        resultCache[host] = CachedResult(addresses, now() + ttlMillis)
    }

    /** 供实验页展示：当前缓存/熔断状态。 */
    fun debugState(): String = buildString {
        appendLine("DNS 缓存 ${resultCache.size} 条：")
        resultCache.forEach { (host, cached) ->
            val ttl = (cached.expiresAtMillis - now()).coerceAtLeast(0)
            appendLine("  $host -> ${cached.addresses.joinToString { it.hostAddress ?: "?" }} (剩余 ${ttl}ms)")
        }
        val nowMs = now()
        val active = blacklist.filterValues { it > nowMs }
        appendLine("熔断 IP ${active.size} 个：" + active.entries.joinToString { "${it.key}(${it.value - nowMs}ms)" })
    }

    private fun now(): Long = android.os.SystemClock.elapsedRealtime()

    internal companion object {
        private const val TAG = "InterviewDns"

        /** IPv4 点分十进制 / IPv6（含冒号）。 */
        internal fun isIpLiteral(host: String): Boolean {
            if (host.contains(':')) return true
            val parts = host.split('.')
            return parts.size == 4 && parts.all { p ->
                p.toIntOrNull()?.let { it in 0..255 } == true
            }
        }

        /**
         * 默认 TTL。
         * ⚠️ 这是**刻意的策略值**，不是对服务端 TTL 的忠实翻译。
         * 真实的递归解析器返回的 TTL 常常是 30~300s，但移动端要考虑：
         *   - 短 TTL：调度变化响应快（CDN 切节点、故障摘除），但 DNS 查询次数多
         *   - 长 TTL：省电省流量，但故障/切流后长期指向旧 IP
         * 取 60s 是移动端的常见折中（微信等客户端也在分钟级）。
         * 本类的 [DnsResolver] 目前不解析服务端 TTL，所以统一用这个值；
         * 若要精确，应在 parseAnswers 里把 TTL 一并返回。
         */
        private const val DEFAULT_TTL_MILLIS = 60_000L

        /** 系统兜底结果的缓存：更短，因为系统 DNS 本身可能就是错的（被劫持）。 */
        private const val SYSTEM_FALLBACK_TTL_MILLIS = 10_000L

        private const val MAX_PENALTY_MILLIS = 60_000L
    }
}
