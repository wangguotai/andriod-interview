package com.interview.net

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 第 1 步「度量层」—— 把一次请求拆成可归因的阶段耗时，并输出分位数。
 *
 * ─── 为什么「没有度量就没有优化」在这里是硬约束 ───
 *
 * 文章里那句「弱网问题藏在尾部」不是修辞。真实分布长这样：
 *
 *   P50 = 80ms（看着很好）  P90 = 200ms   P99 = 3000ms（弱网用户的真实体验）
 *
 * 只报均值/成功率的监控会让整个弱网优化无从下手 —— 你甚至不知道
 * 3s 花在 DNS、还是在 TLS、还是在等首包。所以本类做两件事：
 *   1. **分阶段**：一次请求拆成 DNS / 建连 / TLS / 首包 / 总耗时，
 *      定位到底哪一段在弱网下恶化。
 *   2. **分位数**：P50/P90/P99，让尾部问题可见。
 *
 * ─── 与 NetworkQuality 的分工 ───
 *
 * 本类是「客观事实」（发生了什么）；[NetworkQuality] 是「主观判断」
 * （现在适合用什么策略）。方向是**单向**的：
 *
 *   NetMetrics.record() ──回喂──▶ NetworkQuality.recordRequest()
 *
 * 不允许反向依赖 —— 否则策略层与观测层纠缠，两边都没法单独测试。
 *
 * ⚠️ 内存纪律：只保留最近 [WINDOW] 条。这份数据是「近期体检」不是
 * 「永久账本」；真要长期留存应落盘/上报（本 demo 不做，见注释）。
 * 无锁化用 synchronized 而非 ConcurrentHashMap：写入频率=请求频率（很低），
 * 换算分位需要整窗快照，简单正确优先。
 */
object NetMetrics {

    /** 保留窗口大小。200 条足以覆盖一次典型弱网抖动，且分位计算开销可忽略。 */
    const val WINDOW = 200

    /**
     * 一次请求的阶段耗时（毫秒）。任一阶段未发生则为 -1
     * （如连接复用则无 DNS/TCP/TLS；缓存命中则全部为 -1）。
     */
    data class Stage(
        val dnsMillis: Long,
        val connectMillis: Long,
        val tlsMillis: Long,
        val firstByteMillis: Long,
        val totalMillis: Long,
    ) {
        companion object {
            val EMPTY = Stage(-1, -1, -1, -1, -1)
        }
    }

    data class Record(
        val host: String,
        val method: String,
        /** HTTP 状态码；-1 表示未拿到响应（IO 异常） */
        val code: Int,
        /** 业务判定：拿到 2xx 且未抛异常 */
        val ok: Boolean,
        val protocol: String,
        val reusedConnection: Boolean,
        val fromCache: Boolean,
        val responseBytes: Long,
        val stage: Stage,
        /** 失败原因，仅在 ok=false 时有值 */
        val errorMessage: String?,
        /**
         * 是否为**模拟注入**的样本（默认 false = 真实请求）。
         *
         * 存在的唯一理由：弱网模拟器造的数据必须能与真实流量区分开，
         * 否则仪表盘会把「人造的漂亮曲线」当成真实链路呈现 —— 那是欺骗。
         * UI 据此用虚线/浅色区分，统计上也便于「只看真实样本」。
         */
        val simulated: Boolean = false,
    )

    /** 分位数摘要（快照，供 UI/上报读取）。 */
    data class Summary(
        val total: Int,
        val okCount: Int,
        val errCount: Int,
        val p50TotalMillis: Long,
        val p90TotalMillis: Long,
        val p99TotalMillis: Long,
        val p90FirstByteMillis: Long,
        val avgDnsMillis: Long,
        val avgConnectMillis: Long,
        val avgTlsMillis: Long,
        val connectionReuseRate: Double,
        val cacheHitRate: Double,
    ) {
        val successRate: Double get() = if (total == 0) -1.0 else okCount.toDouble() / total
    }

    private val records = ArrayDeque<Record>(WINDOW)
    private val totalRecorded = AtomicLong(0)

