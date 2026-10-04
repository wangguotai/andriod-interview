//! 主色调提取（hue histogram → 众数）。
//!
//! ─── 用途 ───
//!
//! 瀑布流卡片在图片加载完成前先铺一层「主色调」占位色，避免闪白；
//! 也可用于相似图去重的初筛。它是一个**对缩略图做的、每图必跑**的轻计算 ——
//! 正是适合趟进 native 流水线的那类活。
//!
//! ─── 算法选择：为什么不用 HSV 浮点 ───
//!
//! 教科书做法是逐像素转 HSV 再统计 Hue 直方图。这里刻意不用，原因有二：
//!   1. **跨语言一致性**：Hue 用 `atan2`/浮点算，Rust 与 Kotlin(JVM) 的
//!      数学库实现不同，末位不保证一致，金标准对拍会变成「差不多」，
//!      那就失去了对拍的意义。
//!   2. **性能本身就是结论的一部分**：整数色相在 native 里可以全程 `u32` + 无分支，
//!      如果参考实现用浮点、native 用整数，那对比的就不是语言而是算法，不再公平。
//!
//! 所以两边都用**整数 max/min 色相**（近似 HSV 的 hue）：
//!   delta = max - min；若 delta == 0 → 灰阶，不计入直方图；
//!   否则按 max 落在哪个通道分支，算出一个 [0,360) 的整数色相。
//!
//! ─── 直方图分桶与「众数」的定义 ───
//!
//! 12 个桶（每桶 30°），对权重（饱和度代理 = delta）累加，取权重最大的桶，
//! 用该桶的中心色相 + 平均明度还原成一个 RGB 返回。这种「先分桶再取桶心」
//! 的做法比「取出现次数最多的精确色相」抗噪声得多。

/// 直方图桶数。每桶 30°。
pub const HUE_BUCKETS: usize = 12;

/// 主色调结果。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DominantColor {
    /// 还原出的颜色，`0x00RRGGBB`。
    pub rgb: u32,
    /// 命中的桶下标 `0..HUE_BUCKETS`。
    pub bucket: u32,
    /// 该桶的累计权重（饱和度代理之和）。
    pub weight: u64,
    /// 参与的（非灰阶、非全透明）像素数。
    pub counted_pixels: u64,
}

/// 整数色相：返回 `Some(hue in [0,360))`，灰阶返回 `None`。
///
/// 与参考实现必须逐位一致，所以这里只允许整数乘除。
#[inline]
pub fn hue_deg(r: u8, g: u8, b: u8) -> Option<u32> {
    let (r, g, b) = (r as i32, g as i32, b as i32);
    let max = r.max(g).max(b);
    let min = r.min(g).min(b);
    let delta = max - min;
    if delta == 0 {
        return None;
    }
    // 每个分支先算 *60，再做整数除法，最后 +360 取模，保证非负。
    let hue = if max == r {
        60 * (g - b) / delta
    } else if max == g {
        120 + 60 * (b - r) / delta
    } else {
        240 + 60 * (r - g) / delta
    };
    Some(((hue % 360 + 360) % 360) as u32)
}

/// 从 RGBA8888 图像提取主色调。
///
/// - 全透明像素（alpha == 0）不参与统计 —— 透明像素的 RGB 常是 0，
///   计入会把结果拉向黑色，是个经典错误。
/// - 灰阶像素（delta == 0）不计入色相直方图，但它们的数量会影响「是否退化为灰」的判断。
/// - 若无任何有色像素（纯灰图/空图），退化为「加权平均明度的灰色」。
pub fn dominant_color(src: &[u8]) -> DominantColor {
    if src.len() < 4 {
        return DominantColor { rgb: 0, bucket: 0, weight: 0, counted_pixels: 0 };
    }
    let pixel_count = src.len() / 4;

    let mut bucket_weight = [0u64; HUE_BUCKETS];
    let mut bucket_luma = [0u64; HUE_BUCKETS];
    let mut counted: u64 = 0;

    // 灰度退化路径的累加器
    let mut gray_sum: u64 = 0;
    let mut gray_n: u64 = 0;

    for i in 0..pixel_count {
        let o = i * 4;
        let (r, g, b, a) = (src[o], src[o + 1], src[o + 2], src[o + 3]);
        if a == 0 {
            continue;
        }
        let r_i = r as i32;
        let g_i = g as i32;
        let b_i = b as i32;
        let max = r_i.max(g_i).max(b_i);
        let min = r_i.min(g_i).min(b_i);
        let delta = (max - min) as u64;
        // 明度代理（整数），用于还原桶的代表亮度
        let luma = (r_i as u64 + g_i as u64 + b_i as u64) / 3;

        if delta == 0 {
            gray_sum += luma;
            gray_n += 1;
            continue;
        }
        let hue = hue_deg(r, g, b).unwrap_or(0);
        let bucket = (hue / 30) as usize % HUE_BUCKETS;
        bucket_weight[bucket] += delta;
        bucket_luma[bucket] += luma * delta;
        counted += 1;
    }

    // 找权重最大的桶（权重相同取小下标，保证确定性）
    let mut best = 0usize;
    for i in 1..HUE_BUCKETS {
        if bucket_weight[i] > bucket_weight[best] {
            best = i;
        }
    }

    if bucket_weight[best] == 0 {
        // 纯灰/无色图：退化为灰色
        let luma = if gray_n > 0 { gray_sum / gray_n } else { 0 } as u32;
        let luma = luma.min(255);
        return DominantColor {
            rgb: (luma << 16) | (luma << 8) | luma,
            bucket: 0,
            weight: 0,
            counted_pixels: counted,
        };
    }

    let w = bucket_weight[best];
    let mean_luma = (bucket_luma[best] / w).min(255) as u32;
    let rgb = apply_luma(BUCKET_PALETTE[best], mean_luma);
    DominantColor { rgb, bucket: best as u32, weight: w, counted_pixels: counted }
}

