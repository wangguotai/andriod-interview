package com.interview.image.nativebridge

/**
 * Time: 2026/10/4
 * Author: wgt
 * Description: 图像算法的**纯 Kotlin 参考实现** —— 跨语言金标准的 Java 侧基准。
 *
 * ─── 为什么单独一个文件、且不 import 任何 android.* ───
 *
 * 这份实现要在三种场合运行：
 *   1. 作为 native 不可用时的回退路径（在设备上）；
 *   2. 作为 androidTest 里与 Rust 对拍的「金标准」（在设备上）；
 *   3. 作为 JVM 单元测试的被测对象（**在本机、没有设备**）。
 *
 * 第 3 条是关键：如果把它塞进 [ImagePipelineBridge]，JVM 单测一加载那个类就会
 * 触到 `android.graphics.Bitmap` —— 在单测里那是只会抛异常的 stub，纯算法根本
 * 跑不起来。把算法放在一个**不引用任何 android.* 类型**的文件里，三个场合就能
 * 共用同一份代码，回退路径与金标准永远不会漂移。
 *
 * ─── 为什么全程整数运算（这条很重要，别改成浮点）───
 *
 * 这两个算法的结果要和 Rust 实现**逐位（byte-for-byte）比对**。浮点不满足结合律，
 * Rust 与 JVM 的数学库、编译优化、求和顺序只要有一处不同，末位就可能差 1；
 * 于是对拍测试会被大量「差 1」的噪声淹没，真正的 bug（比如边界算错、通道错位）
 * 反而看不见。整数累加 + 整数除法（两语言的除法都是截断向零）则天然逐位一致。
 *
 * 累加器一律用 [Long]（对应 Rust 侧的 `u64`）：8K 图降到 1×1 时单通道累加会到
 * 2e9 量级，`Int` 会溢出，而 Rust 用的是 64 位无符号，溢出点不一致就会产生假差异。
 *
 * 权威定义见 Rust 侧 `rust/imagepipeline/src/downscale.rs` 与 `dominant.rs`——
 * 本文件是它们的**逐行翻译**，任何「优化」「改进」都会破坏对拍，请勿为之。
 */
object ImagePipelineReference {

    // ─────────────────────────────────────────────────────────────────────
    // 1) 区域平均降采样
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 把 [src]（RGBA8888，[srcW]×[srcH]）区域平均降采样到 [dst]（RGBA8888，[dstW]×[dstH]）。
     *
     * 目标像素 `(dx,dy)` 覆盖的源矩形为
     * `[dx*srcW/dstW, (dx+1)*srcW/dstW)` 与 `[dy*srcH/dstH, (dy+1)*srcH/dstH)`，
     * 全为整数除法；若取整后宽度为 0（放大场景）强制取 1，避免除零。
     * 每个源像素**恰好**被一个目标像素覆盖（不重不漏）。
     *
     * 均值取整为四舍五入：`(sum + count/2) / count`，alpha 同样处理。
     *
     * @throws IllegalArgumentException 尺寸非正，或 [src]/[dst] 缓冲区不足。
     */
    fun downscaleArea(
        src: ByteArray,
        srcW: Int,
        srcH: Int,
        dst: ByteArray,
        dstW: Int,
        dstH: Int,
    ) {
        require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0) { "尺寸不能为 0" }
        require(src.size >= srcW.toLong() * srcH * 4) { "源缓冲区过小" }
        require(dst.size >= dstW.toLong() * dstH * 4) { "目标缓冲区过小" }

        // 边界与索引的中间量用 Long：Rust 侧是 usize（64 位），
        // 而 dy*srcH 在超大图（如 8K 放大到极小）时可能超出 Int。
        // 用 Long 让两边的「大数行为」也保持一致。
        val sw = srcW.toLong()
        val sh = srcH.toLong()
        val dw = dstW.toLong()
        val dh = dstH.toLong()

