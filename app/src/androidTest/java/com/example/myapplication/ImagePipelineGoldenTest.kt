package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.interview.image.nativebridge.ImagePipelineBridge
import com.interview.image.nativebridge.ImagePipelineNative
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Time: 2026/10/4
 * Author: wgt
 * Description: M2 —— **跨语言金标准对拍**：Rust native 与 Kotlin 参考实现逐位一致。
 *
 * ─── 为什么这是性能结论的前置条件 ───
 *
 * 「Rust 比 Java 快」只有在**同算法、同输入、同结果**时才是一句有意义的话。
 * 如果两种实现结果不同，那快的那个只是「算错了」，对比毫无价值。所以本测试
 * 不比较耗时，只比较**字节**：同一份像素分别喂给 native 与 Kotlin，断言输出
 * 完全相同。任何一处取整、边界、通道顺序的差异都会在这里暴露。
 *
 * ─── 为什么用固定种子 ───
 *
 * 随机数据用 `Random(seed)` 生成。否则一旦失败，无法复现到底是哪张图、哪个像素
 * 不一致，只能盯着一个「偶尔红」的测试干瞪眼。
 *
 * ─── 关于 skip ───
 *
 * native 只编 arm64-v8a，x86 模拟器上 [ImagePipelineBridge.available] 为 false。
 * 此时用 [assumeTrue] 跳过 strict 对拍（并打印原因），而不是伪造通过 ——
 * 「没有 native 可对」和「对拍通过」是两件不同的事，不能混为一谈。
 */
@RunWith(AndroidJUnit4::class)
class ImagePipelineGoldenTest {

    private val rnd = Random(42)

    /** 生成 [w]×[h] 的 RGBA8888 随机像素（含随机 alpha，覆盖透明像素路径）。 */
    private fun randomRgba(w: Int, h: Int): ByteArray =
        ByteArray(w * h * 4).also { rnd.nextBytes(it) }

