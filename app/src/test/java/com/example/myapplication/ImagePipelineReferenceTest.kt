package com.example.myapplication

import com.interview.image.nativebridge.ImagePipelineBridge
import com.interview.image.nativebridge.ImagePipelineReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Time: 2026/10/4
 * Author: wgt
 * Description: M2 —— 纯 Kotlin 参考实现的 JVM 单元测试（**不需要设备**）。
 *
 * ─── 这个测试的定位 ───
 *
 * 设备端的金标准对拍（[ImagePipelineGoldenTest]）验证的是「Kotlin 与 Rust 一致」，
 * 它无法回答另一个问题：**参考实现本身的算法对不对**。如果 Kotlin 和 Rust 犯了
 * 同一个错误（比如边界公式一起抄错），对拍会「一致地错」、测试全绿，但金标准是假的。
 * 所以这里用独立的、由算法定义直接推出的**已知答案**把参考实现钉死：
 * 工作区里先有一把可信的尺子，跨语言对拍才有意义。
 *
 * 因此本测试**只调用不触及 `android.graphics.Bitmap` 的纯函数**
 * （[ImagePipelineBridge.JavaFallback] / [ImagePipelineReference]）。在 JVM 上
 * `Bitmap` 是只会抛异常的 stub，任何间接引用都会让整个类加载失败。
 * 这也正是算法被抽到 [ImagePipelineReference] 的原因。
 */
class ImagePipelineReferenceTest {

    /** 按 RGBA 字节序拼一个像素。 */
    private fun px(r: Int, g: Int, b: Int, a: Int = 255): ByteArray =
        byteArrayOf(r.toByte(), g.toByte(), b.toByte(), a.toByte())

    private fun img(vararg pixels: ByteArray): ByteArray {
        val out = ByteArray(pixels.size * 4)
        pixels.forEachIndexed { i, p -> p.copyInto(out, i * 4) }
        return out
    }

    // ─────────────────────────── 降采样 ───────────────────────────

    @Test
    fun downscale2x2to1x1AveragesAllFourPixels() {
        // (0 + 100 + 200 + 40)/4 = 85；四像素恰好全被覆盖，无重复无遗漏
        val src = img(px(0, 0, 0), px(100, 100, 100), px(200, 200, 200), px(40, 40, 40))
        val dst = ByteArray(4)
        ImagePipelineBridge.JavaFallback.downscaleArea(src, 2, 2, dst, 1, 1)
        assertEquals("R 均值", 85, dst[0].toInt() and 0xFF)
        assertEquals("G 均值", 85, dst[1].toInt() and 0xFF)
        assertEquals("B 均值", 85, dst[2].toInt() and 0xFF)
        assertEquals("A 均值", 255, dst[3].toInt() and 0xFF)
    }

    @Test
    fun downscaleRoundsHalfUp_notTruncate() {
        // 两个像素 0 与 1 → 均值 0.5。截断会得 0，四舍五入应得 1。
        // alpha 也用两个不同值 (0,2)/2=1 验证 alpha 走同一条取整路径。
        val src = img(px(0, 0, 0, 0), px(1, 1, 1, 2))
        val dst = ByteArray(4)
        ImagePipelineBridge.JavaFallback.downscaleArea(src, 2, 1, dst, 1, 1)
        assertEquals("R 应四舍五入为 1", 1, dst[0].toInt() and 0xFF)
        assertEquals("alpha 同样四舍五入", 1, dst[3].toInt() and 0xFF)
    }

    @Test
    fun downscale5x1to2x1CoversEverySourcePixelExactlyOnce() {
        // 边界应为 [0,2) 与 [2,5)：左块 {0,10}→5，右块 {20,30,40}→30。
        // 这条专门盯整数除法边界——多/漏一个像素都会改变均值。
        val src = img(
            px(0, 0, 0), px(10, 0, 0), px(20, 0, 0), px(30, 0, 0), px(40, 0, 0),
        )
        val dst = ByteArray(8)
        ImagePipelineBridge.JavaFallback.downscaleArea(src, 5, 1, dst, 2, 1)
        assertEquals("左块均值", 5, dst[0].toInt() and 0xFF)
        assertEquals("右块均值", 30, dst[4].toInt() and 0xFF)
    }

