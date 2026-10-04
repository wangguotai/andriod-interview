package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.interview.image.nativebridge.ImagePipelineBridge
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * Time: 2026/10/4
 * Author: wgt
 * Description: M3 —— Rust vs Java 的同机微基准。
 *
 * ─── 这个基准要回答的问题，以及它**不能**回答的问题 ───
 *
 * 能回答：「同一算法、同一输入、同一台机器，两条实现各花多久」。
 * 不能回答：「所以这个页面会变流畅」。后者是 M4（端到端）的事。
 * 把这两件事混在一起，是性能讨论最常见的偷换 —— 微基准赢了不等于用户体验好了。
 *
 * ─── 为什么分成「纯算法」与「端到端」两组数字 ───
 *
 * 这两组测的不是同一件事，必须分开报，否则结论会互相污染：
 *
 * - **纯算法**（[benchPureAlgorithm]）：两边都从同一份 `ByteArray` 出发。
 *   Java 直接算；Rust 需要先把数组拷进 direct buffer（JNI 只接受 direct），
 *   再把结果拷回来，然后再计时。这样对比的才是**算法本身**，与「怎么喂数据」无关。
 * - **端到端**（[benchEndToEnd]）：走 `ImagePipelineBridge` 的真实调用路径。
 *   这里 Java 侧会多一次 direct→ByteArray 的拷贝（因为它拿不到 direct buffer），
 *   所以 Java 的数会明显更差。**这是真实成本，不是造假** —— 但它有一部分是
 *   「我们的 Java 参考实现没有为 direct buffer 优化」造成的，而不是「Java 慢」。
 *   报告里必须写清这一点，否则就是在拿不公平的对比冒充语言差异。
 *
 * ─── 度量方法（为什么不是「跑一次看耗时」）───
 *
 * - **暖机**：前 N 次不计。JIT 需要把热点方法编译成机器码，冷启动的第一次
 *   通常慢一个数量级，把冷数据混进均值会让结论完全失真。
 * - **取中位数而不是均值**：GC 停顿、CPU 调频、后台线程会制造离群点；
 *   均值会被单次 STW 拉偏，中位数不会。
 * - **同时报 min 与 p90**：min 代表「这套代码最好能多快」（噪声最少），
 *   p90 代表「抖动有多大」。只看中位数会漏掉「中位数不错但偶尔卡 10 倍」的实现。
 * - **先跑一轮丢弃**：让缓冲池、Bitmap 分配等一次性开销发生在计时之外。
 *
 * 运行：
 *   ./gradlew :app:connectedDebugAndroidTest -x lint \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.example.myapplication.ImagePipelineBenchmarkTest
 * 结果从 logcat 抓：adb logcat -d | grep IPBench
 */
@RunWith(AndroidJUnit4::class)
class ImagePipelineBenchmarkTest {

    companion object {
        /** 暖机次数：不计入统计。 */
        private const val WARMUP = 30

        /** 正式计时次数。 */
        private const val ITERS = 60

        private const val TAG = "IPBench"
    }

    /** 一次计时统计，单位毫秒。 */
    private data class Stat(
        val label: String,
        val minMs: Double,
        val medianMs: Double,
        val p90Ms: Double,
    ) {
        override fun toString() =
            "%-28s min=%8.3f  median=%8.3f  p90=%8.3f".format(label, minMs, medianMs, p90Ms)
    }

    /** 跑 [warmup] 次暖机 + [iters] 次计时，返回统计。 */
    private inline fun measure(label: String, warmup: Int = WARMUP, iters: Int = ITERS, block: () -> Unit): Stat {
        repeat(warmup) { block() }
        val samples = DoubleArray(iters)
        for (i in 0 until iters) {
            val t0 = System.nanoTime()
            block()
            samples[i] = (System.nanoTime() - t0) / 1_000_000.0
        }
        samples.sort()
        return Stat(
            label = label,
            minMs = samples.first(),
            medianMs = samples[samples.size / 2],
            p90Ms = samples[(samples.size * 9 / 10).coerceAtMost(samples.size - 1)],
        )
    }

