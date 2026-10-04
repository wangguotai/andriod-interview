package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.interview.image.nativebridge.ImagePipelineBridge
import com.interview.image.nativebridge.ImagePipelineNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Time: 2026/10/4
 * Author: wgt
 * Description: M1 验收 —— native 库可用性 + **像素布局自证**。
 *
 * ─── 为什么这个测试比「能调用」重要 ───
 *
 * 大多数 Rust+Android 的教程止步于「logcat 里打印出了 Hello from Rust」，
 * 那只证明了「符号能连上」。但图像流水线真正的定时炸弹是**通道顺序**：
 * Android 的 `ARGB_8888` 是按整型数值命名（0xAARRGGBB）的，
 * 而它在内存里是 RGBA 字节序。一旦按名字去解读内存，结果不会崩，
 * 只会整张图 R/B 互换 —— 看起来「还能显示」，很难第一眼发现。
 *
 * 所以这里的验收不是「返回了非空」，而是：
 *   1. 对一组**已知颜色**分别构造 Bitmap，读出的通道必须与 `Color.red/green/blue` 完全一致；
 *   2. 故意用 R≠B 的颜色（红/蓝），这样交换顺序一定会被抓到；
 *   3. 再对同一份 buffer 跑一遍纯 Kotlin 参考实现，与 native 结果比对。
 *
 * 第 3 步是 M2「跨语言金标准对拍」的最小先行版：性能对比的前提是**结果一致**。
 *
 * 运行（真机优先，结论必须真机复验）：
 *   ./gradlew :app:connectedDebugAndroidTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=com.example.myapplication.ImagePipelineLayoutTest
 */
@RunWith(AndroidJUnit4::class)
class ImagePipelineLayoutTest {

    /** 构造 1×1 的纯色 Bitmap（用 ARGB 整型构造，语义明确无歧义）。 */
    private fun solidBitmap(color: Int): Bitmap =
        Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun nativeLibraryStateIsReported() {
        // 硬断言（而不是只打日志）：本教学场景 native 只编 arm64-v8a，
        // 所以在 arm64 设备上**必须**走 Rust 路径。若这里成了回退，
        // 说明 .so 没进 APK、ABI 不匹配或符号缺失 —— 都必须当作失败，
        // 否则后面所有「Rust 比 Java 快」的数字都建立在 Java 回退上，纯属自欺。
        // x86 模拟器上 available=false 则是预期行为（走 Java 回退，不崩）。
        val isArm64 = android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
        val describe = ImagePipelineBridge.describe()
        println("[M1] $describe | ${ImagePipelineBridge.deviceAbiSummary()}")

        if (ImagePipelineBridge.available) {
            assertEquals(
                "native 可用时 ABI 版本必须与 Kotlin 侧一致",
                ImagePipelineBridge.EXPECTED_ABI_VERSION,
                ImagePipelineNative.abiVersion(),
            )
            assertTrue(
                "native 可用时应能给出版本字符串（$describe）",
                !ImagePipelineNative.versionString().isNullOrBlank(),
            )
        } else if (isArm64) {
            throw AssertionError(
                "arm64 设备上 native 库不可用，必须修好再谈性能对比：$describe",
            )
        }
    }

    @Test
    fun probeLayoutMatchesColorChannels_forRedBlueGreenWhite() {
        // 特意选 R≠B 的颜色：任何 R/B 互换都会立刻暴露。
        val cases = listOf(
            "红" to Color.RED,
            "绿" to Color.GREEN,
            "蓝" to Color.BLUE,
            "白" to Color.WHITE,
            "黑" to Color.BLACK,
            "任意" to Color.rgb(0x12, 0x34, 0x56),
        )
        for ((name, color) in cases) {
            val got = ImagePipelineBridge.probeLayout(solidBitmap(color))
            val expected = expectedPacked(color)
            assertEquals(
                "$name 的通道顺序不对（R/B 可能互换）：got=$got expected=$expected, " +
                    ImagePipelineBridge.describe(),
                expected,
                got,
            )
        }
    }

    @Test
    fun probeLayoutAgreesWithKotlinReferenceImplementation() {
        // 同一份 bitmaps：native 与纯 Kotlin 参考实现必须给出完全相同的打包值。
        // 这是「跨语言一致」这条红线的第一批砖；M2 会把它扩展到整图算法。
        for (color in listOf(Color.RED, Color.GREEN, Color.BLUE, Color.rgb(0xAB, 0xCD, 0xEF))) {
            val bmp = solidBitmap(color)
            val native = ImagePipelineBridge.probeLayout(bmp)

            val buf = ImagePipelineBridge.allocateRgbaBuffer(1)
            ImagePipelineBridge.readPixels(bmp, buf)
            val bytes = ByteArray(4).also { buf.get(it) }
            val kotlin = ImagePipelineBridge.JavaFallback.pixel(bytes, 1, 0, 0)

            assertEquals("native 与 Kotlin 参考实现结果不一致（color=#${Integer.toHexString(color)}）", kotlin, native)
        }
    }

    /** 期望值：按 RGBA 字节序打包成 0xRRGGBBAA。 */
    private fun expectedPacked(color: Int): Int =
        (Color.red(color) shl 24) or
            (Color.green(color) shl 16) or
            (Color.blue(color) shl 8) or
            Color.alpha(color)
}
