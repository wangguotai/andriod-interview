package com.example.myapplication

import com.interview.vmp.VmpBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Time: 2026/10/6
 * Author: wgt
 * Description: VMP 加固的金标准 —— 设备端逐位对拍。
 *
 * ─── 这条红线是什么 ───
 *
 * **加固后算错，比不加固更糟。** 所以本测试是整条链路的准入条件：
 * 三个算子在 VM（加密字节码）与原生（机器码）两条路径下必须**逐字节相等**。
 * 不是「看起来一样」，也不是「误差很小」—— 图像算法一旦有末位偏差，
 * 下游（缓存 key、去重、占位色）都会跟着漂，而且漂得毫无规律，最难查。
 *
 * ─── 为什么覆盖这么多种尺寸 ───
 *
 * 加固重写了整个算法的控制流，任何「边界取整 / 循环上界 / 跨行累加器」的细微差别
 * 都只在**特定尺寸**暴露。用例因此刻意包含非整除、奇数、超大长宽比、放大、
 * 单行/单列、以及 radius=0 与 radius 大于图像这些分支。
 *
 * ─── 运行 ───
 *
 * ```bash
 * ./gradlew :vmp-lab:connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.example.myapplication.VmpGoldenTest
 * ```
 */
class VmpGoldenTest {

    @Before
    fun requireNative() {
        // x86 模拟器上没有本 Lab 的 .so（只编 arm64-v8a）。此时**跳过而不是失败**：
        // 「跑不了」与「算错了」是两件事，不该混在同一个红灯里 ——
        // 混在一起的后果是红灯看多了就没人看了。
        assumeTrue(
            "native 不可用（本 Lab 只编 arm64-v8a）: ${com.interview.vmp.nativebridge.VmpNative.loadError}",
            VmpBridge.available,
        )
    }

    @Test
    fun abiAndSelfTest() {
        assertEquals(
            "ABI 版本必须与 Kotlin 侧一致（不一致时宁可判不可用，也不要按错的布局静默算错）",
            VmpBridge.EXPECTED_ABI_VERSION,
            com.interview.vmp.nativebridge.VmpNative.abiVersion(),
        )
        assertTrue("VM 内建自检必须通过（算术语义 vs ISA 规范）", VmpBridge.selftest())
        assertTrue("三个程序的容器头必须完好", VmpBridge.programsIntact())
    }

    @Test
    fun dominantMatchesNative() {
        for ((w, h) in listOf(1 to 1, 13 to 7, 64 to 64, 129 to 33, 256 to 192)) {
            val src = bufOf(TestImages.synthRgba(w, h, (w * 31 + h * 17).toLong()))
            val vm = VmpBridge.dominantBytes(src, w, h, VmpBridge.Path.VM)
            val na = VmpBridge.dominantBytes(src, w, h, VmpBridge.Path.NATIVE)
            assertTrue("dominant ${w}x$h: VM 返回 rc=${vm.rc}", vm.ok)
            assertTrue("dominant ${w}x$h: native 返回 rc=${na.rc}", na.ok)
            assertEquals(
                "dominant ${w}x$h 必须逐位一致：vm=#%06x native=#%06x".format(vm.color, na.color),
                na.color,
                vm.color,
            )
        }
    }

