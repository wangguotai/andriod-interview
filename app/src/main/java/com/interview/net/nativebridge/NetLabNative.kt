package com.interview.net.nativebridge

import android.util.Log

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: libnetlab_android.so 的 JNI 声明，**不含任何业务逻辑**。
 *
 * 与图像侧的 [com.interview.image.nativebridge.ImagePipelineNative] 完全同一纪律：
 * 只有 native 方法声明 + 加载失败的捕获。所有「什么时候用 native、什么时候回退」
 * 的判断都在 [NetLabBridge] 里，方便单点审计。
 *
 * ─── 加载策略 ───
 *
 * `System.loadLibrary` 失败（ABI 不匹配、APK 里没有这个 .so、工具链缺失导致
 * 构建时跳过）会抛 `UnsatisfiedLinkError`。接住并记下原因，让上层能给出
 * 「为什么不可用」，并**无痛回退到 OkHttp 原生路径**。
 *
 * 反面做法：在 `static {}` 里直接加载、不捕获 —— 那样只会让 App 启动即崩，
 * 且错误信息里看不出「是 ABI 缺了、库名写错了、还是构建被跳过了」。
 */
internal object NetLabNative {

    private const val TAG = "NetLabNative"

    /** 加载是否成功。 */
    var loaded: Boolean = false
        private set

    /** 加载失败原因（成功时为 null），用于日志与降级说明。 */
    var loadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("netlab_android")
            loaded = true
        } catch (t: UnsatisfiedLinkError) {
            loadError = "UnsatisfiedLinkError: ${t.message}"
            Log.w(TAG, "netlab_android 加载失败，传输实验功能降级：$loadError")
        }
    }

    /** Rust 侧 ABI 版本号；加载失败时返回 -1。 */
    external fun abiVersion(): Int

    /** Rust 侧版本字符串，加载失败时返回 null。 */
    external fun versionString(): String?

    /**
     * 请求准入校验。返回 `0` 表示放行，`> 0` 为拒绝码（见 [NetLabBridge.RejectReason]）。
     *
     * 校验发生在**跨语言边界内侧**：即便调用方绕过 Kotlin 侧路由直接调这里，
     * 明文 http 与不支持的方法也会被拒（安全闸门的第二道）。
     */
    external fun validateRequest(url: String, method: String, hasProxy: Boolean): Int

    /** 创建取消令牌，返回句柄；`0` 表示失败。 */
    external fun cancelTokenNew(): Long

    /** 释放取消令牌。对 `0` 句柄是安全空操作。 */
    external fun cancelTokenFree(handle: Long)

    /** 请求取消；返回「本次是否真正翻转了状态」（已终态时为 false）。 */
    external fun cancelTokenCancel(handle: Long): Boolean

    /** 是否已被取消。 */
    external fun cancelTokenIsCancelled(handle: Long): Boolean

    /**
     * 线格式解码（主要供对拍测试）。成功返回人类可读摘要，失败返回 null。
     */
    external fun wireDecode(buf: ByteArray): String?

    /**
     * 句柄往返自证：对给定令牌走一遍「判定未取消 → 取消 → 判定已取消」。
     * 返回 `1` 表示跨 FFI 的句柄读写语义正常。对应图像侧的 `probeLayout`。
     */
    external fun probeHandleRoundTrip(handle: Long): Int
}
