//! 进程间文件锁（`flock`）：最朴素、也最常被忽略的一种「IPC」。
//!
//! `flock` 不传数据，只做**互斥**：谁拿到排他锁，谁就能独占某个资源（典型是「单实例」
//! 与「临界区串行化」）。它锁的是内核里的 `struct file`，通过一个★继承自父进程的 fd★
//! 在进程间生效 —— 这正是它能跨进程的原因，也是它和 `pthread_mutex`（只在进程内）的本质区别。
//!
//! ─── 演示要暴露的三个真相 ───
//!
//! 1. `flock` 是**劝告锁**：持锁进程之外的代码照样能 `read/write`，锁只约束「也去 flock 的人」。
//! 2. **同一进程内**两个 fd 各自 flock 会互相阻塞（本次演示用 `fork` 到独立进程规避，
//!    见 `shm.rs` 的 fork 纪律）。
//! 3. `LOCK_NB` 拿不到锁时**不阻塞**，立刻回 `EWOULDBLOCK` —— 这是实现「单实例检测」的关键。

use crate::sys;
use std::ffi::CString;
use std::os::unix::io::RawFd;

/// 对一个 fd 加 `flock` 锁。[exclusive]=true 为排他写锁，否则共享读锁；[nonblocking] 决定是否 `LOCK_NB`。
///
/// 返回 `Ok(())` 或负 errno。非阻塞且已被占用时，errno 为 `EWOULDBLOCK`。
pub fn lock(fd: RawFd, exclusive: bool, nonblocking: bool) -> Result<(), i32> {
    let mut op = if exclusive { libc::LOCK_EX } else { libc::LOCK_SH };
    if nonblocking {
        op |= libc::LOCK_NB;
    }
    // flock(2) 在 Android bionic 里有声明（NDK sysroot sys/file.h 已确认）。
    let rc = unsafe { libc::flock(fd, op) };
    if rc != 0 {
        return Err(-unsafe { *libc::__errno() });
    }
    Ok(())
}

/// 释放锁（`LOCK_UN`）。
pub fn unlock(fd: RawFd) -> Result<(), i32> {
    let rc = unsafe { libc::flock(fd, libc::LOCK_UN) };
    if rc != 0 {
        return Err(-unsafe { *libc::__errno() });
    }
    Ok(())
}

