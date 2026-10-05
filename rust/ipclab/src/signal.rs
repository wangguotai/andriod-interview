//! POSIX 信号：`kill` + `sigaction` + `si_pid`（Linux 原生的进程间通知）。
//!
//! ─── 信号作为 IPC 的定位 ───
//!
//! 信号只承载**极少量**信息（信号编号 + 一点点 siginfo）。它是「通知」，不是「数据传输」：
//! 想知道「谁发的、发了几次」，要靠 `SA_SIGINFO` 拿 `si_pid`，而且**普通信号会合并**——
//! 连续发 5 次 `SIGUSR1` 而未处理时，内核只保留 1 个 pending，这可能一次都不被观察到。
//! 想不丢次数得用**实时信号** `SIGRTMIN+n`（本演示用 `SIGRTMIN+2`）。
//!
//! ─── 与 Android/Binder 的边界 ───
//!
//! Binder 的「死亡通知（linkToDeath）」在语义上就是「对端消失 → 我收到一个回调」，
//! 和信号「某进程给我发了个通知」同构；但 Binder 的通知带完整 `IBinder`、跨进程且有序，
//! 远强于信号。所以信号在 Android 上更多是**native 层/系统层**的机制（如 ART 的 suspend、
//! debugger、`SIGSEGV` 转 Java 异常），应用层 IPC 应以 Binder 为主。

use crate::sys;
use std::sync::atomic::{AtomicI32, Ordering};

/// 演示使用的实时信号：`SIGRTMIN+2`。用实时信号是为了避开「普通信号会合并」的坑，
/// 能稳定观察到「发一次、收一次」。`+2` 是为了避开 bionic/ART 内部占用的前方槽位。
fn demo_signal() -> i32 {
    libc::SIGRTMIN() + 2
}

/// 已收到的信号计数（由信号处理函数递增，必须是原子量）。
static RECV_COUNT: AtomicI32 = AtomicI32::new(0);
/// 最近一次信号的发送方 pid（来自 `siginfo_t.si_pid`）。
static LAST_SENDER: AtomicI32 = AtomicI32::new(0);

/// `SA_SIGINFO` 风格的信号处理函数：拿 `siginfo_t` 里的 `si_pid`。
///
/// ─── 处理函数里的纪律（否则就是线上 crash）───
///
/// 信号处理函数运行在**任意线程**、且栈可能很小。所以这里只做 `atomic` 存取，
/// 不分配、不加锁、不打印（`write` 虽是 async-signal-safe，但这里也省了，留给主流程）。
extern "C" fn on_signal(_sig: i32, info: *mut libc::siginfo_t, _ctx: *mut libc::c_void) {
    RECV_COUNT.fetch_add(1, Ordering::SeqCst);
    if !info.is_null() {
        // si_pid() 是 libc 提供的安全访问器（内部按 arch 取联合体成员）。
        let pid = unsafe { (*info).si_pid() };
        LAST_SENDER.store(pid, Ordering::SeqCst);
    }
}

/// 注册信号处理函数。`SA_SIGINFO` 是关键：没有它就拿不到 `si_pid`，无法判断来源。
fn install_handler(sig: i32) -> Result<(), i32> {
    unsafe {
        let mut sa: libc::sigaction = std::mem::zeroed();
        // 先转成裸指针再转 sighandler_t：不同架构下 sighandler_t 可能是
        // usize 或 fn 指针，两步转换在两种定义下都成立。
        sa.sa_sigaction = on_signal as *const () as libc::sighandler_t;
        sa.sa_flags = libc::SA_SIGINFO;
        libc::sigemptyset(&mut sa.sa_mask);
        // 不装 SA_RESTART：演示里要让被信号打断的系统调用返回 EINTR（可观察）。
        let rc = libc::sigaction(sig, &sa, std::ptr::null_mut());
        if rc != 0 {
            return Err(-*libc::__errno());
        }
    }
    Ok(())
}

/// 屏蔽/解除屏蔽某信号。[how] 取 `SIG_BLOCK` / `SIG_UNBLOCK`。
fn mask_signal(sig: i32, how: i32) -> Result<(), i32> {
    unsafe {
        let mut set: libc::sigset_t = std::mem::zeroed();
        libc::sigemptyset(&mut set);
        libc::sigaddset(&mut set, sig);
        let rc = libc::sigprocmask(how, &set, std::ptr::null_mut());
        if rc != 0 {
            return Err(-*libc::__errno());
        }
    }
    Ok(())
}

/// `kill(pid, sig)` 的封装，返回负 errno。
fn send_signal(pid: i32, sig: i32) -> Result<(), i32> {
    let rc = unsafe { libc::kill(pid, sig) };
    if rc != 0 {
        return Err(-unsafe { *libc::__errno() });
    }
    Ok(())
}

