package com.interview.image.nativebridge

import android.util.Log
import java.nio.ByteBuffer

/**
 * Time: 2026/10/4
 * Author: wgt
 * Description: Rust 库（libimagepipeline_android.so）的 JNI 声明，**不含任何业务逻辑**。
 *
 * 这一层刻意做得极薄：只有 native 方法声明 + 加载失败的捕获。所有「什么时候用
 * native、什么时候回退」的判断都在 [ImagePipelineBridge] 里，方便单点审计。
 *
 * ─── 加载策略 ───
 *
 * `System.loadLibrary` 失败（ABI 不匹配、APK 里压根没这个 .so）会抛
 * `UnsatisfiedLinkError`。这里把它接住并记下来，让 [ImagePipelineBridge.available]
 * 能给出「为什么不可用」，而上层可以无痛降级到 Java 实现。
 *
 * 反面做法：在 `static {}` 里直接加载、不捕获——那样只会让 App 在启动时崩掉，
 * 且错误信息里看不出「是 ABI 缺了还是库名写错了」。
 */
internal object ImagePipelineNative {

    private const val TAG = "ImagePipeline"

    /** 加载是否成功。 */
    var loaded: Boolean = false
        private set

    /** 加载失败原因（成功时为 null），用于日志与降级说明。 */
    var loadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("imagepipeline_android")
            loaded = true
        } catch (t: UnsatisfiedLinkError) {
            loadError = "UnsatisfiedLinkError: ${t.message} (${ImagePipelineBridge.deviceAbiSummary()})"
        }
    }

    /**
     * Rust 侧 ABI 版本号；加载失败或调用异常时返回 -1。
     *
     * 加 try/catch 的理由：`loaded` 只保证 `dlopen` 成功，不保证符号齐全。
     * 若 .so 是旧版本（缺了新符号），这里仍可能抛，用它兜底。
     */
    external fun abiVersion(): Int

    /** Rust 侧版本字符串，加载失败时返回 null。 */
    external fun versionString(): String?

    /**
     * 布局探针：把 direct buffer 前 4 字节按 **R,G,B,A** 解释成 `0xRRGGBBAA`。
     *
     * 在 Rust 侧见 `rust/android/src/android_impl.rs::probeLayout`。
     * 返回 `Int` 而非 `Long`：Java 的 `int` 与 Rust 的 `jint` 同宽，能少一次
     * 跨 FFI 的宽度转换就少一类「静默错位」。Kotlin 侧比较时直接用 `Int` 常量。
     */
    external fun probeLayout(buffer: ByteBuffer): Int

    /**
     * 区域平均降采样：RGBA8888 的 [src]（[srcW]×[srcH]）→ [dst]（[dstW]×[dstH]）。
     *
     * 输入/输出都是 direct buffer；成功返回 0，失败返回负数。
     * 算法权威定义见 `rust/imagepipeline/src/downscale.rs` 与
     * [ImagePipelineReference.downscaleArea]（两者必须逐位一致）。
     */
    external fun downscaleArea(
        src: ByteBuffer,
        srcW: Int,
        srcH: Int,
        dst: ByteBuffer,
        dstW: Int,
        dstH: Int,
    ): Int

    /**
     * 主色调提取：RGBA8888 的 [src]（[w]×[h]）→ `0x00RRGGBB`。
     *
     * 成功返回 24 位颜色（**0 是合法的黑色**），失败返回负数 —— 因此判断失败
     * 必须用 `< 0`，不能用 `== 0` 或 `<= 0`。返回 `Int` 而非对象，是为了不给
     * JNI 边界增加 Java 对象的构造/回收成本。
     */
    external fun dominantColor(src: ByteBuffer, w: Int, h: Int): Int
}
