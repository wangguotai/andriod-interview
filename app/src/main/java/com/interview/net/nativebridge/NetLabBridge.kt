package com.interview.net.nativebridge

import android.util.Log

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: Rust 传输实验（netlab）的 Kotlin 收口层。
 *
 * ─── 定位 ───
 *
 * 与 [com.interview.image.nativebridge.ImagePipelineBridge] 同构：上层只认这里，
 * 不直接碰 `external` 方法；native 不可用时**安静降级**，绝不崩在 UnsatisfiedLinkError。
 *
 * ─── 当前实现的范围（诚实标注，别误读）───
 *
 * 本类暴露三层能力：
 *   1. **协议边界与控制面**：请求准入校验、取消令牌、线格式解码；
 *   2. **真实 HTTP/3 传输**：[fetch]（基于 kernel 的 quinn/rustls），**阻塞式**，
 *      必须在网络泳道线程上调用；
 *   3. **安全对拍**：[spkiSha256Hex]，与 OkHttp `CertificatePinner` 比对同一张证书的指纹。
 *
 * ⚠️ 尚未接入的缺口（见 [NetLabBridge] 的 fetch 注释与 NETLAB 文档）：
 *   · QUIC 连接复用（`reusedConnection` 恒为 false）；
 *   · 流式 body；Android 系统 CA 注入；`handshake` 完整重建。
 *
 * 证书固定的**配置通道已接通**（M3）：pin 经 `fetch(..., pins)` →
 * `wire::parse_pin_lines` → rustls 自定义 verifier 在握手里生效；
 * 两侧指纹一致性与「错误指纹必须被拒」有对拍与集成测试钉住。
 *
 * 设计文档：[DESIGN-rust-transport.md]、可行性：[CRONET-FEASIBILITY.md]。
 */
object NetLabBridge {

    private const val TAG = "NetLabNative"

    /** 与 `netlab_android::ABI_VERSION` 对齐；不匹配说明 APK 里是旧 .so。 */
    const val EXPECTED_ABI_VERSION = 3

    /** native 库是否加载且 ABI 匹配。 */
    val available: Boolean by lazy {
        if (!NetLabNative.loaded) {
            Log.w(TAG, "native 库未加载：${NetLabNative.loadError}")
            false
        } else {
            val abi = runCatching { NetLabNative.abiVersion() }.getOrDefault(-1)
            if (abi != EXPECTED_ABI_VERSION) {
                Log.e(TAG, "native ABI 不匹配：so=$abi, kotlin=$EXPECTED_ABI_VERSION，降级")
                false
            } else {
                true
            }
        }
    }

    /**
     * `fetch` 是否可用。
     *
     * 与 [available] 分开是刻意的：`available` 只说明库加载了、ABI 对了；
     * 而 `fetch` 是**会真的发网络请求**的能力，未来若要按灰度/实验开关单独控制，
     * 应只影响这一个判据，而不是把整个 native 层判成不可用。
     * 目前两者等价，但语义不同，分开写避免以后改错地方。
     */
    val fetchAvailable: Boolean get() = available

    /** 人类可读的运行时信息，打日志/上屏用。 */
    fun describe(): String = buildString {
        append("native=").append(if (available) "Rust(netlab)" else "unavailable")
        if (NetLabNative.loaded) {
            append(" abi=").append(runCatching { NetLabNative.abiVersion() }.getOrDefault(-1))
            NetLabNative.versionString()?.let { append(" (").append(it).append(')') }
        } else {
            append(" reason=").append(NetLabNative.loadError)
        }
    }

    /**
     * 请求准入的拒绝原因。数值与 Rust 侧 `RejectReason::code()` **逐一对应**，
     * 改动任一侧都必须同步 —— 这是跨 FFI 错误码的常规契约，不加测试就会漂移。
     */
    enum class RejectReason(val code: Int) {
        BAD_URL(1),
        NOT_HTTPS(2),
        METHOD_NOT_ALLOWED(3),
        PROXY_UNSUPPORTED(4),
        ;

        companion object {
            fun fromCode(code: Int): RejectReason? = entries.firstOrNull { it.code == code }
        }
    }

    /** 校验结果：放行，或被拒（带原因）。 */
    sealed interface Validation {
        data object Allowed : Validation
        data class Rejected(val reason: RejectReason, val rawCode: Int) : Validation
    }

