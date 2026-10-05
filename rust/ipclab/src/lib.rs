//! `ipclab` —— Android / Linux 进程间通信（IPC）原语的**纯 Rust 实现**。
//!
//! ─── 这个 crate 的定位 ───
//!
//! 不引入 nix / mio 等高层封装，只薄薄地包一层 `libc`。理由是本 Lab 的教学目标
//! 就是「看清 syscall 本身」：`pipe2`、`socketpair`、`sendmsg(SCM_RIGHTS)`、
//! `memfd_create`、`mmap`、`kill`、`flock`、`fork`。每多一层封装，就少一条可上屏的证据。
//!
//! ─── 模块划分 ───
//!
//! - [`sys`]：原始 syscall 包装，统一把失败翻译成 `Err(-errno)`，零业务逻辑。
//! - [`demo`]：按名字分发到具体演示，供 JNI 层调用。
//!
//! ─── 错误约定（与上层 JNI 对齐）───
//!
//! sys 层所有函数返回 `Result<T, i32>`，`Err` 一律是**负 errno**（如 `-EPIPE`）。
//! 这样 FFI 层可以直接把 `Err` 透传成 Java 的负错误码，无需二次映射 —— 少一层
//! 转换就少一类「错误码错位」的静默 bug。

pub mod demo;
#[cfg(ipc_linux)]
pub mod sys;

#[cfg(target_os = "android")]
mod android_impl;

/// 本 crate 的日志前缀，便于 logcat 过滤。
pub const LOG_TAG: &str = "IpcLabNative";

/// 稳定 ABI 版本号。Kotlin 侧加载后比对，不匹配即显式降级，绝不用错布局静默算错。
///
/// 变更记录：
///   1 → 初版
pub const ABI_VERSION: i32 = 1;
