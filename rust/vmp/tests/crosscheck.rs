//! 三个算子的「原生实现 ↔ 虚拟机实现」逐位对拍。
//!
//! ─── 这是本 Lab 最值钱的一组测试 ───
//!
//! VMP 加固的**第一性风险**不是「保护得够不够」，而是「加固后还算得对吗」。
//! 字节码是手写的（由 `algorithms/` 生成），VM 的语义是自己定义的，
//! 这两处任何一点偏差都**不会**以崩溃的形式表现出来，而是「结果差一点」。
//! 所以对拍必须覆盖三类输入：
//!
//! 1. **可手算的小用例**（已知答案，如 dominant 的纯红、downscale 的 5→2）；
//! 2. **真实尺寸**（512×384 之类的缩略图量级，覆盖所有循环边界）；
//! 3. **退化与边界**（全透明、纯灰、1×1、放大、半径 0、奇数尺寸）。
//!
//! ─── 关于「蜜罐」的提醒（写给自己）───
//!
//! 随机输入的对拍里，如果两条路径**都错**成同一个值，测试也会通过。
//! 因此随机对拍只能证明「两者一致」，不能单独证明「两者都对」——
//! 所以每类还必须配一个**可手算**的用例钉死绝对值。见各测试的注释。

use vmp::algorithms;
use vmp::vm::{Memory, Vm};

/// 跑一个明文程序（`None` 加解密），并且只在需要时提供内存视图。
fn run_plain(
    blob: &[u8],
    inputs: &[u64],
    mem: Option<&mut Memory<'_>>,
) -> Result<(), vmp::vm::VmError> {
    let mut vm = Vm::new();
    vm.run(blob, None, inputs, mem)
}

// ─────────────────────────────────────────────────────────────────────────────
// 确定性伪随机：不用 rand crate（零依赖），也保证测试可复现。
// ─────────────────────────────────────────────────────────────────────────────
fn lcg(seed: &mut u64) -> u32 {
    *seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
    (*seed >> 33) as u32
}

/// 生成 RGBA 图像：混合「大色块」「噪声」「透明像素」「灰阶像素」，
/// 这样每个分支（hue 三分支、灰度退化、透明跳过）都会被走到。
fn synth_image(w: usize, h: usize, seed: u64) -> Vec<u8> {
    let mut s = seed;
    let mut v = Vec::with_capacity(w * h * 4);
    for i in 0..w * h {
        let r = lcg(&mut s);
        let (rr, gg, bb, aa) = match i % 7 {
            0 => (0, 0, 0, 0),                              // 全透明（应被跳过）
            1 => (200, 200, 200, 255),                      // 灰阶（进灰度分支）
            2 => (255, 0, 0, 255),                          // 纯红（hue=max==r）
            3 => (0, 255, 0, 255),                          // 纯绿（max==g）
            4 => (0, 0, 255, 255),                          // 纯蓝（else 分支）
            5 => (0, 128, 255, 255),                        // 偏蓝（max==b）
            _ => ((r & 0xff) as u8, ((r >> 8) & 0xff) as u8, ((r >> 16) & 0xff) as u8, 255),
        };
        v.extend_from_slice(&[rr, gg, bb, aa]);
    }
    v
}

// ─────────────────────────────────────────────────────────────────────────────
// dominant
// ─────────────────────────────────────────────────────────────────────────────

/// 跑 dominant 的 VM 版本，返回颜色。
fn vm_dominant(src: &[u8]) -> u64 {
    let blob = algorithms::dominant::build();
    let pixel_count = (src.len() / 4) as u64;
    let mut vm = Vm::new();
    let mut work = vec![0u8; 64];
    let mut mem = Memory { src, dst: &mut [], work: &mut work };
    vm.run(&blob, None, &[pixel_count], Some(&mut mem))
        .expect("dominant 字节码应能跑完");
    vm.stack_top(1)[0]
}