    /** 把字节灌进一个 direct buffer（native 只接受 direct buffer）。 */
    private fun direct(bytes: ByteArray): ByteBuffer =
        ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.LITTLE_ENDIAN).also {
            it.put(bytes)
            it.rewind()
        }

    private fun ByteBuffer.toBytes(): ByteArray =
        ByteArray(remaining()).also { get(it) }

    // ─────────────────────────── 降采样对拍 ───────────────────────────

    @Test
    fun downscaleNativeMatchesKotlinReference_bitForBit() {
        assumeTrue(
            "native 不可用（预期在 x86 模拟器上），跳过 strict 对拍：" +
                ImagePipelineBridge.describe(),
            ImagePipelineBridge.available,
        )

        // 覆盖：整数倍、非整数倍、放大（某方向取整为 0 需强制取 1）、极端缩小。
        val cases = listOf(
            Triple(8, 8, 3 to 2),
            Triple(64, 64, 16 to 16),
            Triple(100, 60, 37 to 23),
            Triple(5, 1, 2 to 1),
            Triple(3, 3, 7 to 5),
            Triple(1, 1, 1 to 1),
        )
        for ((sw, sh, d) in cases) {
            val (dw, dh) = d
            val src = randomRgba(sw, sh)

            val nativeDst = ByteBuffer.allocateDirect(dw * dh * 4).order(ByteOrder.LITTLE_ENDIAN)
            val rc = ImagePipelineNative.downscaleArea(direct(src), sw, sh, nativeDst, dw, dh)
            assertEquals("native downscaleArea 应成功（${sw}x$sh→${dw}x$dh），rc=$rc", 0, rc)
            nativeDst.rewind()

            val javaDst = ByteArray(dw * dh * 4)
            ImagePipelineBridge.JavaFallback.downscaleArea(src, sw, sh, javaDst, dw, dh)

            val nativeBytes = nativeDst.toBytes()
            // 给出第一处不一致的位置，避免只看到「数组不相等」
            val diff = (nativeBytes.indices).firstOrNull { nativeBytes[it] != javaDst[it] }
            assertEquals(
                "native 与 Kotlin 降采样结果不一致（${sw}x$sh→${dw}x$dh），" +
                    "首个差异字节索引=$diff native=${diff?.let { nativeBytes[it] }} " +
                    "java=${diff?.let { javaDst[it] }}",
                null,
                diff,
            )
        }
    }

    // ─────────────────────────── 主色调对拍 ───────────────────────────

    @Test
    fun dominantColorNativeMatchesKotlinReference() {
        assumeTrue(
            "native 不可用，跳过 strict 对拍：" + ImagePipelineBridge.describe(),
            ImagePipelineBridge.available,
        )

        val cases = listOf(8 to 8, 64 to 64, 100 to 60, 1 to 1)
        for ((w, h) in cases) {
            val src = randomRgba(w, h)
            val native = ImagePipelineNative.dominantColor(direct(src), w, h)
            val java = ImagePipelineBridge.JavaFallback.dominantColor(src, w, h)
            assertEquals(
                "native 与 Kotlin 主色调不一致（${w}x$h）：" +
                    "native=${Integer.toHexString(native)} java=${Integer.toHexString(java)}",
                java,
                native,
            )
        }
    }

    @Test
    fun dominantColorMatchesOnHandcraftedColorSets() {
        assumeTrue(
            "native 不可用，跳过 strict 对拍：" + ImagePipelineBridge.describe(),
            ImagePipelineBridge.available,
        )
        // 手工构造的边界：纯色、灰阶退化、红蓝平票、全透明、含透明混合。
        val sets = listOf(
            "纯红" to solid(255, 0, 0, 255, 100),
            "纯灰" to solid(120, 120, 120, 255, 16),
            "红蓝平票" to (solid(255, 0, 0, 255, 50) + solid(0, 0, 255, 255, 50)),
            "全透明" to solid(0, 0, 0, 0, 16),
            "透明+红" to (solid(255, 0, 0, 255, 99) + solid(0, 0, 0, 0, 1)),
            "绿色" to solid(0, 255, 0, 255, 64),
        )
        for ((name, src) in sets) {
            val count = src.size / 4
            val native = ImagePipelineNative.dominantColor(direct(src), count, 1)
            val java = ImagePipelineBridge.JavaFallback.dominantColor(src, count, 1)
            assertEquals("$name 对拍不一致：native=$native java=$java", java, native)
        }
    }

    // ─────────────────── 真实 Bitmap 路径（ByteBuffer 读写正确性）───────────────────

    @Test
    fun realBitmapPathAgreesBetweenNativeAndJava() {
        // 这条覆盖的是「ByteBuffer 与 Bitmap 之间的读写」：前面几条对拍直接用
        // ByteArray，绕过了 copyPixelsToBuffer/FromBuffer。若通道顺序或跨度处理
        // 有问题，只在真实 Bitmap 路径上暴露。
        val srcW = 16
        val srcH = 16
        val bmp = Bitmap.createBitmap(srcW, srcH, Bitmap.Config.ARGB_8888).apply {
            // 左红右蓝的渐变块：R/B 互换一定会被抓到。
            for (y in 0 until srcH) {
                for (x in 0 until srcW) {
                    setPixel(x, y, if (x < srcW / 2) Color.rgb(200, 20, 30) else Color.rgb(30, 20, 200))
                }
            }
        }

        // 无论 native 是否可用，两条显式路径都必须给出相同结果。
        val nativeFirst = ImagePipelineBridge.downscaleOutcome(bmp, 4, 4, preferNative = true)
        val javaPath = ImagePipelineBridge.downscaleOutcome(bmp, 4, 4, preferNative = false)

        // 当 native 可用时，preferNative=true 必须真的走 native（防止「以为在测 Rust
        // 其实在测回退」这种基准事故）。
        if (ImagePipelineBridge.available) {
            assertTrue("native 可用时应实走 Rust 路径", nativeFirst.usedNative)
        }
        assertTrue("Java 路径必须走回退", !javaPath.usedNative)

        val a = nativeFirst.bitmap
        val b = javaPath.bitmap
        assertNotNull("降采样应产出位图", a)
        assertNotNull("降采样应产出位图", b)
        assertEquals("输出尺寸", b!!.width, a!!.width)

        // 逐像素比对两张结果位图（走各自 Bitmap 读出，等价于逐字节比对 RGBA）。
        val ab = IntArray(a.width * a.height)
        val bb = IntArray(b.width * b.height)
        a.getPixels(ab, 0, a.width, 0, 0, a.width, a.height)
        b.getPixels(bb, 0, b.width, 0, 0, b.width, b.height)
        assertArrayEqualsMsg("真实 Bitmap 降采样路径不一致", bb, ab)

        // 主色调同样比对两条路径。
        val dn = ImagePipelineBridge.dominantColorOutcome(bmp, preferNative = true)
        val dj = ImagePipelineBridge.dominantColorOutcome(bmp, preferNative = false)
        if (ImagePipelineBridge.available) {
            assertTrue("native 可用时 dominantColor 应实走 Rust", dn.usedNative)
        }
        assertTrue(!dj.usedNative)
        assertEquals(
            "真实 Bitmap 主色调路径不一致：native=${dn.color} java=${dj.color}",
            dj.color,
            dn.color,
        )
        // 左半 (200,20,30) 的色相≈357°→桶 11，右半 (30,20,200) 的色相≈243°→桶 8；
        // 两者权重（delta）相等时取较小桶下标 → 桶 8（蓝）胜出，红分量小。
        // 这里只断言「结果落在调色板桶心颜色上、且两条路径一致」，不硬编码具体值，
        // 避免把手算色相当成测试基准（那本身就是一种容易抄错的假设）。
        assertTrue("主色调必须非负（合法颜色）", dj.color >= 0)
        assertTrue(
            "平票应取较小桶的蓝而非红：color=${Integer.toHexString(dj.color)}",
            ((dj.color and 0xFF)) > ((dj.color shr 16) and 0xFF),
        )
    }

    @Test
    fun nativeReportsFailureOnBadBufferWithNegativeCode() {
        assumeTrue("native 不可用，跳过", ImagePipelineBridge.available)
        // 缓冲过小：native 必须返回负数（而不是 0 —— 0 是合法的 downscale 成功码）。
        val tiny = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
        val dst = ByteBuffer.allocateDirect(4).order(ByteOrder.LITTLE_ENDIAN)
        val rc = ImagePipelineNative.downscaleArea(tiny, 4, 4, dst, 1, 1)
        assertTrue("缓冲不足应返回负错误码，实际 rc=$rc", rc < 0)
        // dominantColor 失败同样返回负数；成功值 0x000000（黑）是合法的，不能用 0 判失败。
        val dc = ImagePipelineNative.dominantColor(tiny, 4, 4)
        assertTrue("缓冲不足应返回负错误码，实际 dc=$dc", dc < 0)
    }

    private fun solid(r: Int, g: Int, b: Int, a: Int, n: Int): ByteArray {
        val out = ByteArray(n * 4)
        for (i in 0 until n) {
            out[i * 4] = r.toByte(); out[i * 4 + 1] = g.toByte()
            out[i * 4 + 2] = b.toByte(); out[i * 4 + 3] = a.toByte()
        }
        return out
    }

    private fun assertArrayEqualsMsg(msg: String, expected: IntArray, actual: IntArray) {
        val diff = expected.indices.firstOrNull { expected[it] != actual[it] }
        assertEquals(
            "$msg，首个差异像素=$diff expected=${diff?.let { Integer.toHexString(expected[it]) }} " +
                "actual=${diff?.let { Integer.toHexString(actual[it]) }}",
            null, diff,
        )
    }
}
