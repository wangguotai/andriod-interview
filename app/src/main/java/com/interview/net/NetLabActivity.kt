package com.interview.net

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.interview.net.nativebridge.NetLabBridge
import com.interview.thread.ThreadPools
import okhttp3.CertificatePinner
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
        bind(R.id.btn_rust_route) { emitRustRoute() }
        bind(R.id.btn_rust_fetch) { runRustH3Fetch() }
        bind(R.id.btn_rust_interceptor) { runInterceptorSeam() }
        bind(R.id.btn_rust_pin) { runPinDemo() }
        bind(R.id.btn_rust_cancel_fallback) { runCancelAndFallback() }
        bind(R.id.btn_rust_ab) { runH3VsH2() }
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
    // 8. Rust HTTP/3：路由判定
    // ─────────────────────────────────────────

    /**
     * 展示「这次请求会不会走 Rust」以及**为什么**。
     *
     * 这是把路由判据从 bool 升级成 [RouteReason] 的收益所在：排查「为什么没生效」时，
     * 「host 不在白名单」和「网络档位不够」是完全不同的两件事，bool 分不出来。
     */
    private fun emitRustRoute() {
        val url = DEMO_URL
        emit(
            """
            【8. Rust HTTP/3 路由判定】
            native 可用 = ${NetLabBridge.available}（${NetLabBridge.describe()}）
            fetch 可用  = ${NetLabBridge.fetchAvailable}

            对 $url 的判定：
              ${NetClient.explainRoute(url)}

            说明：生产默认 [RustTransportConfig.enabled=false]（装上代码 ≠ 开始接管流量）。
            [NetClient.explainRoute] 用受控配置演示判定链：主线程/明文/代理 → 方法/body → 白名单/档位。
            真正的「走 Rust」还需 enabled=true 且档位命中（默认仅 WEAK）。
            """.trimIndent()
        )
    }

    // ─────────────────────────────────────────
    // 9. Rust HTTP/3：真实请求
    // ─────────────────────────────────────────

    /**
     * 用 Rust QUIC 栈发一次真实 HTTP/3 请求。
     *
     * ⚠️ 关键：整个调用提交到 [ThreadPools.network] —— `fetch` 是**阻塞式** JNI
     * 调用，且 IO 在**调用线程**上完成（current-thread runtime + block_on）。
     * 放主线程 = ANR；放自建线程 = 绕开泳道治理。两者都必须避免。
     */
    private fun runRustH3Fetch() {
        if (!NetLabBridge.fetchAvailable) {
            emit("【9. Rust HTTP/3】native 不可用：${NetLabBridge.describe()}\n（x86 模拟器上无 arm64 .so 属预期，回退 OkHttp 即可）")
            return
        }
        emit("【9. Rust HTTP/3】已提交到 net 泳道，结果稍后出现…")
        val accepted = ThreadPools.network.execute(CALLER) {
            val url = DEMO_URL
            val t0 = System.nanoTime()
            val result = NetLabBridge.fetch(
                url = url,
                method = "GET",
                headers = listOf("User-Agent" to "interview-netlab-h3"),
                body = null,
                timeoutMillis = 20_000,
            )
            val wallMs = (System.nanoTime() - t0) / 1_000_000
            emit(when (result) {
                is NetLabBridge.FetchResult.Success -> {
                    val r = result.response
                    // 回灌度量：与 OkHttp 路径同口径（不这么做，两条路径无法比较）。
                    recordRustResponse(DEMO_URL, "GET", r)
                    """
                    【9. Rust HTTP/3】✅ 成功（低层原生桥直连，绕过拦截器）
                      url           = $url
                      status        = ${r.status}
                      protocol      = ${r.protocol}（Rust 侧上报；这里刻意**不**在 Kotlin 手写协议串）
                      connect_ms    = ${r.connectMillis}（含融合的 TLS 握手）
                      first_byte_ms = ${r.firstByteMillis}
                      total_ms      = ${r.totalMillis}
                      墙钟          = ${wallMs}ms（含跨 FFI 开销）
                      body          = ${r.body.size} B / headers ${r.headers.size} 项
                      叶证书 SPKI   = ${r.peerSpkiSha256?.take(16) ?: "n/a"}…

                    已回灌 NetMetrics（点「6.逐条记录」能看到 protocol=HTTP_3 这条；与「10.拦截器接缝」同口径）。
                    ⚠️ 本按钮调的是**低层桥**，故**不经过** RustTransportInterceptor：
                      无路由判定、无防双记、无回退 —— 它证明的是「传输本身能跑」。
                      证明「接缝真的接管了 OkHttp」请点「10.拦截器接缝」。
                    ⚠️ QUIC 的 connect 段含 TLS，与 TCP 路径的 connect/tls 分段**口径不同**，
                    不要把两者的平均值直接相减得出"谁更快"。
                    """.trimIndent()
                }
                is NetLabBridge.FetchResult.Failure -> {
                    val kind = when {
                        result.isPinMismatch -> "（安全事件：证书固定失败）"
                        result.isCancelled -> "（已取消）"
                        else -> ""
                    }
                    "【9. Rust HTTP/3】❌ 失败$kind\n  code=${result.code}\n  ${result.message}\n\n" +
                        "注意：这里**不自动回退** —— 低层桥没有回退逻辑，实验页要让你看到真实失败。\n" +
                        "回退由 RustTransportInterceptor 负责（见「12.取消+回退」）。"
                }
            })
        }
        if (!accepted) {
            emit("⚠ 网络泳道拒绝（配额/队列满）—— 背压，不是 bug")
        }
    }

    // ─────────────────────────────────────────
    // 10. M2 接缝：证明请求真的经 RustTransportInterceptor 接管
    // ─────────────────────────────────────────

    /**
     * 与「9.Rust HTTP/3」的**本质区别**：9 调低层桥（`NetLabBridge.fetch`），
     * 完全绕过 [RustTransportInterceptor]，证明不了「接缝」。本按钮走的是
     * `NetClient.buildWithRustTransport(...)` 装配出来的 client —— 请求先过
     * [AdaptiveRetryInterceptor]，再被 Rust 接管，最后合成 `Response` 回给调用方。
     *
     * 一次点击同时证明四件事：
     *   ① 接缝真的接管了：响应 `protocol == QUIC`（`chain.proceed()` 出不来这个值）；
     *   ② **防双记护栏**生效：`NetMetrics` 恰好 +1，不是 +2；
     *   ③ 真实字段经 Call-keyed map 交接：记录里 protocol 是 Rust 的 `HTTP_3`、
     *     total_ms 是 Rust 实测值，而不是 OkHttp 侧那份 0；
     *   ④ 路由判定先于传输：输出里能看到它为什么被放行（受控注入 level=WEAK）。
     */
    private fun runInterceptorSeam() {
        if (!NetLabBridge.fetchAvailable) {
            emit("【10. 拦截器接缝】native 不可用：${NetLabBridge.describe()}\n（此演示需要 arm64 .so；x86 模拟器上属预期）")
            return
        }
        emit("【10. 拦截器接缝】已提交到 net 泳道：这是**经拦截器**的一次请求（区别于 9 的低层直连）…")
        val accepted = ThreadPools.network.execute(CALLER) {
            val url = "https://$H3_HOST/"
            val cfg = RustTransportConfig(
                enabled = true,             // 受控实验显式打开（生产默认 false）
                hostAllowlist = setOf(H3_HOST),
                triggerLevels = setOf(NetworkLevel.WEAK),
            )
            val client = NetClient.buildWithRustTransport(
                rustTransport = cfg,
                levelProvider = { NetworkLevel.WEAK }, // 注入档位：真机无法按需变弱网
            )
            emit(
                "【10. 路由判定】${explainRoute(url, config = cfg, level = NetworkLevel.WEAK, hasProxy = false)}\n" +
                    " （配置与这次真实请求**同一份**，不是两套说法）"
            )
            val before = NetMetrics.totalRecorded()
            runCatching {
                val req = Request.Builder().url(url).header("User-Agent", CALLER).build()
                client.newCall(req).execute().use { resp ->
                    val after = NetMetrics.totalRecorded()
                    val dash = NetMetrics.recent(1).firstOrNull()
                    emit(
                        """
                        【10. 拦截器接缝】✅ 请求成功
                          status            = ${resp.code}
                          Response.protocol = ${resp.protocol}  ← 应用拦截器合成（OkHttp 官方 Protocol.QUIC）

                        证据链：
                          ① 接缝接管   ：protocol=${resp.protocol} 只可能来自合成响应
                          ② 防双记护栏 ：NetMetrics ${before} → ${after}（增量 ${after - before}，**必须是 1**）
                          ③ 字段交接   ：最近一条 protocol=${dash?.protocol ?: "?"} total=${dash?.stage?.totalMillis ?: "?"}ms
                             （Rust 实测值经 Call-keyed map 交接，非 OkHttp 侧空值）
                          ④ 路由先判   ：见上方「路由判定」行，与本次请求同一份配置

                        若增量是 2，说明防双记护栏失效 —— 分位数已被污染。
                        """.trimIndent()
                    )
                }
            }.onFailure { e ->
                emit("【10. 拦截器接缝】❌ ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (!accepted) emit("⚠ 网络泳道拒绝（配额/队列满）—— 背压，不是 bug")
    }

    // ─────────────────────────────────────────
    // 11. 证书固定（SPKI pin）——安全红线
    // ─────────────────────────────────────────

    /**
     * 一次跑通三层，缺一层就证明不了「pin 真的生效」：
     *   ① **对拍**：同一张叶证书，OkHttp `CertificatePinner` 与 Rust `spkiSha256Hex`
     *      必须算出同一个指纹；
     *   ② **命中**：正确指纹 + 完整链校验 → 成功；
     *   ③ **拒绝**：错误指纹 → 必须 `SPKI-PIN-MISMATCH`，且**绝不回退**。
     */
    private fun runPinDemo() {
        if (!NetLabBridge.fetchAvailable) {
            emit("【11. 证书固定】native 不可用：${NetLabBridge.describe()}")
            return
        }
        emit("【11. 证书固定】已提交到 net 泳道：①对拍 → ②命中 → ③拒绝…")
        val accepted = ThreadPools.network.execute(CALLER) {
            val url = "https://$H3_HOST/"
            var realPin: ByteArray? = null

            runCatching {
                val leaf = fetchLeafCertDer(H3_HOST) ?: return@runCatching
                val ru = NetLabBridge.spkiSha256Hex(leaf)
                val b64 = "sha256/" + okio.ByteString.of(*leaf).sha256().base64()
                val ok = runCatching {
                    CertificatePinner.Builder().add(H3_HOST, b64).build()
                }.isSuccess
                val okHex = NetLabBridge.decodePinToSpki(b64)?.joinToString("") { "%02x".format(it) }
                realPin = ru?.let { hex -> ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }
                emit(
                    """
                    【11-A. 指纹对拍】host=$H3_HOST
                      Rust spkiSha256Hex = ${ru ?: "n/a"}
                      OkHttp pin 解析后   = ${okHex ?: "n/a"}
                      CertificatePinner  = ${if (ok) "接受该 pin 格式" else "拒绝该 pin 格式"}
                      一致 = ${if (ru != null && ru == okHex) "✅ 同一张证书两侧同指纹" else "❌ 两侧指纹不一致，pin 形同虚设"}
                    """.trimIndent()
                )
            }.onFailure { emit("【11-A. 指纹对拍】跳过：取叶证书失败 ${it.message}") }

            val pin = realPin ?: run {
                emit("【11. 证书固定】❌ 未能取得真实指纹，演示中止（不为演示编造假指纹）")
                return@execute
            }

            runCatching {
                NetClient.buildWithRustTransport(
                    rustTransport = RustTransportConfig(enabled = true, hostAllowlist = setOf(H3_HOST)),
                    levelProvider = { NetworkLevel.WEAK },
                    pinProvider = { listOf(null as String? to pin) },
                ).newCall(Request.Builder().url(url).header("User-Agent", CALLER).build()).execute().use {
                    emit("【11-B. pin 命中】✅ status=${it.code} protocol=${it.protocol}（正确指纹 + 完整链校验通过）")
                }
            }.onFailure { emit("【11-B. pin 命中】❌ 正确指纹却失败：${it.message}") }

            val wrong = ByteArray(32) { 0x11 }
            runCatching {
                NetClient.buildWithRustTransport(
                    rustTransport = RustTransportConfig(
                        enabled = true, hostAllowlist = setOf(H3_HOST), fallbackOnFailure = true,
                    ),
                    levelProvider = { NetworkLevel.WEAK },
                    pinProvider = { listOf(null as String? to wrong) },
                ).newCall(Request.Builder().url(url).header("User-Agent", CALLER).build()).execute()
                emit("【11-C. pin 拒绝】⚠ 错误指纹居然成功了 —— pin 未生效，这是**严重**问题")
            }.onFailure { e ->
                val isPin = e.message?.contains("安全事件") == true
                emit(
                    """
                    【11-C. pin 拒绝】${if (isPin) "✅" else "⚠"} 已拒绝
                      异常 = ${e.javaClass.simpleName}
                      信息 = ${e.message}
                      判据 = ${if (isPin) "识别为安全事件（含“安全事件”字样）" else "未标注为安全事件（应补强）"}

                    关键：这是**安全事件**，回退开关虽为 true 也**绝不回退** ——
                    换个通道再试会把「可能正被中间人」的告警掩盖成一次网络抖动。
                    """.trimIndent()
                )
            }
        }
        if (!accepted) emit("⚠ 网络泳道拒绝（配额/队列满）—— 背压，不是 bug")
    }

    // ─────────────────────────────────────────
    // 12. 取消 + 回退（两条最容易做错的路径）
    // ─────────────────────────────────────────

    /**
     * 路径 A（取消）：请求提交后在飞行中 `Call.cancel()`，验证取消经看门狗桥接到
     *   Rust 标志后，在轮询粒度（50ms）内中止，且取消后**不回退重发**。
     *
     * 路径 B（回退边界）：用「错误 pin」做**故意反例** —— 回退开关打开、方法可回退
     *   （GET），但 pin 事件必须压过回退开关，**绝不回退**。它证明的是规则的**另一半**
     *   （「该回退的回退、不该回退的绝不回退」）。
     *
     * ⚠️ 诚实标注：弱网/服务端抖动的「普通失败即可回退成功」路径在本页难以
     *   稳定复现，故该正例以**单测**为准（RustTransportInterceptorIntegrationTest）：
     *   本页只呈现「取消不回退」与「pin 失败不回退」两条负例。
     */
    private fun runCancelAndFallback() {
        if (!NetLabBridge.fetchAvailable) {
            emit("【12. 取消+回退】native 不可用：${NetLabBridge.describe()}")
            return
        }
        emit("【12. 取消+回退】已提交到 net 泳道：A 取消 → B pin 反例不回退…")
        val accepted = ThreadPools.network.execute(CALLER) {
            val url = "https://$H3_HOST/"

            // ── 路径 A：取消 ──
            runCatching {
                val client = NetClient.buildWithRustTransport(
                    rustTransport = RustTransportConfig(enabled = true, hostAllowlist = setOf(H3_HOST)),
                    levelProvider = { NetworkLevel.WEAK },
                )
                val call = client.newCall(Request.Builder().url(url).header("User-Agent", CALLER).build())
                // 在飞行中取消：由装饰器里的看门狗把 OkHttp 的取消状态
                // 桥接到 Rust 的取消标志，native 侧每 50ms 查一次即中止。
                Thread { Thread.sleep(80); call.cancel() }.start()
                val before = NetMetrics.totalRecorded()
                val outcome = runCatching {
                    call.execute().use { "⚠ 未被取消（返回 status=${it.code}）" }
                }.fold(
                    onSuccess = { it },
                    onFailure = { e -> "✅ 已取消：${e.javaClass.simpleName}: ${e.message}" },
                )
                val after = NetMetrics.totalRecorded()
                emit(
                    """
                    【12-A. 取消】$outcome
                      NetMetrics 增量 = ${after - before}（取消不应产生成功的合成记录）
                      回退？= 否（取消后不回退：用户已放弃，重发违背其意图）
                    说明：取消是**协作式**的 —— 看门狗把 OkHttp 的取消状态桥接到 Rust 标志，
                    native 在阶段边界每 50ms 查一次，故可能在数十毫秒后才停下（非硬中断）。
                    """.trimIndent()
                )
            }.onFailure { emit("【12-A. 取消】无法执行：${it.message}") }

            // ── 路径 B：pin 反例（回退规则的另一半）──
            val wrong = ByteArray(32) { 0x22 }
            runCatching {
                NetClient.buildWithRustTransport(
                    rustTransport = RustTransportConfig(
                        enabled = true, hostAllowlist = setOf(H3_HOST), fallbackOnFailure = true,
                    ),
                    levelProvider = { NetworkLevel.WEAK },
                    pinProvider = { listOf(null as String? to wrong) },
                ).newCall(Request.Builder().url(url).header("User-Agent", CALLER).build()).execute()
                emit("【12-B. pin 反例】⚠ 未按预期失败 —— 回退规则待查")
            }.onFailure { e ->
                emit(
                    """
                    【12-B. pin 反例：为何**不**回退】✅ 已上抛
                      信息 = ${e.message}
                      回退开关 = true，方法 = GET（本可回退），但 pin 事件优先 →
                      **绝不回退**。规则：该回退的回退，安全事件与取消绝不回退。
                    """.trimIndent()
                )
            }
        }
        if (!accepted) emit("⚠ 网络泳道拒绝（配额/队列满）—— 背压，不是 bug")
    }

    // ─────────────────────────────────────────
    // 13. 端到端 A/B：h3（cloudflare-quic.com）vs h2（api.github.com）
    // ─────────────────────────────────────────

    /**
     * 为什么用两个**不同** host，而不是同一 host 两次请求：
     * `cloudflare-quic.com` 有 QUIC 监听，`api.github.com` **没有**（此前验证过：
     * 对它走 h3 会在建连阶段超时）。所以 A/B 只能跨 host 对照，且**据此不得声称
     * "h3 比 h2 快/慢"** —— host、路径、服务端都不同；这里量的是「两条链路都能
     * 端到端跑通，且指标同口径入库」，不是性能结论。
     */
    private fun runH3VsH2() {
        emit("【13. h3 vs h2 对照】已提交到 net 泳道（A: Rust h3 / B: OkHttp h2）…")
        val accepted = ThreadPools.network.execute(CALLER) {
            val h3Line: String
            if (NetLabBridge.fetchAvailable) {
                val t0 = System.nanoTime()
                val res = NetLabBridge.fetch(
                    url = "https://$H3_HOST/",
                    method = "GET",
                    headers = listOf("User-Agent" to "$CALLER-ab"),
                    body = null,
                    timeoutMillis = 20_000,
                )
                val wall = (System.nanoTime() - t0) / 1_000_000
                h3Line = when (res) {
                    is NetLabBridge.FetchResult.Success -> {
                        val r = res.response
                        recordRustResponse("https://$H3_HOST/", "GET", r)
                        "「A」Rust h3  $H3_HOST  status=${r.status} proto=${r.protocol} " +
                            "total=${r.totalMillis}ms bytes=${r.body.size} 墙钟=${wall}ms"
                    }
                    is NetLabBridge.FetchResult.Failure ->
                        "「A」Rust h3  失败 code=${res.code} ${res.message}"
                }
            } else {
                h3Line = "「A」Rust h3  跳过（native 不可用）"
            }

            val bLine = runCatching {
                val t0 = System.nanoTime()
                executeOnce("https://$H2_HOST/zen")
                val wall = (System.nanoTime() - t0) / 1_000_000
                val last = NetMetrics.recent(1).firstOrNull()
                "「B」OkHttp h2 $H2_HOST  proto=${last?.protocol} total=${last?.stage?.totalMillis}ms " +
                    "bytes=${last?.responseBytes} 墙钟=${wall}ms"
            }.fold({ it }, { "「B」OkHttp h2 失败 ${it.message}" })

            emit(
                """
                【13. h3 vs h2 端到端对照】（两 host 不具可比性，见下）
                  $h3Line
                  $bLine

                ⚠️ 这不是性能结论，别当成"谁更快"：
                  ① host 不同（$H3_HOST 有 QUIC，$H2_HOST 无）—— 服务端、路径、调度全不同；
                  ② 口径不同：QUIC 的 connect 段**含融合的 TLS**，而 h2 的 connect/tls 分两段，
                     两个数直接相减会得出错误结论；
                  ③ 目的：证明两条链路都能**端到端跑通**且**度量同口径入库**（看「6.逐条记录」）。
                """.trimIndent()
            )
        }
        if (!accepted) emit("⚠ 网络泳道拒绝（配额/队列满）—— 背压，不是 bug")
    }

    // ─────────────────────────────────────────

    /**
     * 把 Rust 响应按 OkHttp 路径同口径回灌 NetMetrics。
     *
     * 抽成一处（按钮 9 与 13 共用）是刻意的：**同一份口径只应写一遍**，
     * 否则两条路径的记录迟早漂移，分位数就不再可比 —— 那正是这套度量存在的意义。
     */
    private fun recordRustResponse(url: String, method: String, r: NetLabBridge.NativeResponse) {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url
        NetMetrics.record(
            NetMetrics.Record(
                host = host,
                method = method,
                code = r.status,
                ok = r.status in 200..299,
                protocol = r.protocol,
                reusedConnection = r.reusedConnection,
                fromCache = false,
                responseBytes = r.body.size.toLong(),
                stage = NetMetrics.Stage(
                    dnsMillis = -1, // Rust 路径的 DNS 由传入 IP 承担，v1 未单独采集
                    connectMillis = r.connectMillis, // ⚠️ QUIC 含融合 TLS，与 TCP 口径不可直接比
                    tlsMillis = -1,
                    firstByteMillis = r.firstByteMillis,
                    totalMillis = r.totalMillis,
                ),
                errorMessage = null,
            )
        )
    }

    /**
     * 取 host 的叶证书 DER（**仅用于与 OkHttp `CertificatePinner` 对拍指纹**）。
     *
     * 走单独的 SSLSocket 握手；本函数不做任何信任决策 —— 真正的信任判定在 Rust
     * 的 rustls 握手内完成。必须在 net 泳道调用（会阻塞）。
     */
    @SuppressLint("CustomX509TrustManager")
    private fun fetchLeafCertDer(host: String): ByteArray? = runCatching {
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }), java.security.SecureRandom())
        val socket = ctx.socketFactory.createSocket(host, 443) as javax.net.ssl.SSLSocket
        socket.use {
            it.startHandshake()
            it.session.peerCertificates.firstOrNull()?.encoded
        }
    }.getOrNull()

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

        /**
         * 有 QUIC 监听的演示 host（用于 h3 的 9/10/11/12 与 A/B 的 A 侧）。
         *
         * ⚠️ 这里**必须**是确有 h3 监听的 host：`api.github.com` 没有 QUIC 监听，
         * 对它走 h3 会在建连阶段超时。这一点此前实测确认过，不是猜测。
         */
        private const val H3_HOST = "cloudflare-quic.com"

        /** 仅走 OkHttp/h2 的对照 host（A/B 的 B 侧；也是 3/4 的既有目标）。 */
        private const val H2_HOST = "api.github.com"

        /** 9 号按钮（低层直连）的演示目标 —— 必须是有 QUIC 的 host。 */
        private const val DEMO_URL = "https://$H3_HOST/"
        private const val DEMO_HOST = H3_HOST
    }
}
