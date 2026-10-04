//! 区域平均降采样（area-average downscale）。
//!
//! ─── 为什么是「区域平均」而不是最近邻 ───
//!
//! 缩略图/占位图的用途是「代表整张图的颜色分布」，最近邻会取到局部噪声，
//! 而区域平均对每个目标像素覆盖的源区域求均值，结果稳定、可用。
//! 代价是每个目标像素要读一片源像素 —— 这正好是「一趟 native 遍历」的收益点：
//! 在 Java 里这个内层循环要么逐像素 JNI（不可能），要么写成一堆 int[] 中转。
//!
//! ─── 为什么全程整数运算 ───
//!
//! 本模块刻意**不使用浮点**。原因不是性能，而是**可复现性**：
//! 这个函数的结果要和 Kotlin 参考实现逐位比对（M2 的跨语言金标准）。
//! 浮点的结合律不成立，两种语言、两次编译优化只要有一处求和顺序不同，
//! 末位就可能差 1，然后「结果不一致」的告警会淹没真正的 bug。
//! 整数累加 + 整数除法（两语言都是截断向零）则天然逐位一致。
//!
//! 累加用 `u64`：4K 图降到 1×1 时，单个通道的累加上限是
//! 3840*2160*255 ≈ 2.1e9，已超出 `u32`（4.29e9 勉强够但没余量，
//! 且 8K 会溢出），用 `u64` 一劳永逸。

/// 把 `src`（RGBA8888，`src_w`×`src_h`）区域平均降采样到 `dst`（RGBA8888，`dst_w`×`dst_h`）。
///
/// 返回值：成功返回 `Ok(())`；尺寸/缓冲区不合法返回 `Err`。
///
/// 边界定义（与参考实现必须一致）：
/// - 目标像素 `(dx, dy)` 覆盖的源矩形为
///   `[dx*src_w/dst_w, (dx+1)*src_w/dst_w) × [dy*src_h/dst_h, (dy+1)*src_h/dst_h)`；
/// - 用整数除法定位边界，因此每个源像素**恰好**被一个目标像素覆盖（无重叠、无遗漏）；
/// - 若因取整导致某方向宽度为 0（放大场景），强制取 1，避免除零。
/// - 均值取整方式：`(sum + count/2) / count`（四舍五入），alpha 同样处理。
pub fn downscale_area(
    src: &[u8],
    src_w: u32,
    src_h: u32,
    dst: &mut [u8],
    dst_w: u32,
    dst_h: u32,
) -> Result<(), &'static str> {
    if src_w == 0 || src_h == 0 || dst_w == 0 || dst_h == 0 {
        return Err("尺寸不能为 0");
    }
    let src_need = src_w as usize * src_h as usize * 4;
    let dst_need = dst_w as usize * dst_h as usize * 4;
    if src.len() < src_need {
        return Err("源缓冲区过小");
    }
    if dst.len() < dst_need {
        return Err("目标缓冲区过小");
    }

    let src_w_us = src_w as usize;
    for dy in 0..dst_h as usize {
        let y0 = dy * src_h as usize / dst_h as usize;
        let y1 = ((dy + 1) * src_h as usize / dst_h as usize).max(y0 + 1).min(src_h as usize);
        for dx in 0..dst_w as usize {
            let x0 = dx * src_w_us / dst_w as usize;
            let x1 = ((dx + 1) * src_w_us / dst_w as usize)
                .max(x0 + 1)
                .min(src_w_us);

            let mut sum = [0u64; 4];
            let mut count: u64 = 0;
            for y in y0..y1 {
                let row = y * src_w_us * 4;
                for x in x0..x1 {
                    let i = row + x * 4;
                    sum[0] += src[i] as u64;
                    sum[1] += src[i + 1] as u64;
                    sum[2] += src[i + 2] as u64;
                    sum[3] += src[i + 3] as u64;
                    count += 1;
                }
            }
            // count 至少为 1（上面已保证 x1>x0、y1>y0）
            let o = (dy * dst_w as usize + dx) * 4;
            for c in 0..4 {
                dst[o + c] = ((sum[c] + count / 2) / count) as u8;
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn px(r: u8, g: u8, b: u8, a: u8) -> [u8; 4] {
        [r, g, b, a]
    }

    #[test]
    fn downscale_2x2_to_1x1_averages_all_four() {
        let src: Vec<u8> = [
            px(0, 0, 0, 255),
            px(100, 100, 100, 255),
            px(200, 200, 200, 255),
            px(40, 40, 40, 255),
        ]
        .concat();
        let mut dst = [0u8; 4];
        downscale_area(&src, 2, 2, &mut dst, 1, 1).unwrap();
        // (0+100+200+40)/4 = 85
        assert_eq!(dst, [85, 85, 85, 255]);
    }

    #[test]
    fn downscale_rounding_is_round_half_up() {
        // 两个像素 0 和 1 → 均值 0.5 → 四舍五入应为 1（(0+1+1)/2 = 1）
        let src: Vec<u8> = [px(0, 0, 0, 0), px(1, 1, 1, 2)].concat();
        let mut dst = [0u8; 4];
        downscale_area(&src, 2, 1, &mut dst, 1, 1).unwrap();
        assert_eq!(dst, [1, 1, 1, 1]);
    }

    #[test]
    fn downscale_covers_every_source_pixel_exactly_once() {
        // 5x1 → 2x1：边界应为 [0,2) 与 [2,5)，全部覆盖、不重不漏
        let mut src = Vec::new();
        for v in 0..5u8 {
            src.extend_from_slice(&px(v * 10, 0, 0, 255));
        }
        let mut dst = [0u8; 8];
        downscale_area(&src, 5, 1, &mut dst, 2, 1).unwrap();
        // 左：0,10 → 5；右：20,30,40 → 30
        assert_eq!(dst[0], 5, "左块均值");
        assert_eq!(dst[4], 30, "右块均值");
    }

    #[test]
    fn rejects_bad_sizes() {
        let src = [0u8; 16];
        let mut dst = [0u8; 4];
        assert!(downscale_area(&src, 2, 2, &mut dst, 0, 1).is_err());
        assert!(downscale_area(&src, 2, 2, &mut dst, 1, 1).is_ok());
        let mut small = [0u8; 2];
        assert!(downscale_area(&src, 2, 2, &mut small, 1, 1).is_err());
    }
}
