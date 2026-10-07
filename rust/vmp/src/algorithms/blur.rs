//! 盒式模糊 —— 虚拟机版本。
//!
//! 与 `imagepipeline::blur::blur_box` **逐位一致**是硬要求。原实现语义：
//!
//! ```text
//! n = 2r + 1
//! 第一趟（水平）src → tmp：  对每个 (y,x) 的每个通道
//!     sum over k in 0..n of src[ y*w*4 + clamp(x+k-r, 0, w-1)*4 + ch ]
//!     写回 (sum + n/2)/n
//! 第二趟（垂直）tmp → dst：  对每个 (y,x) 的每个通道
//!     sum over k in 0..n of tmp[ clamp(y+k-r, 0, h-1)*w*4 + x*4 + ch ]
//!     写回 (sum + n/2)/n
//! radius == 0 → 整段拷贝
//! ```
//!
//! ─── 两趟都落 u8 是**正确性要求**，不是实现细节 ───
//!
//! `blur.rs` 的注释里专门区分过：box blur 的两趟**不会**引入 downscale 里那种
//! double-rounding 问题，因为「水平模糊后的图」本身就是一个有意义的中间态，
//! 两趟各自四舍五入正是这个算子的定义。VM 版必须保持「中间结果落 u8」——
//! 若把中间结果留在 u64/u32，结果会系统性偏亮，差异小到极难发现。
//! 因此第一趟的输出写进 `WORK`（u8），第二趟从 `WORK` 读、写 `DST`。
//!
//! ─── 无符号 clamp 的坑 ───
//!
//! `x + k - r` 在 `x + k < r` 时**下溢**成接近 2⁶⁴ 的大数，此时 `min(w-1)`
//! 会得到 `w-1`（而不是期望的 0），边缘取错像素。原实现用 `saturating_sub`
//! 避免它，而 VMP-1 没有饱和减法 —— 所以字节码里必须显式写成「先比较、
//! 再选减法或 0」。这是**ISA 表达能力不足时必须在字节码里补回来**的典型例子：
//! 加固不会替你修正语义，只会把语义的每个细节都摊开。
//!
//! ─── 别名问题：为什么第二趟不能就地写 ───
//!
//! 第二趟要「读 work、写结果」。如果为了省一块缓冲让第二趟**就地**改写 work，
//! 那么「读到的 yy 行」可能已被本趟改过，结果会随扫描方向系统性偏移。
//! 所以第二趟必须写到另一块内存（这里直接写 `DST`）。
//! 这正是加固这类算子时最常被忽略的一类代价 —— 不是 CPU，而是**内存预算**。
//!
//! ─── 地址约定 ───
//!
//! 三段视图各自就是对应缓冲，字节码里的地址一律**相对段起点**
//! （`SRC` = 源、`WORK` = 中间结果、`DST` = 输出），由 JNI 层把缓冲对上。
//!
//! 输入槽：`0` w、`1` h、`2` radius。

use crate::asm::ProgBuilder;
use crate::format::{out, region};

use super::builder;

// aux 标量槽
const Y: i64 = 0;
const X: i64 = 1;
const K: i64 = 2;
const N: i64 = 3;
const II: i64 = 4;
const COORD: i64 = 5;
const W1: i64 = 6;
const H1: i64 = 7;
const S0: i64 = 9; // s0..s3 = 9..12
const TMP: i64 = 14;

const IN_W: i64 = 0;
const IN_H: i64 = 1;
const IN_R: i64 = 2;

