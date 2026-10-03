package com.interview.image

import java.util.Random

/**
 * Time: 2026/10/3
 * Author: wgt
 * Description: 「大图加载」教学的数据源 —— 分页产出确定性的超大原图描述。
 *
 * 为什么这里的图必须「超大」（这是本 demo 成立的前提，不是凑数）：
 *
 * BitmapFactory 的降采样（inSampleSize）有一个**触发门槛**：只有解码器发现
 * 原图尺寸明显大于调用方声明的目标尺寸时，才会真的按 2 的幂次折半解码。
 * 如果原图本身就只有几百像素、和 ImageView 的显示尺寸差不多甚至更小，
 * inSampleSize 会一直停在 1 —— 此时无论你写 inJustDecodeBounds 也好、
 * 手算采样率也好，**解码出的位图尺寸完全一样，内存占用也不会下降**。
 * 那样「逐步降采样能省内存」这个结论根本无法被观测到，教学就失真了。
 *
 * 所以这里刻意把原图的长边推到 2000~3200px：一张 2000×2000 的 ARGB_8888
 * 位图约 16MB，几张就能把普通机型的堆压到 GC 边缘，降采样的收益才有对照价值。
 *
 * 确定性（本 demo 的另一条铁律）：
 * 所有随机性都必须由 **page / index 派生**，禁止 Math.random / System.currentTimeMillis。
 * 否则每次运行拿到的图不一样，前后两次实验的「内存占用量」「耗时」就没法对比，
 * 随机性会直接毁掉对照实验。这里用 seed 串的 hashCode 喂给 [Random]，同一 seed
 * 在任何一次运行、任何设备上都产出同一组宽高。
 */
data class ImageSpec(
    val id: String,
    val url: String,
    val originalWidth: Int,
    val originalHeight: Int,
)

object ImageFeed {

    /** 默认每页数量。对外暴露出来，供解剖台做「翻页」时不硬编码 20。 */
    const val PAGE_SIZE = 20

    private const val MIN_LONG_SIDE = 2000
    private const val MAX_LONG_SIDE = 3200

    /** 宽高比的抖动区间（w / h），跨过 1.0 让竖图与横图都出现，瀑布流才有错落 */
    private const val MIN_RATIO = 0.62f
    private const val MAX_RATIO = 1.55f

    /**
     * 分页拿到一批「超大原图」。
     *
     * @param page 页码，从 0 或 1 起都可，参与 seed 派生，不同页不重复
     * @param pageSize 每页数量
     */
    fun page(page: Int, pageSize: Int = 20): List<ImageSpec> =
        List(pageSize) { index -> build(page, index) }

    private fun build(page: Int, index: Int): ImageSpec {
        // seed 只由 page/index 决定：URL 与宽高都从它派生，保证跨运行完全一致。
        val seed = "ii-$page-$index"
        val rng = Random(seed.hashCode().toLong())

        // 长边在 2000~3200 之间均匀取值（含端点）
        val longSide = MIN_LONG_SIDE + rng.nextInt(MAX_LONG_SIDE - MIN_LONG_SIDE + 1)
        // 宽高比在 0.62~1.55 之间抖动：<1 是竖图，>1 是横图
        val ratio = MIN_RATIO + rng.nextFloat() * (MAX_RATIO - MIN_RATIO)

        val rawWidth: Int
        val rawHeight: Int
        if (ratio >= 1f) {
            rawWidth = longSide
            rawHeight = (longSide / ratio).toInt()
        } else {
            rawHeight = longSide
            rawWidth = (longSide * ratio).toInt()
        }

        // 取偶数：odd 尺寸会让行字节对齐出现补位，可能给内存估算引入无关噪声
        val width = rawWidth and 0x7FFFFFFE
        val height = rawHeight and 0x7FFFFFFE

        // picsum 的 seed 形式：同一 seed 永远返回同一张图
        val url = "https://picsum.photos/seed/$seed/$width/$height"

        return ImageSpec(id = seed, url = url, originalWidth = width, originalHeight = height)
    }
}