/// 通过对一个**独立进程**（`fork` 出来的子进程）持锁期间的行为，证明锁真的跨进程互斥。
///
/// 编排：
///   1. 父进程 open [path] 并在 `fork` 前加排他锁；
///   2. `fork` 子进程：子进程对**自己的** fd 尝试 `LOCK_EX | LOCK_NB`，
///      因为父进程持锁，子进程拿到的是 `EWOULDBLOCK` —— 这就是「单实例」原理；
///   3. 子进程把结果写进日志管道（不 sleep，靠 `waitpid` 同步）；
///   4. 父进程 `waitpid`，再解锁，并让子进程之外再验证一次「解锁后能拿到」。
///
/// 返回值里同时包含「持锁期间被拒」与「解锁后可获得」两段证据。
pub fn flock_demo(path: &str) -> String {
    let mut out = String::new();

    let fd = match sys::open(path, libc::O_RDWR | libc::O_CREAT | libc::O_CLOEXEC, 0o600) {
        Ok(f) => f,
        Err(e) => return format!("[flock] open({}) 失败: {}\n", path, sys::errno_name(e)),
    };
    out.push_str(&format!("[flock] open({}) → fd={}\n", path, fd));

    if let Err(e) = lock(fd, true, true) {
        let _ = sys::close(fd);
        return out + &format!("[flock] 父进程 LOCK_EX 失败: {}\n", sys::errno_name(e));
    }
    out.push_str("[flock] 父进程取得 LOCK_EX（排他锁）\n");

    // 用一对 pipe 把子进程的结论带回父进程，避免 sleep 猜时序。
    let (rlog, wlog) = match sys::pipe2(libc::O_CLOEXEC) {
        Ok(v) => v,
        Err(e) => {
            let _ = sys::close(fd);
            return out + &format!("[flock] pipe2 失败: {}\n", sys::errno_name(e));
        }
    };

    // 子进程里不能 malloc（fork 后非 async-signal-safe），所以这条 CString 与
    // 两条候选文案都在 fork **之前** 备好，子进程只用裸指针挑选其一写出。
    let cpath = match CString::new(path) {
        Ok(c) => c,
        Err(_) => {
            let _ = sys::close(fd);
            return out + "[flock] 路径含非法字符\n";
        }
    };
    let msg_rejected = "[flock] 子进程 LOCK_EX|LOCK_NB → EWOULDBLOCK（父进程持锁，故被拒 ⇒ 跨进程互斥成立）\n"
        .as_bytes();
    let msg_unexpected = "[flock] 子进程 LOCK_EX|LOCK_NB 竟然成功——锁没跨进程？\n".as_bytes();
    let msg_open_failed = "[flock] 子进程 open 失败\n".as_bytes();

    let pid = unsafe { libc::fork() };
    if pid < 0 {
        out.push_str("[flock] fork 失败\n");
    } else if pid == 0 {
        // ── 子进程：全程只调用 async-signal-safe 的函数（open/close/read/write/flock/_exit）──
        // 不 malloc、不 format、不碰 Rust 标准库的分配路径 —— 否则在多线程 App 里
        // fork 后极可能死在锁住的 malloc 上（经典 fork+threads 死锁）。
        let child = unsafe { libc::open(cpath.as_ptr(), libc::O_RDWR | libc::O_CLOEXEC, 0o600) };
        let msg: &[u8] = if child < 0 {
            msg_open_failed
        } else {
            let op = libc::LOCK_EX | libc::LOCK_NB;
            let rc = unsafe { libc::flock(child, op) };
            if rc == 0 {
                msg_unexpected
            } else {
                let e = -unsafe { *libc::__errno() };
                if e == -libc::EWOULDBLOCK {
                    msg_rejected
                } else {
                    msg_open_failed // 非预期 errno 也走这条，父进程会看到线索
                }
            }
        };
        unsafe {
            libc::write(wlog, msg.as_ptr() as *const libc::c_void, msg.len());
            libc::_exit(0);
        }
    }

    // 父进程：关闭本地写端后读子进程结论（读完即 EOF）
    let _ = sys::close(wlog);
    let mut buf = [0u8; 256];
    match sys::read_once(rlog, &mut buf) {
        Ok(n) if n > 0 => out.push_str(&String::from_utf8_lossy(&buf[..n])),
        _ => out.push_str("[flock] 未收到子进程结论\n"),
    }

    let mut status = 0;
    unsafe { libc::waitpid(pid, &mut status, 0) };
    let _ = sys::close(rlog);

    // 解锁后，本进程应能再次拿到（证明锁可释放、不是永久占用）
    if unlock(fd).is_ok() {
        match lock(fd, true, true) {
            Ok(()) => out.push_str("[flock] 父进程解锁后重新 LOCK_EX → 成功（锁可释放）\n"),
            Err(e) => out.push_str(&format!("[flock] 解锁后重锁失败: {}\n", sys::errno_name(e))),
        }
    }
    let _ = sys::close(fd);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn exclusive_lock_is_reentrant_before_unlock_then_ok() {
        let path = format!("/tmp/ipclab_flock_{}.lock", std::process::id());
        let fd = sys::open(path.as_str(), libc::O_RDWR | libc::O_CREAT, 0o600).unwrap();
        lock(fd, true, true).unwrap();
        unlock(fd).unwrap();
        lock(fd, true, true).unwrap();
        unlock(fd).unwrap();
        sys::close(fd).unwrap();
        let _ = std::fs::remove_file(&path);
    }

    #[test]
    fn flock_demo_shows_cross_process_exclusion() {
        let path = format!("/tmp/ipclab_flockdemo_{}.lock", std::process::id());
        let log = flock_demo(&path);
        assert!(log.contains("跨进程互斥成立"), "应证明跨进程互斥：\n{log}");
        assert!(log.contains("锁可释放"), "解锁后应能重锁：\n{log}");
        let _ = std::fs::remove_file(&path);
    }
}
