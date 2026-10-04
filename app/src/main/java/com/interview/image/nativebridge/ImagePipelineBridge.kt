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

    /**
     * 与 Rust 侧 `imagepipeline_android::ABI_VERSION` 对齐；不匹配说明 APK 里是旧 .so。
     *
     * 变更记录：1 → M1（probeLayout）；2 → M2（新增 downscaleArea / dominantColor）。
     * 升级到 2 的意义：若 APK 里残留只导出 M1 符号的旧 .so，`available` 会因版本
     * 不匹配而判 false，从而**显式降级**，而不是在调用新符号时抛 `UnsatisfiedLinkError`。
     */
    const val EXPECTED_ABI_VERSION = 2

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

        /**
         * 参考实现降采样。这里只做一层转发，算法本体在 [ImagePipelineReference]。
         *
         * 为什么不把算法直接写在这里：这两个方法要同时服务设备端回退与 JVM 单测，
         * 而本 object 所在的 [ImagePipelineBridge] 引用了 `android.graphics.Bitmap`，
         * 在 JVM 单测里加载即失败。转发到纯 Kotlin 文件后，回退路径与对拍金标准
         * 共用同一份代码，永远不会出现「回退实现和对拍实现漂移」。
         */
        fun downscaleArea(
            src: ByteArray,
            srcW: Int,
            srcH: Int,
            dst: ByteArray,
            dstW: Int,
            dstH: Int,
        ) = ImagePipelineReference.downscaleArea(src, srcW, srcH, dst, dstW, dstH)

        /** 参考实现主色调，返回 `0x00RRGGBB`。算法见 [ImagePipelineReference.dominantColor]。 */
        fun dominantColor(src: ByteArray, w: Int, h: Int): Int =
            ImagePipelineReference.dominantColor(src, w, h)
    }

    /**
     * direct buffer 缓冲池。
     *
     * ─── 为什么必须复用，而不是每次 `allocateDirect` ───
     *
     * direct buffer 的内存不在 Java 堆上，分配要走系统调用、回收要依赖 Cleaner
     * 且不确定何时发生。在「每张图都要读一次像素」的路径上，每次 new 一个 direct
     * buffer 会把分配/回收成本摊进耗时 —— M3 的基准结论会因此被污染（对比的变成
     * 了分配器而不是算法）。这里按「字节数」缓存并复用，尺寸不同才扩容。
     *
     * 用 [ThreadLocal] 而不是全局池：池里的 buffer 会被 `copyPixelsToBuffer` 等
     * 原地读写，多线程共享需要加锁；而 JNI 调用天然按线程使用，ThreadLocal 既
     * 免锁又天然隔离。代价是每个线程各留一份（本场景线程数极少，可接受）。
     *
     * 返回的 buffer 已 rewind，capacity 可能大于请求值 —— 调用方仍应按实际
     * 像素数（w*h*4）解读，多出的尾部字节不参与运算。
     */
    object RgbaBufferPool {
        private val local: ThreadLocal<HashMap<Int, ByteBuffer>> =
            ThreadLocal.withInitial { HashMap<Int, ByteBuffer>() }

        /** 取一个 capacity ≥ [byteSize] 的 direct buffer（LITTLE_ENDIAN，位置归零）。 */
        fun acquire(byteSize: Int): ByteBuffer {
            require(byteSize > 0) { "byteSize 必须为正" }
            val map = local.get() ?: HashMap<Int, ByteBuffer>().also { local.set(it) }
            val buf = map[byteSize]
            if (buf != null) {
                buf.clear()
                return buf
            }
            val fresh = ByteBuffer.allocateDirect(byteSize).order(ByteOrder.LITTLE_ENDIAN)
            map[byteSize] = fresh
            return fresh
        }

        /** 释放当前线程持有的所有缓冲（测试收尾/长驻线程退出时调用，非必需）。 */
        fun clear() = local.get()?.clear()
    }

    /** 一次降采样的结果：结果位图 + 实际走的路径，供 M3 做公平对比时区分。 */
    class DownscaleOutcome(val bitmap: Bitmap?, val usedNative: Boolean)

    /** 一次主色调提取的结果：颜色（`0xRRGGBB`）+ 实际走的路径。 */
    class DominantOutcome(val color: Int, val usedNative: Boolean)

    /**
     * 降采样（面向调用方）。[preferNative]=true 时优先走 Rust，[available] 为 false
     * 或 native 返回负数（如缓冲不足）时回退到 [JavaFallback]。返回 null 表示
     * 输入位图/尺寸非法（native 与 Java 都拒绝的情况）。
     *
     * M3 基准需要**强制**只跑一条路径：传 `preferNative=false` 即纯 Java，
     * 传 `true` 在 native 可用时即纯 Rust。返回 [DownscaleOutcome] 的版本可用来
     * 断言「我这次确实跑的是我以为的那条路径」——避免基准测了半天其实是回退。
     */
    fun downscale(
        bitmap: Bitmap,
        dstW: Int,
        dstH: Int,
        preferNative: Boolean = true,
    ): Bitmap? = downscaleOutcome(bitmap, dstW, dstH, preferNative).bitmap

    fun downscaleOutcome(
        bitmap: Bitmap,
        dstW: Int,
        dstH: Int,
        preferNative: Boolean = true,
    ): DownscaleOutcome {
        val srcW = bitmap.width
        val srcH = bitmap.height
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            return DownscaleOutcome(null, usedNative = false)
        }
        val srcBytes = RgbaBufferPool.acquire(srcW * srcH * 4)
        readPixels(bitmap, srcBytes)

        val useNative = preferNative && available
        val outcome = ByteArray(dstW * dstH * 4)
        val ok: Boolean
        if (useNative) {
            // 输入输出用**不同的** direct buffer：Rust 侧假设二者不别名。
            val dstBytes = RgbaBufferPool.acquire(dstW * dstH * 4)
            val rc = ImagePipelineNative.downscaleArea(srcBytes, srcW, srcH, dstBytes, dstW, dstH)
            ok = rc == 0
            if (ok) {
                dstBytes.rewind()
                dstBytes.get(outcome, 0, outcome.size)
            } else {
                Log.w(TAG, "native downscaleArea 返回 $rc，回退 Java 实现")
            }
        } else {
            val srcArray = ByteArray(srcW * srcH * 4).also { srcBytes.rewind(); srcBytes.get(it) }
            ok = runCatching {
                JavaFallback.downscaleArea(srcArray, srcW, srcH, outcome, dstW, dstH)
            }.isSuccess
        }
        if (!ok) return DownscaleOutcome(null, usedNative = false)

        val out = Bitmap.createBitmap(dstW, dstH, Bitmap.Config.ARGB_8888)
        // 用 direct buffer 写回（与读像素同一套约定，避免 ByteArray→IntArray 的通道重排）。
        val writeBuf = RgbaBufferPool.acquire(outcome.size)
        writeBuf.put(outcome)
        writePixels(out, writeBuf)
        return DownscaleOutcome(out, usedNative = useNative)
    }

    /**
     * 主色调（面向调用方）。语义同 [downscale]：native 优先、可强制、失败回退。
     * 失败（尺寸非法）时返回 `-1`（合法颜色恒为非负）。
     */
    fun dominantColor(bitmap: Bitmap, preferNative: Boolean = true): Int =
        dominantColorOutcome(bitmap, preferNative).color

    fun dominantColorOutcome(bitmap: Bitmap, preferNative: Boolean = true): DominantOutcome {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return DominantOutcome(-1, usedNative = false)

        val buf = RgbaBufferPool.acquire(w * h * 4)
        readPixels(bitmap, buf)

        val useNative = preferNative && available
        if (useNative) {
            val rc = ImagePipelineNative.dominantColor(buf, w, h)
            if (rc >= 0) return DominantOutcome(rc, usedNative = true)
            Log.w(TAG, "native dominantColor 返回 $rc，回退 Java 实现")
        }
        val srcArray = ByteArray(w * h * 4).also { buf.rewind(); buf.get(it) }
        val color = runCatching {
            JavaFallback.dominantColor(srcArray, w, h)
        }.getOrElse { -1 }
        return DominantOutcome(color, usedNative = false)
    }
}
