package com.interview.稳定性监控

import android.os.Build
import android.os.Looper
import android.util.Log
import com.example.myapplication.BuildConfig
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.interview.net.NetClient
import com.interview.net.NetConfig
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Time: 2026/10/16
 * Author: wgt
 * Description: 稳定性监控的**真实 HTTP 上报出口** —— 把 [StabilityReporter] 的批次发到配置的 endpoint
 *
 * ══════════════════════════════════════════════════════════════════════
 * 一、本类存在的理由，以及它必须守住的那条纪律
 * ══════════════════════════════════════════════════════════════════════
 *
 * 在它之前，`StabilityReporter.drainTo(upload)` 的 upload 一直是
 * `StabilityMonitor.ReportDriver.simulatedUpload`：**不联网**，只把批次记进
 * `uploadLog` 再打一行 logcat 就返回 true。于是「三通道都装了」的表象下，
 * 数据其实停在环形缓冲里，**永远到不了平台**（见 StabilityMonitor 的缺口说明）。
 *
 * 本类补上那一步。但补的时候有一条**硬约定**不能破：
 *
 *   ⚠️ **endpoint / token 绝不进源码、绝不进版本库。**
 *
 * 把真实 host/token 写进开仓库的 demo，就是「演示项泄露成生产配置」——
 * 后面所有人都会拿这份 demo 当模板，于是凭据被复制进无数工程。
 * 所以配置的取值路径是：
 *
 * ```
 *   local.properties（.gitignore 掉）  ──Gradle──▶  BuildConfig  ──运行时──▶  本类
 *        crash.upload.url  ──────────────────────▶  CRASH_UPLOAD_URL
 *        crash.upload.token ──────────────────────▶  CRASH_UPLOAD_TOKEN
 * ```
 *
 * 没配时两个常量是空串，[isConfigured] 返回 false，UI 应显示**"未配置"**
 * 而不是"上报失败" —— 这两者的处置完全不同：前者是没接线，后者是真的丢了数据。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 二、为什么必须走 NetClient.build()，而不是自己 new OkHttpClient
 * ══════════════════════════════════════════════════════════════════════
 *
 * `com.interview.net.NetClient` 的类注释写着：它是**唯一**允许
 * `OkHttpClient.Builder()` 出现的地方。这里遵守它，不是因为 lint 会拦，
 * 而是因为自建 client = 自建连接池 + 自建 DNS 缓存，会让"崩溃上报"这条
 * 低频链路游离在全局网络治理之外（复用率、坏 IP 熔断、分阶段度量全都统计不到）。
 *
 * 但**不能直接拿 `NetClient.shared`**：shared 是 API 档（base 超时 8s、自适应、
 * 允许重试），而崩溃上报的预算是**硬的、小的**（见下）。所以我们用
 * [NetClient.build] 传一份专用 [NetConfig] —— 复用全局治理，但执行参数按本链路分档。
 * 这与 `NetClient.downloads`（下载档）的做法一致。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 三、重试与预算：为什么是 2 次、为什么 4xx 不重试
 * ══════════════════════════════════════════════════════════════════════
 *
 * `drainTo` 的语义是「**返回 true 才清账**」（才会把事件从环形缓冲移除、才允许
 * 调用方清崩溃遗嘱）。所以失败**必须**返回 false 让事件留着，下次再发。
 *
 * 但"留着"不等于"当场无限重试"：本类跑在 `ThreadPools.scheduled` 的后台泳道上，
 * 一次上报占着线程不放，后面的周期 tick 就排不上。所以：
 *
 *   · 单批最多重试 [MAX_RETRIES] 次（含首次共 3 次尝试），退避 500ms / 1500ms；
 *   · 每次 Call 的超时被 [UPLOAD_CONFIG] 压到 3~4s（且关掉自适应），
 *     保证最坏 3×3s + 2s 退避 ≈ 11s，且还有 [BUDGET_MILLIS] 硬刹车兜底；
 *   · **4xx 一律不重试**：401/403 是凭据错、413 是包太大，重试 100 次也还是 4xx，
 *     只会白烧用户的流量。只有 **5xx 与网络异常**才重试（服务端过载/抖动是暂时的）。
 *
 * ⚠️ **诚实边界（刻意做的一件"政治不正确"的事）**：崩溃批次用 POST 发送，
 *    而 POST 按 HTTP 语义是**非幂等**的（`AdaptiveRetryInterceptor` 因此默认不重试 POST）。
 *    这里为了"别丢证据"仍对它做重试，代价是**服务端可能收到重复批次**。
 *    去重责任在服务端，判据用 `(pid, timestamp, name)` 三元组（与 CrashJournal
 *    的销账粒度是同一个取舍）。若服务端不打算去重，请把 [MAX_RETRIES] 改成 0。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 四、线程与凭据的两条红线
 * ══════════════════════════════════════════════════════════════════════
 *
 * 1. **绝不在主线程 sleep**。调用方保证在后台泳道驱动（`ThreadPools.scheduled`），
 *    但"保证"这种事不能只写在注释里。所以进来先做一次主线程自检：
 *    真在主线程就直接记警告 + 返回 false —— 宁可不发，也不能把一次上报
 *    变成一次 ANR（这正是本仓库其它类反复强调的那条线）。
 *
 * 2. **token 绝不出现在任何日志里**：不打请求头、不打完整 URL（可能含 query），
 *    `describe()` 只回显 endpoint 的 scheme+host+path。异常 message 里也不带 token
 *    （OkHttp 的 IOException 天然不含请求头，这里只需保证自己别往里塞）。
 */
