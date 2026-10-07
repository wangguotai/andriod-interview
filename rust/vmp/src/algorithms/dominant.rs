//! 主色调提取 —— 虚拟机版本。
//!
//! 与 `imagepipeline::dominant::dominant_color` **逐位一致**是硬要求。
//! 原实现的语义（逐行对照写下来的，别凭记忆）：
//!
//! ```text
//! 对每个像素 (r,g,b,a)：
//!   a == 0                    → 跳过
//!   max = max(r,g,b)，min = min(r,g,b)，delta = max - min
//!   luma = (r + g + b) / 3                     // 整数除法（截断）
//!   delta == 0                → gray_sum += luma; gray_n += 1; 跳过
//!   hue = 整数色相（见下）
//!   bucket = (hue / 30) % 12
//!   bucket_weight[bucket] += delta
//!   bucket_luma[bucket]   += luma * delta
//!   counted += 1
//! 取权重最大的桶（平票取小下标）
//!   权重为 0 → rgb = 灰(avg luma)，bucket=0，weight=0
//!   否则     → rgb = palette[best] × (bucket_luma[best] / weight)，截断到 255
//! ```
//!
//! 整数色相（`max == r` 优先判定，**顺序不能换**；`60*(g-b)/delta` 里
//! `g-b` 可能是负数，按「向零截断」做有符号除法）：
//!
//! ```text
//! if max == r:  hue = 60*(g-b)/delta
//! elif max == g: hue = 120 + 60*(b-r)/delta
//! else:          hue = 240 + 60*(r-g)/delta
//! hue = (hue % 360 + 360) % 360
//! ```
//!
//! 通道顺序也是语义的一部分：原实现用 `max == r` 而不是 `>=`，
//! 所以当 max 同时等于 r 与 g 时走 R 分支。字节码里必须复刻这个优先级。
//!
//! ─── 一个必须踩过才会记住的坑 ───
//!
//! 调色板缩放 `color * luma / 255` 必须用**无符号除法**（`DIV`），不是 `IDIV`。
//! 这里两个操作数都是非负，看起来无所谓 —— 但 VM 的 `IDIV` 是向零截断、
//! `DIV` 是无符号截断，一旦有人往这条链上塞进负数（比如误用了 `LOADBS`），
//! 结果的差异会藏在「颜色差一点」里，而不是报错。所以选 `DIV` 并把理由写下来。
//!
//! ─── VM 侧存储安排 ───
//!
//! `aux`：`[0..12]` 权重、`[12..24]` 亮度×权重、`[24]` gray_sum、`[25]` gray_n、
//! `[26]` counted、`[27]` i、`[28]` o、`[29..33]` r/g/b/a、`[33]` max、`[34]` min、
//! `[35]` delta、`[36]` luma、`[37]` hue、`[38]` bucket、`[39]` best、`[40]` bestw、
//! `[41]` m。
//!
//! 输入槽：`0 = pixel_count`。`SRC` 段的地址是相对段起点的字节偏移（由 JNI 层把缓冲对上）。
//! 结果（`0x00RRGGBB`）压在栈顶。
//!
//! 常量池：[0]=12、[1]=3、[2]=30、[3]=60、[4]=120、[5]=240、[6]=255、[7]=360、
//! [8..20]=调色板 R、[20..32]=G、[32..44]=B —— 三张表**连续且各 12 项**，
//! 因此「按 best 查表」就是 `PUSHMD(8+best)` / `PUSHMD(20+best)` / `PUSHMD(32+best)`。
//! 用「基址 + 运行时下标」而不是在字节码里写 if/else 链，是为了让调色板
//! 保持成一整块**数据**而不是十二条控制流 —— 前者反编译时只是一张表，后者会散开。

use crate::format::{out, region};

use super::builder;

// aux 槽位（与上面的说明一一对应）
const WEIGHT_BASE: i64 = 0;
const LUMA_BASE: i64 = 12;
const GRAY_SUM: i64 = 24;
const GRAY_N: i64 = 25;
const COUNTED: i64 = 26;
const I_SLOT: i64 = 27;
const O_SLOT: i64 = 28;
const R_SLOT: i64 = 29;
const G_SLOT: i64 = 30;
const B_SLOT: i64 = 31;
const A_SLOT: i64 = 32;
const MAX_SLOT: i64 = 33;
const MIN_SLOT: i64 = 34;
const DELTA_SLOT: i64 = 35;
const LUMA_SLOT: i64 = 36;
const HUE_SLOT: i64 = 37;
const BUCKET_SLOT: i64 = 38;
const BEST_SLOT: i64 = 39;
const BESTW_SLOT: i64 = 40;
const M_SLOT: i64 = 41;

