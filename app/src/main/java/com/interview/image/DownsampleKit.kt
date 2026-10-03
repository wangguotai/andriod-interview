package com.interview.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Time: 2026/10/3
 * Author: wgt
 * Description: 降采样内核 —— 把「先量尺寸、再算 inSampleSize、最后真解码」这条链
 * 的每一步数字都摊开成证据。纯逻辑，无 Context / 无线程 / 无 UI。
 *
 * ─── 这个类为什么存在 ───
 *
 * 学生记得住「inJustDecodeBounds → 算 inSampleSize → 真解码」这个**顺序**，
 * 但记不住**数字**。而这条链真正的难点全在数字上：3000×4000 缩到 600×800，
 * 为什么算出来的是 4 而不是 5？为什么解出来是 750×1000 而不是 600×800？
 * wrap_content 又为什么会让内存涨十几倍？这些只能靠把中间值逐行打出来才讲得清。
 *
 * 所以这里的公共 API 不只是「能解码」，而是「每个中间量都拿得出来」：
 * [readBounds] 暴露原图尺寸，[calculateInSampleSize] 暴露推导过程，
 * [decode] 返回一个把真实 bitmap 与全部数字打包在一起的 [DecodeResult]。
 *
 * ─── 算法核对结论（源码级，非记忆；出处与分级见文件末尾）───
 *
 * 硬要求：算出的采样结果**不得小于目标尺寸**，否则会糊。做法是对每个维度分别
 * 求 `floor(src / req)`，取两者**较小者**，再向下取整到 2 的幂，且至少为 1。
 *
 * 为什么「取较小者」：sample 越小 → 输出越大。两个维度的 floor 里取小，能保证
 * 采样后在**两个方向**上都 ≥ 目标（`floor(src/req)` 本身的语义就是「最多能缩
 * sample 倍而仍不小于 req」），不会出现某个方向被缩过头而发糊。
 *
 * 为什么「只能是 2 的幂」：解码器的采样是在像素网格上按固定步长抽点实现的
 * （Skia 的 SkSampler、JPEG 的 libjpeg 原生缩放均只支持 2/4/8 这类因子），
 * 官方文档也把 inSampleSize 的最终值定义为「基于 2 的幂，其它值向下取整」。
 * 所以这里自己先取整，不把这一点不确定性留给 framework —— Glide 也是这么做的。
 *
 * ⚠️ 本类是教学复刻，不是 Glide 的通用算法。上面这条简化式对齐的是
 * Glide 默认链路（`DEFAULT = CENTER_OUTSIDE`，以及 FIT_CENTER；三者都是
 * QUALITY 取 min）的行为；Glide 真正的通用算法还多一层「浮点 scale →
 * 反算 out 尺寸 → 再取 min/max → 最后一记 highestOneBit」，
 * 且 MEMORY 模式有一记 <<1 兜底。完整对照见同目录 `NOTES-glide-source.md`。
 *
 * ─── 证据分级（哪些查证过、哪些是推断）───
 *
 * 【已确认·官方文档】Android `BitmapFactory.Options#inSampleSize` 明确写：
 *   "the decoder uses a final value based on powers of 2, any other value
 *    will be rounded down to the nearest power of 2"，且 `<= 1` 一律当作 1。
 *
 * 【已确认·AOSP 源码】`frameworks/base/libs/hwui/jni/BitmapFactory.cpp`
 *   `doDecode()`：JNI 层**自己不做 2 的幂取整**，只把 `sampleSize <= 0` 修正为 1，
 *   随后原样交给 Skia（`codecOptions.fSampleSize = sampleSize` 与
 *   `codec->getSampledDimensions(sampleSize)`）。也就是说「向下取整到 2 的幂」
 *   不是 C++ 层做的。
 *
 * 【已确认·Skia 源码】`src/codec/SkCodecPriv.h` 的 `GetSampledDimension()`：
 *   `return srcDimension / sampleSize;`（整数除法，向下取整），并且
 *   `sampleSize > srcDimension` 时返回 1；`SkSampledCodec` 用它处理 PNG/JPEG/
 *   BMP/ICO/WBMP。JPEG 会先把 4/2（及 8）这类因子交给 libjpeg 原生缩放。
 *   ⇒ 现代 Skia 对**非 2 的幂**其实是按整数除法抽点的，并非统一强转 2 的幂。
 *   这与官方文档的措辞在细节上已经不完全一致。
 *
 * 【已确认·Glide 源码】`Downsampler.calculateScaling()`：
 *   `powerOfTwoSampleSize = Math.max(1, Integer.highestOneBit(scaleFactor));`
 *   —— Glide **从不依赖** framework 的取整，自己先取好 2 的幂；QUALITY 用
 *   `Math.min(widthScale, heightScale)`，MEMORY 用 `Math.max(...)`，MEMORY 下
 *   还有 `if (powerOfTwoSampleSize < 1f/exactScaleFactor) <<1` 的兜底。
 *   各格式像素取整方向也不同（PNG 向下 / JPEG 向上 / WEBP 在 N 前后不同）。
 *
 * 【推断/教学简化】上面那条「逐维 floor → 取 min → 取 2 的幂」的式子，是
 *   把 Glide 默认（QUALITY, min）链路的结果**等价化**后的教学表达，便于学生
 *   手算；它对默认图片链路成立，但不是 Glide 的通用算法（见上）。
 *
 * 【不确定】非 2 的幂在**不同 Android 版本 / 不同格式**上到底如何取整，会随
 *   Skia 版本变化；本类通过「自己先取 2 的幂」绕开了这个不确定性，因此不依赖它。
 */
