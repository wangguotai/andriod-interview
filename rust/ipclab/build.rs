//! 构建脚本：定义一个语义化 cfg `ipc_linux`。
//!
//! ─── 为什么需要它 ───
//!
//! 本 crate 的实现全部是 **Linux 专属** syscall（`pipe2` / `memfd_create` / `SCM_RIGHTS` /
//! `flock` / `fork` / POSIX 信号）。这些在 macOS 上根本不存在，而 `target_os = "android"`
//! 在 cfg 层面**不等于 `linux`**。若直接用 `#[cfg(target_os = "linux")]` 会漏掉 Android。
//!
//! 于是用 build script 在「linux 或 android」时打开 `ipc_linux`，源码里统一写
//! `#[cfg(ipc_linux)]`。好处有二：
//!   1. 语义清晰 —— 一眼看出「这段是 Linux/Android 原生」；
//!   2. 宿主（macOS）上 `cargo build` 仍能通过（走 demo 的降级分支），CI 里做
//!      真类型检查则用 `cargo check --target aarch64-linux-android`，不依赖 NDK 链接器。

fn main() {
    // 新 rustc 要求显式声明自定义 cfg，否则 `unexpected_cfgs` 报警。
    println!("cargo:rustc-check-cfg=cfg(ipc_linux)");

    let os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if os == "linux" || os == "android" {
        println!("cargo:rustc-cfg=ipc_linux");
    }

    // 源码里没有生成文件，但保留 rerun 指令便于后续扩展。
    println!("cargo:rerun-if-changed=build.rs");
}
