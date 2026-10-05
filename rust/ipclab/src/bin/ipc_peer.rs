//! `ipc_peer` —— 一个**独立的本机进程**，用于演示「跨进程」而不是「同进程两个线程」。
//!
//! ─── 诚实说明：本 Lab 为什么没有把它当作默认路径 ───
//!
//! 直觉上「跨进程 IPC」最好用「两个真进程」。但在 Android 上有一个现实约束：
//! 应用进程**不允许 exec** 另一个可执行文件来跑演示（SELinux + 应用沙箱，
//! 从 API 29 起对 `exec` 的限制更严）。把本二进制塞进 APK 的 `nativeLibraryDir`
//! 再 `ProcessBuilder` 拉起，在多数设备/系统版本上会被拒。
//!
//! 所以本 Lab 的**主力路径**是：
//!   - Binder：真正的两个进程（`android:process=":ipc_remote"`），最贴近 Android 现实；
//!   - LocalSocket / TCP：客户端在跨进程 Service 里，也是真进程；
//!   - 原生 pipe/FIFO/shm/signal/flock：走 `fork()`（Android 的 zygote 模型本身就依赖 fork），
//!     子进程只跑 async-signal-safe 代码、绝不 exec —— 这在应用进程里是允许的。
//!
//! 本二进制只在**有 adb shell 权限**时手动跑（此时是真正的独立进程，用它来交叉验证
//! FIFO / 共享内存的语义），用法见 `ipc-lab/NOTES-ipc-lab.md`。它刻意不依赖 Android，
//! 只调用 `ipclab` 的纯 Rust 演示。

#[cfg(ipc_linux)]
fn usage() {
    eprintln!(
        "ipc_peer —— ipclab 的独立进程对端\n\
         \n\
         用法:\n\
           ipc_peer pipe             运行匿名管道演示\n\
           ipc_peer fifo <path>      运行命名管道演示\n\
           ipc_peer shm <text>       运行 memfd 共享内存 + SCM_RIGHTS 演示\n\
           ipc_peer signal           运行跨进程信号演示\n\
           ipc_peer flock <path>     运行 flock 演示\n\
           ipc_peer unix <path>      运行 AF_UNIX（abstract/filesystem）自测\n"
    );
}

#[cfg(ipc_linux)]
fn main() {
    let args: Vec<String> = std::env::args().collect();
    let kind = args.get(1).cloned().unwrap_or_default();
    if kind.is_empty() || kind == "-h" || kind == "--help" {
        usage();
        return;
    }
    let arg = args.get(2).cloned().unwrap_or_default();

    // 直接复用库里的编排，确保「独立进程」与「库内调用」跑的是同一段代码。
    let (available, _ok, log) = ipclab::demo::run(&kind, &arg);
    if !available {
        eprintln!("{}", log);
        std::process::exit(2);
    }
    // 用 write 而非 println：避免格式化层对 `{}` 之类字符的额外处理，保持原文。
    use std::io::Write;
    let mut stdout = std::io::stdout();
    let _ = stdout.write_all(log.as_bytes());
    let _ = stdout.flush();
}

#[cfg(not(ipc_linux))]
fn main() {
    eprintln!("ipc_peer 只在 Linux/Android 上可用（本 crate 的演示依赖 Linux syscall）");
    std::process::exit(2);
}