#[test]
fn dominant_vm_matches_native_on_hand_computed_cases() {
    // ① 纯红 100 像素：调色板第 0 桶 (255,63,0) × 明度 85 → 0x551500（与原生单测同值）
    let red: Vec<u8> = (0..100).flat_map(|_| [255u8, 0, 0, 255]).collect();
    assert_eq!(vm_dominant(&red), 0x55_1500, "纯红：调色板桶心 × 明度");
    assert_eq!(
        vm_dominant(&red) as u32,
        imagepipeline::dominant::dominant_color(&red).rgb,
        "必须与原生逐位一致"
    );

    // ② 纯灰 16 像素 120 → 退化灰 (120,120,120)
    let gray: Vec<u8> = (0..16).flat_map(|_| [120u8, 120, 120, 255]).collect();
    assert_eq!(vm_dominant(&gray), 0x78_7878, "纯灰退化");
    assert_eq!(
        vm_dominant(&gray) as u32,
        imagepipeline::dominant::dominant_color(&gray).rgb
    );

    // ③ 红蓝各 50（平票取小桶 → 第 0 桶）
    let mut tie: Vec<u8> = (0..50).flat_map(|_| [255u8, 0, 0, 255]).collect();
    tie.extend((0..50).flat_map(|_| [0u8, 0, 255, 255]));
    assert_eq!(
        vm_dominant(&tie) as u32,
        imagepipeline::dominant::dominant_color(&tie).rgb,
        "平票必须同样取小桶"
    );
}