object CrashUploader {

    private const val TAG = "CrashUpload"

    /** 含首次在内的总尝试次数 = 1 + MAX_RETRIES */
    private const val MAX_RETRIES = 2

    /**
     * 指数退避序列（ms）。刻意写死而不是复用 `AdaptiveRetryInterceptor.backoffMillis`：
     * 那条是给交互式请求用的（含抖动、300ms 起），而崩溃上报要**可预测**——
     * 抖动会把端到端预算变成区间，不利于给泳道排期。
     */
    private val BACKOFF_MILLIS = longArrayOf(500L, 1_500L)

    /**
     * 单批总耗时硬刹车。退避 + 各次 Call 超时理论上已能收敛，但仍设一道兜底：
     * 超过它就不再重试（避免异常情况下把后台泳道焊死）。
     */
    private const val BUDGET_MILLIS = 10_000L

    /** 响应体只留前 200 字符进日志：够定位（错误码/JSON 片段），又不至于污染 logcat */
    private const val BODY_LOG_LIMIT = 200

    /**
     * 上报链路专用的网络档位。
     *
     * 为什么不用 `NetConfig.DEFAULT`：它是给"用户正在等的交互式请求"定的
     * （8s 起、自适应、允许重试）。崩溃上报是**后台尽力而为**的活，
     * 预算必须小而固定 —— 自适应超时甚至会把它抬到 60s，那是灾难。
     *
     * `maxAttempts = 1`：重试由本类**统一**控制。两条重试逻辑叠加的话，
     * 最坏尝试次数会变成 3×3=9 次，日志里也很难说清"这到底是第几次"。
     */
    private val UPLOAD_CONFIG = NetConfig(
        baseCallTimeoutMillis = 3_000,
        maxCallTimeoutMillis = 3_000,
        connectTimeoutMillis = 3_000,
        readTimeoutMillis = 4_000,
        writeTimeoutMillis = 4_000,
        maxAttempts = 1,
        adaptiveTimeout = false,
    )

    private val gson = Gson()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * 本进程的会话 id，随机且**同进程稳定**。
     *
     * 为什么要它：服务端按 (pid, timestamp) 去重时，pid 会跨进程复用（系统回收后
     * 分给别的 App），单靠 pid 会把两个不同进程的事件混在一起。加一个随机 sessionId
     * 才能把"同一个进程生命周期"的事件圈出来。
     *
     * ⚠️ 诚实边界：它是**首次用到本对象时**生成，不是严格意义上的"进程启动瞬间"。
     *    对本用途（圈定一次进程生命周期）没有区别，但不该宣称成"开机时间"。
     */
    private val sessionId: String = UUID.randomUUID().toString()

