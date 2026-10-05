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
 * ─── ⚠️ 当前实现的范围（诚实标注，别误读）───
 *
 * 本类目前暴露的是**协议边界与控制面**：请求准入校验、取消令牌、线格式解码。
 * **它不提供 `fetch`** —— 真正的 QUIC 传输（quinn/rustls）尚未接入，原因见
 * [rust/netlab-android/src/lib.rs] 与设计文档 §4.1（async runtime 如何纳入
 * ThreadPools 线程治理尚未解决）。因此这里**刻意不存在一个「看起来能发请求
 * 但其实是空实现」的方法** —— 那正是最典型的假绿。
 *
 * 设计文档：[DESIGN-rust-transport.md]、可行性：[CRONET-FEASIBILITY.md]。
 */
object NetLabBridge {

    private const val TAG = "NetLabNative"

    /** 与 `netlab_android::ABI_VERSION` 对齐；不匹配说明 APK 里是旧 .so。 */
    const val EXPECTED_ABI_VERSION = 1

    /**
     * native 库是否加载且 ABI 匹配。
     *
     * native 库**只编 arm64-v8a**（与图像侧同一取舍）。x86 模拟器上会是 false，
     * 此时所有 [NetLabBridge] 功能降级为「不可用」，调用方应回退 OkHttp。
     */
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
}