#[test]
fn dominant_vm_matches_native_on_synthetic_images() {
    for (w, h, seed) in [(1usize, 1usize, 1u64), (13, 7, 2), (64, 64, 3), (129, 33, 4)] {
        let img = synth_image(w, h, seed);
        let native = imagepipeline::dominant::dominant_color(&img).rgb as u64;
        let got = vm_dominant(&img);
        assert_eq!(got, native, "dominant {w}x{h} seed={seed}");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// downscale
// ─────────────────────────────────────────────────────────────────────────────

fn vm_downscale(src: &[u8], sw: u32, sh: u32, dw: u32, dh: u32) -> Vec<u8> {
    let blob = algorithms::downscale::build();
    let xs_base = 0u64;
    let cnt_base = (2 * dw) as u64;
    let mut aux_need = (3 * dw) as usize;
    aux_need = aux_need.max(algorithms::downscale::SCALAR_BASE as usize + 16);
    let _ = aux_need;
    let mut work = vec![0u8; (dw * dh * 16) as usize];
    let mut dst = vec![0u8; (dw * dh * 4) as usize];
    let mut mem = Memory { src, dst: &mut dst, work: &mut work };
    run_plain(
        &blob,
        &[sw as u64, sh as u64, xs_base, cnt_base, dw as u64, dh as u64],
        Some(&mut mem),
    )
    .expect("downscale 字节码应能跑完");
    dst
}

#[test]
fn downscale_vm_matches_native_on_hand_computed_cases() {
    // 2x2 → 1x1：均值 85（与原生单测同值，绝对值可手算）
    let src: Vec<u8> = [[0u8, 0, 0, 255], [100, 100, 100, 255], [200, 200, 200, 255], [40, 40, 40, 255]]
        .concat();
    let got = vm_downscale(&src, 2, 2, 1, 1);
    assert_eq!(got, vec![85, 85, 85, 255], "2x2→1x1 均值");
    let mut native = [0u8; 4];
    imagepipeline::downscale::downscale_area(&src, 2, 2, &mut native, 1, 1).unwrap();
    assert_eq!(got, native.to_vec(), "必须与原生逐位一致");

    // 5x1 → 2x1：边界 [0,2) 与 [2,5)，均值 5 与 30（可手算）
    let mut src = Vec::new();
    for v in 0..5u8 {
        src.extend_from_slice(&[v * 10, 0, 0, 255]);
    }
    let got = vm_downscale(&src, 5, 1, 2, 1);
    assert_eq!(got[0], 5, "左块均值");
    assert_eq!(got[4], 30, "右块均值");
}

#[test]
fn downscale_vm_matches_native_on_real_sizes_and_edges() {
    let cases = [
        // (sw, sh, dw, dh) —— 覆盖：常见缩略图、非整除、放大、单像素、极端长宽比
        (64usize, 64usize, 16usize, 16usize),
        (512, 384, 128, 96),
        (129, 97, 33, 31), // 非整除，边界最容易差 1
        (8, 8, 3, 2),      // 原生注释里 double-rounding 出问题的那个尺寸
        (5, 1, 2, 1),
        (1, 1, 1, 1),
        (3, 3, 5, 5), // 放大
        (100, 3, 7, 2),
    ];
    for (sw, sh, dw, dh) in cases {
        let src = synth_image(sw, sh, (sw * 31 + sh * 17) as u64);
        let got = vm_downscale(&src, sw as u32, sh as u32, dw as u32, dh as u32);
        let mut native = vec![0u8; dw * dh * 4];
        imagepipeline::downscale::downscale_area(
            &src,
            sw as u32,
            sh as u32,
            &mut native,
            dw as u32,
            dh as u32,
        )
        .unwrap();
        assert!(
            got == native,
            "downscale {sw}x{sh}→{dw}x{dh} 不一致：\n vm    ={:?}\n native={:?}",
            &got[..got.len().min(32)],
            &native[..native.len().min(32)]
        );
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// blur
// ─────────────────────────────────────────────────────────────────────────────

fn vm_blur(src: &[u8], w: u32, h: u32, radius: u32) -> Vec<u8> {
    let blob = algorithms::blur::build();
    let need = (w * h * 4) as usize;
    let mut work = vec![0u8; need];
    let mut dst = vec![0u8; need];
    let mut mem = Memory { src, dst: &mut dst, work: &mut work };
    run_plain(&blob, &[w as u64, h as u64, radius as u64], Some(&mut mem))
        .expect("blur 字节码应能跑完");
    dst
}

#[test]
fn blur_vm_matches_native_on_hand_computed_cases() {
    // 3 像素、r=1、边缘复制：73 / 80 / 87（与原生单测同值，绝对值可手算）
    let src: Vec<u8> = [[10u8, 10, 10, 255], [200, 200, 200, 255], [30, 30, 30, 255]].concat();
    let got = vm_blur(&src, 3, 1, 1);
    assert_eq!(got[0], 73, "左边缘复制");
    assert_eq!(got[4], 80, "中间");
    assert_eq!(got[8], 87, "右边缘复制");

    // 竖列 1x3、r=1：与横排同值（盒式模糊可分离）
    let mut got = vm_blur(&src, 1, 3, 1);
    assert_eq!(got[0], 73);
    assert_eq!(got[4], 80);
    assert_eq!(got[8], 87);
    got.clear();

    // r=0 → 恒等
    let src4: Vec<u8> = [[10u8, 20, 30, 255], [200, 100, 50, 255], [30, 60, 90, 255], [90, 90, 90, 128]]
        .concat();
    assert_eq!(vm_blur(&src4, 4, 1, 0), src4, "半径 0 应恒等");
}

#[test]
fn blur_vm_matches_native_on_real_sizes_and_edges() {
    let cases = [
        // (w, h, radius)
        (3usize, 1usize, 1u32),
        (1, 3, 1),
        (1, 1, 2),
        (2, 2, 3),   // 半径大于图像
        (16, 16, 1),
        (33, 17, 2), // 奇数尺寸 + 小半径
        (64, 64, 6),
        (128, 96, 8),
        (40, 40, 0), // 恒等路径
    ];
    for (w, h, r) in cases {
        let src = synth_image(w, h, (w * 7 + h * 13 + r as usize) as u64);
        let got = vm_blur(&src, w as u32, h as u32, r);
        let mut native = vec![0u8; w * h * 4];
        imagepipeline::blur::blur_box(&src, w as u32, h as u32, &mut native, r).unwrap();
        assert!(
            got == native,
            "blur {w}x{h} r={r} 不一致（前 8 字节：vm={:?} native={:?}）",
            &got[..8],
            &native[..8]
        );
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 交叉检查：VM 内部一致性
// ─────────────────────────────────────────────────────────────────────────────

#[test]
fn vm_programs_are_not_stored_in_plaintext() {
    // 加密后的 blob 里，**明文头之后**不应出现「连续的、能一眼看出的操作码序列」。
    // 这里做一个弱但可复现的检查：把密文载荷与「同长度的零」比较 ——
    // 若加密没生效（比如 build.rs 忘了调用 apply_keystream），载荷会是高度结构化的，
    // 表现为「非零字节的分布极其规律」。更强的人工检查见 NOTES 的 `strings` 步骤。
    for name in vmp::PROGRAM_NAMES {
        let p = vmp::find(name).unwrap();
        let payload = &p.blob[vmp::format::HEADER_LEN..];
        let zeros = payload.iter().filter(|&&b| b == 0).count();
        assert!(
            zeros * 10 < payload.len(),
            "{name} 的密文里有过多零字节（{zeros}/{}），加密很可能没生效",
            payload.len()
        );
        // 明文头必须可读（这是设计，见 ISA.md 第 4 节）。
        // 注意字节序：MAGIC 是 u32，小端写出，所以文件里是 `31 4D 50 56`（"1MPV"）。
        assert_eq!(
            &p.blob[..4],
            &vmp::format::MAGIC.to_le_bytes(),
            "{name} 的明文头 magic 应可读"
        );
    }
}