    /** 批次序号。只增不减，用于服务端排"同一进程内批次的先后" */
    private val batchIndex = AtomicLong(0)

    private val attempts = AtomicLong(0)
    private val successes = AtomicLong(0)
    private val failures = AtomicLong(0)

    /** HTTP 层实际尝试次数（含重试）。attempts 数的是"批次"，这个数的是"次" */
    private val httpAttempts = AtomicLong(0)

    @Volatile
    private var lastError: String = ""

    @Volatile
    private var lastCostMillis: Long = -1

    /**
     * 上报客户端。懒加载：没配 endpoint 时**不应该**构造它
     * （构造会拉起 DNS、连接池，纯属浪费）。
     */
    private val client: OkHttpClient by lazy {
        Log.i(TAG, "构造崩溃上报专用 OkHttpClient（复用 NetClient 治理：DNS/度量/重试装配）")
        NetClient.build(UPLOAD_CONFIG)
    }

    // ─────────────────────────────────────────────
    // 公开 API
    // ─────────────────────────────────────────────

    /**
     * 配置是否齐备。
     *
     * ⚠️ 语义严格限定为「**endpoint 有效**」。token 是可选的（见类注释的配置表），
     *    没有 token 不代表没配置。UI 必须用这个值区分"未配置"与"上报失败"：
     *    前者是没接线，后者是数据真要丢了。
     */
    fun isConfigured(): Boolean = normalizeEndpoint(BuildConfig.CRASH_UPLOAD_URL) != null

    /**
     * 真实发送一批事件。
     *
     * @return **true 当且仅当服务端以 2xx 确认收到**。调用方（`StabilityMonitor.flush`）
     *   据此清账崩溃遗嘱 —— 所以这里**绝不能**像 `simulatedUpload` 那样无条件返回 true。
     *   endpoint 未配置、主线程调用、重试耗尽，一律 false，事件保留。
     */
    fun upload(batch: List<StabilityReporter.Event>): Boolean {
        val endpoint = normalizeEndpoint(BuildConfig.CRASH_UPLOAD_URL)
        if (endpoint == null) {
            // ⚠️ 这里**不是**"上报失败"，是"没接线"。但仍然必须返回 false：
            //    返回 true 会让调用方清掉崩溃遗嘱 —— 那就等于**静默丢掉崩溃**。
            lastError = "未配置 crash.upload.url"
            Log.w(TAG, "未配置 endpoint，跳过上传（事件保留，不清账）")
            return false
        }
        return uploadWith(batch, endpoint, BuildConfig.CRASH_UPLOAD_TOKEN, client)
    }

    /** 供实验页展示的读数。**绝不回显 token**，endpoint 也去掉 query（可能藏凭据）。 */
    fun describe(): String = buildString {
        appendLine("── 崩溃上报出口（真实 HTTP）──")
        appendLine("endpoint：${describeEndpoint()}")
        appendLine("token：${if (BuildConfig.CRASH_UPLOAD_TOKEN.isNotBlank()) "已配置（不回显）" else "未配置（可选）"}")
        appendLine(
            "批次尝试 ${attempts.get()}：成功 ${successes.get()} / 失败 ${failures.get()}" +
                    "（HTTP 实际尝试 ${httpAttempts.get()} 次）"
        )
        appendLine("最近耗时：${if (lastCostMillis < 0) "n/a" else "${lastCostMillis}ms"}")
        appendLine("最近错误：${lastError.ifEmpty { "（无）" }}")
        appendLine(
            "语义：2xx=服务端确认收到；4xx 不重试；5xx/网络异常最多重试 $MAX_RETRIES 次" +
                    "（退避 ${BACKOFF_MILLIS.joinToString("/")}ms，总预算 ${BUDGET_MILLIS}ms）"
        )
        appendLine("sessionId=$sessionId（进程内稳定）batchIndex=${batchIndex.get()}")
    }

    // ─────────────────────────────────────────────
    // 可注入的核心（单测入口）
    // ─────────────────────────────────────────────