    /**
     * 记录变更订阅者（仪表盘用）。
     *
     * ⚠️ 回调发生在**写入线程**（真实路径是 OkHttp 的回调线程，模拟路径是 UI 线程）。
     * 订阅者必须自己切回主线程，本类不替它做 —— 度量层不该依赖 Android Looper。
     * 用 CopyOnWrite 是因为「读多写极少」：注册通常一次，通知在每次请求后。
     */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** 记录一次请求。**由 [NetEventListener] 在 callEnd 时调用**，业务代码不要手动调。 */
    fun record(record: Record) {
        synchronized(records) {
            if (records.size >= WINDOW) records.removeFirst()
            records.addLast(record)
        }
        totalRecorded.incrementAndGet()

        // 单向回喂感知层：真实 RTT/成败比系统类型更可信。
        // ⚠️ 模拟样本**不**回喂 —— 它不该污染真实的网络质量判定
        // （否则「点了几次模拟弱网」会让策略层以为真的弱网了）。
        if (!record.simulated) {
            NetworkQuality.recordRequest(record.stage.totalMillis, record.ok)
        }

        listeners.forEach { runCatching { it() } }
    }

    /** 生成分位数摘要。纯读，可在主线程调用（整窗 ≤200 条）。 */
    fun summary(): Summary = synchronized(records) { summarize(records.toList()) }

    /**
     * 对**给定的一组记录**生成摘要（纯函数）。
     *
     * 为什么要把 [summary] 拆出这一层：仪表盘的「只看真实样本」开关必须让
     * 分位线也同步只算真实样本 —— 否则会出现「曲线是真实的、P99 却含模拟数据」
     * 这种最难发现的口径不一致。口径只应有一处实现，这里就是那一处。
     */
    fun summarize(snapshot: List<Record>): Summary {
        if (snapshot.isEmpty()) {
            return Summary(0, 0, 0, -1, -1, -1, -1, -1, -1, -1, -1.0, -1.0)
        }
        val totals = snapshot.map { it.stage.totalMillis }.filter { it >= 0 }.sorted()
        val ttfb = snapshot.map { it.stage.firstByteMillis }.filter { it >= 0 }.sorted()
        val okCount = snapshot.count { it.ok }
        return Summary(
            total = snapshot.size,
            okCount = okCount,
            errCount = snapshot.size - okCount,
            p50TotalMillis = percentile(totals, 50),
            p90TotalMillis = percentile(totals, 90),
            p99TotalMillis = percentile(totals, 99),
            p90FirstByteMillis = percentile(ttfb, 90),
            avgDnsMillis = avgOf(snapshot.map { it.stage.dnsMillis }),
            avgConnectMillis = avgOf(snapshot.map { it.stage.connectMillis }),
            avgTlsMillis = avgOf(snapshot.map { it.stage.tlsMillis }),
            connectionReuseRate = if (snapshot.isEmpty()) -1.0
            else snapshot.count { it.reusedConnection }.toDouble() / snapshot.size,
            cacheHitRate = if (snapshot.isEmpty()) -1.0
            else snapshot.count { it.fromCache }.toDouble() / snapshot.size,
        )
    }

    /** 累计记录数（不受窗口限制），用于判断「样本够不够」而非分位数。 */
    fun totalRecorded(): Long = totalRecorded.get()

    fun reset() {
        synchronized(records) { records.clear() }
        totalRecorded.set(0)
        listeners.forEach { runCatching { it() } }
    }

    /**
     * 最近 N 条记录（新→旧），供实验页展示「逐条证据」。
     * 这比只有汇总数字更重要 —— 教学场景要能看到**每一个失败长什么样**。
     */
    fun recent(limit: Int = 20): List<Record> =
        synchronized(records) { records.toList().asReversed().take(limit) }

    // ─────────────────────────────────────────
    // 纯函数（可单测）
    // ─────────────────────────────────────────

    /**
     * 最近邻法取分位（不插值），1-based nearest-rank：
     *   rank = ceil(N × p / 100)，index = rank - 1
     *
     * 选它的原因：请求耗时分布长尾很重，插值会凭空造出「不存在的耗时」，
     * 而观测数据里每个数都对应一次真实请求，取真实值更诚实。
     *
     * ⚠️ 别用 0-based 的 `N*p/100`：它在 N=10、p=50 时会返回第 6 个值，
     * 让分位数系统性偏高一位。这类 off-by-one 不会报错，只会让监控悄悄骗人。
     */
    internal fun percentile(sorted: List<Long>, p: Int): Long {
        if (sorted.isEmpty()) return -1
        val rank = (sorted.size * p + 99) / 100        // ceil(N*p/100)
        return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
    }

    /** 只统计 >=0 的样本；无样本返回 -1（而不是 0，避免「0ms 飞快」的误读）。 */
    internal fun avgOf(values: List<Long>): Long {
        val valid = values.filter { it >= 0 }
        if (valid.isEmpty()) return -1
        return valid.sum() / valid.size
    }
}
