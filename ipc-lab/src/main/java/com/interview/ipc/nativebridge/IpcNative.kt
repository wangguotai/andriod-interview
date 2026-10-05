package com.interview.ipc.nativebridge

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: Rust 库（libipclab.so）的 JNI 声明，**不含任何业务逻辑**。
 *
 * 与 `ImagePipelineNative` 同一条纪律：这一层极薄，只有 native 方法声明 + 加载失败的捕获。
 * 「什么时候用 native、失败了怎么办」的判断都在 [IpcNativeBridge] 里，方便单点审计。
 *
 * ─── 加载策略 ───
 *
 * `System.loadLibrary` 失败（ABI 不匹配、APK 里没这个 .so）会抛 `UnsatisfiedLinkError`。
 * 接住它并记下原因，让上层能**显式降级**并给出「为什么不可用」，而不是在启动时崩掉。
 */
internal object IpcNative {

    private const val TAG = "IpcLab/Native"

    var loaded: Boolean = false
        private set

    var loadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("ipclab")
            loaded = true
        } catch (t: UnsatisfiedLinkError) {
            loadError = "UnsatisfiedLinkError: ${t.message}"
        }
    }

    /**
     * 跑一个原生演示，返回整段多行日志。入参 [kind] 见 [IpcNativeBridge] 的常量。
     *
     * 返回 `String` 而非分段回调：演示是**有序证据链**，一次返回整段，顺序与内容
     * 都是原生保证的，不在 Kotlin 侧二次拼接。
     */
    external fun runDemo(kind: String, arg: String): String?

    /** 能力列表，逗号分隔。 */
    external fun supportedDemos(): String?

    /** native ABI 版本号；加载失败或符号缺失时返回 -1。 */
    external fun abiVersion(): Int

    /** 当前 native 视角的 pid / tid，用于把「谁在跑」写进证据。 */
    external fun nativeGetpid(): Int
    external fun nativeGettid(): Int

    /** 把负 errno 翻译成符号名。 */
    external fun errnoName(code: Int): String?

    external fun versionString(): String?
}