    @Test
    fun downscaleRejectsIllegalSizes() {
        val src = ByteArray(16)
        val dst = ByteArray(4)
        assertThrows(IllegalArgumentException::class.java) {
            ImagePipelineBridge.JavaFallback.downscaleArea(src, 2, 2, dst, 0, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImagePipelineBridge.JavaFallback.downscaleArea(src, 0, 2, dst, 1, 1)
        }
        // 目标缓冲区不足
        assertThrows(IllegalArgumentException::class.java) {
            ImagePipelineBridge.JavaFallback.downscaleArea(src, 2, 2, ByteArray(2), 1, 1)
        }
        // 合法尺寸不抛
        ImagePipelineBridge.JavaFallback.downscaleArea(src, 2, 2, dst, 1, 1)
    }

    // ─────────────────────────── 主色调 ───────────────────────────

    @Test
    fun dominantOfSolidRedIsPaletteBucketZeroScaledByLuma() {
        val src = img(*(Array(100) { px(255, 0, 0) }))
        val detail = ImagePipelineReference.dominantColorDetail(src, 100, 1)
        assertEquals("纯红应落在第 0 桶", 0, detail.bucket)
        assertEquals("countedPixels 应为全部 100 个不透明有色像素", 100L, detail.countedPixels)
        // 调色板桶心 (255,63,0) × 明度 (255+0+0)/3=85 → (85,21,0) = 0x551500
        assertEquals("调色板桶心 × 明度缩放", 0x551500, detail.rgb)
        assertEquals(
            "对外接口结果应与 detail 一致",
            detail.rgb,
            ImagePipelineBridge.JavaFallback.dominantColor(src, 100, 1),
        )
    }

    @Test
    fun dominantOfPureGrayFallsBackToAverageGray() {
        val src = img(*(Array(16) { px(120, 120, 120) }))
        val color = ImagePipelineBridge.JavaFallback.dominantColor(src, 16, 1)
        assertEquals("R", 120, (color shr 16) and 0xFF)
        assertEquals("G", 120, (color shr 8) and 0xFF)
        assertEquals("B", 120, color and 0xFF)
        assertEquals("纯灰图无有色像素", 0L, ImagePipelineReference.dominantColorDetail(src, 16, 1).weight)
    }

    @Test
    fun dominantIgnoresFullyTransparentPixels() {
        // 99 红 + 1 全透明黑：透明像素 alpha==0 必须直接跳过，
        // 否则 RGB=0 会把平均明度从 85 拉低，红色分量明显变小。
        val src = img(*(Array(99) { px(255, 0, 0) }), px(0, 0, 0, 0))
        val detail = ImagePipelineReference.dominantColorDetail(src, 100, 1)
        assertEquals("全透明像素不计入 countedPixels", 99L, detail.countedPixels)
        assertEquals("红色分量仍应为 85（未被透明像素拉黑）", 85, (detail.rgb shr 16) and 0xFF)
        assertTrue("主色应仍偏红", ((detail.rgb shr 16) and 0xFF) > (detail.rgb and 0xFF))
    }

    @Test
    fun dominantTieTakesSmallerBucket() {
        // 等量红(桶0)与蓝(桶8)：权重相同，必须稳定取下标小者 0。
        val src = img(*(Array(50) { px(255, 0, 0) }), *(Array(50) { px(0, 0, 255) }))
        val detail = ImagePipelineReference.dominantColorDetail(src, 100, 1)
        assertEquals("平票取较小桶", 0, detail.bucket)
        assertEquals("红蓝权重相等", detail.weight, ImagePipelineReference.dominantColorDetail(
            img(*(Array(50) { px(0, 0, 255) }), *(Array(50) { px(255, 0, 0) })), 100, 1,
        ).weight)
    }

    @Test
    fun dominantIsDeterministicAcrossRepeatedRuns() {
        // 固定种子的伪随机图：同输入两次结果必须完全相同（否则对拍毫无意义）。
        val rnd = java.util.Random(42)
        val src = ByteArray(64 * 64 * 4)
        rnd.nextBytes(src)
        val a = ImagePipelineBridge.JavaFallback.dominantColor(src, 64, 64)
        val b = ImagePipelineBridge.JavaFallback.dominantColor(src, 64, 64)
        assertEquals("同输入必须得到同结果", a, b)
        assertTrue("主色调颜色必须非负（0 是合法黑色）", a >= 0)
    }

    @Test
    fun dominantRejectsIllegalSizes() {
        assertThrows(IllegalArgumentException::class.java) {
            ImagePipelineBridge.JavaFallback.dominantColor(ByteArray(16), 0, 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImagePipelineBridge.JavaFallback.dominantColor(ByteArray(16), 1, 0)
        }
        // 缓冲区不足：2x2 需要 16 字节，只给 3 字节
        assertThrows(IllegalArgumentException::class.java) {
            ImagePipelineBridge.JavaFallback.dominantColor(ByteArray(3), 2, 2)
        }
    }
}