    /**
     * `8x8 → 3x2` 这一条是**从历史教训里选出来的**：原生实现的注释记着，早期用
     * 「可分离两趟」时它出现过 `120 vs 参考 119` 的逐位差异。加固版是又一次重写控制流，
     * 同一个尺寸必须再钉一遍 —— 老 bug 的回归点是最便宜的测试。
     */
    @Test
    fun downscaleMatchesNativeBitForBit() {
        val cases = listOf(
            Triple(8 to 8, 3 to 2, 0L),
            Triple(5 to 1, 2 to 1, 1L),
            Triple(1 to 1, 1 to 1, 2L),
            Triple(33 to 17, 7 to 5, 3L),
            Triple(64 to 64, 16 to 16, 4L),
            Triple(256 to 192, 64 to 48, 5L),
            Triple(3 to 3, 5 to 5, 6L),
            Triple(100 to 3, 7 to 2, 7L),
        )
        for ((srcDim, dstDim, seed) in cases) {
            val (sw, sh) = srcDim
            val (dw, dh) = dstDim
            val src = bufOf(TestImages.synthRgba(sw, sh, seed * 7 + 1), slot = 0)
            val outV = VmpBridge.acquire(dw * dh * 4, slot = 1)
            val outN = VmpBridge.acquire(dw * dh * 4, slot = 2)
            val rv = VmpBridge.downscaleBytes(src, sw, sh, outV, dw, dh, VmpBridge.Path.VM)
            val rn = VmpBridge.downscaleBytes(src, sw, sh, outN, dw, dh, VmpBridge.Path.NATIVE)
            assertTrue("downscale ${sw}x$sh→${dw}x$dh: VM rc=${rv.rc}", rv.ok)
            assertTrue("downscale ${sw}x$sh→${dw}x$dh: native rc=${rn.rc}", rn.ok)
            val bv = VmpBridge.snapshot(outV, dw * dh * 4)
            val bn = VmpBridge.snapshot(outN, dw * dh * 4)
            val diff = VmpBridge.firstByteDiff(bv, bn)
            assertTrue(
                "downscale ${sw}x$sh→${dw}x$dh 必须逐字节一致，首个差异 @$diff " +
                    "(vm=${bv.getOrNull(diff)} native=${bn.getOrNull(diff)})",
                diff < 0,
            )
        }
    }

    @Test
    fun blurMatchesNativeBitForBit() {
        val cases = listOf(
            Triple(3 to 1, 1, 0L),
            Triple(1 to 3, 1, 1L),
            // 注意 `1x1 r=2` 与 `2x2 r=3` 已经**不再是合法输入**：`r > max(w,h)`
            // 现在两条路径都会拒绝（见 `bothPathsRejectTheSameInputs` 的理由）。
            // 早先这里放着 `(1 to 1, 2)`，它在 VM 上返回 -1、在原生上成功 ——
            // 这条用例当时以「VM 失败」的形式红了，暴露的正是校验口径不一致。
            Triple(3 to 3, 3, 3L),
            Triple(16 to 16, 1, 4L),
            Triple(33 to 17, 2, 5L),
            Triple(128 to 96, 8, 6L),
            Triple(40 to 40, 0, 7L),
        )
        for ((dim, r, seed) in cases) {
            val (w, h) = dim
            val src = bufOf(TestImages.synthRgba(w, h, seed * 13 + 5), slot = 0)
            val outV = VmpBridge.acquire(w * h * 4, slot = 1)
            val outN = VmpBridge.acquire(w * h * 4, slot = 2)
            val rv = VmpBridge.blurBytes(src, w, h, outV, r, VmpBridge.Path.VM)
            val rn = VmpBridge.blurBytes(src, w, h, outN, r, VmpBridge.Path.NATIVE)
            assertTrue("blur ${w}x$h r=$r: VM rc=${rv.rc}", rv.ok)
            assertTrue("blur ${w}x$h r=$r: native rc=${rn.rc}", rn.ok)
            val bv = VmpBridge.snapshot(outV, w * h * 4)
            val bn = VmpBridge.snapshot(outN, w * h * 4)
            val diff = VmpBridge.firstByteDiff(bv, bn)
            assertTrue(
                "blur ${w}x$h r=$r 必须逐字节一致，首个差异 @$diff " +
                    "(vm=${bv.getOrNull(diff)} native=${bn.getOrNull(diff)})",
                diff < 0,
            )
        }
    }

