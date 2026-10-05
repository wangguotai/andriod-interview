//! 原始 syscall 包装层。**零业务逻辑**，只做两件事：
//!
//! 1. 把路径 / 缓冲等 Rust 类型翻译成 libc 需要的裸指针；
//! 2. 把 `libc::*` 的返回值统一翻译成 `Result<T, i32>`，`Err` 恒为**负 errno**。
//!
//! ─── 为什么统一用 `-errno` 而不是各自返回 -1 ───
//!
//! 上层（JNI → Kotlin）需要把失败原因**如实**呈现在证据面板上。若像 C 那样只回 -1，
//! 调用方还得再 `errno` 一次（而且 errno 在多线程下是线程局部的、跨 FFI 更易错位）。
//! 这里在失败发生的那一刻就把 errno 抓成负值带走，是最不容易出错的做法。

use std::ffi::CString;
use std::os::unix::io::RawFd;

/// `pipe2(2)`。返回 `(读端, 写端)`。
///
/// `flags` 传 `libc::O_CLOEXEC` 是纪律而非可选：不设 CLOEXEC 的话，子进程 exec 后会
/// 意外继承这两个 fd，导致「明明父进程关了写端、读端却收不到 EOF」这类幽灵 bug。
pub fn pipe2(flags: i32) -> Result<(RawFd, RawFd), i32> {
    let mut fds: [RawFd; 2] = [-1; 2];
    let rc = unsafe { libc::pipe2(fds.as_mut_ptr(), flags) };
    if rc != 0 {
        return Err(last_errno());
    }
    Ok((fds[0], fds[1]))
}

/// `socketpair(2)`：创建一对**全双工**的已连接套接字。
///
/// `AF_UNIX` + `SOCK_STREAM` 是 Android 上 `LocalSocket` 的底层形态；这里直接用它，
/// 是为了能和 Java 的 `LocalSocket` 互操作（同一套 unix domain socket 语义）。
pub fn socketpair_stream(cloexec: bool) -> Result<(RawFd, RawFd), i32> {
    let mut fds: [RawFd; 2] = [-1; 2];
    let type_ = libc::SOCK_STREAM | if cloexec { libc::SOCK_CLOEXEC } else { 0 };
    let rc = unsafe { libc::socketpair(libc::AF_UNIX, type_, 0, fds.as_mut_ptr()) };
    if rc != 0 {
        return Err(last_errno());
    }
    Ok((fds[0], fds[1]))
}

/// `mkfifo(2)`：创建一个命名管道（FIFO）。`mode` 通常传 `0o600`。
pub fn mkfifo(path: &str, mode: u32) -> Result<(), i32> {
    let c = CString::new(path).map_err(|_| -libc::EINVAL)?;
    let rc = unsafe { libc::mkfifo(c.as_ptr(), mode as libc::mode_t) };
    if rc != 0 {
        return Err(last_errno());
    }
    Ok(())
}

/// `open(2)`。最常用的是 `O_RDONLY` / `O_WRONLY|O_NONBLOCK`。
///
/// 对 FIFO 而言 `O_NONBLOCK` 很关键：不带它打开只写端会**阻塞到有读者出现**为止。
/// 演示里要先把「阻塞打开」和「非阻塞打开 + ENXIO」当作可断言的行为对照出来。
pub fn open(path: &str, flags: i32, mode: u32) -> Result<RawFd, i32> {
    let c = CString::new(path).map_err(|_| -libc::EINVAL)?;
    let fd = unsafe { libc::open(c.as_ptr(), flags, mode as libc::mode_t) };
    if fd < 0 {
        return Err(last_errno());
    }
    Ok(fd)
}

/// `unlink(2)`：删除路径（FIFO / 落盘的 memfd 都用得到）。不存在时忽略 `ENOENT`。
pub fn unlink(path: &str) -> Result<(), i32> {
    let c = CString::new(path).map_err(|_| -libc::EINVAL)?;
    let rc = unsafe { libc::unlink(c.as_ptr()) };
    if rc != 0 {
        let e = last_errno();
        if e == -libc::ENOENT {
            return Ok(());
        }
        return Err(e);
    }
    Ok(())
}