/**
 * 一次真实解码的完整证据。全部字段都直接可在界面上展示。
 *
 * ⚠️ 刻意放在**顶层**（而不是塞进 [DownsampleKit]）：这样调用方可以
 * `import com.interview.image.DecodeResult` 或直接以 `DecodeResult` 引用，
 * 不必写 `DownsampleKit.DecodeResult` —— 与同包既有调用方保持一致。
 */
data class DecodeResult(
    val bitmap: Bitmap,
    val inSampleSize: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val decodedWidth: Int,
    val decodedHeight: Int,
    /** 真实占用：取 [Bitmap.getByteCount]，不是自己乘出来的估算值 */
    val byteCount: Int,
    val requestedWidth: Int,
    val requestedHeight: Int,
    /** 从读元信息到解码完成的全过程耗时（毫秒） */
    val elapsedMs: Long,
    /** 人类可读的推导过程，逐行，可直接打到界面上 */
    val trace: String,
)

object DownsampleKit {

    // ─────────────────────────────────────────────
    // 结果模型
    // ─────────────────────────────────────────────

    data class Bounds(val width: Int, val height: Int) {
        val isValid: Boolean get() = width > 0 && height > 0
    }

    // ─────────────────────────────────────────────
    // 第 1 步：只读元信息
    // ─────────────────────────────────────────────

    /**
     * 第 1 步：只读图片宽高元信息。
     *
     * ─── 为什么必须先来这么一趟 ───
     *
     * 因为 **Binder/Java 层在解码前根本不知道图有多大**。要算 inSampleSize 就得先有
     * 原图尺寸，而如果直接真解码，像素内存已经按原图分配下去了 —— 那时再算就晚了。
     * `inJustDecodeBounds = true` 让解码器只读文件头里的宽高，**完全不分配像素内存**
     * （BitmapFactory 在这种情况下返回 null，只回填 outWidth/outHeight），
     * 于是可以「零像素代价」拿到尺寸，再决定缩多少。
     *
     * ─── 返回值约定 ───
     *
     * · 文件不存在 / 不是普通文件 → 返回 **null**，不抛异常（调用方好判断）。
     * · `outWidth` / `outHeight` 为 **-1** 表示解码失败（文件损坏、不是图片、
     *   或文件头过大无法解析），此时同样返回 null。
     * · 返回非 null 时 [Bounds.isValid] 恒为 true。
     */
    fun readBounds(path: String): Bounds? {
        val file = File(path)
        // 文件不存在直接短路：decodeFile 对不存在路径也会失败，但那条路径会走
        // 到 native 层再回来，错误信息不如这里明确，也没必要付出这次 IO 探测。
        if (!file.exists() || !file.isFile) return null

        val options = BitmapFactory.Options().apply {
            // ← 关键：这一位打开后只解析文件头，不分配像素内存
            inJustDecodeBounds = true
        }
        // 此调用在 inJustDecodeBounds 下**返回 null 是正常的**（没有 bitmap），
        // 尺寸通过 options 回填；解析失败时 outWidth/outHeight 保持 -1。
        BitmapFactory.decodeFile(path, options)

        val bounds = Bounds(options.outWidth, options.outHeight)
        return if (bounds.isValid) bounds else null
    }

