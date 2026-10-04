//! `imagepipeline` —— 纯计算的图像流水线内核。
//!
//! ─── 为什么单独一个 crate ───
//!
//! 这个 crate **零 Android、零 JNI 依赖**。它是刻意的：性能工程里最贵的是
//! 「改一行 → 装机 → 手滑 → 抓 trace」这个反馈环。把算法从 JNI 里剥出来，
//! 就能在宿主平台用 `cargo test` 秒级验证正确性，只有「真的要看端到端」时
//! 才上真机。
//!
//! ─── 像素布局：这里唯一容易搞错、且错了会静默偏色的地方 ───
//!
//! 本 crate 的所有像素 API 都按 **RGBA8888 字节流**（每像素 4 字节，顺序 R,G,B,A）约定，
//! 与 Android `Bitmap.copyPixelsToBuffer` / `copyPixelsFromBuffer` 对
//! `Config.ARGB_8888` 的语义一致。
//!
//! ⚠️ 注意名字的误导性：Android 的 config 叫 `ARGB_8888`，但那是按**整型数值**
//! 命名（`0xAARRGGBB`），不等于内存里的字节顺序。ARGB_8888 的 Bitmap 在内存中
//! 是 **RGBA 字节序**（skia 的 `kN32_SkColorType` → `kRGBA_8888`，小端）。所以：
//!
//! - ✅ Kotlin: `bitmap.copyPixelsToBuffer(directBuf)` → Rust: `&[u8]` 按 R,G,B,A 读
//! - ❌ Kotlin: `IntArray` + `bitmap.getPixels(...)` → Rust: `&[u32]` —— 会 R/B 互换
//!
//! 这条约定由 `android` crate 侧的一次性断言守护（M1），以及本 crate 的
//! 单测（构造已知色块验证通道顺序）共同保证。

#![deny(unsafe_op_in_unsafe_fn)]

/// 单像素字节数（RGBA8888）。
pub const BYTES_PER_PIXEL: usize = 4;

/// 一张 RGBA8888 图像的不可变视图。
///
/// 不持有所有权：调用方（JNI 层或本机测试）负责保证 `data` 的长度与 `width*height*4` 相符。
/// 不做拷贝是刻意的 —— 流水线的第一步收益就来自「少一次整图 memcpy」。
#[derive(Clone, Copy, Debug)]
pub struct RgbaView<'a> {
    data: &'a [u8],
    width: u32,
    height: u32,
}

impl<'a> RgbaView<'a> {
    /// 以 RGBA8888 字节流构造视图。
    ///
    /// 长度为 0 或 `width == 0 || height == 0` 视为空图；长度与尺寸不符时返回 `None`
    /// （而不是 panic：JNI 边界上的 panic 会直接杀进程）。
    pub fn new(data: &'a [u8], width: u32, height: u32) -> Option<Self> {
        let expected = (width as usize)
            .checked_mul(height as usize)?
            .checked_mul(BYTES_PER_PIXEL)?;
        if expected == 0 || data.len() < expected {
            return None;
        }
        Some(Self {
            data: &data[..expected],
            width,
            height,
        })
    }

    #[inline]
    pub fn width(&self) -> u32 {
        self.width
    }

    #[inline]
    pub fn height(&self) -> u32 {
        self.height
    }

    /// 按行读取第 `y` 行的 RGBA 字节（长度为 `width*4`）。
    #[inline]
    pub fn row(&self, y: u32) -> &'a [u8] {
        debug_assert!(y < self.height);
        let stride = self.width as usize * BYTES_PER_PIXEL;
        let start = y as usize * stride;
        &self.data[start..start + stride]
    }

    /// 读取某像素为 `(r, g, b, a)`。
    #[inline]
    pub fn pixel(&self, x: u32, y: u32) -> [u8; 4] {
        let i = ((y as usize * self.width as usize) + x as usize) * BYTES_PER_PIXEL;
        [
            self.data[i],
            self.data[i + 1],
            self.data[i + 2],
            self.data[i + 3],
        ]
    }
}

/// 一张 RGBA8888 图像的可变视图（流水线输出用）。
#[derive(Debug)]
pub struct RgbaViewMut<'a> {
    data: &'a mut [u8],
    width: u32,
    height: u32,
}

impl<'a> RgbaViewMut<'a> {
    pub fn new(data: &'a mut [u8], width: u32, height: u32) -> Option<Self> {
        let expected = (width as usize)
            .checked_mul(height as usize)?
            .checked_mul(BYTES_PER_PIXEL)?;
        if expected == 0 || data.len() < expected {
            return None;
        }
        Some(Self {
            data: &mut data[..expected],
            width,
            height,
        })
    }

    #[inline]
    pub fn width(&self) -> u32 {
        self.width
    }

    #[inline]
    pub fn height(&self) -> u32 {
        self.height
    }

    #[inline]
    pub fn set_pixel(&mut self, x: u32, y: u32, px: [u8; 4]) {
        let i = ((y as usize * self.width as usize) + x as usize) * BYTES_PER_PIXEL;
        self.data[i..i + BYTES_PER_PIXEL].copy_from_slice(&px);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn view_rejects_size_mismatch() {
        let buf = [0u8; 4];
        assert!(RgbaView::new(&buf, 2, 2).is_none(), "2x2 需要 16 字节");
        assert!(RgbaView::new(&buf, 1, 1).is_some());
        assert!(RgbaView::new(&buf, 0, 1).is_none(), "零宽视为空图");
    }

    #[test]
    fn pixel_layout_is_rgba_byte_order() {
        // 红、绿、蓝、白四个像素，直接按 RGBA 字节写。
        let buf = [
            255, 0, 0, 255, // 红
            0, 255, 0, 255, // 绿
            0, 0, 255, 255, // 蓝
            255, 255, 255, 255, // 白
        ];
        let v = RgbaView::new(&buf, 4, 1).unwrap();
        assert_eq!(v.pixel(0, 0), [255, 0, 0, 255]);
        assert_eq!(v.pixel(1, 0), [0, 255, 0, 255]);
        assert_eq!(v.pixel(2, 0), [0, 0, 255, 255]);
        assert_eq!(v.pixel(3, 0), [255, 255, 255, 255]);
    }
}
