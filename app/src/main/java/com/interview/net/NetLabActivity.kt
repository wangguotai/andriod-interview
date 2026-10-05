package com.interview.net

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.interview.thread.ThreadPools
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 网络层优化实验页 —— 把「感知 / 度量 / DNS / 重试」四层逐一亮出来。
 *
 * ─── 这一页和线程治理页的分工 ───
 *
 * 线程治理页证明的是「谁在跑」；本页证明的是「跑得怎么样」。
 * 两者共用同一套泳道：本页所有请求都提交到 [ThreadPools.network]，
 * 绝不在 Activity 里 new Thread —— 否则实验数据会绕过泳道治理，失去可比性。
 *
 * ─── 操作顺序（照着点就能看到完整证据链）───
 *
 *   1 网络质量 → 2 DNS 状态 → 3 单次 API → 5 指标分位 → 6 逐条记录
 *   4 并发 10 次：观察连接复用率从 0% 升起来（第二次后就都是复用）
 *   7 对照：系统 DNS vs 自定义 DNS 的解析耗时与地址差异
 *
 * 目标接口用 GitHub API（见 [com.interview.retrofit.GITHUB_API]），
 * 与仓库既有 Retrofit 示例同源，便于对照。
 */
class NetLabActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private val log = StringBuilder()
    private val running = AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_net_lab)
        output = findViewById(R.id.tv_output)

        bind(R.id.btn_quality) { emitQuality() }
        bind(R.id.btn_dns) { emit("【2. DNS 状态】\n${NetClient.dnsDebugState()}") }
        bind(R.id.btn_single) { emit("【3. 单次 API】已提交，结果稍后出现（看 logcat: NetLab）") }
        bind(R.id.btn_burst) { runBurst() }
        bind(R.id.btn_summary) { emitSummary() }
        bind(R.id.btn_records) { emitRecords() }
        bind(R.id.btn_raw_dns) { runDnsComparison() }
        bind(R.id.btn_clear) {
            log.setLength(0)
            NetMetrics.reset()
            emit("已清空输出与指标窗口")
        }
    }

    // ─────────────────────────────────────────
    // 1. 网络质量
    // ─────────────────────────────────────────

    private fun emitQuality() {
        val q = NetClient.networkQuality()
        emit(
            """
            【1. 网络质量感知】
            level        = ${q.level}（score=${q.score}）
            transport    = ${q.transport}  metered=${q.metered}
            smoothRtt    = ${if (q.smoothRttMillis < 0) "无样本" else "${q.smoothRttMillis}ms"}
            successRate  = ${if (q.successRate < 0) "无样本" else "%.0f%%".format(q.successRate * 100)}
            samples      = ${q.sampleCount}

            判定细节：${q.detail}

            说明：level 是策略层唯一判据。真正请求几次后，动态 RTT 会覆盖
            「样本不足」的保守判定 —— 这就是「感知」比「网络类型」可信的地方。
            """.trimIndent()
        )
    }

    // ─────────────────────────────────────────
    // 3. 单次 API（快速请求）
    // ─────────────────────────────────────────

    /**
     * 提交一次快速 API 调用。返回是否被泳道接受（背压语义，与 ImageDownloader 一致）。
     */
    private fun submitApiCall() {
        val accepted = ThreadPools.network.execute(CALLER) {
            runCatching { executeOnce("https://api.github.com/zen") }
                .onFailure { Log.w(TAG, "请求失败：${it.message}") }
        }
        if (!accepted) {
            emit("⚠ 网络泳道拒绝（配额/队列满）—— 这是背压，不是 bug")
        }
    }

    /** 同步执行一次 GET，只读状态码，body 由 EventListener 统计字节数。 */
    @Throws(IOException::class)
    private fun executeOnce(url: String, client: OkHttpClient = NetClient.shared): Int {
        val request = Request.Builder().url(url).header("User-Agent", "interview-netlab").build()
        client.newCall(request).execute().use { response ->
            return response.code
        }
    }

    // ─────────────────────────────────────────
    // 4. 并发 10 次（观察连接复用）
    // ─────────────────────────────────────────

    /**
     * 并发提交 10 次请求。
     *
     * 预期现象（可作为「连接复用」这一指标的现场证据）：
     *   第一次：DNS/TCP/TLS 都有耗时，reused=false；
     *   之后：DNS/建连为 -1，reused=true —— 用 EventListener 精确捕获。
     */
    private fun runBurst() {
        val n = 10
        val accepted = AtomicInteger(0)
        repeat(n) {
            val ok = ThreadPools.network.execute(CALLER) {
                running.incrementAndGet()
                runCatching { executeOnce("https://api.github.com/zen") }
                running.decrementAndGet()
            }
            if (ok) accepted.incrementAndGet() else Log.w(TAG, "burst 第 $it 次被泳道拒绝")
        }
        emit(
            "【4. 并发 $n 次】泳道接受 ${accepted.get()}/$n，其余被背压拒绝（正常）。\n" +
                "等待 ~2s 后点「5.指标分位」看连接复用率。"
        )
    }

    // ─────────────────────────────────────────
    // 5. 分位数摘要
    // ─────────────────────────────────────────

    private fun emitSummary() {
        val s = NetMetrics.summary()
        if (s.total == 0) {
            emit("【5. 指标】窗口内无样本，先点 3/4 发几次请求。")
            return
        }
        emit(
            """
            【5. 指标分位数】（窗口 ${NetMetrics.WINDOW} 条，累计 ${NetMetrics.totalRecorded()} 条）
            total=${s.total}  ok=${s.okCount}  err=${s.errCount}  success=${"%.0f%%".format(s.successRate * 100)}

            ── 总耗时（看清尾部）──
            P50 = ${s.p50TotalMillis}ms
            P90 = ${s.p90TotalMillis}ms
            P99 = ${s.p99TotalMillis}ms

            ── 各阶段均值（-1 表示该阶段被复用跳过）──
            DNS    = ${s.avgDnsMillis}ms
            建连   = ${s.avgConnectMillis}ms
            TLS    = ${s.avgTlsMillis}ms
            首包P90 = ${s.p90FirstByteMillis}ms

            ── 复用率 ──
            连接复用 = ${"%.0f%%".format(s.connectionReuseRate * 100)}
            缓存命中 = ${"%.0f%%".format(s.cacheHitRate * 100)}

            说明：P50 好看不代表没问题，弱网问题一定先出现在 P90/P99，
            所以才必须分开统计而不是只看平均。
            """.trimIndent()
        )
    }

    // ─────────────────────────────────────────
    // 6. 逐条记录
    // ─────────────────────────────────────────

    private fun emitRecords() {
        val records = NetMetrics.recent(15)
        if (records.isEmpty()) {
            emit("【6. 逐条记录】暂无。")
            return
        }
        val sb = StringBuilder("【6. 最近 ${records.size} 条（新→旧）】\n")
        records.forEach { r ->
            val stage = r.stage
            sb.append(
                "%s %-4s %s %s reused=%s cache=%s\n".format(
                    if (r.ok) "✅" else "❌",
                    r.code.let { if (it < 0) "ERR" else "$it" },
                    r.protocol,
                    "${stage.totalMillis}ms",
                    if (r.reusedConnection) "Y" else "N",
                    if (r.fromCache) "Y" else "N",
                )
            )
            sb.append(
                "     dns=${stage.dnsMillis} tcp=${stage.connectMillis} tls=${stage.tlsMillis} " +
                    "ttfb=${stage.firstByteMillis} bytes=${r.responseBytes}\n"
            )
            r.errorMessage?.let { sb.append("     ⚠ $it\n") }
        }
        emit(sb.toString())
    }

    // ─────────────────────────────────────────
    // 7. 对照：系统 DNS vs 自定义 DNS
    // ─────────────────────────────────────────

    /**
     * 旁路对照实验 —— 本页唯一**刻意绕开** NetClient.shared 的地方。
     *
     * 为什么这里可以自建 client：实验需要「不装自定义 DNS」的对照组，
     * 而共享 client 的 DNS 是固定的。自建的成本是**另起一份连接池**，
     * 这恰好说明了「收口」的价值 —— 每多一个自建 client，
     * 就多一份不共享的连接池与 DNS 缓存。
     *
     * 对照内容：
     *   · 两者解析出的 IP 列表是否一致（调度差异）
     *   · 两者耗时（自定义 DNS 未预热会慢一次；系统 DNS 有全局缓存）
     */
    @SuppressLint("DefaultLocale")
    private fun runDnsComparison() {
        emit("【7. DNS 对照】解析中（每个 client 各发一次，取消引用式自建仅用于实验）…")
        val host = "api.github.com"

        ThreadPools.background.execute(CALLER) {
            // A. 系统 DNS（裸 InetAddress）—— 冷缓存
            val sysStart = System.nanoTime()
            val sysAddrs = runCatching { InetAddress.getAllByName(host).toList() }
                .getOrDefault(emptyList())
            val sysMs = (System.nanoTime() - sysStart) / 1_000_000

            // B. 自定义 DNS（走 InterviewDns，含多 IP 与熔断）
            val customStart = System.nanoTime()
            val customAddrs = runCatching { NetClient.dns.lookup(host) }
                .getOrDefault(emptyList())
            val customMs = (System.nanoTime() - customStart) / 1_000_000

            // C. 再查一次自定义：验证 TTL 缓存生效（应接近 0ms）
            val warmStart = System.nanoTime()
            runCatching { NetClient.dns.lookup(host) }
            val warmMs = (System.nanoTime() - warmStart) / 1_000_000

            emit(
                """
                【7. DNS 对照】host=$host
                系统 DNS    : ${sysMs}ms -> ${sysAddrs.joinToString { it.hostAddress ?: "?" }}
                自定义 DNS  : ${customMs}ms -> ${customAddrs.joinToString { it.hostAddress ?: "?" }}
                自定义(热)  : ${warmMs}ms  ← TTL 缓存命中，弱网下省的就是这一块

                注意：两边 IP 不一致是**正常的**，不代表谁错 ——
                递归解析器/调度不同，本就可能返回不同就近节点。
                自定义 DNS 的价值在于：多 IP（供 Happy Eyeballs 优选）、
                真 TTL 缓存、坏 IP 熔断；而不是「比系统快」。
                （防劫持需要 DoH/DoT，本实现不含 —— 见 InterviewDns 注释）
                """.trimIndent()
            )
        }
    }

    // ─────────────────────────────────────────

    private fun bind(id: Int, action: () -> Unit) {
        findViewById<Button>(id).setOnClickListener {
            runCatching { action() }.onFailure { emit("执行失败：${it.message}") }
        }
    }

    private fun emit(text: String) {
        runOnUiThread {
            log.append(text).append("\n\n")
            output.text = log.toString()
        }
    }

    companion object {
        private const val TAG = "NetLab"

        /** 调用方标识：与图片模块区分，便于泳道配额归因。 */
        private const val CALLER = "interview.netlab"
    }
}
