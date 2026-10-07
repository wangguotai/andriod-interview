//! 宿主机（x86-64 / aarch64 macOS）上的「解释 vs 直接」基准。
//!
//! ─── 它补充设备端基准的哪一块 ───
//!
//! 设备端基准（`vmp-lab/src/androidTest/…/VmpBenchmarkTest.kt`）给出的是
//! **端到端**结论：Kotlin → JNI → VM 的总耗时比。但那个比值混了两件事：
//!   1. 解释执行的固有开销（每条指令一次 `match` 派发、栈操作、可选解密）；
//!   2. 运行环境的相对性能（模拟器的 native 路径跑在 host 上，
//!      而 VM 路径要反复穿 JNI/解释循环，两者的「缩放」并不一致）。
//!
//! 这个宿主基准跑**同一个 `VmExec`**（与 JNI 共用，见 `exec.rs` 的说明），
//! 因此它测的就是「同机、同编译选项下，解释 vs 直接」的纯比值 ——
//! 可以拿来回答「设备上看到的 1000x 里，有多少是解释器的锅」。
//!
//! 运行（必须 release，debug 下这个数没有意义；要显式 `--ignored`）：
//! ```bash
//! RUSTUP_HOME=… CARGO_HOME=… \
//!   cargo test -p vmp_android --release --test host_bench -- --ignored --nocapture
//! ```
//!
//! `#[ignore]` 是刻意的：它要跑 ~70 秒（三个算子 × 2 条路径 × 101 次计时），
//! 不该拖慢 `cargo test` 的默认回路。Rust 里长基准就是这么隔离的 ——
//! 默认忽略、需要时显式点名。

use std::time::Instant;

use vmp_android::exec::{native, VmExec};

fn synth(w: usize, h: usize) -> Vec<u8> {
    let mut v = Vec::with_capacity(w * h * 4);
    for i in 0..w * h {
        v.extend_from_slice(&[
            ((i * 7) & 0xff) as u8,
            ((i * 13) & 0xff) as u8,
            ((i * 29) & 0xff) as u8,
            255,
        ]);
    }
    v
}

/// 暖机 + 计时，返回 (中位 µs, 最小 µs)。
///
/// 为什么两个都要：native 的几个算子在 host 上只要几十 µs，
/// **中位数会被机器上的其它负载污染**（实测 native blur 在 76~219µs 间跳），
/// 于是比值能飘 3 倍。CPU-bound 的 microbenchmark 里，**最小值**才是
/// 最接近「无干扰」的估计量（一次没有被抢占的执行）。
/// 文档里两个都报，并明确指出比值的可信带宽。
fn bench(warmup: usize, iters: usize, mut f: impl FnMut()) -> (f64, f64) {
    for _ in 0..warmup {
        f();
    }
    let mut us = Vec::with_capacity(iters);
    for _ in 0..iters {
        let t = Instant::now();
        f();
        us.push(t.elapsed().as_nanos() as f64 / 1000.0);
    }
    us.sort_by(|a, b| a.partial_cmp(b).unwrap());
    (us[iters / 2], us[0])
}

#[test]
#[ignore = "长基准（~70s）：需要显式 --ignored；见文件头的运行说明"]
fn host_interpret_vs_direct() {
    let (w, h) = (256usize, 192usize);
    let src = synth(w, h);
    let mut ex = VmExec::new();

    let (dw, dh) = (64usize, 48usize);
    let mut ds = vec![0u8; dw * dh * 4];
    let mut bl = vec![0u8; w * h * 4];

    // ── dominant ──
    let (na_dom, na_dom_min) = bench(5, 101, || {
        std::hint::black_box(native::dominant(std::hint::black_box(&src), w as u32, h as u32).unwrap());
    });
    let (vm_dom, vm_dom_min) = bench(5, 101, || {
        std::hint::black_box(ex.dominant(std::hint::black_box(&src), w as u32, h as u32).unwrap());
    });
    // ⚠️ 必须在**紧接着**这次测量之后取 steps：`ex.last_steps()` 是「上一次运行」的，
    // 任何后续调用都会覆盖它。第一版把三处的 steps 都放到最后取，于是 dominant
    // 那一行印出的是 blur 的指令数（4.55e7 vs 真实的 6.3e6），ns/指令随之错成 1/7。
    let vm_steps_dom = ex.last_steps();

    // ── downscale ──
    let (na_ds, na_ds_min) = bench(5, 101, || {
        native::downscale(&src, w as u32, h as u32, &mut ds, dw as u32, dh as u32).unwrap();
    });
    let (vm_ds, vm_ds_min) = bench(5, 101, || {
        ex.downscale(&src, w as u32, h as u32, &mut ds, dw as u32, dh as u32).unwrap();
    });
    let vm_steps_ds = ex.last_steps();
    let vm_pages_ds = ex.last_fetch_pages();
    let vm_hits_ds = ex.last_cache_hits();

    // ── blur ──
    let (na_bl, na_bl_min) = bench(5, 101, || {
        native::blur(&src, w as u32, h as u32, &mut bl, 2).unwrap();
    });
    let (vm_bl, vm_bl_min) = bench(5, 101, || {
        ex.blur(&src, w as u32, h as u32, &mut bl, 2).unwrap();
    });
    let vm_steps_bl = ex.last_steps();

    println!();
    println!("=== 宿主基准（解释 vs 直接，同一份 VmExec / release，暖机 5 / 计时 101）===");
    println!("算子                     native中位  VM中位   中位比值 | native最小  VM最小  最小比值 | VM指令数  ns/指令");
    row("dominant 256x192", na_dom, vm_dom, na_dom_min, vm_dom_min, vm_steps_dom);
    row("downscale 256x192→64x48", na_ds, vm_ds, na_ds_min, vm_ds_min, vm_steps_ds);
    row("blur 256x192 r=2", na_bl, vm_bl, na_bl_min, vm_bl_min, vm_steps_bl);
    println!();
    println!("downscale 页取证：解密 {vm_pages_ds} 页 / 命中 {vm_hits_ds} 次");
    // 注意：Rust 的字符串**不能**用 `+` 拼接（与 Kotlin 不同），
    // 要么写成相邻字面量的 `concat!`，要么放一个长字面量里。
    println!(
        "（设备端比值见 NOTES-vmp-lab.md；**最小比值**更可信 —— native 侧只有几十 µs，\
         中位数容易被机器上的其它负载抬高）"
    );
    println!();
}

fn row(label: &str, na: f64, vm: f64, na_min: f64, vm_min: f64, steps: u64) {
    println!(
        "{:24} {:9.1} {:8.1} {:8.1}x | {:9.1} {:8.1} {:8.1}x | {:10} {:8.2}",
        label,
        na,
        vm,
        vm / na,
        na_min,
        vm_min,
        vm_min / na_min,
        steps,
        vm_min * 1000.0 / steps as f64,
    );
}