    // ─────────────────────────────────────────────
    // 第 2 步：算 inSampleSize
    // ─────────────────────────────────────────────

    /**
     * 第 2 步：复刻 BitmapFactory 对 inSampleSize 的语义。
     *
     * 返回 `(采样倍数, 推导过程文本)`。算法与依据见类注释；要点：
     * 1. 逐维求 `floor(src / req)` —— 该维「最多缩多少倍还不小于目标」；
     * 2. 取两维**较小者** —— 保证两个方向都不小于目标（不发糊）；
     * 3. 向下取整到 **2 的幂**，且至少为 1 —— 解码器只认 2 的幂；
     * 4. 目标大于等于原图时不放大，sample 停在 1。
     *
     * @param reqW / [reqH] 目标尺寸；`<= 0` 视为「未获得有效尺寸」，返回 1。
     *   调用方若要走「退化全尺寸」的教学路径，请直接用 [decode]（它会把
     *   「退化为全尺寸解码」这句话写进 trace）。
     *
     * ⚠️ 推导文本里的「内存」一行按 **ARGB_8888（4 字节/像素）** 估算，
     * 因为本方法签名不含 config；真实内存请以 [DecodeResult.byteCount] 为准。
     */
    fun calculateInSampleSize(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Pair<Int, String> {
        if (srcW <= 0 || srcH <= 0) {
            return 1 to "原图尺寸无效（$srcW × $srcH），退化为 inSampleSize = 1"
        }
        if (reqW <= 0 || reqH <= 0) {
            return 1 to "目标尺寸无效（$reqW × $reqH），退化为 inSampleSize = 1"
        }

        // 1) 逐维取整除法：该维最多能缩这么多倍而结果仍 >= 目标
        val sampleX = srcW / reqW
        val sampleY = srcH / reqH

        // 2) 取较小者：sample 越小输出越大，取小可保证两维都不小于目标。
        //    coerceAtLeast(1) 处理「目标 >= 原图」——此时 floor 为 0，不放大。
        val rawSample = minOf(sampleX, sampleY).coerceAtLeast(1)

        // 3) 向下取整到 2 的幂（解码器只支持 2 的幂；自己取好，不依赖 framework）
        val sample = powerOfTwoFloor(rawSample)

        // 4) 按整数除法预估真正解出的尺寸（JPEG 等格式可能略有出入，见 decode）
        val expectedW = srcW / sample
        val expectedH = srcH / sample

        // 列对齐：让几个数字位数一致，"原图 3000 × 4000 / 目标  600 ×  800" 才好读
        val pad = listOf(srcW, srcH, reqW, reqH, expectedW, expectedH)
            .maxOf { it.toString().length }
        fun n(v: Int) = v.toString().padStart(pad)

        val srcBytes = srcW.toLong() * srcH * 4L
        val dstBytes = expectedW.toLong() * expectedH * 4L

        val trace = buildString {
            appendLine("原图 ${n(srcW)} × ${n(srcH)}")
            appendLine("目标 ${n(reqW)} × ${n(reqH)}")
            if (rawSample == 1 && (sampleX <= 0 || sampleY <= 0)) {
                appendLine("目标不小于原图，不放大 → 1")
            }
            appendLine("宽方向 floor($srcW / $reqW) = $sampleX")
            appendLine("高方向 floor($srcH / $reqH) = $sampleY")
            appendLine("取较小者 → $rawSample")
            appendLine("向下取整到 2 的幂 → $sample")
            appendLine("最终解码 ${n(expectedW)} × ${n(expectedH)}（目标 $reqW × $reqH）")
            appendLine(
                "内存 ${srcW}×${srcH}×4 = ${formatBytes(srcBytes)}" +
                    "  →  ${expectedW}×${expectedH}×4 = ${formatBytes(dstBytes)}" +
                    "（${ratioText(srcBytes, dstBytes)}）"
            )
        }
        return sample to trace
    }

    /**
     * 第 2、3 步连着做：读元信息 → 算 inSampleSize → 用算好的倍数真解码。
     *
     * ─── reqW / reqH <= 0 的行为（重点教学场景）───
     *
     * 对应 `ImageView` 的 `wrap_content`：View 的尺寸由内容决定，而内容就是这张图，
     * 鸡生蛋——布局在测量完成前给不出有效目标尺寸。此时**退回 inSampleSize = 1
     * 的全尺寸解码**，并在 trace 里明确写出「未获得有效目标尺寸，退化为全尺寸解码」。
     * 这正是要演示的 OOM 场景：布局给不出尺寸 → 无法降采样 → 整张原图进内存。
     *
     * ─── 耗时口径 ───
     *
     * [DecodeResult.elapsedMs] 用 [System.nanoTime] 计时，覆盖「读元信息 + 真解码」
     * 全过程（与 Glide DecodeJob 是同一段工作），不是只算 decodeFile 那一下。
     *
     * @param config 目标位图配置；它影响 [byteCount]，但不影响 inSampleSize。
     * @throws IllegalStateException 当文件不存在 / 损坏 / 不是图片（[readBounds] 返回 null），
     *   或真解码失败。签名不允许返回 null，故这里 fail-fast，不静默降级。
     */
    fun decode(
        path: String,
        reqW: Int,
        reqH: Int,
        config: Bitmap.Config = Bitmap.Config.ARGB_8888,
    ): DecodeResult {
        val startNanos = System.nanoTime()

        // ── 第 1 步：只读元信息（不分配像素内存）──
        val bounds = readBounds(path)
            ?: throw IllegalStateException("无法读取图片元信息（文件不存在、损坏或不是图片）：$path")
        val srcW = bounds.width
        val srcH = bounds.height

        // ── 第 2 步：目标尺寸是否有效？无效则退化为全尺寸（wrap_content 场景）──
        val hasTarget = reqW > 0 && reqH > 0
        val (sample, derivation) = if (hasTarget) {
            calculateInSampleSize(srcW, srcH, reqW, reqH)
        } else {
            1 to "未获得有效目标尺寸，退化为全尺寸解码（inSampleSize = 1）"
        }

        // ── 第 3 步：置回 inJustDecodeBounds=false，用算好的倍数真解码 ──
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = false // ← 与第 1 步互斥；这一步才真正分配像素内存
            inSampleSize = sample
            inPreferredConfig = config
        }
        val bitmap = BitmapFactory.decodeFile(path, options)
            ?: throw IllegalStateException("解码失败（文件损坏或不是图片）：$path")

        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

        // ── 证据组装：推导过程 + 真实结果 ──
        val expectedW = srcW / sample
        val expectedH = srcH / sample
        val trace = buildString {
            appendLine(derivation)
            appendLine()
            appendLine("── 真解码（inJustDecodeBounds=false，inSampleSize=$sample）──")
            appendLine("实际解码 ${bitmap.width} × ${bitmap.height}（配置 ${bitmap.config}）")
            // byteCount 取真实值：部分格式像素取整方向不同，实际尺寸未必等于整数除法预估
            appendLine("真实内存 byteCount = ${bitmap.byteCount} B = ${formatBytes(bitmap.byteCount.toLong())}")
            if (hasTarget && (bitmap.width != expectedW || bitmap.height != expectedH)) {
                appendLine(
                    "注：实际尺寸与整数除法预估（$expectedW × $expectedH）不一致 —— " +
                        "各解码器对 inSampleSize 的取整方向不同（PNG 向下、JPEG 向上等），以实际为准。"
                )
            }
            if (!hasTarget) {
                appendLine("⚠ wrap_content 场景根因：布局给不出目标尺寸 → 无法降采样 → 原图整张进内存。")
            }
            appendLine("耗时 $elapsedMs ms")
        }

        return DecodeResult(
            bitmap = bitmap,
            inSampleSize = sample,
            sourceWidth = srcW,
            sourceHeight = srcH,
            decodedWidth = bitmap.width,
            decodedHeight = bitmap.height,
            byteCount = bitmap.byteCount,
            requestedWidth = reqW,
            requestedHeight = reqH,
            elapsedMs = elapsedMs,
            trace = trace,
        )
    }