/// 跨进程信号演示：`fork` 子进程 → 父 `kill` 子 → 子的处理函数拿到 `si_pid` → 回读验证。
///
/// ─── 为什么无 sleep 也不会丢信号 ───
///
/// 父进程在 `fork` **之前**就 `SIG_BLOCK` 了该实时信号，子进程继承了这个屏蔽字。
/// 于是父进程无论多早 `kill`，信号都只会变成子进程的 **pending**，不会丢；
/// 子进程用 `sigsuspend(空集)` **原子地**「解除屏蔽 + 等待」，pending 信号立刻被投递。
/// 这套「先屏蔽、后 fork、再原子解除」是信号编程里消除竞态的标准手法。
pub fn signal_demo() -> String {
    let mut out = String::new();
    let sig = demo_signal();
    out.push_str(&format!(
        "[signal] 使用实时信号 SIGRTMIN+2 = {}（实时信号不合并，可稳定计数）\n",
        sig
    ));

    if let Err(e) = install_handler(sig) {
        return out + &format!("[signal] 注册 sigaction 失败: {}\n", sys::errno_name(e));
    }
    out.push_str("[signal] 已注册 SA_SIGINFO 处理函数（可读取 siginfo.si_pid）\n");

    // 关键：父进程先屏蔽，子进程才能继承屏蔽字，从而消除「发得比等得早」的竞态。
    if let Err(e) = mask_signal(sig, libc::SIG_BLOCK) {
        return out + &format!("[signal] SIG_BLOCK 失败: {}\n", sys::errno_name(e));
    }

    // 子进程把结论写回父进程的管道
    let (rlog, wlog) = match sys::pipe2(libc::O_CLOEXEC) {
        Ok(v) => v,
        Err(e) => return out + &format!("[signal] pipe2 失败: {}\n", sys::errno_name(e)),
    };

    let pid = unsafe { libc::fork() };
    if pid < 0 {
        out.push_str("[signal] fork 失败\n");
        let _ = sys::close(rlog);
        let _ = sys::close(wlog);
        return out;
    }
    if pid == 0 {
        // ── 子进程（仅 async-signal-safe）──
        // 用 sigsuspend 原子地解除屏蔽并等待；父进程的 kill 即便早已发生也不会丢。
        let mut empty: libc::sigset_t = unsafe { std::mem::zeroed() };
        unsafe {
            libc::sigemptyset(&mut empty);
            libc::alarm(3); // 看门狗：万一没收到，默认动作终止本进程，父进程据此判超时
            while RECV_COUNT.load(Ordering::SeqCst) == 0 {
                libc::sigsuspend(&empty);
            }
            let n = RECV_COUNT.load(Ordering::SeqCst);
            let from = LAST_SENDER.load(Ordering::SeqCst);
            let line = format!(
                "[signal] 子进程(pid={}) 收到 {} 次信号，siginfo.si_pid={}（父进程 pid ⇒ 确认发送方）\n",
                libc::getpid(),
                n,
                from
            );
            let b = line.as_bytes();
            libc::write(wlog, b.as_ptr() as *const libc::c_void, b.len());
            libc::_exit(0);
        }
    }

    // 父进程：立刻发信号（子进程即使还没跑到 sigsuspend，信号也会 pending 住）
    match send_signal(pid, sig) {
        Ok(()) => out.push_str(&format!("[signal] 父进程 kill({}, {}) → 发送给子进程\n", pid, sig)),
        Err(e) => out.push_str(&format!("[signal] kill 失败: {}\n", sys::errno_name(e))),
    }

    // 关闭本地写端，读子进程结论（读到 EOF 为止）
    let _ = sys::close(wlog);
    let mut buf = [0u8; 256];
    let mut got = String::new();
    loop {
        match sys::read_once(rlog, &mut buf) {
            Ok(0) => break,
            Ok(n) => got.push_str(&String::from_utf8_lossy(&buf[..n])),
            Err(_) => break,
        }
    }
    let _ = sys::close(rlog);

    let mut status = 0;
    unsafe { libc::waitpid(pid, &mut status, 0) };
    if got.is_empty() {
        out.push_str("[signal] 子进程未能返回证据（可能被看门狗 SIGALRM 终止）\n");
    } else {
        out.push_str(&got);
    }

    // 父进程自证：向自己投递一次，用 sigtimedwait 消费并核对 si_pid == 自身。
    // （信号处于屏蔽态时不会调用处理函数，而是留在 pending，sigtimedwait 能安全取走。）
    let _ = send_signal(sys::getpid(), sig);
    unsafe {
        let mut set: libc::sigset_t = std::mem::zeroed();
        libc::sigemptyset(&mut set);
        libc::sigaddset(&mut set, sig);
        let mut info: libc::siginfo_t = std::mem::zeroed();
        let ts = libc::timespec { tv_sec: 1, tv_nsec: 0 };
        let rc = libc::sigtimedwait(&set, &mut info, &ts);
        if rc == sig {
            out.push_str(&format!(
                "[signal] 父进程对自身投递并用 sigtimedwait 取回，si_pid={}（=自身 pid ⇒ 来源可辨识）\n",
                info.si_pid()
            ));
        } else {
            out.push_str("[signal] 自身投递取回失败或超时\n");
        }
        // 恢复默认，避免影响后续演示
        mask_signal(sig, libc::SIG_UNBLOCK).ok();
    }

    out
}

/// 返回当前进程/线程的标识，供上层在证据里标注「谁在跑」。
pub fn whoami() -> (i32, i32) {
    (sys::getpid(), sys::gettid())
}

/// 把一段十六进制字符串按 ASCII 转字节（保留给后续可能的扩展演示）。
#[allow(dead_code)]
pub fn hex_to_bytes(hex: &str) -> Vec<u8> {
    hex.as_bytes()
        .chunks(2)
        .filter_map(|p| {
            let s = std::str::from_utf8(p).ok()?;
            u8::from_str_radix(s, 16).ok()
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn signal_demo_delivers_with_sender_pid() {
        let log = signal_demo();
        assert!(log.contains("siginfo.si_pid="), "应读到发送方 pid：\n{log}");
        assert!(log.contains("确认发送方"), "应确认来源：\n{log}");
    }
}
