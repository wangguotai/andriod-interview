package com.interview.image

import android.graphics.Bitmap

/**
 * Time: 2026/10/3
 * Author: wgt
 * Description: 三态对照实验 —— 同一个 ImageView、同一张原图，只改「目标尺寸怎么来」。
 *
 * ─── 这个实验要回答的问题 ───
 *
 * 学生在听完降采样原理后，最常见的疑问是：
 * 「反正图片最后都会被缩放到控件那么大，我解码大一点有什么区别？」
 *
 * 这个实验就是用来击穿这句话的。三个场景里，**屏幕上看到的东西完全一样**，
 * 但进程持有的位图内存差一个数量级。
 *
 * 关键是要把两个容易混淆的量分开说：
 *
 * · **持有内存（heldBytes）**＝ 解码出的 Bitmap 实际占多少。这是我们真正关心的。
 * · **显示占用（displayBytes）**＝ 控件把它画出来时，屏幕上那块区域的大小。
 *   它**恒等于** targetW × targetH × 4，三个场景完全一致。
 *
 * 「显示一样、持有差 15 倍」—— 这就是降采样存在的全部意义。
 *
 * ─── 三个场景 ───
 *
 * 1. **normal（正确姿势）**
 *    先量出 ImageView 的真实尺寸当作目标，交给 inSampleSize 去缩。
 *    → 最优解：画质够用，内存最小。
 *
 * 2. **wrap_content（性能陷阱）**
 *    `wrap_content` 意味着 View 的尺寸由内容决定，而内容就是这张图 ——
 *    鸡生蛋问题：要测量 View 得先有图，要有图得先知道 View 多大。
 *    Glide 在测量真正完成前拿不到有效尺寸，只能**退回全尺寸解码**。
 *    → 内存飙到 15 倍以上，这就是面试里那个经典 OOM 场景。
 *    注意：Glide 后续确实会做一次"等比缩放到控件大小"的重解码，
 *    但**那是在全尺寸已经进内存之后**的补救，峰值内存该飙还是飙。
 *
 * 3. **override（强制指定）**
 *    布局确实给不出尺寸时（wrap_content / 动态布局 / 复用池），
 *    用 `.override(w, h)` 把目标尺寸**显式喂给 Glide**，降采样立刻恢复生效。
 *    → 与 normal 等价，证明"问题不在图片，在于有没有给出目标尺寸"。
 *
 * ─── 为什么这里用裸 BitmapFactory 而不是 Glide ───
 *
 * 因为要让学生看到 `inSampleSize` 这个数本身。Glide 内部的 Options 是局部变量，
 * 没有稳定钩子（详见 NOTES-glide-source.md 第 6 节）。而三态对照的核心结论
 * 「有没有目标尺寸 → inSampleSize 是 1 还是 4」必须在数值上可见，
 * 所以这一步用我们自己完全掌控的 BitmapFactory 来复刻 Glide 的决策逻辑。
 * 两页互为印证：瀑布流页证明"Glide 真的交付了小图"，
 * 本页证明"小图是怎么被算出来的"。
 */
object DecodeComparison {

    /** 三个场景的枚举 */
    enum class Scenario(val label: String) {
        NORMAL("normal\n按显示尺寸"),
        WRAP_CONTENT("wrap_content\n拿不到尺寸"),
        OVERRIDE("override\n强制指定"),
    }

    data class Row(
        val scenario: Scenario,
        val result: DecodeResult,
        /**
         * 把它画到 target 大小的控件上时，屏幕上那块的像素内存。
         * 三行**必须完全相等** —— 这是本实验最有力的一句证词。
         */
        val displayBytes: Long,
        /** 相对最优解的倍数（以持有内存最小的那一行为 1.0） */
        var heldRatio: Float = 1f,
    )

    data class Report(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val targetWidth: Int,
        val targetHeight: Int,
        val rows: List<Row>,
    ) {
        /** 直接可以贴到界面上的文本报告 */
        fun render(): String = buildString {
            appendLine("原图 ${sourceWidth} × ${sourceHeight}  (ARGB_8888)")
            appendLine("全尺寸像素内存 ${MemoryProbe.format(sourceWidth.toLong() * sourceHeight * 4L)}")
            appendLine("ImageView 实际显示 ${targetWidth} × ${targetHeight}")
            appendLine("─".repeat(46))

            rows.forEach { row ->
                val r = row.result
                appendLine("【${row.scenario.label.replace("\n", " ")}】")
                appendLine("  解码尺寸   ${r.decodedWidth} × ${r.decodedHeight}")
                appendLine("  inSampleSize = ${r.inSampleSize}")
                appendLine("  持有内存   ${MemoryProbe.format(r.byteCount.toLong())}   (${"%.1f".format(row.heldRatio)}×)")
                appendLine("  显示占用   ${MemoryProbe.format(row.displayBytes)}")
                appendLine("  解码耗时   ${r.elapsedMs} ms")
                appendLine()
            }

            appendLine("─".repeat(46))
            appendLine("★ 三行的「显示占用」完全相同 —— 屏幕上看到的一模一样。")
            appendLine("★ 但「持有内存」相差 ${"%.1f".format(rows.maxOf { it.heldRatio })} 倍。")
            appendLine("  这就是「显示尺寸」必须提前告知解码器的原因。")
        }
    }

    /**
     * 跑完整的三态对照。
     *
     * 注意：本方法会做 3 次真实解码，**必须在后台线程调用**（调用方负责，
     * 见 DecodeComparisonActivity 里用 ThreadPools.background 提交）。
     */
    fun run(path: String, targetWidth: Int, targetHeight: Int): Report {
        val bounds = DownsampleKit.readBounds(path)
            ?: error("读不到图片元信息：$path")
        require(targetWidth > 0 && targetHeight > 0) { "目标尺寸必须为正：$targetWidth × $targetHeight" }

        val rows = mutableListOf<Row>()

        // ── 场景 1：normal —— 目标尺寸来自 View 的真实测量值 ──
        rows += row(Scenario.NORMAL, path, targetWidth, targetHeight, targetWidth, targetHeight)

        // ── 场景 2：wrap_content —— 拿不到有效尺寸，退化全尺寸 ──
        // 用 0 表示"没有有效目标"，DownsampleKit 会复刻 BitmapFactory 的行为：
        // inSampleSize 停在 1，全尺寸进内存。
        rows += row(Scenario.WRAP_CONTENT, path, 0, 0, targetWidth, targetHeight)

        // ── 场景 3：override —— 显式指定，与 normal 等价 ──
        rows += row(Scenario.OVERRIDE, path, targetWidth, targetHeight, targetWidth, targetHeight)

        // 倍数以最小持有内存为基准，让"差多少倍"有个直观锚点
        val minBytes = rows.minOf { it.result.byteCount }.coerceAtLeast(1)
        rows.forEach { it.heldRatio = it.result.byteCount.toFloat() / minBytes }

        return Report(
            sourceWidth = bounds.width,
            sourceHeight = bounds.height,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            rows = rows,
        )
    }

    private fun row(
        scenario: Scenario,
        path: String,
        reqW: Int,
        reqH: Int,
        displayW: Int,
        displayH: Int,
    ): Row {
        val result = DownsampleKit.decode(path, reqW, reqH, Bitmap.Config.ARGB_8888)
        return Row(
            scenario = scenario,
            result = result,
            // 显示占用：控件实际画出来的那块区域的像素内存，恒为 targetW × targetH × 4。
            // 它与解码尺寸无关 —— 这句话是本实验的题眼。
            displayBytes = displayW.toLong() * displayH * 4L,
        )
    }
}