        for (dy in 0 until dstH) {
            val dyL = dy.toLong()
            val y0 = dyL * sh / dh
            val y1 = (((dyL + 1) * sh / dh).coerceAtLeast(y0 + 1)).coerceAtMost(sh)
            for (dx in 0 until dstW) {
                val dxL = dx.toLong()
                val x0 = dxL * sw / dw
                val x1 = (((dxL + 1) * sw / dw).coerceAtLeast(x0 + 1)).coerceAtMost(sw)

                var sr = 0L; var sg = 0L; var sb = 0L; var sa = 0L
                var count = 0L
                var y = y0
                while (y < y1) {
                    val row = (y * sw * 4).toInt()
                    var x = x0
                    while (x < x1) {
                        val i = row + (x * 4).toInt()
                        sr += (src[i].toInt() and 0xFF).toLong()
                        sg += (src[i + 1].toInt() and 0xFF).toLong()
                        sb += (src[i + 2].toInt() and 0xFF).toLong()
                        sa += (src[i + 3].toInt() and 0xFF).toLong()
                        count++
                        x++
                    }
                    y++
                }
                // count 至少为 1（上面已保证 x1>x0、y1>y0）
                val o = ((dyL * dw + dxL) * 4).toInt()
                dst[o] = ((sr + count / 2) / count).toInt().toByte()
                dst[o + 1] = ((sg + count / 2) / count).toInt().toByte()
                dst[o + 2] = ((sb + count / 2) / count).toInt().toByte()
                dst[o + 3] = ((sa + count / 2) / count).toInt().toByte()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 2) 盒式模糊（M5）
    // ─────────────────────────────────────────────────────────────────────

    /**
     * 盒式模糊（box blur）的**逐行翻译**，权威定义见 `rust/imagepipeline/src/blur.rs::blur_box`。
     *
     * ─── 为什么选盒式而不是高斯 ───
     *
     * 这里的目的不是做出最好看的模糊，而是提供一个**可逐位对拍**的算子：
     * 盒式只有整数加权（全是 1），没有浮点权重，两语言极易做到逐位一致。
     * M5 真正的结论（屏幕上要的模糊交给 GPU `RenderEffect`、CPU 版只在「要拿像素」
     * 时才划算）不依赖模糊核的好坏，依赖的是「谁在执行、结果要不要被当数据用」。
     *
     * ─── 与 Rust 必须逐字一致的语义 ───
     *
     * - `radius == 0` → 直接拷贝（identity）；
     * - 窗口 `n = 2*radius + 1`；越界采样用**边缘复制**（clamp 到 `[0, w-1]`/`[0, h-1]`），
     *   这也是 Skia/GPU 模糊的常规约定，避免边缘发黑；
     * - clamp 写法照抄 Rust：`xx = (x + k).saturating_sub(radius).min(w-1)`，
     *   因 `x + k >= 0`，等价于 `clamp(x + k - radius, 0, w-1)`；
     * - 均值取整：`(sum + n/2) / n`，四舍五入（整数除法，`n/2` 也是整数除法）；
     * - 两趟：先水平写中间缓冲 `tmp`，再垂直写回 `dst`；**两趟都在 u8 上落值**。
     *
     * ⚠️ 这里「两趟都落 u8」不会引入 downscale 那种 double-rounding 问题：
     * 盒式模糊的水平结果本身就是一个有意义的中间态（等于先做一次横条模糊），
     * 而 downscale 的中间结果只是被丢弃的中间量。两者不同，别混为一谈。
     *
     * 累加用 [Long]（对应 Rust 的 `u64`）。这些尺寸不会溢出，但用 Long 让两边
     * 的「大数行为」一致，避免假差异。
     *
     * @throws IllegalArgumentException 尺寸非正、radius 为负，或 [src]/[dst] 缓冲区不足。
     */
    fun blurBox(src: ByteArray, w: Int, h: Int, dst: ByteArray, radius: Int) {
        require(radius >= 0) { "radius 不能为负" }
        require(w > 0 && h > 0) { "尺寸不能为 0" }
        val need = w.toLong() * h * 4
        require(src.size >= need) { "源缓冲区过小" }
        require(dst.size >= need) { "目标缓冲区过小" }
        val needI = w * h * 4

        if (radius == 0) {
            src.copyInto(dst, 0, 0, needI)
            return
        }

        val r = radius
        val n = (2L * r + 1).toLong()

        // ── 第一趟：水平（src → tmp），两趟都落 u8 ──
        val tmp = ByteArray(needI)
        for (y in 0 until h) {
            val row = y * w * 4
            for (x in 0 until w) {
                var s0 = 0L; var s1 = 0L; var s2 = 0L; var s3 = 0L
                for (k in 0..(2 * r)) {
                    // (x+k).saturating_sub(r).min(w-1)：边缘复制
                    val xx = (x + k - r).coerceAtLeast(0).coerceAtMost(w - 1)
                    val i = row + xx * 4
                    s0 += (src[i].toInt() and 0xFF).toLong()
                    s1 += (src[i + 1].toInt() and 0xFF).toLong()
                    s2 += (src[i + 2].toInt() and 0xFF).toLong()
                    s3 += (src[i + 3].toInt() and 0xFF).toLong()
                }
                val o = row + x * 4
                tmp[o] = ((s0 + n / 2) / n).toInt().toByte()
                tmp[o + 1] = ((s1 + n / 2) / n).toInt().toByte()
                tmp[o + 2] = ((s2 + n / 2) / n).toInt().toByte()
                tmp[o + 3] = ((s3 + n / 2) / n).toInt().toByte()
            }
        }

        // ── 第二趟：垂直（tmp → dst）──
        val stride = w * 4
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s0 = 0L; var s1 = 0L; var s2 = 0L; var s3 = 0L
                for (k in 0..(2 * r)) {
                    // (y+k).saturating_sub(r).min(h-1)
                    val yy = (y + k - r).coerceAtLeast(0).coerceAtMost(h - 1)
                    val i = yy * stride + x * 4
                    s0 += (tmp[i].toInt() and 0xFF).toLong()
                    s1 += (tmp[i + 1].toInt() and 0xFF).toLong()
                    s2 += (tmp[i + 2].toInt() and 0xFF).toLong()
                    s3 += (tmp[i + 3].toInt() and 0xFF).toLong()
                }
                val o = y * stride + x * 4
                dst[o] = ((s0 + n / 2) / n).toInt().toByte()
                dst[o + 1] = ((s1 + n / 2) / n).toInt().toByte()
                dst[o + 2] = ((s2 + n / 2) / n).toInt().toByte()
                dst[o + 3] = ((s3 + n / 2) / n).toInt().toByte()
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // 3) 主色调
    // ─────────────────────────────────────────────────────────────────────

    /** 直方图桶数，每桶 30°。必须与 Rust 侧 `HUE_BUCKETS` 相同。 */
    const val HUE_BUCKETS = 12

    /**
     * 12 个桶的代表色（调色板）。**必须与 Rust 侧 `BUCKET_PALETTE` 逐字节相同**。
     *
     * 为什么用固定调色板而非现算 HSL→RGB：现算意味着浮点或复杂整数公式要在
     * 两语言里逐位一致，风险高；固定表把「色相→代表色」变成 12 个常量，
     * 两语言各自照抄同一张表即可，差异可能性归零。代价是代表色量化到 12 个色相，
     * 对「占位色」这个用途完全够。
     */
    private val BUCKET_PALETTE = arrayOf(
        intArrayOf(255, 63, 0),
        intArrayOf(255, 191, 0),
        intArrayOf(191, 255, 0),
        intArrayOf(63, 255, 0),
        intArrayOf(0, 255, 63),
        intArrayOf(0, 255, 191),
        intArrayOf(0, 191, 255),
        intArrayOf(0, 63, 255),
        intArrayOf(63, 0, 255),
        intArrayOf(191, 0, 255),
        intArrayOf(255, 0, 191),
        intArrayOf(255, 0, 63),
    )

    /** 主色调的完整结果，供单测断言 bucket / weight / countedPixels（对外只暴露 rgb）。 */
    class DominantResult(
        /** `0xRRGGBB`，非负；0 是合法的黑色。 */
        val rgb: Int,
        /** 命中的桶下标 `0..HUE_BUCKETS-1`。 */
        val bucket: Int,
        /** 该桶累计权重（delta 之和）。 */
        val weight: Long,
        /** 参与统计的（alpha>0 且 delta>0）像素数。 */
        val countedPixels: Long,
    )

    /**
     * 整数色相：返回 `[0,360)`，灰阶（delta==0）返回 null。
     *
     * 与 Rust `hue_deg` 逐位一致：每个分支先算 `60*Δ` 再整数除法，
     * 最后 `((hue%360)+360)%360` 保证非负（Kotlin 的 `%` 与 Rust 一样是
     * 截断取余，负数时结果为负，这一步不能省）。
     */
    fun hueDeg(r: Int, g: Int, b: Int): Int? {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta == 0) return null
        val hue = when (max) {
            r -> 60 * (g - b) / delta
            g -> 120 + 60 * (b - r) / delta
            else -> 240 + 60 * (r - g) / delta
        }
        return ((hue % 360) + 360) % 360
    }

    /**
     * 从 RGBA8888 图像提取主色调，返回 `0x00RRGGBB`（非负；0 是合法黑色）。
     *
     * 规则（与 Rust 一致）：
     * - alpha==0 的像素**跳过**，不参与任何统计（透明像素 RGB 常为 0，计入会拉黑结果）；
     * - delta==0 的灰阶像素不计入色相直方图，但影响「是否退化为灰」的判断；
     * - 选权重最大的桶，**权重相同取下标小者**（保证确定性）；
     * - 无有色像素时退化为加权平均明度的灰色。
     *
     * @throws IllegalArgumentException 尺寸非正，或 [src] 缓冲区不足。
     */
    fun dominantColor(src: ByteArray, w: Int, h: Int): Int = dominantColorDetail(src, w, h).rgb

    fun dominantColorDetail(src: ByteArray, w: Int, h: Int): DominantResult {
        require(w > 0 && h > 0) { "尺寸不能为 0" }
        val needed = w.toLong() * h * 4
        require(src.size >= needed) { "缓冲区过小" }
        val pixelCount = w * h

        val bucketWeight = LongArray(HUE_BUCKETS)
        val bucketLuma = LongArray(HUE_BUCKETS)
        var counted = 0L
        var graySum = 0L
        var grayN = 0L

        for (i in 0 until pixelCount) {
            val o = i * 4
            val r = src[o].toInt() and 0xFF
            val g = src[o + 1].toInt() and 0xFF
            val b = src[o + 2].toInt() and 0xFF
            val a = src[o + 3].toInt() and 0xFF
            if (a == 0) continue

            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = (max - min).toLong()
            // 明度代理（整数）：用于还原桶的代表亮度
            val luma = (r + g + b) / 3

            if (delta == 0L) {
                graySum += luma
                grayN += 1
                continue
            }
            val hue = hueDeg(r, g, b) ?: 0
            val bucket = hue / 30 % HUE_BUCKETS
            bucketWeight[bucket] += delta
            bucketLuma[bucket] += luma * delta
            counted += 1
        }

        // 找权重最大的桶（严格大于 → 权重相同取较小下标）
        var best = 0
        for (i in 1 until HUE_BUCKETS) {
            if (bucketWeight[i] > bucketWeight[best]) best = i
        }

        if (bucketWeight[best] == 0L) {
            val luma = (if (grayN > 0) graySum / grayN else 0L).coerceAtMost(255L).toInt()
            return DominantResult(
                rgb = (luma shl 16) or (luma shl 8) or luma,
                bucket = 0,
                weight = 0,
                countedPixels = counted,
            )
        }

        val weight = bucketWeight[best]
        val meanLuma = (bucketLuma[best] / weight).coerceAtMost(255L).toInt()
        val rgb = applyLuma(BUCKET_PALETTE[best], meanLuma)
        return DominantResult(rgb, best, weight, counted)
    }

    /**
     * 按整数比例 `luma/255` 缩放调色板颜色，返回 `0xRRGGBB`。
     * 整数乘除 + 截断，对每通道 `& 0xFF`，与 Rust `apply_luma` 一致。
     */
    fun applyLuma(color: IntArray, luma: Int): Int {
        val l = luma.coerceAtMost(255)
        val r = (color[0] * l / 255) and 0xFF
        val g = (color[1] * l / 255) and 0xFF
        val b = (color[2] * l / 255) and 0xFF
        return (r shl 16) or (g shl 8) or b
    }
}