    /**
     * 校验一次请求能否交给 Rust 传输。
     *
     * native 不可用时返回 [Validation.Rejected]（`BAD_URL` 是最近似的占位）——
     * 语义上「不可用」等同于「不允许走该路径」，调用方应回退 OkHttp。
     * ⚠️ 这里不返回 `Allowed` 是有意为之：**不可用时必须拒绝，而不是放行**，
     * 否则调用方可能真的去调一个不存在的传输实现。
     */
    fun validate(url: String, method: String, hasProxy: Boolean = false): Validation {
        if (!available) return Validation.Rejected(RejectReason.BAD_URL, rawCode = -1)
        val code = runCatching { NetLabNative.validateRequest(url, method, hasProxy) }
            .getOrDefault(-1)
        return when (code) {
            0 -> Validation.Allowed
            else -> {
                val reason = RejectReason.fromCode(code) ?: RejectReason.BAD_URL
                Validation.Rejected(reason, rawCode = code)
            }
        }
    }

    /**
     * 取消令牌的**自主管理**包装：`use { }` 语义，保证句柄恰好释放一次。
     *
     * 为什么提供它而不是把 Long 句柄裸露给调用方：**句柄泄漏/重复释放是这个
     * 边界上最阴的错误** —— 不会崩在调用点，只会在之后再崩，且堆栈毫无指向。
     * 用 AutoCloseable 把「创建/释放」配对写死，调用方想错都难。
     *
     * native 不可用时 [newToken] 返回 null，调用方走 OkHttp（那边用 `Call.cancel()`）。
     */
    class CancelTokenHandle internal constructor(private val handle: Long) : AutoCloseable {
        /** 裸句柄，供传给 [fetch] 的 `cancelHandle`。**不要在其他地方使用**。 */
        internal val rawHandle: Long get() = handle

        /** 请求取消；返回「本次是否真正翻转了状态」。 */
        fun cancel(): Boolean =
            handle != 0L && runCatching { NetLabNative.cancelTokenCancel(handle) }.getOrDefault(false)

        val isCancelled: Boolean
            get() = handle != 0L && runCatching { NetLabNative.cancelTokenIsCancelled(handle) }.getOrDefault(false)

        override fun close() {
            if (handle != 0L) {
                runCatching { NetLabNative.cancelTokenFree(handle) }
            }
        }
    }

    /** 创建一个取消令牌；native 不可用时返回 null。 */
    fun newToken(): CancelTokenHandle? {
        if (!available) return null
        val handle = runCatching { NetLabNative.cancelTokenNew() }.getOrDefault(0L)
        return if (handle == 0L) null else CancelTokenHandle(handle)
    }

    /**
     * 线格式解码（对拍测试用）。成功返回可读摘要，失败或不可用返回 null。
     */
    fun wireDecode(bytes: ByteArray): String? {
        if (!available) return null
        return runCatching { NetLabNative.wireDecode(bytes) }.getOrNull()
    }

    /**
     * 句柄往返自证。对应图像侧的 `probeLayout`：把「跨 FFI 句柄语义」变成
     * 一条可断言的证据，而不是文档里的一句假设。
     *
     * @return true 表示创建→取消→判定 的往返语义正确
     */
    fun probeHandleRoundTrip(): Boolean {
        if (!available) return false
        val handle = runCatching { NetLabNative.cancelTokenNew() }.getOrDefault(0L)
        if (handle == 0L) return false
        return try {
            runCatching { NetLabNative.probeHandleRoundTrip(handle) }.getOrDefault(-1) == 1
        } finally {
            // 恰好释放一次 —— 这正是用 AutoCloseable 而不是裸 Long 的收益。
            runCatching { NetLabNative.cancelTokenFree(handle) }
        }
    }

    // ─────────────────────────────────────────
    // HTTP/3 fetch（M2）
    // ─────────────────────────────────────────

    /** HTTP 方法白名单。与 Rust 侧 `request::ALLOWED_METHODS` **必须一致**（有单测钉）。 */
    val allowedMethods: Set<String> = setOf("GET", "HEAD", "PUT", "DELETE")