/// 12 个桶的代表色（HSV 桶心 `h=15+30*i`、饱和/明度满值，预先算好的常量）。
///
/// 为什么用**固定调色板**而不是每张图现算 HSL→RGB：
/// 现算意味着浮点或复杂整数公式要在 Rust 与 Kotlin 两处**逐位一致**，
/// 只要有一处分支/取整不同，对拍测试就会报「结果不一致」，而排查成本极高。
/// 调色板把「色相→代表色」这一步变成 12 个常量，两语言只需各自照抄同一张表，
/// 差异可能性归零。代价是代表色被量化到 12 个色相，对本用途（占位色）完全够。
///
/// 这张表必须与 Kotlin 参考实现中的同名表**逐字节相同**。
pub const BUCKET_PALETTE: [(u8, u8, u8); HUE_BUCKETS] = [
    (255, 63, 0),
    (255, 191, 0),
    (191, 255, 0),
    (63, 255, 0),
    (0, 255, 63),
    (0, 255, 191),
    (0, 191, 255),
    (0, 63, 255),
    (63, 0, 255),
    (191, 0, 255),
    (255, 0, 191),
    (255, 0, 63),
];

/// 按整数比例 `luma/255` 缩放调色板颜色，返回 `0xRRGGBB`。
///
/// 整数乘除 + 截断，无浮点，保证跨语言逐位一致。
#[inline]
pub fn apply_luma(color: (u8, u8, u8), luma: u32) -> u32 {
    let l = luma.min(255);
    let r = (color.0 as u32 * l / 255) & 0xFF;
    let g = (color.1 as u32 * l / 255) & 0xFF;
    let b = (color.2 as u32 * l / 255) & 0xFF;
    (r << 16) | (g << 8) | b
}

#[cfg(test)]
mod tests {
    use super::*;

    fn solid(r: u8, g: u8, b: u8, a: u8, n: usize) -> Vec<u8> {
        let mut v = Vec::with_capacity(n * 4);
        for _ in 0..n {
            v.extend_from_slice(&[r, g, b, a]);
        }
        v
    }

    #[test]
    fn hue_of_primaries() {
        assert_eq!(hue_deg(255, 0, 0), Some(0), "红色");
        assert_eq!(hue_deg(0, 255, 0), Some(120), "绿色");
        assert_eq!(hue_deg(0, 0, 255), Some(240), "蓝色");
        assert_eq!(hue_deg(128, 128, 128), None, "灰阶无色相");
    }

    #[test]
    fn dominant_of_solid_red_is_reddish() {
        let img = solid(255, 0, 0, 255, 100);
        let d = dominant_color(&img);
        assert_eq!(d.bucket, 0, "红色应落在第 0 桶");
        assert_eq!(d.counted_pixels, 100);
        // 调色板第 0 桶为 (255,63,0)，明度 85 → (85,21,0)
        assert_eq!(d.rgb, 0x551500, "调色板桶心 × 明度缩放");
    }

    #[test]
    fn dominant_ignores_fully_transparent_pixels() {
        // 99 个红色 + 1 个全透明黑（RGB=0）：不应把结果拉黑
        let mut img = solid(255, 0, 0, 255, 99);
        img.extend_from_slice(&[0, 0, 0, 0]);
        let d = dominant_color(&img);
        assert_eq!(d.counted_pixels, 99, "全透明像素不应计入");
        let r = (d.rgb >> 16) & 0xFF;
        let b = d.rgb & 0xFF;
        // 纯红的平均明度是 (255+0+0)/3 = 85；若透明像素混入，红分量会被拉到接近 0
        assert_eq!(r, 85, "应是纯红按 85 明度缩放：rgb={:#x}", d.rgb);
        assert!(r > b, "主色应仍偏红：rgb={:#x}", d.rgb);
    }

    #[test]
    fn dominant_of_gray_image_falls_back_to_gray() {
        let img = solid(120, 120, 120, 255, 16);
        let d = dominant_color(&img);
        assert_eq!(d.weight, 0, "纯灰图无有色像素");
        let r = (d.rgb >> 16) & 0xFF;
        let g = (d.rgb >> 8) & 0xFF;
        let b = d.rgb & 0xFF;
        assert_eq!((r, g, b), (120, 120, 120), "应退化为平均明度的灰");
    }

    #[test]
    fn dominant_is_deterministic_on_tie() {
        // 等量红与蓝：权重相同，应稳定取较小桶（红 < 蓝）
        let mut img = solid(255, 0, 0, 255, 50);
        img.extend_from_slice(&solid(0, 0, 255, 255, 50));
        let d1 = dominant_color(&img);
        let d2 = dominant_color(&img);
        assert_eq!(d1, d2, "同样输入必须同结果");
        assert_eq!(d1.bucket, 0, "平票取较小桶");
    }
}
