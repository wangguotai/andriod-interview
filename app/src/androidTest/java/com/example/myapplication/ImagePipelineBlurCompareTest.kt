package com.example.myapplication

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.media.ImageReader
import android.graphics.PixelFormat
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.request.RequestOptions
import com.interview.image.nativebridge.ImagePipelineBridge
import com.interview.image.nativebridge.ImagePipelineNative
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random
import kotlin.math.abs

/**
 * Time: 2026/10/4
 * Author: wgt
 * Description: M5 核心产出 —— 模糊的**对标基准**：自己写 CPU 模糊 vs 交给框架。
 *
 * ─── 这个基准要回答的问题 ───
 *
 * 不是「Rust 比 Java 快多少」（那是 M3 的问题），而是：
 *
 *   **什么情况下该自己写 CPU 模糊，什么情况下该交给框架（Skia / Glide）？**
 *
 * 所以本测试有三组对照，各自回答一个子问题：
 *
 * 1. [benchCpuRustVsKotlin]：同起点 `ByteArray`，Rust 直算 vs Kotlin 参考实现。
 *    回答「CPU 侧自己写时，native 值不值」。
 * 2. [benchCpuVsSkiaRenderEffect]：同起点 `Bitmap`，自己写的 CPU 模糊 vs
 *    `RenderEffect`（Skia/GPU）。回答「要不要自己写」。
 * 3. [glideBitmapTransformationIsAlsoCpu]：说明 Glide 的 `BitmapTransformation`
 *    与我们的 CPU 路径**同类**（都跑在 Glide 的解码线程池上、走 CPU），不是 GPU。
 *
 * ─── 关于 RenderEffect 的度量纪律（非常重要，别误读数字）───
 *
 * `RenderEffect` 的执行发生在 **RenderThread / GPU**。用 `System.nanoTime()` 围着
 * 一次 `Canvas.drawBitmap` 只能测到**主线程提交绘制命令的时间**，通常极短。
 * **这个数字不代表 GPU 上的实际成本**，绝不能拿它去说「框架快 N 倍」。
 * 本测试同时报两件事：
 *   - CPU 侧提交耗时（可比、但只反映提交）；
 *   - 一条和 CPU 模糊结果的**相似度**，用来证明 RenderEffect 路径**真的产生了模糊**
 *     （而不是画了个原图被当成「框架很快」）。
 *
 * ⚠️ 还要说清：CPU 盒式模糊与 Skia 高斯模糊**不是同一种算法**，两者的输出
 * 逐像素不相等（本测试只做相似度比较，绝不断言相等），耗时因此**不可直接比较优劣**。
 *
 * 运行：
 *   ANDROID_SERIAL=<serial> ./gradlew :app:connectedDebugAndroidTest -x lint \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.example.myapplication.ImagePipelineBlurCompareTest
 * 结果：adb logcat -d | grep -E 'IPBlur'
 */
@RunWith(AndroidJUnit4::class)
class ImagePipelineBlurCompareTest {

    companion object {
        private const val WARMUP = 30
        private const val ITERS = 60
        private const val TAG = "IPBlur"
    }

    /** 一次计时统计，单位毫秒。手法与 [ImagePipelineBenchmarkTest] 保持一致。 */
    private data class Stat(
        val label: String,
        val minMs: Double,
        val medianMs: Double,
        val p90Ms: Double,
    ) {
        override fun toString() =
            "%-34s min=%8.3f  median=%8.3f  p90=%8.3f".format(label, minMs, medianMs, p90Ms)
    }

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