/// `write(2)` 全量写。返回**实际写入字节数**（教学场景里它就是证据：
/// 一次 write 写进原子管道上限（PIPE_BUF=4096）以内的量时是原子的）。
pub fn write_all(fd: RawFd, buf: &[u8]) -> Result<usize, i32> {
    let mut off = 0usize;
    while off < buf.len() {
        let n = unsafe {
            libc::write(
                fd,
                buf[off..].as_ptr() as *const libc::c_void,
                buf.len() - off,
            )
        };
        if n < 0 {
            let e = last_errno();
            if e == -libc::EINTR {
                continue; // 被信号打断要重试，这是系统调用最常见的坑
            }
            return Err(e);
        }
        if n == 0 {
            return Err(-libc::EPIPE);
        }
        off += n as usize;
    }
    Ok(off)
}

/// `read(2)` 一次读（不循环），返回读到的字节数；返回 0 表示对端已关闭（EOF）。
///
/// 刻意**不**循环到读满：演示要展示「字节流没有消息边界」这件事 —— 写 8 字节、
/// 读 3 字节是合法结果，剩余 5 字节留在内核缓冲里。循环读满反而会把这个真相藏起来。
pub fn read_once(fd: RawFd, buf: &mut [u8]) -> Result<usize, i32> {
    loop {
        let n = unsafe { libc::read(fd, buf.as_mut_ptr() as *mut libc::c_void, buf.len()) };
        if n < 0 {
            let e = last_errno();
            if e == -libc::EINTR {
                continue;
            }
            return Err(e);
        }
        return Ok(n as usize);
    }
}

/// `close(2)`。重复 close 会返回 `EBADF`，调用方按需忽略。
pub fn close(fd: RawFd) -> Result<(), i32> {
    if fd < 0 {
        return Ok(());
    }
    let rc = unsafe { libc::close(fd) };
    if rc != 0 {
        return Err(last_errno());
    }
    Ok(())
}

/// `poll(2)` 单 fd 版本：`timeout_ms < 0` 表示无限等待。返回是否**可读或已挂断**。
///
/// ─── 一个真机上才暴露的坑 ───
///
/// 最初这里只判断 `POLLIN`，结果「写端关闭后 poll 读端」返回超时 —— 因为在 Linux 上
/// 所有写端关闭时，读端的 `revents` 是 **`POLLHUP`**（对端挂断），**不含 `POLLIN`**。
/// 而此后 `read` 会立即返回 0（EOF）。所以「可读」的正确判据必须带上 `POLLHUP`/`POLLERR`，
/// 否则「等 EOF」这类收尾逻辑会静默超时。这条是照着设备输出改的，不是照书写的。
pub fn poll_readable(fd: RawFd, timeout_ms: i32) -> Result<bool, i32> {
    let mut pfd = libc::pollfd {
        fd,
        events: libc::POLLIN,
        revents: 0,
    };
    let rc = unsafe { libc::poll(&mut pfd, 1, timeout_ms) };
    if rc < 0 {
        let e = last_errno();
        if e == -libc::EINTR {
            return Ok(false);
        }
        return Err(e);
    }
    let ready = (pfd.revents & (libc::POLLIN | libc::POLLHUP | libc::POLLERR)) != 0;
    Ok(rc > 0 && ready)
}

/// `fcntl(fd, F_SETFL, flags)` —— 演示里用来把 FIFO 读端切成非阻塞（`O_NONBLOCK`）。
pub fn set_nonblocking(fd: RawFd, nonblocking: bool) -> Result<(), i32> {
    let cur = unsafe { libc::fcntl(fd, libc::F_GETFL) };
    if cur < 0 {
        return Err(last_errno());
    }
    let next = if nonblocking {
        cur | libc::O_NONBLOCK
    } else {
        cur & !libc::O_NONBLOCK
    };
    let rc = unsafe { libc::fcntl(fd, libc::F_SETFL, next) };
    if rc < 0 {
        return Err(last_errno());
    }
    Ok(())
}

/// `getpid(2)`。
pub fn getpid() -> i32 {
    unsafe { libc::getpid() }
}

/// `gettid(2)`：内核线程 id。演示里区分「同进程不同线程」与「不同进程」要用它。
pub fn gettid() -> i32 {
    unsafe { libc::syscall(libc::SYS_gettid) as i32 }
}

/// 从 `stat` 读取一个文件的 inode 与设备号，用来**证明两个 fd 指向同一个内核对象**。
///
/// 这是 memfd / FIFO 演示的核心证据之一：共享内存的「共享」不该只靠口头宣称，
/// 而应能指出「两边 mmap 的是同一个 `st_ino`」。
pub fn stat_ino(path: &str) -> Result<(u64, u64), i32> {
    let c = CString::new(path).map_err(|_| -libc::EINVAL)?;
    let mut st: libc::stat = unsafe { std::mem::zeroed() };
    let rc = unsafe { libc::stat(c.as_ptr(), &mut st) };
    if rc != 0 {
        return Err(last_errno());
    }
    Ok((st.st_ino as u64, st.st_dev as u64))
}