    /**
     * 真正干活的实现，**把 endpoint / token / client / sleep 全部参数化**。
     *
     * 为什么要有这个 internal 重载：单测里 `BuildConfig.CRASH_UPLOAD_URL` 必然是空串
     * （它来自不入库的 local.properties），若 [upload] 把这三者写死，测试就无从下手 ——
     * 只能去改 BuildConfig，那等于把测试和目标环境绑死。
     * 所以**"配置读取"与"发送语义"必须分开**：前者薄（[upload] 三行），
     * 后者厚（本函数，可被 MockWebServer 完整覆盖）。
     *
     * @param sleep 退避睡眠的注入点。默认真睡；测试传 `{}` 让 5xx 重试用例秒级跑完。
     */
    internal fun uploadWith(
        batch: List<StabilityReporter.Event>,
        endpoint: String,
        token: String,
        client: OkHttpClient,
        sleep: (Long) -> Unit = { Thread.sleep(it) },
    ): Boolean {
        // 空批次不算一次上报（与 ReportDriver.simulatedUpload 的取舍一致：
        // 否则每 30s 的定时器会把"尝试次数"刷成定时器滴答数，读数就失去意义了）。
        // 放在这里而不是 [upload] 里，是为了让"空批次"这条语义也被单测覆盖到。
        if (batch.isEmpty()) return true

        // ── 红线：绝不在主线程 sleep / 做阻塞 IO ──
        if (onMainThread()) {
            lastError = "在主线程调用，已拒绝（会阻塞主线程）"
            failures.incrementAndGet()
            Log.w(TAG, "在**主线程**调用上传，已拒绝并返回 false（请在 ThreadPools 后台泳道驱动）")
            return false
        }

        val index = batchIndex.incrementAndGet()
        val startedNs = System.nanoTime()
        val payload = buildPayload(batch, index)
        val json = runCatching { gson.toJson(payload) }.getOrElse {
            // 序列化都失败就没必要发；但仍要留着事件（返回 false）
            lastError = "序列化失败：${it.javaClass.simpleName}"
            failures.incrementAndGet()
            Log.w(TAG, "批次序列化失败，事件保留", it)
            return false
        }
        val requestBody = json.toRequestBody(JSON)
        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .apply {
                // token 只在非空时进请求头。**绝不**放进 URL query
                //（URL 会进日志/代理/服务端 access log，是最常见的凭据泄露路径）。
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .build()

        attempts.incrementAndGet()
        Log.i(TAG, "开始上报：批次 #$index，${batch.size} 条，${json.length} 字节 → ${redact(endpoint)}")

        var failure = ""
        for (attempt in 0..MAX_RETRIES) {
            httpAttempts.incrementAndGet()
            try {
                client.newCall(request).execute().use { resp ->
                    val code = resp.code
                    // 只取前 N 字符：够看错误码/JSON 片段，又不会把 logcat 刷爆
                    val snippet = runCatching { resp.body?.string().orEmpty().take(BODY_LOG_LIMIT) }
                        .getOrDefault("")
                    val oneLineSnippet = snippet.replace('\n', ' ').replace('\r', ' ')

                    if (code in 200..299) {
                        successes.incrementAndGet()
                        lastError = ""
                        lastCostMillis = elapsedMs(startedNs)
                        Log.i(
                            TAG,
                            "✅ 上报成功 HTTP $code（批次 #$index，第 ${attempt + 1}/${MAX_RETRIES + 1} 次尝试，" +
                                    "耗时 ${lastCostMillis}ms）" +
                                    if (oneLineSnippet.isNotEmpty()) " body=$oneLineSnippet" else ""
                        )
                        return true
                    }

                    failure = "HTTP $code"
                    Log.w(
                        TAG,
                        "上报失败 HTTP $code（批次 #$index，第 ${attempt + 1}/${MAX_RETRIES + 1} 次尝试）" +
                                if (oneLineSnippet.isNotEmpty()) " body=$oneLineSnippet" else ""
                    )
                    // 4xx：重试没有意义（401/403 凭据错、413 包太大），立刻收手。
                    if (!shouldRetryStatus(code)) {
                        lastError = "HTTP $code（客户端错误，不重试）"
                        failures.incrementAndGet()
                        lastCostMillis = elapsedMs(startedNs)
                        return false
                    }
                }
            } catch (e: IOException) {
                // 网络异常（DNS/建连/超时/断流）：典型是暂时的，值得重试。
                failure = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(
                    TAG,
                    "上报网络异常（批次 #$index，第 ${attempt + 1}/${MAX_RETRIES + 1} 次尝试）：$failure"
                )
            }

            // 退避：先看总预算，再决定要不要继续 + 睡多久。
            val elapsed = elapsedMs(startedNs)
            if (attempt < MAX_RETRIES) {
                val backoff = BACKOFF_MILLIS.getOrElse(attempt) { BACKOFF_MILLIS.last() }
                if (elapsed + backoff > BUDGET_MILLIS) {
                    Log.w(TAG, "已用 ${elapsed}ms，再退避 ${backoff}ms 会超总预算 ${BUDGET_MILLIS}ms，停止重试")
                    break
                }
                sleep(backoff)
            }
        }

        lastError = "$failure（重试 ${MAX_RETRIES} 次后仍失败）"
        failures.incrementAndGet()
        lastCostMillis = elapsedMs(startedNs)
        Log.w(TAG, "⛔ 批次 #$index 上报失败，事件保留待下轮：$lastError（耗时 ${lastCostMillis}ms）")
        return false
    }

    // ─────────────────────────────────────────────
    // 纯逻辑（可单测，无副作用）
    // ─────────────────────────────────────────────

    /**
     * 归一化 endpoint：去空白、解析、只接受 http(s)。
     *
     * 返回 null 表示"未配置/配错"—— [isConfigured] 与 [upload] 共用它，
     * 保证"UI 说未配置"与"实际上传时的判断"是**同一个口径**（两处各写一遍
     * 就会漂移：UI 显示已配置、实际却不发）。
     *
     * 为什么在这里做 scheme 校验而不是交给 OkHttp：`toHttpUrlOrNull()` 会接受
     * `file://` 之类，那不是"网络上报"，早一点判成"未配置"比运行时抛错清楚。
     */
    internal fun normalizeEndpoint(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val url = trimmed.toHttpUrlOrNull() ?: return null
        if (url.scheme != "http" && url.scheme != "https") return null
        return url.toString()
    }

    /**
     * 状态码是否值得重试。**只放 5xx**（服务端过载/网关故障，属临时）。
     *
     * 与 `AdaptiveRetryInterceptor` 的 `RETRYABLE_CODES`（408/429/502/503/504）
     * 刻意不同、而且**更保守**：那条是给幂等的 GET 用的；崩溃上报是 POST，
     * 多试一次就多一次"服务端已入库但我没收到响应"的重复风险，所以只在
     * 明确"对方自己出了问题"的 5xx 上重试，网络异常单独在 catch 里判。
     */
    internal fun shouldRetryStatus(code: Int): Boolean = code in 500..599

    // ─────────────────────────────────────────────
    // 内部工具
    // ─────────────────────────────────────────────

    /**
     * 主线程自检。
     *
     * ⚠️ 必须写成"主 Looper **非 null 且** 相等"，不能简写成
     *    `Looper.myLooper() == Looper.getMainLooper()`。原因：JVM 单测里
     *    `testOptions.unitTests.isReturnDefaultValues = true` 会让 android.jar 的
     *    方法返回默认值，于是 myLooper() 与 getMainLooper() **都是 null**，
     *    两者相等 ⇒ 简写版会把每个测试都误判成"在主线程"，全部返回 false，
     *    测试直接变成假绿（测不到任何东西）。这个坑不写下来下次还会踩。
     */
    private fun onMainThread(): Boolean {
        val main = Looper.getMainLooper() ?: return false
        return Looper.myLooper() === main
    }

    private fun elapsedMs(startNs: Long): Long = (System.nanoTime() - startNs) / 1_000_000

    /** endpoint 的展示形式：去掉 userinfo 与 query（都可能藏凭据），只留 scheme://host/path */
    private fun redact(endpoint: String): String {
        val url = endpoint.toHttpUrlOrNull() ?: return "（无法解析）"
        return buildString {
            append(url.scheme).append("://").append(url.host)
            if (url.port != okhttp3.HttpUrl.defaultPort(url.scheme)) append(":").append(url.port)
            append(url.encodedPath)
        }
    }

    private fun describeEndpoint(): String {
        val ep = normalizeEndpoint(BuildConfig.CRASH_UPLOAD_URL)
            ?: return "未配置（在 local.properties 里加 crash.upload.url，见其中注释示例）"
        return redact(ep)
    }

    // ─────────────────────────────────────────────
    // 上报体（wire contract）
    // ─────────────────────────────────────────────

    private fun buildPayload(
        batch: List<StabilityReporter.Event>,
        index: Long,
    ): BatchPayload = BatchPayload(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        sdkInt = Build.VERSION.SDK_INT,
        deviceModel = Build.MODEL ?: "",
        processName = currentProcessName(),
        sessionId = sessionId,
        batchIndex = index,
        events = batch.map { it.toPayload() },
    )

    /**
     * 取当前进程名。
     *
     * `Application.getProcessName()` 是 API 28 才有的**静态**方法（不需要 Context，
     * 这一点很关键：本类刻意不持有 Context，避免在崩溃路径上依赖已失效的应用上下文）。
     *
     * ⚠️ 诚实边界：API < 28 拿不到进程名（要拿就得碰 `ActivityThread` 那个隐藏 API，
     *    本仓库的纪律是不碰隐藏 API，见 StabilityReporter.isForeground 的同类取舍）。
     *    所以低版本这里如实留空串，而不是编一个看起来像的包名。
     */
    private fun currentProcessName(): String = runCatching {
        if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName().orEmpty() else ""
    }.getOrDefault("")

    private fun StabilityReporter.Event.toPayload() = EventPayload(
        kind = kind.name,
        timestamp = timestamp,
        name = name,
        valueMs = valueMs,
        threadName = threadName,
        isMainThread = isMainThread,
        isForeground = isForeground,
        pid = pid,
        detail = detail,
        stack = stack,
    )

    /**
     * 批级上报体。字段名是**线上的 wire contract**，所以每个字段都用
     * `@SerializedName` 钉死 —— 不用它、依赖 Kotlin 字段名的话，R8（`opt.shrink`）
     * 把字段混淆后 JSON key 会变成 a/b/c，服务端解析出来全是空，**且编译期毫无提示**。
     * 本仓库 proguard-rules.pro 已有 `-keepclassmembers ... @SerializedName <fields>`
     * 的规则，所以标注即受保护。
     */
    private data class BatchPayload(
        @SerializedName("appVersion") val appVersion: String,
        @SerializedName("versionCode") val versionCode: Int,
        @SerializedName("sdkInt") val sdkInt: Int,
        @SerializedName("deviceModel") val deviceModel: String,
        @SerializedName("processName") val processName: String,
        @SerializedName("sessionId") val sessionId: String,
        @SerializedName("batchIndex") val batchIndex: Long,
        @SerializedName("events") val events: List<EventPayload>,
    )

    /** 事件体。刻意**逐字段显式映射**，不直接序列化 `StabilityReporter.Event`：
     *  内部类加字段/改结构时，wire 格式不该跟着被动变化（那是接口契约，要显式改）。 */
    private data class EventPayload(
        @SerializedName("kind") val kind: String,
        @SerializedName("timestamp") val timestamp: Long,
        @SerializedName("name") val name: String,
        @SerializedName("valueMs") val valueMs: Long,
        @SerializedName("threadName") val threadName: String,
        @SerializedName("isMainThread") val isMainThread: Boolean,
        @SerializedName("isForeground") val isForeground: Boolean,
        @SerializedName("pid") val pid: Int,
        @SerializedName("detail") val detail: String,
        @SerializedName("stack") val stack: String,
    )
}
