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
///
/// ─── 实现要点（一次失败的优化 + 一次成功的重排）───
///
/// **失败尝试：可分离两趟（先水平后垂直）。** 直觉上它缓存友好、代码更短，
/// 但中间结果必须存成 `u8`，于是「求平均」被做了**两次**（每趟各一次），
/// 舍入误差累积，实测 8x8→3x2 就出现 native=120 vs 参考=119 的逐位差异。
/// 这不是精度「差不多」的问题 —— 它直接破坏了跨语言对拍这条验收红线。
/// 结论：**在要求逐位一致的场合，不要引入中间量化**。
///
/// **采用的写法：单趟累加、只舍一次、按输出行重排。**
/// - 每个输出行维护 `dst_w × 4` 个 `u64` 累加器，扫过它覆盖的所有源行后才做
///   一次 `/ count`，与参考实现的运算顺序完全一致（因此逐位相等）。
/// - 内层对每个源行都是**从左到右顺序读**：外层虽按输出行分组，但组内每个源行的
///   读取都是线性的，缓存行利用率远好于朴素的「逐目标像素跳 k×k 块」。
/// - 累加器只在行间复用与重置，分配被压到 `O(dst_w)`，与 `dst_h` 无关。
/// - `u64` 累加保留：单行内单通道上限是 `src_w × 255`，8K 图也才 2e6，
///   其实 `u32` 够用；但块和还要参与 `+ n/2` 的舍入，为跨语言一致性
///   采用与参考实现相同的 64 位语义，避免任何溢出边界上的分歧。
///
/// 注：曾考虑用「乘法倒数（magic number）」替换每个输出像素的 4 次整数除法 ——
/// 除法在 ARM 上确实是 20+ cycle 的串行操作。但那需要严格的误差界证明，
/// 否则四舍五入的边界值会与参考实现差 1，同样破坏逐位对拍。正确性优先。
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
    let sw = src_w as usize;
    let sh = src_h as usize;
    let dw = dst_w as usize;
    let dh = dst_h as usize;

    let src_need = sw * sh * 4;
    let dst_need = dw * dh * 4;
    if src.len() < src_need {
        return Err("源缓冲区过小");
    }
    if dst.len() < dst_need {
        return Err("目标缓冲区过小");
    }

    // 退化情形：尺寸相同直接拷贝
    if dw == sw && dh == sh {
        dst[..dst_need].copy_from_slice(&src[..src_need]);
        return Ok(());
    }

    // 预计算 x 边界（与参考实现同一套整数除法公式）
    let mut x0s = vec![0usize; dw];
    let mut x1s = vec![0usize; dw];
    for dx in 0..dw {
        let x0 = dx * sw / dw;
        let x1 = ((dx + 1) * sw / dw).max(x0 + 1).min(sw);
        x0s[dx] = x0;
        x1s[dx] = x1;
    }

    // 中间累加器：每个输出像素 4 个 u64。注意这里累积的是**完整**的块和，
    // 直到最后才对 count 做一次四舍五入除法 —— 这正是与参考实现逐位一致的关键。
    let mut sum = vec![0u64; dw * 4];
    let mut count = vec![0u64; dw];

    // 按输出行处理：同一行内连续扫过所有它覆盖的源行。
    // 内层对每个源行都是「从左到右顺序读」，与源的存储顺序一致。
    for dy in 0..dh {
        let y0 = dy * sh / dh;
        let y1 = ((dy + 1) * sh / dh).max(y0 + 1).min(sh);

        // 重置累加器（只重置这一行用到的 dw*4 个 u64，成本可忽略）
        sum.fill(0);
        count.fill(0);

        for y in y0..y1 {
            let row = y * sw * 4;
            for dx in 0..dw {
                let x0 = x0s[dx];
                let x1 = x1s[dx];
                let mut i = row + x0 * 4;
                let base = dx * 4;
                // 逐像素 4 字节；j 从起点累加，避免每次重算索引
                for _ in x0..x1 {
                    sum[base] += src[i] as u64;
                    sum[base + 1] += src[i + 1] as u64;
                    sum[base + 2] += src[i + 2] as u64;
                    sum[base + 3] += src[i + 3] as u64;
                    i += 4;
                }
                count[dx] += (x1 - x0) as u64;
            }
        }

        let dst_row = dy * dw * 4;
        for dx in 0..dw {
            let n = count[dx];
            let base = dx * 4;
            let o = dst_row + dx * 4;
            // n 至少为 1（x1>x0、y1>y0 已保证）
            dst[o] = ((sum[base] + n / 2) / n) as u8;
            dst[o + 1] = ((sum[base + 1] + n / 2) / n) as u8;
            dst[o + 2] = ((sum[base + 2] + n / 2) / n) as u8;
            dst[o + 3] = ((sum[base + 3] + n / 2) / n) as u8;
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