    /** 造一张有真实色彩分布的位图（纯随机会退化成噪声图，主色调没意义）。 */
    private fun testBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        val rnd = Random(7)
        for (y in 0 until h) {
            for (x in 0 until w) {
                // 左半暖色、右半冷色 + 轻微噪声：既有确定的主色调，又不是纯色
                val base = if (x < w / 2) 0xFFB04020.toInt() else 0xFF2040B0.toInt()
                val jitter = rnd.nextInt(24) - 12
                val r = ((base shr 16 and 0xFF) + jitter).coerceIn(0, 255)
                val g = ((base shr 8 and 0xFF) + jitter).coerceIn(0, 255)
                val b = ((base and 0xFF) + jitter).coerceIn(0, 255)
                px[y * w + x] = Color.argb(255, r, g, b)
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    // ───────────────────────── 纯算法对比 ─────────────────────────

    @Test
    fun benchPureAlgorithm() {
        assumeTrue("native 不可用，跳过基准：" + ImagePipelineBridge.describe(), ImagePipelineBridge.available)

        println("$TAG === 纯算法：降采样（同起点 ByteArray，Rust 含 direct 拷贝） ===")
        val cases = listOf(
            Triple(512, 384, 128 to 96),
            Triple(1024, 768, 256 to 192),
            Triple(2048, 1536, 512 to 384),
            Triple(16, 16, 4 to 4),          // 小图：探测 JNI 固定开销的 crossover
        )
        for ((sw, sh, d) in cases) {
            val (dw, dh) = d
            val src = ByteArray(sw * sh * 4).also { Random(3).nextBytes(it) }

            // Java：直接算
            val javaDst = ByteArray(dw * dh * 4)
            val javaStat = measure("java  downscale ${sw}x$sh→${dw}x$dh") {
                ImagePipelineBridge.JavaFallback.downscaleArea(src, sw, sh, javaDst, dw, dh)
            }

            // Rust：数组→direct →JNI→结果 direct→数组，**拷贝在计时内**（这是使用它的真实代价）
            val srcBuf = ImagePipelineBridge.allocateRgbaBuffer(sw * sh)
            val nativeDst = ImagePipelineBridge.allocateRgbaBuffer(dw * dh)
            val outArray = ByteArray(dw * dh * 4)
            val rustStat = measure("rust  downscale ${sw}x$sh→${dw}x$dh") {
                srcBuf.clear(); srcBuf.put(src); srcBuf.rewind()
                nativeDst.clear()
                com.interview.image.nativebridge.ImagePipelineNative
                    .downscaleArea(srcBuf, sw, sh, nativeDst, dw, dh)
                nativeDst.rewind(); nativeDst.get(outArray)
            }

            println("$TAG $javaStat")
            println("$TAG $rustStat")
            println(
                "$TAG   → 中位数加速比 = %.2f×（>1 表示 Rust 更快）".format(javaStat.medianMs / rustStat.medianMs)
            )
        }
    }

    @Test
    fun benchPureAlgorithmDominant() {
        assumeTrue("native 不可用，跳过基准：" + ImagePipelineBridge.describe(), ImagePipelineBridge.available)

        println("$TAG === 纯算法：主色调（同起点 ByteArray，Rust 含 direct 拷贝） ===")
        for ((w, h) in listOf(64 to 64, 256 to 256, 512 to 512)) {
            val src = ByteArray(w * h * 4).also { Random(5).nextBytes(it) }

            val javaStat = measure("java  dominant ${w}x$h") {
                ImagePipelineBridge.JavaFallback.dominantColor(src, w, h)
            }
            val srcBuf = ImagePipelineBridge.allocateRgbaBuffer(w * h)
            val rustStat = measure("rust  dominant ${w}x$h") {
                srcBuf.clear(); srcBuf.put(src); srcBuf.rewind()
                com.interview.image.nativebridge.ImagePipelineNative.dominantColor(srcBuf, w, h)
            }
            println("$TAG $javaStat")
            println("$TAG $rustStat")
            println(
                "$TAG   → 中位数加速比 = %.2f×".format(javaStat.medianMs / rustStat.medianMs)
            )
        }
    }

    // ───────────────────────── 端到端（走 bridge 真实路径）─────────────────────────

    @Test
    fun benchEndToEnd() {
        assumeTrue("native 不可用，跳过基准：" + ImagePipelineBridge.describe(), ImagePipelineBridge.available)

        println("$TAG === 端到端：ImagePipelineBridge（含 Bitmap 读写与 Java 侧 direct→array 拷贝） ===")
        for ((sw, sh, d) in listOf(Triple(1024, 768, 256 to 192), Triple(2048, 1536, 512 to 384))) {
            val (dw, dh) = d
            val bmp = testBitmap(sw, sh)

            // 先确认两条路径确实各走各的（防止「以为在测 Rust 其实在测回退」）
            val warmNative = ImagePipelineBridge.downscaleOutcome(bmp, dw, dh, preferNative = true)
            val warmJava = ImagePipelineBridge.downscaleOutcome(bmp, dw, dh, preferNative = false)
            assertTrue("preferNative=true 时必须实走 Rust", warmNative.usedNative)
            assertTrue("preferNative=false 时必须走 Java 回退", !warmJava.usedNative)

            val javaStat = measure("java  e2e ${sw}x$sh→${dw}x$dh") {
                ImagePipelineBridge.downscaleOutcome(bmp, dw, dh, preferNative = false)
            }
            val rustStat = measure("rust  e2e ${sw}x$sh→${dw}x$dh") {
                ImagePipelineBridge.downscaleOutcome(bmp, dw, dh, preferNative = true)
            }
            println("$TAG $javaStat")
            println("$TAG $rustStat")
            println(
                "$TAG   → 中位数加速比 = %.2f×".format(javaStat.medianMs / rustStat.medianMs)
            )
        }

        val bmp = testBitmap(512, 512)
        val dn = ImagePipelineBridge.dominantColorOutcome(bmp, preferNative = true)
        val dj = ImagePipelineBridge.dominantColorOutcome(bmp, preferNative = false)
        assertTrue("dominantColor 也应实走 Rust", dn.usedNative)
        assertTrue(!dj.usedNative)
        val javaStat = measure("java  e2e dominant 512x512") {
            ImagePipelineBridge.dominantColorOutcome(bmp, preferNative = false)
        }
        val rustStat = measure("rust  e2e dominant 512x512") {
            ImagePipelineBridge.dominantColorOutcome(bmp, preferNative = true)
        }
        println("$TAG $javaStat")
        println("$TAG $rustStat")
        println("$TAG   → 中位数加速比 = %.2f×".format(javaStat.medianMs / rustStat.medianMs))
    }

    /**
     * JNI 固定开销探针：对极小输入反复调用，看「一次 JNI 往返」大约多少钱。
     *
     * 为什么值得单独测：这直接决定「多小的图不值得走 native」。
     * 如果一次调用约 5µs，而小图算法只要 1µs，那 native 就是负收益 ——
     * 这个阈值必须写在结论里，而不是含糊地说「Rust 更快」。
     */
    @Test
    fun benchJniCallOverhead() {
        assumeTrue("native 不可用，跳过：" + ImagePipelineBridge.describe(), ImagePipelineBridge.available)

        println("$TAG === JNI 固定开销（1x1 往返） ===")
        val src = ImagePipelineBridge.allocateRgbaBuffer(1)
        val dst = ImagePipelineBridge.allocateRgbaBuffer(1)
        val stat = measure("jni downscale 1x1→1x1", iters = 200) {
            com.interview.image.nativebridge.ImagePipelineNative.downscaleArea(src, 1, 1, dst, 1, 1)
        }
        println("$TAG $stat")
        println(
            "$TAG   → 单次 JNI 往返中位数 ≈ %.2f µs（≈ %.2f ms / 千次）".format(
                stat.medianMs * 1000, stat.medianMs * 1000,
            )
        )
    }

    /**
     * 把「计算」与「编组」拆开：source buffer 预先填好，计时区间**只有 JNI 调用**，
     * 不含 ByteArray↔direct 的两次拷贝。
     *
     * ─── 为什么必须补这一组 ───
     *
     * [benchPureAlgorithm] 里 Rust 的计时把两次拷贝也算进去了，而 Java 直接从
     * ByteArray 算、没有这一步。实测结果是 Rust 反而更慢（0.68~0.74×）——
     * 如果不把这组拆出来，就会得出「Rust 算得比 Java 慢」的**错误结论**，
     * 而真相是「拷贝吃掉了收益」。这两句话对应的优化方向完全不同：
     *   前者 → 别用 Rust；后者 → 别用 ByteArray 喂数据（改用 direct buffer 或
     *   让上游直接产出 direct buffer）。
     *
     * 所以本组数字回答的是「native 计算本身快不快」；
     * [benchPureAlgorithm] 回答的是「用 ByteArray 喂它、端到端划不划算」。
     * 两者都要报。
     */
    @Test
    fun benchPureComputeWithoutMarshalling() {
        assumeTrue("native 不可用，跳过基准：" + ImagePipelineBridge.describe(), ImagePipelineBridge.available)

        println("$TAG === 只算不拷：source buffer 预先填好，计时仅含 JNI 调用 ===")
        for ((sw, sh, d) in listOf(
            Triple(512, 384, 128 to 96),
            Triple(1024, 768, 256 to 192),
            Triple(2048, 1536, 512 to 384),
        )) {
            val (dw, dh) = d
            val src = ByteArray(sw * sh * 4).also { Random(3).nextBytes(it) }
            val srcBuf = ImagePipelineBridge.allocateRgbaBuffer(sw * sh)
            val dstBuf = ImagePipelineBridge.allocateRgbaBuffer(dw * dh)
            srcBuf.clear(); srcBuf.put(src); srcBuf.rewind()

            val javaDst = ByteArray(dw * dh * 4)
            val javaStat = measure("java  [calc] downscale ${sw}x$sh→${dw}x$dh") {
                ImagePipelineBridge.JavaFallback.downscaleArea(src, sw, sh, javaDst, dw, dh)
            }
            val rustStat = measure("rust  [calc] downscale ${sw}x$sh→${dw}x$dh") {
                com.interview.image.nativebridge.ImagePipelineNative
                    .downscaleArea(srcBuf, sw, sh, dstBuf, dw, dh)
            }
            println("$TAG $javaStat")
            println("$TAG $rustStat")
            println("$TAG   → 纯计算中位数加速比 = %.2f×".format(javaStat.medianMs / rustStat.medianMs))
        }

        for ((w, h) in listOf(64 to 64, 256 to 256, 512 to 512)) {
            val src = ByteArray(w * h * 4).also { Random(5).nextBytes(it) }
            val srcBuf = ImagePipelineBridge.allocateRgbaBuffer(w * h)
            srcBuf.clear(); srcBuf.put(src); srcBuf.rewind()

            val javaStat = measure("java  [calc] dominant ${w}x$h") {
                ImagePipelineBridge.JavaFallback.dominantColor(src, w, h)
            }
            val rustStat = measure("rust  [calc] dominant ${w}x$h") {
                com.interview.image.nativebridge.ImagePipelineNative.dominantColor(srcBuf, w, h)
            }
            println("$TAG $javaStat")
            println("$TAG $rustStat")
            println("$TAG   → 纯计算中位数加速比 = %.2f×".format(javaStat.medianMs / rustStat.medianMs))
        }
    }
}