    /**
     * 一次 HTTP/3 传输的结果。
     *
     * `peerSpkiSha256` 是**叶证书的 SPKI-SHA256**，用于与 OkHttp `CertificatePinner`
     * 对拍（同一张证书两侧必须算出一致指纹）。v1 不会用它做信任决策 ——
     * 信任判定在 rustls 握手内完成。
     */
    data class NativeResponse(
        val status: Int,
        val protocol: String,
        val reusedConnection: Boolean,
        val connectMillis: Long,
        val firstByteMillis: Long,
        val totalMillis: Long,
        /** 响应头，**有序且允许重复键**（Set-Cookie 必须全部保留）。 */
        val headers: List<Pair<String, String>>,
        val body: ByteArray,
        val peerSpkiSha256: String?,
    ) {
        // data class 含 ByteArray 时自动生成的 equals/hashCode 是**按引用比较**的，
        // 与「值相等」直觉不符，且会让单测里 assertEq 莫名失败。显式覆写。
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is NativeResponse) return false
            return status == other.status &&
                protocol == other.protocol &&
                reusedConnection == other.reusedConnection &&
                connectMillis == other.connectMillis &&
                firstByteMillis == other.firstByteMillis &&
                totalMillis == other.totalMillis &&
                headers == other.headers &&
                body.contentEquals(other.body) &&
                peerSpkiSha256 == other.peerSpkiSha256
        }

        override fun hashCode(): Int {
            var result = status
            result = 31 * result + protocol.hashCode()
            result = 31 * result + reusedConnection.hashCode()
            result = 31 * result + connectMillis.hashCode()
            result = 31 * result + firstByteMillis.hashCode()
            result = 31 * result + totalMillis.hashCode()
            result = 31 * result + headers.hashCode()
            result = 31 * result + body.contentHashCode()
            result = 31 * result + (peerSpkiSha256?.hashCode() ?: 0)
            return result
        }
    }

    /** fetch 的失败结果（Rust 侧明确拒绝/失败，或 native 不可用）。 */
    sealed interface FetchResult {
        data class Success(val response: NativeResponse) : FetchResult

        /**
         * 失败。`code` 与 Rust `FetchError::code()` / `RejectReason::code()` 对应；
         * `code == -1` 表示「native 不可用」。
         */
        data class Failure(val code: Int, val message: String) : FetchResult {
            /** 是否是证书固定（pinning）失败 —— 安全事件，不应被当作普通网络抖动重试。 */
            val isPinMismatch: Boolean get() = message.contains("SPKI-PIN-MISMATCH")

            /** 是否被取消（协作式），与真失败语义不同。 */
            val isCancelled: Boolean get() = code == 12
        }
    }

    /**
     * 发起一次 HTTP/3 请求。**阻塞式**——调用方必须在网络泳道线程上调用。
     *
     * native 不可用时返回 `Failure(-1, ...)`，调用方应回退 OkHttp（不是抛异常）。
     */
    fun fetch(
        url: String,
        method: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        timeoutMillis: Long = 15_000,
        cancelHandle: CancelTokenHandle? = null,
        pins: List<Pair<String?, ByteArray>> = emptyList(),
    ): FetchResult {
        if (!fetchAvailable) {
            return FetchResult.Failure(-1, "native 不可用：${NetLabNative.loadError ?: "ABI 不匹配"}")
        }
        val bytes = runCatching {
            NetLabNative.fetch(
                url = url,
                method = method,
                headers = encodeHeaderLines(headers),
                body = body,
                timeoutMs = timeoutMillis,
                cancelHandle = cancelHandle?.rawHandle ?: 0L,
                pins = encodePinLines(pins),
            )
        }.getOrNull() ?: return FetchResult.Failure(-1, "JNI 调用失败（可能 native 崩溃被拦）")

        return parseWire(bytes)
    }

    /**
     * 计算证书 SPKI-SHA256（不连网）。用于与 OkHttp `CertificatePinner` 对拍。
     */
    fun spkiSha256Hex(certDer: ByteArray): String? {
        if (!available) return null
        return runCatching { NetLabNative.spkiSha256Hex(certDer) }.getOrNull()
    }

    /** 与 Rust `wire::encode_header_lines` 对应：`Name: value\r\n`。 */
    internal fun encodeHeaderLines(headers: List<Pair<String, String>>): ByteArray =
        headers.joinToString(separator = "") { (k, v) -> "$k: $v\r\n" }.toByteArray(Charsets.UTF_8)

    /**
     * 与 Rust `wire::parse_pin_lines` 对应：`<host|*>\t<64位hex>\r\n`。
     *
     * `host == null` 或 `"*"` 表示全局生效。
     *
     * ⚠️ **这里刻意做长度校验并抛错**（而不是像 Rust 侧那样悄悄丢弃非法行）：
     * Kotlin 侧是配置的**产生方**，配置写错应当在最近的地方大声失败；
     * Rust 侧是**消费方**，跨 FFI 收到的可能已被截断，故那边选择「拒收并报告」。
     * 两侧策略不同是有意的，不是不一致。
     */
    internal fun encodePinLines(pins: List<Pair<String?, ByteArray>>): ByteArray? {
        if (pins.isEmpty()) return null
        val sb = StringBuilder()
        pins.forEach { (host, pin) ->
            require(pin.size == 32) { "SPKI 固定必须是 32 字节（SHA-256），实际 ${pin.size}" }
            sb.append(host?.takeIf { it.isNotBlank() } ?: "*")
            sb.append('\t')
            pin.forEach { b -> sb.append("%02x".format(b)) }
            sb.append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * 解析与 OkHttp `CertificatePinner` 同格式的 pin 串，取出其中的**十六进制 SPKI**。
     *
     * 支持 OkHttp 的三种写法：`sha256/BASE64`、`sha256/AAAAAAAAAAA=…`（base64）、
     * 以及我们内部用的 64 位十六进制直给。返回 32 字节或 null。
     *
     * 存在意义：OkHttp 的 `CertificatePinner` 只提供「校验」不提供「取指纹」，
     * 要证明两条实现算出**同一个指纹**，必须先能把它给的 base64 解成字节。
     */
    fun decodePinToSpki(pin: String): ByteArray? {
        val body = pin.substringAfter("sha256/", pin).trim()
        return when {
            body.length == 64 && body.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' } ->
                ByteArray(32) { i -> body.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
            else -> runCatching {
                // 用 okio 而不是 java.util.Base64：后者要 API 26，而 minSdk=24；
                // okio 是 okhttp 既有依赖，纯 JVM、单测里也能跑。
                okio.ByteString.Companion.run { body.decodeBase64() }?.toByteArray()
            }.getOrNull()?.takeIf { it.size == 32 }
        }
    }

    /**
     * 解析 Rust 侧线格式。**必须与 `rust/netlab/src/wire.rs` 严格一致**，
     * 否则会解析出错值且不报错 —— 这正是「跨语言重复实现」要重点防的。
     *
     * 黄金样本对拍见 `NetLabWireParityTest`（十六进制串由 `cargo run --example wire_golden` 生成）。
     */
    internal fun parseWire(bytes: ByteArray): FetchResult {
        val sep = findHeaderEnd(bytes) ?: return FetchResult.Failure(-1, "线格式缺少空行分隔")
        val head = String(bytes, 0, sep, Charsets.UTF_8)
        val body = bytes.copyOfRange(sep, bytes.size)
        val lines = head.split('\n')

        val first = lines.firstOrNull()?.trim().orEmpty()
        if (first.startsWith("ERR:")) {
            val code = first.removePrefix("ERR:").trim().toIntOrNull() ?: -1
            val msg = lines.drop(1).firstOrNull { it.startsWith("msg=") }
                ?.removePrefix("msg=") ?: ""
            return FetchResult.Failure(code, msg)
        }
        if (first != "OK") return FetchResult.Failure(-1, "线格式首行非法：$first")

        var status = -1
        var proto = "?"
        var reused = false
        var connectMs = -1L
        var firstByteMs = -1L
        var totalMs = 0L
        var bodyBytes = 0L
        var spki: String? = null
        val headers = ArrayList<Pair<String, String>>()

        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            if (line.startsWith("hdr=")) {
                // 按**首个** ':' 切分：值本身可含 ':'（时间戳、URL）。
                val rest = line.substring(4)
                val idx = rest.indexOf(':')
                if (idx >= 0) {
                    headers += rest.substring(0, idx).trim().lowercase() to
                        rest.substring(idx + 1).trim()
                } else {
                    headers += rest.trim().lowercase() to ""
                }
                continue
            }
            val eq = line.indexOf('=')
            if (eq < 0) continue // 未知行：忽略（前向兼容）
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            when (key) {
                "status" -> status = value.toIntOrNull() ?: return FetchResult.Failure(-1, "status 非法")
                "proto" -> proto = value
                "reused" -> reused = value == "true"
                "connect_ms" -> connectMs = value.toLongOrNull() ?: -1
                "first_byte_ms" -> firstByteMs = value.toLongOrNull() ?: -1
                "total_ms" -> totalMs = value.toLongOrNull() ?: 0
                "body_bytes" -> bodyBytes = value.toLongOrNull() ?: 0
                "spki" -> spki = value
                else -> {}
            }
        }
        if (status < 0) return FetchResult.Failure(-1, "线格式缺少必填字段 status")
        if (bodyBytes != body.size.toLong()) {
            // 长度不符是**最危险**的错法（会读到错位 body）——宁可失败也不猜。
            return FetchResult.Failure(-1, "body 长度不符：声明 $bodyBytes 实际 ${body.size}")
        }
        return FetchResult.Success(
            NativeResponse(status, proto, reused, connectMs, firstByteMs, totalMs, headers, body, spki)
        )
    }

    /** 找头部与 body 的分界（连续两个 `\n` 之后）。 */
    private fun findHeaderEnd(bytes: ByteArray): Int? {
        var i = 0
        while (i + 1 < bytes.size) {
            if (bytes[i] == '\n'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) return i + 2
            i++
        }
        return null
    }
}