    // ─────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────

    /**
     * 某个尺寸在给定 config 下的像素内存（字节）。
     *
     * ⚠️ 这是**估算**（width × height × 每像素字节），与 `Bitmap.getByteCount()`
     * 在多数情况下一致，但 rowBytes 对齐、HARDWARE 位图等情形可能不同。
     * 教学里用它算「如果不降采样要多少内存」，真实占用一律以 [DecodeResult.byteCount] 为准。
     *
     * 结果按 [Int.MAX_VALUE] 截断，避免超大尺寸相乘溢出。
     */
    fun bytesOf(w: Int, h: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Int {
        if (w <= 0 || h <= 0) return 0
        val bytes = w.toLong() * h.toLong() * bytesPerPixel(config)
        return bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 不大于 [n] 的最大 2 的幂（1, 2, 4, 8 ...）；[n] < 1 时返回 1。
     *
     * 直接借用 [Integer.highestOneBit]：对正数它返回最高位对应的 2 的幂，
     * 恰好就是「向下取整到 2 的幂」。n=0 时 highestOneBit 返回 0，故先兜到 1。
     */
    fun powerOfTwoFloor(n: Int): Int = if (n < 1) 1 else Integer.highestOneBit(n)

    /**
     * 每像素字节数。
     *
     * 用 **config.name 字符串**而非直接引用枚举常量来判断：RGBA_F16（API 26）、
     * RGBA_1010102（API 29）等常量在低版本运行时的 `Bitmap.Config` 里并不存在，
     * 直接 `when(config)` 引用它们可能触发 NoSuchFieldError。按名字判断对
     * minSdk 24 是安全的，且新增配置时走 else 也有一致的兜底。
     */
    private fun bytesPerPixel(config: Bitmap.Config): Int = when (config.name) {
        "ALPHA_8" -> 1
        "RGB_565", "ARGB_4444" -> 2
        "RGBA_F16" -> 8
        // ARGB_8888 / RGBA_1010102 / HARDWARE 都是每像素 4 字节
        else -> 4
    }

    /** 与 [MemoryProbe.format] 同一口径（1024 进制、两位小数），避免两处数字打架。 */
    private fun formatBytes(bytes: Long): String =
        "%.2fMB".format(bytes.toDouble() / 1024 / 1024)

    /** 内存倍数文本：接近整数时写成「1/16」更直观，否则给「0.73×」这种。 */
    private fun ratioText(from: Long, to: Long): String {
        if (to <= 0L) return "—"
        val ratio = from.toDouble() / to.toDouble()
        val rounded = kotlin.math.round(ratio)
        return if (ratio >= 1.5 && kotlin.math.abs(ratio - rounded) < 0.05) {
            "1/${rounded.toInt()}"
        } else {
            "%.2f×".format(ratio)
        }
    }
}