/// 生成盒式模糊的 VMP-1 字节码（明文容器，未加密）。
pub fn build() -> Vec<u8> {
    let mut b = builder(vec![4, 4, 1], out::WORK);
    let c4 = b.konst(4);

    // n = 2r + 1；w-1、h-1
    b.pushn(IN_R).pushi(2).mul().pushi(1).add().pushi(N).auxwr(1);
    b.pushn(IN_W).pushi(1).sub().pushi(W1).auxwr(1);
    b.pushn(IN_H).pushi(1).sub().pushi(H1).auxwr(1);

    // radius == 0 → 整段拷贝 src → dst（**不是 work**：结果是最终输出，见下）
    b.pushn(IN_R).jz("copy");
    b.jmp("pass1");

    b.label("copy");
    b.pushi(0).pushi(II).auxwr(1);
    b.label("copy_loop");
    b.pushi(II).auxrd(1);
    b.pushn(IN_W).pushn(IN_H).mul().pushm(c4).mul();
    b.cmpltu().jz("done");
    // 值 = src[ii]（`LOADB` 会弹掉它自己的地址 ii）
    b.pushi(II).auxrd(1).loadb(region::SRC);
    // 地址 = ii（压栈顶，与 DSTOREB 的「地址在栈顶」一致）
    b.pushi(II).auxrd(1);
    // ⚠️ 输出必须落 DST：r=0 时没有第二趟，work 里的东西永远不会被搬到 dst。
    // 第一版写成 work，症状是「r=0 时输出全零」—— 一个只有在对拍里才暴露的错。
    b.dstoreb(region::DST);
    b.pushi(1).pushi(II).auxaddu();
    b.jmp("copy_loop");

    // ─────────────────── 第一趟：水平（src → work）───────────────────
    b.label("pass1");
    b.pushi(0).pushi(Y).auxwr(1);
    b.label("p1_y");
    b.pushi(Y).auxrd(1).pushn(IN_H).cmpltu().jz("p1_y_done");
    b.pushi(0).pushi(X).auxwr(1);
    b.label("p1_x");
    b.pushi(X).auxrd(1).pushn(IN_W).cmpltu().jz("p1_x_done");
    reset_sums(&mut b);
    b.pushi(0).pushi(K).auxwr(1);
    b.label("p1_k");
    b.pushi(K).auxrd(1).pushi(N).auxrd(1).cmpltu().jz("p1_k_done");
    // xx = clamp(x + k - r, 0, w-1) → COORD
    emit_clamp(&mut b, "p1", X, W1);
    // ii = y*w*4 + xx*4
    b.pushi(Y).auxrd(1).pushn(IN_W).mul().pushm(c4).mul();
    b.pushi(COORD).auxrd(1).pushm(c4).mul().add();
    b.pushi(II).auxwr(1);
    emit_accum(&mut b, region::SRC);
    b.pushi(1).pushi(K).auxaddu();
    b.jmp("p1_k");
    b.label("p1_k_done");
    emit_row_base(&mut b);
    emit_store(&mut b, region::WORK);
    b.pushi(1).pushi(X).auxaddu();
    b.jmp("p1_x");
    b.label("p1_x_done");
    b.pushi(1).pushi(Y).auxaddu();
    b.jmp("p1_y");
    b.label("p1_y_done");

    // ─────────────────── 第二趟：垂直（work → dst）───────────────────
    b.label("pass2");
    b.pushi(0).pushi(Y).auxwr(1);
    b.label("p2_y");
    b.pushi(Y).auxrd(1).pushn(IN_H).cmpltu().jz("done");
    b.pushi(0).pushi(X).auxwr(1);
    b.label("p2_x");
    b.pushi(X).auxrd(1).pushn(IN_W).cmpltu().jz("p2_x_done");
    reset_sums(&mut b);
    b.pushi(0).pushi(K).auxwr(1);
    b.label("p2_k");
    b.pushi(K).auxrd(1).pushi(N).auxrd(1).cmpltu().jz("p2_k_done");
    // yy = clamp(y + k - r, 0, h-1) → COORD
    emit_clamp(&mut b, "p2", Y, H1);
    // ii = yy*w*4 + x*4
    b.pushi(COORD).auxrd(1).pushn(IN_W).mul().pushm(c4).mul();
    b.pushi(X).auxrd(1).pushm(c4).mul().add();
    b.pushi(II).auxwr(1);
    emit_accum(&mut b, region::WORK);
    b.pushi(1).pushi(K).auxaddu();
    b.jmp("p2_k");
    b.label("p2_k_done");
    emit_row_base(&mut b);
    emit_store(&mut b, region::DST);
    b.pushi(1).pushi(X).auxaddu();
    b.jmp("p2_x");
    b.label("p2_x_done");
    b.pushi(1).pushi(Y).auxaddu();
    b.jmp("p2_y");

    b.label("done");
    b.halt();

    b.finish()
}

/// 清零四通道累加器 s0..s3。
fn reset_sums(b: &mut ProgBuilder) {
    for s in 0..4i64 {
        b.pushi(0).pushi(S0 + s).auxwr(1);
    }
}

/// `coord = (a + k >= r) ? min(a+k-r, limit) : 0` —— 手工展开的 `saturating_sub`。
///
/// `tag` 让每次调用的标签唯一（汇编器的标签是全局的，重名会 panic）。
fn emit_clamp(b: &mut ProgBuilder, tag: &str, a: i64, limit: i64) {
    b.pushi(a).auxrd(1).pushi(K).auxrd(1).add();
    b.pushi(TMP).auxwr(1);
    b.pushi(TMP).auxrd(1).pushn(IN_R).cmpltu();
    b.jz(&format!("clamp_sub_{tag}"));
    b.pushi(0);
    b.jmp(&format!("clamp_have_{tag}"));
    b.label(&format!("clamp_sub_{tag}"));
    b.pushi(TMP).auxrd(1).pushn(IN_R).sub();
    b.label(&format!("clamp_have_{tag}"));
    b.pushi(limit).auxrd(1).minu();
    b.pushi(COORD).auxwr(1);
}

/// 在 `II` 处把 4 个通道累加进 s0..s3。
fn emit_accum(b: &mut ProgBuilder, src_region: u8) {
    for ch in 0..4i64 {
        b.pushi(II).auxrd(1).pushi(ch).add().loadb(src_region);
        b.pushi(S0 + ch).auxaddu();
    }
}

/// 把 `o = y*w*4 + x*4` 写进 `TMP`（供 [`emit_store`] 用）。
fn emit_row_base(b: &mut ProgBuilder) {
    b.pushi(Y).auxrd(1).pushn(IN_W).mul().pushi(4).mul();
    b.pushi(X).auxrd(1).pushi(4).mul().add();
    b.pushi(TMP).auxwr(1);
}

/// `out[o + ch] = (s[ch] + n/2) / n`，地址相对输出段起点。
fn emit_store(b: &mut ProgBuilder, dst_region: u8) {
    for ch in 0..4i64 {
        // 值 = (s[ch] + n/2) / n
        b.pushi(S0 + ch).auxrd(1).pushi(N).auxrd(1).pushi(2).div().add();
        b.pushi(N).auxrd(1).div();
        // 地址（栈顶）= o + ch
        b.pushi(TMP).auxrd(1).pushi(ch).add();
        b.dstoreb(dst_region);
    }
}