/// 调色板 —— 必须与 `imagepipeline::dominant::BUCKET_PALETTE` 逐字节相同。
pub const BUCKET_PALETTE: [(u8, u8, u8); 12] = [
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

/// 生成主色调的 VMP-1 字节码（明文容器，未加密）。
pub fn build() -> Vec<u8> {
    let mut b = builder(vec![1], out::WORK);

    // ── 常量池：标量在前，三张调色板表紧随其后（下标连续）──
    let c12 = b.konst(12);
    let c3 = b.konst(3);
    let c30 = b.konst(30);
    let c60 = b.konst(60);
    let c120 = b.konst(120);
    let c240 = b.konst(240);
    let c255 = b.konst(255);
    let c360 = b.konst(360);
    // 三张表必须用 `konst_force`（不去重），否则重复分量会被折叠、下标不再连续，
    // 而 `PUSHMD(base + best)` 会静默查错项 —— 见 `asm.rs` 的注释。
    let pal_r = {
        let base = b.konst_force(BUCKET_PALETTE[0].0 as u64);
        for c in BUCKET_PALETTE.iter().skip(1) {
            b.konst_force(c.0 as u64);
        }
        base
    };
    let pal_g = {
        let base = b.konst_force(BUCKET_PALETTE[0].1 as u64);
        for c in BUCKET_PALETTE.iter().skip(1) {
            b.konst_force(c.1 as u64);
        }
        base
    };
    let pal_b = {
        let base = b.konst_force(BUCKET_PALETTE[0].2 as u64);
        for c in BUCKET_PALETTE.iter().skip(1) {
            b.konst_force(c.2 as u64);
        }
        base
    };
    assert_eq!(pal_g, pal_r + 12, "调色板 R/G 表必须连续");
    assert_eq!(pal_b, pal_r + 24, "调色板 G/B 表必须连续");

    // ── 初始化 ──
    for i in 0..12i64 {
        b.pushi(0).pushi(WEIGHT_BASE + i).auxwr(1);
        b.pushi(0).pushi(LUMA_BASE + i).auxwr(1);
    }
    for s in [GRAY_SUM, GRAY_N, COUNTED, I_SLOT] {
        b.pushi(0).pushi(s).auxwr(1);
    }

    // ── 主循环 ──
    b.label("pixel");
    b.pushi(I_SLOT).auxrd(1).pushn(0).cmpltu().jz("end_pixel");

    // o = i*4
    b.pushi(I_SLOT).auxrd(1).pushi(4).mul().pushi(O_SLOT).auxwr(1);

    // r/g/b/a ← src[o + k]（SRC 段的地址是相对段起点的字节偏移）
    for (k, slot) in [(0i64, R_SLOT), (1, G_SLOT), (2, B_SLOT), (3, A_SLOT)] {
        b.pushi(O_SLOT).auxrd(1).pushi(k).add();
        b.loadb(region::SRC);
        b.pushi(slot).auxwr(1);
    }

    // alpha == 0 → 跳过
    b.pushi(A_SLOT).auxrd(1).jz("next_pixel");

    // max / min / delta / luma
    b.pushi(R_SLOT).auxrd(1).pushi(G_SLOT).auxrd(1);
    b.pushi(B_SLOT).auxrd(1).maxi().maxi();
    b.pushi(MAX_SLOT).auxwr(1);
    b.pushi(R_SLOT).auxrd(1).pushi(G_SLOT).auxrd(1);
    b.pushi(B_SLOT).auxrd(1).mini().mini();
    b.pushi(MIN_SLOT).auxwr(1);
    b.pushi(MAX_SLOT).auxrd(1).pushi(MIN_SLOT).auxrd(1).sub().pushi(DELTA_SLOT).auxwr(1);
    b.pushi(R_SLOT).auxrd(1).pushi(G_SLOT).auxrd(1).add();
    b.pushi(B_SLOT).auxrd(1).add().pushm(c3).div().pushi(LUMA_SLOT).auxwr(1);

    // delta == 0 → 灰度
    b.pushi(DELTA_SLOT).auxrd(1).jz("gray");

    // ── hue 三分支（顺序：先 r，再 g，否则 else）──
    b.pushi(MAX_SLOT).auxrd(1).pushi(R_SLOT).auxrd(1).eq().jnz("hue_r");
    b.pushi(MAX_SLOT).auxrd(1).pushi(G_SLOT).auxrd(1).eq().jnz("hue_g");
    // else: 240 + 60*(r-g)/delta
    b.pushm(c240)
        .pushm(c60)
        .pushi(R_SLOT).auxrd(1).pushi(G_SLOT).auxrd(1).sub()
        .mul()
        .pushi(DELTA_SLOT).auxrd(1).idiv()
        .add()
        .jmp("hue_done");
    b.label("hue_r");
    // 60*(g-b)/delta
    b.pushm(c60)
        .pushi(G_SLOT).auxrd(1).pushi(B_SLOT).auxrd(1).sub()
        .mul()
        .pushi(DELTA_SLOT).auxrd(1).idiv()
        .jmp("hue_done");
    b.label("hue_g");
    // 120 + 60*(b-r)/delta
    b.pushm(c120)
        .pushm(c60)
        .pushi(B_SLOT).auxrd(1).pushi(R_SLOT).auxrd(1).sub()
        .mul()
        .pushi(DELTA_SLOT).auxrd(1).idiv()
        .add();
    b.label("hue_done");
    // 规范化：(hue % 360 + 360) % 360
    b.pushi(HUE_SLOT).auxwr(1);
    b.pushi(HUE_SLOT).auxrd(1).pushm(c360).irem()
        .pushm(c360).add()
        .pushm(c360).irem()
        .pushi(HUE_SLOT).auxwr(1);

    // bucket = (hue / 30) % 12
    b.pushi(HUE_SLOT).auxrd(1).pushm(c30).div().pushm(c12).rem().pushi(BUCKET_SLOT).auxwr(1);

    // bucket_weight[bucket] += delta
    b.pushi(DELTA_SLOT).auxrd(1);
    b.pushi(WEIGHT_BASE).pushi(BUCKET_SLOT).auxrd(1).add();
    b.auxaddu();
    // bucket_luma[bucket] += luma * delta
    b.pushi(LUMA_SLOT).auxrd(1).pushi(DELTA_SLOT).auxrd(1).mul();
    b.pushi(LUMA_BASE).pushi(BUCKET_SLOT).auxrd(1).add();
    b.auxaddu();
    // counted += 1
    b.pushi(1).pushi(COUNTED).auxaddu();

    b.jmp("next_pixel");

    // ── 灰度分支 ──
    b.label("gray");
    b.pushi(LUMA_SLOT).auxrd(1).pushi(GRAY_SUM).auxaddu();
    b.pushi(1).pushi(GRAY_N).auxaddu();

    // ── 循环尾 ──
    b.label("next_pixel");
    b.pushi(1).pushi(I_SLOT).auxaddu();
    b.jmp("pixel");

    // ── 找最大权重桶（平票取小下标）──
    b.label("end_pixel");
    b.pushi(0).pushi(BEST_SLOT).auxwr(1);
    b.pushi(BEST_SLOT).auxrd(1).auxrd(1); // w = aux[0]
    b.pushi(BESTW_SLOT).auxwr(1);
    b.pushi(1).pushi(I_SLOT).auxwr(1);
    b.label("scan");
    b.pushi(I_SLOT).auxrd(1).pushm(c12).cmpltu().jz("scan_done");
    // 求值 w = aux[i]，与 bestw 比较；比较消费两份 w，故先复制一份
    b.pushi(I_SLOT).auxrd(1).auxrd(1); // w
    b.dup(); // w w
    b.pushi(BESTW_SLOT).auxrd(1); // w w bestw
    b.cmpgtu(); // w (w>bestw)
    b.jz("scan_no_update"); // w
    // 更新：bestw = w；best = i
    b.pushi(BESTW_SLOT).auxwr(1);
    b.pushi(I_SLOT).auxrd(1).pushi(BEST_SLOT).auxwr(1);
    b.jmp("scan_next");
    b.label("scan_no_update");
    b.pop();
    b.label("scan_next");
    b.pushi(1).pushi(I_SLOT).auxaddu();
    b.jmp("scan");

    b.label("scan_done");
    // bestw == 0 → 灰色退化
    b.pushi(BESTW_SLOT).auxrd(1).jz("gray_fallback");

    // ── 有主色 ──
    // m = (aux[12+best] / bestw).min(255)
    b.pushi(LUMA_BASE).pushi(BEST_SLOT).auxrd(1).add().auxrd(1);
    b.pushi(BESTW_SLOT).auxrd(1).div().pushm(c255).minu();
    b.pushi(M_SLOT).auxwr(1);
    // rgb = (R<<16)|(G<<8)|B，每个通道 = palette * m / 255（无符号截断）
    for (base, shift) in [(pal_r, 16i64), (pal_g, 8), (pal_b, 0)] {
        // ⚠️ `PUSHMD` 要的是**常量池下标**，所以这里用 `pushi(base)` 压下标本身，
        // 而不是 `pushm(base)`（那是压 const[base] 的**值**）。写成后者时的症状
        // 是「下标越界」或（更糟）查到一个完全无关的常量 —— 又一次「差一点」的错。
        b.pushi(base).pushi(BEST_SLOT).auxrd(1).add().pushmd().pushi(M_SLOT).auxrd(1).mul();
        b.pushm(c255).div();
        if shift > 0 {
            b.pushi(shift).shl();
        }
    }
    b.or().or();
    b.halt();

    // ── 灰色退化 ──
    b.label("gray_fallback");
    // l = gray_n > 0 ? gray_sum/gray_n : 0，再 min(255)（原实现里 255 上限是死代码，但保持一致）
    b.pushi(GRAY_N).auxrd(1);
    b.jnz("gray_avg");
    b.pushi(0).jmp("gray_have");
    b.label("gray_avg");
    b.pushi(GRAY_SUM).auxrd(1).pushi(GRAY_N).auxrd(1).div();
    b.label("gray_have");
    b.pushm(c255).minu();
    b.pushi(LUMA_SLOT).auxwr(1);
    // rgb = (l<<16)|(l<<8)|l
    b.pushi(LUMA_SLOT).auxrd(1).pushi(16).shl();
    b.pushi(LUMA_SLOT).auxrd(1).pushi(8).shl().or();
    b.pushi(LUMA_SLOT).auxrd(1).or();
    b.halt();

    b.finish()
}