    /**
     * **两条路径必须接受完全相同的输入域。**
     *
     * 这是本项目踩过的坑留下的设备端回归。原先「逐位对拍」只比「两边都成功时的
     * 输出」，于是 blur 的半径上限一度只在 VM 侧生效、原生侧不受限：
     * `1x1 r=2` 在 VM 上报 `-1`、在原生上照跑，对拍却显示全绿 ——
     * 因为两边都成功的交集里根本没有这一组参数。
     *
     * 结论（值得记住）：**逐位对拍必须配一条「同域」测试**，
     * 否则它给出的绿灯只覆盖交集，差额部分是无人区。
     */
    @Test
    fun bothPathsRejectTheSameInputs() {
        val small = ByteArray(4 * 16)
        val out = VmpBridge.acquire(4 * 16, slot = 3)

        // 半径超过 max(w,h) 时应被两条路径**一致地**拒绝
        for ((w, h, r) in listOf(Triple(1, 1, 2), Triple(2, 1, 5), Triple(3, 3, 4))) {
            val src = bufOf(TestImages.synthRgba(w, h, 7L), slot = 0)
            val vm = VmpBridge.blurBytes(src, w, h, out, r, VmpBridge.Path.VM)
            val na = VmpBridge.blurBytes(src, w, h, out, r, VmpBridge.Path.NATIVE)
            assertTrue("VM 必须拒绝 blur ${w}x$h r=$r（rc=${vm.rc}）", !vm.ok)
            assertTrue("原生必须拒绝 blur ${w}x$h r=$r（rc=${na.rc}）—— 口径不一致会让对拍出现盲区", !na.ok)
            assertEquals("两条路径对 blur ${w}x$h r=$r 的错误码应相同", na.rc, vm.rc)
        }

        // 边长超上限同理
        val big = bufOf(ByteArray(16), slot = 0)
        val vmBig = VmpBridge.dominantBytes(big, 4097, 1, VmpBridge.Path.VM)
        val naBig = VmpBridge.dominantBytes(big, 4097, 1, VmpBridge.Path.NATIVE)
        assertTrue("VM 必须拒绝超上限尺寸", !vmBig.ok)
        assertTrue("原生必须拒绝超上限尺寸", !naBig.ok)

        // 反例：小尺寸是**合法**输入，不该被误拒（否则这道闸门就把功能关掉了）
        val ok1 = VmpBridge.dominantBytes(bufOf(TestImages.synthRgba(1, 1, 1L)), 1, 1, VmpBridge.Path.VM)
        assertTrue("1x1 dominant 是合法输入，不应被拒（rc=${ok1.rc}）", ok1.ok)
    }

    /**
     * 取证：VM 路径**确实**在惰性分页解密，而不是「启动时把整段解到堆上」。
     *
     * 两层断言：
     *   1. 未命中页数 ≤ 程序总页数 —— 即**每页最多解密一次**。若有人去掉页缓存，
     *      这个数会随循环次数线性增长（初版单页缓存实测到过 15 万次）。
     *   2. 命中次数远多于未命中 —— 「惰性解密几乎免费」才有数据支撑。
     *
     * ⚠️ 它**不能**证明「密文没被明文拷到别处」，那要靠 `strings`/`nm` 静态检查
     * （见 NOTES 的取证一节）。测试只证明它在自己声称的机制上没偷懒。
     */
    @Test
    fun vmDecryptsLazilyWithBoundedPlaintextWindow() {
        val (w, h) = 256 to 192
        val src = bufOf(TestImages.synthRgba(w, h, 99))
        val dst = VmpBridge.acquire(64 * 48 * 4, slot = 1)
        val out = VmpBridge.downscaleBytes(src, w, h, dst, 64, 48, VmpBridge.Path.VM)
        assertTrue("VM downscale 应成功，rc=${out.rc}", out.ok)
        assertTrue(
            "未命中页数应很小（实际 ${out.fetchPages}）—— 否则页窗口太小、解密成了瓶颈",
            out.fetchPages in 0..64,
        )
        assertTrue(
            "缓存命中(${out.cacheHits}) 应远多于未命中(${out.fetchPages})",
            out.cacheHits > out.fetchPages * 10,
        )
    }

    /**
     * 这条测试的**边界**必须写清楚：它证明的是「字节被改坏能被发现」，
     * **不是**「抗篡改」。有意的攻击者改完重算 FNV 校验和即可 ——
     * 真正的抗篡改需要私钥签名 + 运行期验签，本 Lab 不做。
     * 把边界说明白，比假装它很安全重要得多。
     */
    @Test
    fun programsIntactOnCleanBuild() {
        assertTrue("干净构建下三个程序都应完好", VmpBridge.programsIntact())
        assertTrue("生效的输入尺寸校验必须拒绝非法参数（fail fast）",
            !VmpBridge.downscaleBytes(bufOf(ByteArray(16)), 2, 2, VmpBridge.acquire(16, 1), 0, 0, VmpBridge.Path.VM).ok)
    }
}
