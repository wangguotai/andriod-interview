//! 盒式模糊（box blur）——用于 M5「自己写 vs 交给框架」的对标。
//!
//! ─── 为什么选「盒式」而不是「高斯」 ───
//!
//! 本模块的目的不是做出最好看的模糊，而是提供一个**可逐位对拍、可比复杂度**的
//! 算子。盒式模糊是一趟滑动窗口求和，数学上干净（没有浮点权重），
//! 两语言实现极易做到逐位一致；而真正要讲清的那一课在别处：
//! **屏幕上要的模糊，API 31+ 的 `RenderEffect` 交给 RenderThread/GPU 做，
//! 根本不占主线程；自己写 CPU 模糊只在「需要把模糊后的像素当数据用」时才划算。**
//!
//! ─── 边界与取整（必须与 Kotlin 参考实现逐字一致）───
//!
//! - 窗口半径 `r`，窗口大小 `n = 2r + 1`；
//! - 越界采样用**边缘复制**（clamp 到 `[0, w-1]` / `[0, h-1]`），
//!   这也是 Skia/GPU 模糊的常规约定，避免边缘发黑；
//! - 均值取整：`(sum + n/2) / n`，四舍五入 —— 与 downscale/dominant 同一套约定；
//! - 先水平一趟（写到中间缓冲），再垂直一趟（写回 dst），两趟都在 `u8` 上落值。
//!
//! ⚠️ 注意：这里「两趟都落 u8」**不会**引入 downscale 里那个 double-rounding 问题，
//! 因为盒式模糊的中间结果本身就是最终要用的值（水平模糊后的图是有意义的中间态），
//! 而 downscale 的中间结果只是被丢弃的中间量。两者不可混为一谈。
//!
//! 复杂度：O(w·h·r)。`r` 通常 8~24，可接受；若要 O(w·h) 需要前缀和滑窗，
//! 但那会让「与朴素参考实现对比」失去公平性（比的是算法而非语言），故不做。

/// 盒式模糊。`radius = 0` 时等于拷贝。
///
/// 成功返回 `Ok(())`；尺寸/缓冲不合法返回 `Err`。
pub fn blur_box(
    src: &[u8],
    w: u32,
    h: u32,
    dst: &mut [u8],
    radius: u32,
) -> Result<(), &'static str> {
    if w == 0 || h == 0 {
        return Err("尺寸不能为 0");
    }
    let wi = w as usize;
    let hi = h as usize;
    let need = wi * hi * 4;
    if src.len() < need {
        return Err("源缓冲区过小");
    }
    if dst.len() < need {
        return Err("目标缓冲区过小");
    }

    if radius == 0 {
        dst[..need].copy_from_slice(&src[..need]);
        return Ok(());
    }

    let r = radius as usize;
    let n = (2 * r + 1) as u64;

    // ── 第一趟：水平 ──
    let mut tmp = vec![0u8; need];
    for y in 0..hi {
        let row = y * wi * 4;
        for x in 0..wi {
            let mut s0 = 0u64;
            let mut s1 = 0u64;
            let mut s2 = 0u64;
            let mut s3 = 0u64;
            for k in 0..(2 * r + 1) {
                // x + k - r，clamp 到 [0, wi-1]：边缘复制
                let xx = (x + k).saturating_sub(r).min(wi - 1);
                let i = row + xx * 4;
                s0 += src[i] as u64;
                s1 += src[i + 1] as u64;
                s2 += src[i + 2] as u64;
                s3 += src[i + 3] as u64;
            }
            let o = row + x * 4;
            tmp[o] = ((s0 + n / 2) / n) as u8;
            tmp[o + 1] = ((s1 + n / 2) / n) as u8;
            tmp[o + 2] = ((s2 + n / 2) / n) as u8;
            tmp[o + 3] = ((s3 + n / 2) / n) as u8;
        }
    }

    // ── 第二趟：垂直 ──
    let stride = wi * 4;
    for y in 0..hi {
        for x in 0..wi {
            let mut s0 = 0u64;
            let mut s1 = 0u64;
            let mut s2 = 0u64;
            let mut s3 = 0u64;
            for k in 0..(2 * r + 1) {
                let yy = (y + k).saturating_sub(r).min(hi - 1);
                let i = yy * stride + x * 4;
                s0 += tmp[i] as u64;
                s1 += tmp[i + 1] as u64;
                s2 += tmp[i + 2] as u64;
                s3 += tmp[i + 3] as u64;
            }
            let o = y * stride + x * 4;
            dst[o] = ((s0 + n / 2) / n) as u8;
            dst[o + 1] = ((s1 + n / 2) / n) as u8;
            dst[o + 2] = ((s2 + n / 2) / n) as u8;
            dst[o + 3] = ((s3 + n / 2) / n) as u8;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn px(v: u8) -> [u8; 4] {
        [v, v, v, 255]
    }

    #[test]
    fn radius_zero_is_identity() {
        let src: Vec<u8> = [px(10), px(200), px(30), px(90)].concat();
        let mut dst = vec![0u8; src.len()];
        blur_box(&src, 4, 1, &mut dst, 0).unwrap();
        assert_eq!(dst, src);
    }

    #[test]
    fn single_row_3_pixels_r1_clamped_edges() {
        // 3 像素、r=1、边缘复制 + 四舍五入 (sum + n/2)/n，n=3：
        // x=0: clamp(-1)=0 → (10+10+200)=220, (220+1)/3 = 73
        // x=1: (10+200+30)=240, (240+1)/3 = 80
        // x=2: clamp(3)=2 → (200+30+30)=260, (260+1)/3 = 87
        let src: Vec<u8> = [px(10), px(200), px(30)].concat();
        let mut dst = vec![0u8; src.len()];
        blur_box(&src, 3, 1, &mut dst, 1).unwrap();
        assert_eq!(dst[0], 73, "左边缘复制");
        assert_eq!(dst[4], 80, "中间");
        assert_eq!(dst[8], 87, "右边缘复制");
    }

    #[test]
    fn vertical_pass_blurs_columns() {
        // 3x1 的竖列、r=1：与上面的水平情形数值应完全一致（可分离性）
        let src: Vec<u8> = [px(10), px(200), px(30)].concat();
        let mut dst = vec![0u8; src.len()];
        blur_box(&src, 1, 3, &mut dst, 1).unwrap();
        assert_eq!(dst[0], 73);
        assert_eq!(dst[4], 80);
        assert_eq!(dst[8], 87);
    }

    #[test]
    fn flat_image_is_unchanged() {
        // 常量图经过模糊仍应是常量（边缘复制保证了这一点）
        let src: Vec<u8> = vec![px(123); 5 * 4].concat();
        let mut dst = vec![0u8; src.len()];
        blur_box(&src, 5, 4, &mut dst, 2).unwrap();
        assert!(dst.iter().enumerate().all(|(i, &v)| i % 4 == 3 || v == 123));
    }

    #[test]
    fn rejects_bad_buffers() {
        let src = [0u8; 16];
        let mut dst = [0u8; 16];
        assert!(blur_box(&src, 0, 2, &mut dst, 1).is_err());
        let mut small = [0u8; 4];
        assert!(blur_box(&src, 2, 2, &mut small, 1).is_err());
    }
}
