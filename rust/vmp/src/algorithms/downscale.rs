//! 区域平均降采样 —— 虚拟机版本。
//!
//! 与 `imagepipeline::downscale::downscale_area` **逐位一致**是硬要求。
//! 原实现的关键语义（也是它比「可分离两趟」更难写对的地方）：
//!
//! ```text
//! 边界：x0 = dx*sw/dw, x1 = max((dx+1)*sw/dw, x0+1).min(sw)     // 整数除法
//!       y0 = dy*sh/dh, y1 = max((dy+1)*sh/dh, y0+1).min(sh)
//! 累加：每个输出像素的每个通道是**整块和**，扫完整块才做一次除法
//! 取整：(sum + count/2) / count                                  // 四舍五入
//! 退化：dw==sw && dh==sh → 整段拷贝
//! ```
//!
//! ─── 为什么必须「整块和」，不能边扫边平均 ───
//!
//! `downscale.rs` 的注释里记着一次失败：可分离两趟把「求平均」做了两次、
//! 各舍入一次，8×8→3×2 就出现 native=120 vs 参考=119 的逐位差异。
//! 这不是「精度差不多」，而是直接违反对拍红线。VM 版必须复刻「只舍一次」。
//!
//! ─── VM 版怎么复刻：累加器住在 `WORK` 的 u32 槽里 ───
//!
//! 原实现每个输出行有 `dst_w × 4` 个 `u64` 累加器。VM 没有寄存器，
//! 所以把累加器放在**内存**里：每个输出像素占 `WORK` 的 16 字节（4 个 u32 槽），
//! 先清零、再累加，最后除以 `count` 取整后按 **RGBA 字节流**写进 `DST`。
//!
//! ⚠️ 这里有一个**必须写下来的坑**（第一版就踩了）：累加器是 u32 槽，
//! 若直接把最终的 `DST` 当累加器数组（u32 视图），那么输出的每个通道是
//! `[v,0,0,0]`，而原生实现写的是 RGBA **字节流** `[R,G,B,A]` ——
//! 两者布局不同，对拍必然失败，而且在 JNI 侧看起来「就差一点」，极难归因。
//! 所以累加（u32 槽，`WORK`）与落盘（u8 字节流，`DST`）**必须分开两段内存**。
//!
//! 代价是 `WORK` 需要 `dw*dh*16` 字节（比输出本身大 4 倍）——
//! 这正是「把寄存器换成内存」的账，见 NOTES 文档的代价一节。
//!
//! ─── 与原生实现的唯一差异（必须写下来）───
//!
//! 原实现累加用 `u64`；本版是 u32。二者都**不可能溢出**：单通道上界是
//! `sw × 255 ≤ 4096×255 ≈ 1.04M`，加上 `+n/2` 也远小于 2³²。所以逐位结果相同。
//! 这是「用不变量换掉一个 64 位类型」的取舍，由 JNI 层的 `MAX_DIM` 兜住 ——
//! 是「VM 加固反过来约束调用方」的一个具体例子。
//!
//! ─── 地址约定 ───
//!
//! 三段内存视图各自**恰好就是**对应的缓冲：`SRC` = 源字节流、`WORK` = 累加器暂存、
//! `DST` = 输出字节流。因此字节码里的地址一律**相对段起点**，由 JNI 层把缓冲对上。
//!
//! 输入槽：`0` sw、`1` sh、`2` xs 基址、`3` cnt 基址、`4` dw、`5` dh。
//! `aux`：`xs` 起每列 2 格（x0,x1）、`cnt` 起每列 1 格（count），
//! 标量统一放在 [`SCALAR_BASE`] 之后。

use crate::format::{out, region};

use super::builder;

/// 标量槽在 aux 里的基址。放在高地址端，避免与调用方给的 xs/cnt 区间冲突。
pub const SCALAR_BASE: i64 = 16000;
const DX: i64 = SCALAR_BASE;
const DY: i64 = SCALAR_BASE + 1;
const Y: i64 = SCALAR_BASE + 2;
const Y1: i64 = SCALAR_BASE + 3;
const ROW: i64 = SCALAR_BASE + 4;
const SROW: i64 = SCALAR_BASE + 5;
const X: i64 = SCALAR_BASE + 6;
const X1V: i64 = SCALAR_BASE + 7;
const ACC: i64 = SCALAR_BASE + 8;
const NSLOT: i64 = SCALAR_BASE + 9;

