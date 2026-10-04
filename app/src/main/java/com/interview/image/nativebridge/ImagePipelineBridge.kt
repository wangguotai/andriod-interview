package com.interview.image.nativebridge

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Time: 2026/10/4
 * Author: wgt
 * Description: Rust 图像流水线的 JNI 入口 —— Kotlin 侧唯一与 native 打交道的类。
 *
 * ─── 这个类的定位 ───
 *
 * 它是「Rust 加速」这条教学线的收口点：上层（瀑布流、基准测试）只认这里的
 * [available] / [probeLayout] / [processXxx]，不关心底层是 Rust 还是 Java。
 * 一旦 native 库没编出来（比如只装了 arm64 target 却在 x86 模拟器上跑），
 * 这里要**安静地降级**，而不是让整个 App 崩在 `UnsatisfiedLinkError` 上。
 *
 * ─── 为什么提供两份实现（native + Java 回退）───
 *
 * 性能教学里最容易站不住的结论是「Rust 比 Java 快」。要让它站住，必须
 * **同机、同算法、同输入**地对照。所以这个类里同时存在：
 *   - native 路径：走 Rust（[ImagePipelineNative]）
 *   - 回退路径：纯 Kotlin 参考实现（[JavaFallback]）
 * 两者必须给出**逐位一致**的结果——这条由 androidTest 里的金标准测试保证。
 * 不是「差不多就行」：算法结果对不上，说明要么 Rust 写错了，要么布局约定错了
 * （见 [probeLayout] 的注释），任何一种都不能拿来做性能对比。
 *
 * ─── 像素布局约定（务必与 Rust 侧一致）───
 *
 * 统一按 **RGBA8888 字节序**传输：每像素 4 字节，顺序 R,G,B,A。
 * 这与 `Bitmap.copyPixelsToBuffer` 对 `Config.ARGB_8888` 的语义一致。
 *
 * ⚠️ `ARGB_8888` 是按**整型数值**（0xAARRGGBB）命名的，**不代表内存字节顺序**。
 * 如果改用 `IntArray` + `getPixels()` 再按 `u32` 传给 Rust，会 R/B 互换、
 * 全图偏色。这类错误不会崩、只会「颜色不对」，是 native 图像最阴的坑之一，
 * 所以 [probeLayout] 把它做成可断言的证据。
 */
object ImagePipelineBridge {

    private const val TAG = "ImagePipeline"

    /** 与 Rust 侧 `imagepipeline_android::ABI_VERSION` 对齐；不匹配说明 APK 里是旧 .so。 */
    const val EXPECTED_ABI_VERSION = 1

    /**
     * native 库是否加载且 ABI 匹配。
     *
     * native 库**只编 arm64-v8a**（本教学场景的取舍：真机与 arm64 模拟器都够，
     * 避免四 ABI 拖慢构建）。所以在 x86 模拟器上这里会是 false，走 Java 回退。
     */
    val available: Boolean by lazy {
        if (!ImagePipelineNative.loaded) {
            Log.w(TAG, "native 库未加载，回退 Java 实现：${ImagePipelineNative.loadError}")
            false
        } else {
            val abi = ImagePipelineNative.abiVersion()
            if (abi != EXPECTED_ABI_VERSION) {
                Log.e(TAG, "native ABI 版本不匹配：so=$abi, kotlin=$EXPECTED_ABI_VERSION，回退 Java 实现")
                false
            } else {
                true
            }
        }
    }

    /** 人类可读的运行时信息，打日志/上屏用。 */
    fun describe(): String = buildString {
        append("native=").append(if (available) "Rust" else "Java-fallback")
        if (ImagePipelineNative.loaded) {
            append(" abi=").append(ImagePipelineNative.abiVersion())
            ImagePipelineNative.versionString()?.let { append(" (").append(it).append(')') }
        } else {
            append(" reason=").append(ImagePipelineNative.loadError)
        }
    }

    /**
     * 布局自证：把 [bitmap] 左上角第一个像素按 RGBA 读出来，返回 `0xRRGGBBAA`。
     *
     * 期望值与 `Color.red/green/blue/alpha` 拼出的值相等。这是 M1 的核心验收项：
     * **先证明布局约定是对的，再谈性能**。否则后面所有滤镜结果都是错的，
     * 而且错得很安静。
     *
     * 返回 `Int`（`0xRRGGBBAA`）。最高位在 Kotlin `Int` 里是符号位，但比较时
     * 两边用同一种算法拼装，正负一致，不影响判定。
     */
    fun probeLayout(bitmap: Bitmap): Int {
        val buf = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
        val one = Bitmap.createBitmap(bitmap, 0, 0, 1, 1)
        one.copyPixelsToBuffer(buf)
        buf.rewind()
        val packed = if (available) {
            ImagePipelineNative.probeLayout(buf)
        } else {
            val b = ByteArray(4).also { buf.get(it) }
            ((b[0].toInt() and 0xFF) shl 24) or
                ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or
                (b[3].toInt() and 0xFF)
        }
        return packed
    }

    /** 供测试与基准使用：把 RGBA 字节流读/写的缓冲分配集中在一处，避免各处 new。 */
    fun allocateRgbaBuffer(pixelCount: Int): ByteBuffer =
        ByteBuffer.allocateDirect(pixelCount * 4).order(ByteOrder.LITTLE_ENDIAN)

    /** 把 Bitmap 像素读进 direct buffer（RGBA8888）。 */
    fun readPixels(bitmap: Bitmap, into: ByteBuffer) {
        into.rewind()
        bitmap.copyPixelsToBuffer(into)
        into.rewind()
    }

    /** 把 direct buffer（RGBA8888）写回 Bitmap。 */
    fun writePixels(bitmap: Bitmap, from: ByteBuffer) {
        from.rewind()
        bitmap.copyPixelsFromBuffer(from)
        from.rewind()
    }

    /** 当前设备 ABI 摘要，便于把「为什么走了回退」写进日志/结论。 */
    fun deviceAbiSummary(): String =
        "ABIs=${Build.SUPPORTED_ABIS.joinToString(",")}"

    /**
     * 纯 Kotlin 参考实现（回退路径 + 金标准）。
     *
     * 注意：这里刻意**不是**逐像素 `getPixel/setPixel`。那种写法会把
     * 「Rust 快」这件事变成「Rust 比一个坏实现快」，结论一文不值。
     * 参考实现也走 direct buffer + 一次性数组访问，是「Java 侧正常水平」的实现。
     */
    object JavaFallback {
        /** 取某像素（RGBA 字节流）。 */
        fun pixel(data: ByteArray, width: Int, x: Int, y: Int): Int {
            val i = (y * width + x) * 4
            return ((data[i].toInt() and 0xFF) shl 24) or
                ((data[i + 1].toInt() and 0xFF) shl 16) or
                ((data[i + 2].toInt() and 0xFF) shl 8) or
                (data[i + 3].toInt() and 0xFF)
        }
    }
}