    /**
     * 造一张**高频**位图：模糊只有在有高频内容时才看得出差别，
     * 纯色/渐变图模糊前后几乎一样，相似度校验会失去意义。
     * 用固定种子棋盘 + 噪声，保证可复现。
     */
    private fun testBitmap(w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val px = IntArray(w * h)
        val rnd = Random(11)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val base = if ((x / 4 + y / 4) % 2 == 0) 0xFFE0A020.toInt() else 0xFF2040C0.toInt()
                val j = rnd.nextInt(40) - 20
                val r = ((base shr 16 and 0xFF) + j).coerceIn(0, 255)
                val g = ((base shr 8 and 0xFF) + j).coerceIn(0, 255)
                val b = ((base and 0xFF) + j).coerceIn(0, 255)
                px[y * w + x] = Color.argb(255, r, g, b)
            }
        }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun rgbaOf(bitmap: Bitmap): ByteArray {
        val buf = ByteBuffer.allocateDirect(bitmap.width * bitmap.height * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        bitmap.copyPixelsToBuffer(buf)
        val out = ByteArray(buf.capacity())
        buf.rewind(); buf.get(out)
        return out
    }

    /** 两图 RGB 三通道平均绝对差（0..255）；忽略 alpha。越小越像。 */
    private fun maeRgb(a: ByteArray, b: ByteArray): Double {
        require(a.size == b.size)
        var sum = 0L
        var n = 0
        var i = 0
        while (i < a.size) {
            sum += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)).toLong()
            sum += abs((a[i + 1].toInt() and 0xFF) - (b[i + 1].toInt() and 0xFF)).toLong()
            sum += abs((a[i + 2].toInt() and 0xFF) - (b[i + 2].toInt() and 0xFF)).toLong()
            n += 3
            i += 4
        }
        return sum.toDouble() / n
    }

    /**
     * 逐像素 RGB 的平均水平梯度（相邻像素差绝对值）。
     * 这是个**与模糊核无关**的高频能量指标：任何真正的模糊都会把它拉低，
     * 而「原图直接抄一遍」或「全黑」都不会像模糊那样平滑。
     * 用它自证 RenderEffect 生效，比「和 CPU 盒式比差异」更可靠 ——
     * 盒式与高斯本就不是同一算法，两者差异大并不代表某方没生效。
     */
    private fun gradRgb(a: ByteArray, w: Int): Double {
        var sum = 0L
        var n = 0
        for (y in 0 until a.size / 4 / w) {
            for (x in 1 until w) {
                val i = (y * w + x) * 4
                val j = i - 4
                sum += abs((a[i].toInt() and 0xFF) - (a[j].toInt() and 0xFF)).toLong()
                sum += abs((a[i + 1].toInt() and 0xFF) - (a[j + 1].toInt() and 0xFF)).toLong()
                sum += abs((a[i + 2].toInt() and 0xFF) - (a[j + 2].toInt() and 0xFF)).toLong()
                n += 3
            }
        }
        return sum.toDouble() / n
    }

    // ─────────────────────────────────────────────────────────────────────
    // 第 1 组：纯 CPU —— Rust vs Kotlin 参考实现
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 同起点 `ByteArray`，两条 CPU 实现各跑两组数字：
     *  - `[calc]`：source buffer 预先灌好，计时区间**只有 JNI 调用**（Rust 不含拷贝）；
     *  - `[copy]`：计时区间含 `ByteArray ↔ direct` 的两次拷贝（用 ByteArray 喂 native 的真实代价）。
     * Kotlin 参考实现直接从 ByteArray 算，天然没有拷贝问题。
     */
    @Test
    fun benchCpuRustVsKotlin() {
        assumeTrue("native 不可用，跳过：" + ImagePipelineBridge.describe(), ImagePipelineBridge.available)

        println("$TAG === 第1组 CPU：Rust vs Kotlin 盒式模糊（同起点 ByteArray）===")
        for ((w, h) in listOf(256 to 256, 512 to 512, 1024 to 768)) {
            val radius = 6
            val bytes = w * h * 4
            val src = ByteArray(bytes).also { Random(9).nextBytes(it) }
            val javaDst = ByteArray(bytes)

            val javaStat = measure("java   blur ${w}x$h r=$radius") {
                ImagePipelineBridge.JavaFallback.blurBox(src, w, h, javaDst, radius)
            }

            // Rust 纯计算：buffer 预填，不计拷贝
            val srcBuf = ImagePipelineBridge.allocateRgbaBuffer(w * h)
            val dstBuf = ImagePipelineBridge.allocateRgbaBuffer(w * h)
            srcBuf.clear(); srcBuf.put(src); srcBuf.rewind()
            val rustCalc = measure("rust   blur ${w}x$h r=$radius [calc]") {
                ImagePipelineNative.blurBox(srcBuf, w, h, dstBuf, radius)
            }

            // Rust 含拷贝：模拟用 ByteArray 喂 native 的真实代价
            val rustCopyLoop = measure("rust   blur ${w}x$h r=$radius [copy]") {
                srcBuf.clear(); srcBuf.put(src); srcBuf.rewind()
                dstBuf.clear()
                ImagePipelineNative.blurBox(srcBuf, w, h, dstBuf, radius)
                dstBuf.rewind(); dstBuf.get(javaDst)
            }

            println("$TAG $javaStat")
            println("$TAG $rustCalc")
            println("$TAG $rustCopyLoop")
            println("$TAG   → 纯计算中位数加速比 = %.2f×；含拷贝 = %.2f×".format(
                javaStat.medianMs / rustCalc.medianMs,
                javaStat.medianMs / rustCopyLoop.medianMs,
            ))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 第 2 组：自己写的 CPU 模糊 vs Skia RenderEffect（GPU）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Skia/GPU 模糊路径 —— 把 `RenderEffect` 真正跑在 **GPU** 上，并支持一次读回。
     *
     * ─── 为什么不能用「画进 Bitmap 的 Canvas」───
     *
     * 实测踩的坑（真机 Redmi K40 / Android 12）：
     *   `Canvas(Bitmap).drawRenderNode(...)` 直接抛
     *   `IllegalArgumentException: Software rendering doesn't support drawRenderNode`。
     * 因为 `Bitmap` 的 `Canvas` 是**软件**后端，而 RenderEffect 只在硬件管线里生效。
     * 同时 `Paint` 上**根本没有** `setRenderEffect`（`javap android.graphics.Paint` 可证），
     * `Bitmap.createBitmap(..., matrix, ...)` 也不应用 RenderEffect。
     * 也就是说：「随便找个 Canvas 画一下」得到的数字要么崩溃、要么是没生效的假模糊。
     *
     * ─── 可用做法：ImageReader 的硬件 Surface ───
     *
     * 用 `ImageReader`（RGBA_8888）拿到一个 `Surface`，在它的 **硬件 Canvas** 上绘制
     * 录制了 RenderEffect 的 `RenderNode`，再把结果图读回。这条路径真的走了 GPU：
     * `surface.lockHardwareCanvas()` 返回的是硬件画布。
     *
     * ⚠️ 度量纪律：把绘制命令交给 GPU 的「提交耗时」极短；真正的 GPU 成本只在
     * **读回**（强制同步）时才体现。本类同时给出两个数字，绝不混为一谈。
     */
    @RequiresApi(31)
    private class GpuBlur(private val w: Int, private val h: Int) {
        private val reader: ImageReader =
            ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, /* maxImages = */ 3)

        /** 丢弃可能已排队的旧帧（不计时），避免队列满时死等。 */
        fun drain() {
            reader.acquireLatestImage()?.close()
        }

        private fun drawOnce(src: Bitmap, radius: Float) {
            val node = RenderNode("ipblur").apply {
                // 关键：RenderNode 默认没有 bounds，不 setPosition 时 RecordingCanvas
                // 里画的任何东西都不会被 drawRenderNode 落到目标上（实测输出全黑，
                // MAE≈源图均值）。这条坑不设会得到「框架很快但结果是空的」的假象。
                setPosition(0, 0, w, h)
                setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP))
            }
            val rec = node.beginRecording(w, h)
            rec.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            node.endRecording()

            val surface = reader.surface
            val canvas = surface.lockHardwareCanvas()
            canvas.drawRenderNode(node)
            surface.unlockCanvasAndPost(canvas)
        }

        /** 只提交：把绘制命令交给 GPU（不含同步、不含读回）。 */
        fun submitOnly(src: Bitmap, radius: Float) {
            drain()
            drawOnce(src, radius)
        }

        /** 提交 + 读回：读回会强制等待 GPU 完成，更接近端到端（含一次性读回拷贝）。 */
        fun submitAndReadback(src: Bitmap, radius: Float): ByteArray {
            drain()
            drawOnce(src, radius)
            return readback()
        }

        private fun readback(): ByteArray {
            // ImageReader 的产图是异步的：unlockCanvasAndPost 返回时 GPU 可能还没把
            // 结果 buffer 交还给队列，立即 acquireLatestImage 会拿到 null。轮询等待，
            // 超时则如实报错（绝不伪造一张空图当结果）。
            val deadline = System.nanoTime() + 2_000_000_000L
            var image = reader.acquireLatestImage()
            while (image == null && System.nanoTime() < deadline) {
                Thread.sleep(1)
                image = reader.acquireLatestImage()
            }
            if (image == null) error("ImageReader 未产出图像：RenderEffect 路径可能未生效")
            try {
                val plane = image.planes[0]
                val buf = plane.buffer
                val out = ByteArray(w * h * 4)
                // RGBA_8888 的 plane 字节序即 R,G,B,A，与全仓库约定一致。
                var i = 0
                val rowStride = plane.rowStride
                for (y in 0 until h) {
                    buf.position(y * rowStride)
                    buf.get(out, i, w * 4)
                    i += w * 4
                }
                return out
            } finally {
                image.close()
            }
        }

        fun close() = reader.close()
    }

    /**
     * 先自证：RenderEffect 路径在当前设备上**确实产生了模糊**。
     * 判据：skia 输出与「原图」的差异，明显大于它与「CPU 模糊结果」的差异。
     * 若 `setRenderEffect` 在当前后端未生效，输出会≈原图，这条会红 —— 这正是
     * 我们想要的失败方式（否则会把一条没生效的路径当成「框架很快」）。
     */
    @Test
    fun skiaRenderEffectActuallyBlurs() {
        assumeTrue("需要 API 31+ 的 RenderEffect", Build.VERSION.SDK_INT >= 31)

        val src = testBitmap(128, 128)
        val cpu = ImagePipelineBridge.blur(src, radius = 6, preferNative = false)!!
        val gpu = GpuBlur(128, 128)
        try {
            val skiaB = gpu.submitAndReadback(src, 6f)
            val srcB = rgbaOf(src)
            val cpuB = rgbaOf(cpu)

            val maeSrcVsCpu = maeRgb(srcB, cpuB)
            val maeSrcVsSkia = maeRgb(srcB, skiaB)
            val maeCpuVsSkia = maeRgb(cpuB, skiaB)
            val gSrc = gradRgb(srcB, 128)
            val gCpu = gradRgb(cpuB, 128)
            val gSkia = gradRgb(skiaB, 128)

            println(
                (
                    "$TAG [自证] MAE(原图,CPU)=%.2f MAE(原图,Skia)=%.2f MAE(CPU,Skia)=%.2f  |  " +
                        "grad 原图=%.2f CPU=%.2f Skia=%.2f"
                    ).format(maeSrcVsCpu, maeSrcVsSkia, maeCpuVsSkia, gSrc, gCpu, gSkia),
            )
            // RenderEffect 必须真的改变了像素（不是 no-op / 空渲染）
            assertTrue(
                "RenderEffect 似乎没生效：输出与原图几乎相同（MAE=%.2f）".format(maeSrcVsSkia),
                maeSrcVsSkia > 1.0,
            )
            // 真正的模糊一定降低高频能量：GPU 输出梯度应显著低于原图。
            // 这是「RenderEffect 真的模糊了」的核无关证据（盒式/高斯都会被满足）。
            assertTrue(
                "GPU 输出的高频能量没有下降，不像模糊：grad 原图=%.2f Skia=%.2f".format(gSrc, gSkia),
                gSkia < gSrc * 0.8,
            )
            assertTrue(
                "CPU 盒式输出的高频能量没有下降：grad 原图=%.2f CPU=%.2f".format(gSrc, gCpu),
                gCpu < gSrc * 0.8,
            )
            // 但绝不断言逐位相等：盒式 ≠ 高斯，MAE 不为 0 是预期的。
            assertTrue("两种算法不应逐位相同", maeCpuVsSkia > 0.0)
        } finally {
            gpu.close()
        }
    }

    @Test
    fun benchCpuVsSkiaRenderEffect() {
        assumeTrue("需要 API 31+ 的 RenderEffect", Build.VERSION.SDK_INT >= 31)

        println("$TAG === 第2组 CPU(自己写) vs Skia RenderEffect(框架/GPU) ===")
        println("$TAG （skia 两行：submit=仅提交绘制命令；submit+readback=含 GPU 同步与读回拷贝）")
        for ((w, h) in listOf(256 to 256, 512 to 512, 1024 to 768)) {
            val radius = 6
            val src = testBitmap(w, h)

            // CPU：走 bridge 真实路径（含 Bitmap 读像素 + Java 回退算法 + 写回）
            val cpuStat = measure("cpu    blur ${w}x$h r=$radius (java-fallback)") {
                ImagePipelineBridge.blur(src, radius, preferNative = false)
            }
            val cpuNativeStat = if (ImagePipelineBridge.available) {
                measure("cpu    blur ${w}x$h r=$radius (rust-native)") {
                    ImagePipelineBridge.blur(src, radius, preferNative = true)
                }
            } else null

            val gpu = GpuBlur(w, h)
            try {
                val skiaSubmit = measure("skia   submit ${w}x$h r=$radius") {
                    gpu.submitOnly(src, radius.toFloat())
                }
                val skiaRoundTrip = measure("skia   submit+readback ${w}x$h r=$radius") {
                    gpu.submitAndReadback(src, radius.toFloat())
                }
                println("$TAG $cpuStat")
                cpuNativeStat?.let { println("$TAG $it") }
                println("$TAG $skiaSubmit")
                println("$TAG $skiaRoundTrip")
                println(
                    "$TAG   → submit 只反映 CPU 提交命令（GPU 成本未计入）；" +
                        "submit+readback 含 GPU 同步与一次性读回，非纯 GPU 时间；" +
                        "且 Skia 高斯 ≠ CPU 盒式，三者耗时不可直接比较优劣",
                )
            } finally {
                gpu.close()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 第 3 组：与 Glide BitmapTransformation 的关系（说明性）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Glide 的 `BitmapTransformation` 与我们的 CPU 路径**同类**：它由 Glide 的
     * **解码/变换线程池**（默认是 CPU 线程池，最终由 `Downsampler` 等调用）
     * 在 `ResourceDecoder` 阶段执行，跑在 CPU 上、读写 `Bitmap` 像素 —— 不是 GPU。
     *
     * 也就是说：
     *   - 你在 Glide 里挂一个自定义 `BitmapTransformation` 做模糊，本质就是本文
     *     「自己写 CPU 模糊」这条路径，只是线程由 Glide 管、生命周期由 Glide 管；
     *   - 若你的目标是「屏幕上要的模糊」，Glide transformation **不会**把工作交给
     *     GPU，仍是在 CPU 上算完再上传纹理 —— 这时 `RenderEffect` 才是更省的那条路。
     *   - Glide 自带的 `BlurTransformation`（需额外依赖，本仓库未引入）同理，也是 CPU。
     *
     * 本测试只构造一个包住我们 CPU 模糊的 transformation 并断言 RequestOptions
     * 能接受它，**不做真实 Glide load**（那需要 Glide 单例与网络/资源，属于另一层）。
     * 目的是把「API 形状」和「它跑在哪」这件事写成可编译的证据 + 注释。
     */
    @Test
    fun glideBitmapTransformationIsAlsoCpu() {
        val transformation = object : BitmapTransformation() {
            override fun transform(
                pool: BitmapPool,
                toTransform: Bitmap,
                outWidth: Int,
                outHeight: Int,
            ): Bitmap {
                // 复用 Glide 的 BitmapPool 拿目标位图，再走我们的 CPU 模糊。
                // 注意：这里走的是 Java 回退路径 —— Glide 线程池上调用 native 也行，
                // 但本示例保持「与 Glide 默认行为同类」的纯 CPU 语义。
                val out = pool.get(outWidth, outHeight, Bitmap.Config.ARGB_8888)
                val result = ImagePipelineBridge.blur(toTransform, radius = 6, preferNative = false)
                if (result != null) out.setPixelsFrom(result)
                return out
            }

            override fun updateDiskCacheKey(messageDigest: MessageDigest) {
                messageDigest.update("cpu-blur-r6-v1".toByteArray())
            }

            override fun equals(other: Any?): Boolean = other is BitmapTransformation && other.javaClass == javaClass
            override fun hashCode(): Int = javaClass.hashCode()
        }

        val options = RequestOptions.bitmapTransform(transformation)
        assertNotNull("RequestOptions 应接受自定义 BitmapTransformation", options)

        // 真正复用 Glide 的光标是 `options.transformations`；这里只断言构造成功。
        println(
            "$TAG [说明] Glide BitmapTransformation 走 Glide 解码线程池、CPU 计算，" +
                "与本仓库的 CPU 模糊同类；不是 GPU。",
        )
    }

    /** 小工具：把源位图的像素按 RGBA 拷进目标位图（同一尺寸）。 */
    private fun Bitmap.setPixelsFrom(src: Bitmap) {
        val buf = ByteBuffer.allocateDirect(src.width * src.height * 4).order(ByteOrder.LITTLE_ENDIAN)
        src.copyPixelsToBuffer(buf)
        buf.rewind()
        copyPixelsFromBuffer(buf)
    }
}