const IN_SW: i64 = 0;
const IN_SH: i64 = 1;
const IN_XS: i64 = 2;
const IN_CNT: i64 = 3;
const IN_DW: i64 = 4;
const IN_DH: i64 = 5;

/// 本程序需要的 aux 格数（含调用方区间与标量区）—— JNI 层据此校验。
pub fn aux_need(dw: usize) -> usize {
    (3 * dw).max(SCALAR_BASE as usize + 16)
}

/// 生成降采样的 VMP-1 字节码（明文容器，未加密）。
pub fn build() -> Vec<u8> {
    // 输入维度：[4]sw [4]sh [1]xs [1]cnt [4]dw [4]dh
    let mut b = builder(vec![4, 4, 1, 1, 4, 4], out::DST);
    let c4 = b.konst(4);
    let c16 = b.konst(16);
    let c2 = b.konst(2);

    // ── 预计算 x 边界表：x0[dx]、x1[dx]（放在调用方给的 xs 区间）──
    b.pushi(0).pushi(DX).auxwr(1);
    b.label("lut");
    b.pushi(DX).auxrd(1).pushn(IN_DW).cmpltu().jz("lut_done");
    // x0 = dx*sw/dw → aux[xs + dx*2]
    b.pushi(DX).auxrd(1).pushn(IN_SW).mul().pushn(IN_DW).div();
    b.pushn(IN_XS).pushi(DX).auxrd(1).pushi(2).mul().add();
    b.auxwr(1);
    // x1 = ((dx+1)*sw/dw).max(x0+1).min(sw) → aux[xs + dx*2 + 1]
    b.pushi(DX).auxrd(1).pushi(1).add().pushn(IN_SW).mul().pushn(IN_DW).div();
    b.pushn(IN_XS).pushi(DX).auxrd(1).pushi(2).mul().add().auxrd(1).pushi(1).add();
    b.maxu();
    b.pushn(IN_SW).minu();
    b.pushn(IN_XS).pushi(DX).auxrd(1).pushi(2).mul().add().pushi(1).add();
    b.auxwr(1);
    b.pushi(1).pushi(DX).auxaddu();
    b.jmp("lut");
    b.label("lut_done");

    // ── 逐输出行 ──
    b.pushi(0).pushi(DY).auxwr(1);
    b.label("dy_loop");
    b.pushi(DY).auxrd(1).pushn(IN_DH).cmpltu().jz("dy_done");

    // 清零本行累加器（`WORK` 段，每像素 16 字节 = 4 个 u32 槽）
    b.pushi(0).pushi(DX).auxwr(1);
    b.label("zero_loop");
    b.pushi(DX).auxrd(1).pushn(IN_DW).cmpltu().jz("zero_done");
    b.pushi(DY).auxrd(1).pushn(IN_DW).mul().pushi(DX).auxrd(1).add().pushm(c16).mul();
    b.pushi(ACC).auxwr(1);
    for ch in 0..4i64 {
        b.pushi(0); // 值
        b.pushi(ACC).auxrd(1).pushi(ch * 4).add(); // 地址（栈顶）
        b.dstorew(region::WORK);
    }
    // ⚠️ count[dx] 必须**每个输出行**重置。原实现里 count 是行内累加器、每 dy 重置；
    // 忘了重置就会跨行累加，`n` 变成「所有行采样数之和」，结果随图高线性变暗，
    // 而且**不报任何错**。这是把行累加器借宿到全局内存时最容易漏的一步。
    b.pushi(0).pushn(IN_CNT).pushi(DX).auxrd(1).add().auxwr(1);
    b.pushi(1).pushi(DX).auxaddu();
    b.jmp("zero_loop");
    b.label("zero_done");

    // y0 = dy*sh/dh；y1 = ((dy+1)*sh/dh).max(y0+1).min(sh)
    b.pushi(DY).auxrd(1).pushn(IN_SH).mul().pushn(IN_DH).div().pushi(Y).auxwr(1);
    b.pushi(DY).auxrd(1).pushi(1).add().pushn(IN_SH).mul().pushn(IN_DH).div();
    b.pushi(Y).auxrd(1).pushi(1).add().maxu().pushn(IN_SH).minu();
    b.pushi(Y1).auxwr(1);

    b.label("y_loop");
    b.pushi(Y).auxrd(1).pushi(Y1).auxrd(1).cmpltu().jz("y_done");
    // row = y*sw*4
    b.pushi(Y).auxrd(1).pushn(IN_SW).mul().pushm(c4).mul().pushi(ROW).auxwr(1);

    b.pushi(0).pushi(DX).auxwr(1);
    b.label("dx_loop");
    b.pushi(DX).auxrd(1).pushn(IN_DW).cmpltu().jz("dx_done");
    b.pushi(DY).auxrd(1).pushn(IN_DW).mul().pushi(DX).auxrd(1).add().pushm(c16).mul();
    b.pushi(ACC).auxwr(1);
    b.pushn(IN_XS).pushi(DX).auxrd(1).pushi(2).mul().add().auxrd(1).pushi(X).auxwr(1);
    b.pushn(IN_XS).pushi(DX).auxrd(1).pushi(2).mul().add().pushi(1).add().auxrd(1);
    b.pushi(X1V).auxwr(1);

    b.label("px_loop");
    b.pushi(X).auxrd(1).pushi(X1V).auxrd(1).cmpltu().jz("px_done");
    // 源地址是**相对 SRC 段**的字节偏移
    b.pushi(ROW).auxrd(1).pushi(X).auxrd(1).pushm(c4).mul().add().pushi(SROW).auxwr(1);
    for ch in 0..4i64 {
        b.pushi(SROW).auxrd(1).pushi(ch).add().loadb(region::SRC);
        b.pushi(ACC).auxrd(1).pushi(ch * 4).add().loadw(region::WORK);
        b.add(); // 值
        b.pushi(ACC).auxrd(1).pushi(ch * 4).add(); // 地址（栈顶）
        b.dstorew(region::WORK);
    }
    b.pushi(1).pushi(X).auxaddu();
    b.jmp("px_loop");
    b.label("px_done");

    // count[dx] += (x1 - x0)
    b.pushi(X1V).auxrd(1);
    b.pushn(IN_XS).pushi(DX).auxrd(1).pushi(2).mul().add().auxrd(1);
    b.sub();
    b.pushn(IN_CNT).pushi(DX).auxrd(1).add();
    b.auxaddu();

    b.pushi(1).pushi(DX).auxaddu();
    b.jmp("dx_loop");
    b.label("dx_done");
    b.pushi(1).pushi(Y).auxaddu();
    b.jmp("y_loop");
    b.label("y_done");

    // ── 本行取整写回：dst[(dy*dw+dx)*4 + ch] = (acc + n/2) / n ──
    b.pushi(0).pushi(DX).auxwr(1);
    b.label("fin_loop");
    b.pushi(DX).auxrd(1).pushn(IN_DW).cmpltu().jz("fin_done");
    b.pushi(DY).auxrd(1).pushn(IN_DW).mul().pushi(DX).auxrd(1).add().pushm(c16).mul();
    b.pushi(ACC).auxwr(1);
    b.pushn(IN_CNT).pushi(DX).auxrd(1).add().auxrd(1).pushi(NSLOT).auxwr(1);
    for ch in 0..4i64 {
        // 结果地址（相对 DST 段）= (dy*dw+dx)*4 + ch
        b.pushi(ACC).auxrd(1).pushi(ch * 4).add().loadw(region::WORK);
        b.pushi(NSLOT).auxrd(1).pushm(c2).div().add();
        b.pushi(NSLOT).auxrd(1).div(); // 值
        // 地址（栈顶）= (dy*dw+dx)*4 + ch
        b.pushi(DY).auxrd(1).pushn(IN_DW).mul().pushi(DX).auxrd(1).add().pushm(c4).mul().pushi(ch).add();
        b.dstoreb(region::DST);
    }
    b.pushi(1).pushi(DX).auxaddu();
    b.jmp("fin_loop");
    b.label("fin_done");

    b.pushi(1).pushi(DY).auxaddu();
    b.jmp("dy_loop");
    b.label("dy_done");
    b.halt();

    b.finish()
}