/// 从 **fd** 读 inode / 设备号。memfd 没有路径（除非落盘到 /proc/self/fd），
/// 只能走 `fstat`。
pub fn fstat_ino(fd: RawFd) -> Result<(u64, u64), i32> {
    let mut st: libc::stat = unsafe { std::mem::zeroed() };
    let rc = unsafe { libc::fstat(fd, &mut st) };
    if rc != 0 {
        return Err(last_errno());
    }
    Ok((st.st_ino as u64, st.st_dev as u64))
}

/// 把 errno 读成负值。**必须在失败发生后立刻调用**，中间不能插入任何可能改写
/// errno 的调用（这也是本文件里每处 `if rc != 0 { return Err(last_errno()) }`
/// 都紧贴着系统调用的原因）。
#[inline]
fn last_errno() -> i32 {
    unsafe { -*libc::__errno() }
}

/// 供上层组装可读错误信息用：把负 errno 翻译成符号名。
///
/// 刻意只覆盖本 Lab 会真实遇到的 errno —— 不追求全表，追求「看到名字就知道问题」。
pub fn errno_name(err: i32) -> &'static str {
    match -err {
        libc::ENOENT => "ENOENT",
        libc::EINVAL => "EINVAL",
        libc::EACCES => "EACCES",
        libc::EPERM => "EPERM",
        libc::EEXIST => "EEXIST",
        libc::ENXIO => "ENXIO",
        libc::EPIPE => "EPIPE",
        libc::EAGAIN => "EAGAIN / EWOULDBLOCK",
        libc::ENOSYS => "ENOSYS",
        libc::EADDRINUSE => "EADDRINUSE",
        libc::ECONNREFUSED => "ECONNREFUSED",
        libc::ETIMEDOUT => "ETIMEDOUT",
        libc::EPROTO => "EPROTO",
        libc::ENOMEM => "ENOMEM",
        libc::EINTR => "EINTR",
        libc::EBADF => "EBADF",
        _ => "ERRNO_UNKNOWN",
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// pipe2 是全部字节流演示的地基，先证明它可用、且 CLOEXEC 位确实生效。
    #[test]
    fn pipe2_roundtrip_and_cloexec() {
        let (r, w) = pipe2(libc::O_CLOEXEC).expect("pipe2 应成功");
        assert_eq!(write_all(w, b"hello").unwrap(), 5);

        let mut buf = [0u8; 16];
        let n = read_once(r, &mut buf).unwrap();
        assert_eq!(&buf[..n], b"hello");

        // CLOEXEC 必须在 fd 上可见
        let flags = unsafe { libc::fcntl(w, libc::F_GETFD) };
        assert!(flags & libc::FD_CLOEXEC != 0, "O_CLOEXEC 未生效");

        close(r).unwrap();
        close(w).unwrap();
    }

    /// 关闭写端后，读端必须读到 EOF（0 字节）—— 这是「靠 EOF 定长收尾」协议的前提。
    #[test]
    fn pipe_eof_after_writer_closed() {
        let (r, w) = pipe2(libc::O_CLOEXEC).unwrap();
        close(w).unwrap();
        let mut buf = [0u8; 8];
        assert_eq!(read_once(r, &mut buf).unwrap(), 0, "写端已关，读端应得到 EOF");
        close(r).unwrap();
    }

    #[test]
    fn socketpair_is_bidirectional() {
        let (a, b) = socketpair_stream(true).unwrap();
        write_all(a, b"ping").unwrap();
        let mut buf = [0u8; 8];
        let n = read_once(b, &mut buf).unwrap();
        assert_eq!(&buf[..n], b"ping");

        write_all(b, b"pong").unwrap();
        let n = read_once(a, &mut buf).unwrap();
        assert_eq!(&buf[..n], b"pong");
        close(a).unwrap();
        close(b).unwrap();
    }

    /// errno 名字表至少要能正确翻译本 Lab 反复用到的几个。
    #[test]
    fn errno_names_cover_expected_set() {
        assert_eq!(errno_name(-libc::EPIPE), "EPIPE");
        assert_eq!(errno_name(-libc::ENXIO), "ENXIO");
        assert_eq!(errno_name(-libc::ENOENT), "ENOENT");
    }
}
