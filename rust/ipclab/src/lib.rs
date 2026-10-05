//! `ipclab` —— Android / Linux 进程间通信（IPC）原语的**纯 Rust 实现**。
//!
//! ─── 这个 crate 的定位 ───
//!
//! 不引入 nix / mio 等高层封装，只薄薄地包一层 `libc`。理由是本 Lab 的教学目标
//! 就是「看清 syscall 本身」：`socketpair`、`sendmsg(SCM_RIGHTS)`、`memfd_create`、
//! `mmap`、`kill`、`flock`、`fork`。每多一层封装，就少一条可上屏的证据。
//!
//! ─── 模块划分 ───
//!
//! - [`sys`]：原始 syscall 包装，统一把失败翻译成 `Err(-errno)`，零业务逻辑。
//! - [`filelock`]：`flock` 文件锁演示。
//! - [`pipe`]：匿名管道 `pipe2` 与命名管道 FIFO 的演示。
//! - [`signal`]：POSIX 信号（`kill` + `SA_SIGINFO`）演示。
//! - [`stream`]：AF_UNIX（abstract / filesystem）字节流演示。
//! - [`demo`]：按名字分发到具体演示，供 JNI 层调用。
//!
//! ─── 错误约定（与上层 JNI 对齐）───
//!
//! sys 层所有函数返回 `Result<T, i32>`，`Err` 一律是**负 errno**（如 `-EPIPE`）。
//! 这样 FFI 层可以直接把 `Err` 透传成 Java 的负错误码，无需二次映射 —— 少一层
//! 转换就少一类「错误码错位」的静默 bug。
//!
//! ─── 一个必须在这里做掉的初始化 ───
//!
//! Rust 的 std 在**可执行程序**启动时会把 `SIGPIPE` 设为忽略，但本 crate 是被 JVM
//! `dlopen` 进来的 cdylib，那段启动代码不会跑。若不显式忽略，向已断开的 socket / pipe
//! 写数据会**直接杀掉 App 进程**（默认动作是终止），而不是返回 `EPIPE`。
//! 见 [`ensure_init`]：每个演示入口都会先调它一次。

pub mod demo;
#[cfg(ipc_linux)]
pub mod filelock;
#[cfg(ipc_linux)]
pub mod pipe;
#[cfg(ipc_linux)]
pub mod signal;
#[cfg(ipc_linux)]
pub mod stream;
#[cfg(ipc_linux)]
pub mod sys;

#[cfg(target_os = "android")]
mod android_impl;

#[cfg(ipc_linux)]
use std::sync::Once;
/// 本 crate 的日志前缀，便于 logcat 过滤。
pub const LOG_TAG: &str = "IpcLabNative";

/// 稳定 ABI 版本号。Kotlin 侧加载后比对，不匹配即显式降级，绝不用错布局静默算错。
///
/// 变更记录：
///   1 → 初版：AF_UNIX（abstract / filesystem）字节流、匿名管道 / FIFO、POSIX 信号、flock
pub const ABI_VERSION: i32 = 1;

#[cfg(ipc_linux)]
static INIT: Once = Once::new();

/// 幂等初始化：忽略 `SIGPIPE`。
///
/// 必须在任何可能写 pipe/socket 的演示之前执行一次。之所以不用构造器属性
/// （ctor / `.init_array`），是因为那类机制在不同链接方式下行为不一；
/// 用一个显式的 `Once` 由演示入口调用，最不容易「看起来设了其实没设」。
///
/// 只在 Linux/Android 上有意义：本 crate 的演示依赖 POSIX 字节流语义。
#[cfg(ipc_linux)]
pub fn ensure_init() {
    INIT.call_once(|| unsafe {
        libc::signal(libc::SIGPIPE, libc::SIG_IGN);
    });
}

#[cfg(not(ipc_linux))]
pub fn ensure_init() {}
