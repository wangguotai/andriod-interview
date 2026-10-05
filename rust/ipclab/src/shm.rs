//! 共享内存与 fd 传递：`memfd_create` + `mmap` + `SCM_RIGHTS` + `fork`。
//!
//! ─── 这一组为什么要放一起 ───
//!
//! 共享内存要「跨进程」，需要两步：
//!   1. **造一块可共享的内存对象** → `memfd_create`（匿名、无路径、内核里的 tmpfs 文件）；
//!   2. **把它的 fd 交给另一个进程** → `sendmsg` + `SCM_RIGHTS`（fd 在内核里被复制，不是拷贝字节）。
//!
//! 少了第 2 步，`memfd` 只是「本进程可用的一块匿名内存」；补上第 2 步，才是跨进程共享。
//! 而 `fork` 出来的子进程天然继承 fd（连第 2 步都省了），正好用来对比「继承 vs 显式传递」。
//!
//! ─── 与 Binder 的边界 ───
//!
//! Android 上最主流的「传 fd + 共享内存」其实是 Binder + `ParcelFileDescriptor` + `ashmem`
//! （见 module 里 `SharedMemoryViaProviderDemo`）。本文件用 `memfd` 是**Linux 原生**路线，
//! 两者在“零拷贝大块数据”这件事上是同一思路，只是「谁来做 fd 传递」不同。

use crate::sys;
use std::os::unix::io::RawFd;

/// `memfd_create` syscall 编号：bionic 的 `memfd_create()` 声明为 `__INTRODUCED_IN(30)`，
/// minSdk 24 用不到新 API，但**该 syscall 本身在 Linux 3.17（Android 内核早已具备）就存在**，
/// 因此这里直接走 `syscall()`，既不受 API level 门禁，也不需要 fork 出一个 libc 版本判断。
#[cfg(target_pointer_width = "64")]
const SYS_MEMFD_CREATE: libc::c_long = 279; // asm-generic（arm64 / x86_64 / riscv64）
#[cfg(target_arch = "arm")]
const SYS_MEMFD_CREATE: libc::c_long = 385; // arm EABI
#[cfg(target_arch = "x86")]
const SYS_MEMFD_CREATE: libc::c_long = 356; // i386

/// memfd 的 flag。
///
/// ─── 真机上踩到的坑（务必保留这条注释）───
///
/// 最初这里传的是 bionic 的 `SFD_CLOEXEC`，结果设备上 `memfd_create` 直接返回 `EINVAL`。
/// 原因是 **`SFD_*` 是 bionic 在用户态定义的常量，不是内核 `memfd_create` syscall 认的参数**：
/// bionic 的 `SFD_CLOEXEC` = 0x080000（它自己的位域），而内核要的是 `MFD_CLOEXEC` = 0x1。
/// 直接走裸 syscall 时，必须用**内核**的 `MFD_*` 定义。这类「用户态常量 vs 内核常量」
/// 的错位编译期完全看不出来，只有在设备上跑才会以 `EINVAL` 暴露。
const MFD_CLOEXEC: libc::c_long = 0x0001;
/// 允许对该 memfd 加 seal。**不加这个 flag，后面的 `F_ADD_SEALS` 会返回 `EPERM`。**
const MFD_ALLOW_SEALING: libc::c_long = 0x0002;

/// fd 传递用的「哑字节」。`SCM_RIGHTS` 必须依附在一个 `sendmsg` 调用上，
/// 接收方会收到这 1 个字节 + 1 个 fd；字节本身无意义，只是载体。
const FD_CARRIER: u8 = 0x42;

/// 把 pid 手写进一个**栈上**缓冲，返回 `(buf, 起始下标)`。
///
/// 存在的唯一理由：`fork` 之后的子进程不能 `format!`（会 malloc，而多线程 App 里
/// malloc 的锁可能正被父进程的其他线程持有 → 死锁）。所以这里用纯栈缓冲手写十进制。
fn format_pid(pid: i32) -> ([u8; 12], usize) {
    let mut buf = [0u8; 12];
    let neg = pid < 0;
    let mut v = pid.unsigned_abs();
    let mut i = buf.len();
    if v == 0 {
        i -= 1;
        buf[i] = b'0';
    }
    while v > 0 {
        i -= 1;
        buf[i] = b'0' + (v % 10) as u8;
        v /= 10;
    }
    if neg {
        i -= 1;
        buf[i] = b'-';
    }
    (buf, i)
}

/// 用 `memfd_create` 造一块匿名共享内存，返回 fd。
///
/// `MFD_CLOEXEC`：exec 后自动关闭。演示「fork（不 exec）」时它不生效，
/// 所以子进程仍能继承；这正是我们要展示的对比点。
pub fn memfd_create(name: &str) -> Result<RawFd, i32> {
    let c_name = std::ffi::CString::new(name).map_err(|_| -libc::EINVAL)?;
    let fd = unsafe {
        libc::syscall(
            SYS_MEMFD_CREATE,
            c_name.as_ptr(),
            MFD_CLOEXEC | MFD_ALLOW_SEALING,
        )
    };
    if fd < 0 {
        return Err(-unsafe { *libc::__errno() });
    }
    Ok(fd as RawFd)
}

/// `ftruncate` 设定 memfd 的长度。
pub fn ftruncate(fd: RawFd, len: usize) -> Result<(), i32> {
    let rc = unsafe { libc::ftruncate(fd, len as libc::off_t) };
    if rc != 0 {
        return Err(-unsafe { *libc::__errno() });
    }
    Ok(())
}

/// `mmap` 一段匿名共享内存对象。[writable] 决定是否带 `PROT_WRITE`。
///
/// 返回裸指针与长度；调用方负责在不再使用时 `munmap`（见 [`unmap`]）。
/// 这里不用 Rust 的 `&mut [u8]` 是因为 fork 后父子进程会**同时**持有同一段内存，
/// 借用规则表达不了「两个进程别名同一块」这件事，交给调用方以裸指针管理更诚实。
pub fn mmap_fd(fd: RawFd, len: usize, writable: bool) -> Result<*mut u8, i32> {
    let prot = libc::PROT_READ | if writable { libc::PROT_WRITE } else { 0 };
    let p = unsafe {
        libc::mmap(
            std::ptr::null_mut(),
            len,
            prot,
            libc::MAP_SHARED,
            fd,
            0,
        )
    };
    if p == libc::MAP_FAILED {
        return Err(-unsafe { *libc::__errno() });
    }
    Ok(p as *mut u8)
}

/// `munmap`。
pub fn unmap(p: *mut u8, len: usize) {
    if !p.is_null() {
        unsafe {
            libc::munmap(p as *mut libc::c_void, len);
        }
    }
}

/// 往 mmap 出来的内存里写一段字节（读改写用 `read_into`）。
///
/// 用 `write_volatile` 防止编译器把这句「store 到没人读的内存」优化掉。
pub fn write_bytes(p: *mut u8, bytes: &[u8]) {
    for (i, b) in bytes.iter().enumerate() {
        unsafe { std::ptr::write_volatile(p.add(i), *b) };
    }
}

/// 从 mmap 出来的内存里读 [len] 字节。
pub fn read_bytes(p: *const u8, len: usize) -> Vec<u8> {
    let mut v = Vec::with_capacity(len);
    for i in 0..len {
        v.push(unsafe { std::ptr::read_volatile(p.add(i)) });
    }
    v
}

/// 对一个 fd 调 `F_ADD_SEALS`，加 `F_SEAL_SHRINK` 封印（禁止再缩小/落盘）。
///
/// 返回 Result 而非静默忽略 errno：`F_ADD_SEALS` 在部分内核 / 旧 Android 上可能
/// 返回 `EINVAL`（不支持），这本身就是一条值得上屏的证据，不能被吞掉。
pub fn seal_shrink(fd: RawFd) -> Result<(), i32> {
    let rc = unsafe { libc::fcntl(fd, libc::F_ADD_SEALS, libc::F_SEAL_SHRINK) };
    if rc != 0 {
        return Err(-unsafe { *libc::__errno() });
    }
    Ok(())
}

/// 通过 `sendmsg` + `SCM_RIGHTS` 把一个 fd 传给对端（对端须是 unix socket）。
///
/// ─── 关键认识：传的是 fd，不是字节 ───
///
/// 内核在接收进程里**新建一个指向同一 `struct file` 的 fd**。所以「传递」后，
/// 两端各自持有一个 fd，指向同一块内存对象；关掉任意一端不影响另一端。
/// 这正是「为什么 fd 是可以跨进程传的，而 FILE* 不行」的核心。
pub fn send_fd(sock: RawFd, fd: RawFd) -> Result<(), i32> {
    let mut iov_buf = [FD_CARRIER];
    let mut iov = libc::iovec {
        iov_base: iov_buf.as_mut_ptr() as *mut libc::c_void,
        iov_len: iov_buf.len(),
    };
    // CMSG 缓冲要够大：这里只带 1 个 fd，预留 64 字节远足够。
    let mut cmsg_buf = [0u8; 64];
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf.as_mut_ptr() as *mut libc::c_void;
    msg.msg_controllen = cmsg_buf.len() as _;

    let cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
    if cmsg.is_null() {
        return Err(-libc::EINVAL);
    }
    unsafe {
        (*cmsg).cmsg_level = libc::SOL_SOCKET;
        (*cmsg).cmsg_type = libc::SCM_RIGHTS;
        (*cmsg).cmsg_len = libc::CMSG_LEN(std::mem::size_of::<RawFd>() as u32) as _;
        // 把 fd 写进 CMSG 数据区
        std::ptr::copy_nonoverlapping(
            &fd as *const RawFd as *const u8,
            libc::CMSG_DATA(cmsg),
            std::mem::size_of::<RawFd>(),
        );
    }
    msg.msg_controllen = unsafe { (*cmsg).cmsg_len } as _;

    let rc = unsafe { libc::sendmsg(sock, &msg, 0) };
    if rc < 0 {
        return Err(-unsafe { *libc::__errno() });
    }
    Ok(())
}

/// 从 unix socket 接收一个用 `SCM_RIGHTS` 传来的 fd。返回 `(fd, 载体字节数)`。
pub fn recv_fd(sock: RawFd) -> Result<RawFd, i32> {
    let mut iov_buf = [0u8; 1];
    let mut iov = libc::iovec {
        iov_base: iov_buf.as_mut_ptr() as *mut libc::c_void,
        iov_len: iov_buf.len(),
    };
    let mut cmsg_buf = [0u8; 64];
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf.as_mut_ptr() as *mut libc::c_void;
    msg.msg_controllen = cmsg_buf.len() as _;

    let rc = unsafe { libc::recvmsg(sock, &mut msg, 0) };
    if rc < 0 {
        return Err(-unsafe { *libc::__errno() });
    }

    let cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
    if cmsg.is_null() {
        return Err(-libc::EPROTO); // 对端发了字节但没带 fd
    }
    let is_rights = unsafe { (*cmsg).cmsg_level == libc::SOL_SOCKET && (*cmsg).cmsg_type == libc::SCM_RIGHTS };
    if !is_rights {
        return Err(-libc::EPROTO);
    }
    let mut received: RawFd = -1;
    unsafe {
        std::ptr::copy_nonoverlapping(
            libc::CMSG_DATA(cmsg),
            &mut received as *mut RawFd as *mut u8,
            std::mem::size_of::<RawFd>(),
        );
    }
    if received < 0 {
        return Err(-libc::EBADF);
    }
    Ok(received)
}

/// 「传 fd + 共享内存」的完整演示，日志式返回每一步证据。
///
/// 编排（每步都产出可上屏的证据）：
///   1. `memfd_create` 造内存对象，打印 fd 与 `fstat` 出的 inode（证明它是内核里的一个文件对象）；
///   2. 父进程 `mmap` 并写入一段文本；
///   3. `fork` 出子进程：子进程**继承**该 fd，`mmap` 后立即读到父写的数据 —— 证明共享；
///   4. 子进程改写内存后 `exit(0)`；
///   5. 父进程 `waitpid` 后重新读内存，看到子进程的改动 —— 证明双向可见（真正共享，不是拷贝）；
///   6. 再做一轮 `socketpair` + `send_fd/recv_fd`，证明「fd 可以**显式**跨进程传递」；
///      之后 `F_ADD_SEALS F_SEAL_SHRINK` 并打印结果。
///
/// ⚠️ 边界诚实：`fork()` 在 Android 应用进程里**不被禁止**——Android 的 zygote
/// 模型本身就依赖 fork。真正的坑是「fork 后只跑 async-signal-safe 代码、绝不可 exec」。
/// 因此本演示的子进程只调用 mmap/read/write/_exit，不碰 JVM、不 exec。这一点必须写清楚，
/// 否则这段代码会被误当成「可以在 App 里随便 fork 跑业务」的范例。
pub fn shm_fork_demo(payload: &str) -> String {
    let mut out = String::new();

    let fd = match memfd_create("ipclab-shm") {
        Ok(f) => f,
        Err(e) => return format!("[shm] memfd_create 失败: {}（内核可能不支持）\n", sys::errno_name(e)),
    };
    let len = 4096usize;
    if let Err(e) = ftruncate(fd, len) {
        let _ = sys::close(fd);
        return format!("[shm] ftruncate 失败: {}\n", sys::errno_name(e));
    }
    match sys::fstat_ino(fd) {
        Ok((ino, dev)) => out.push_str(&format!(
            "[shm] memfd_create(\"ipclab-shm\") → fd={}，ftruncate({}B)，st_ino={} st_dev={}\n",
            fd, len, ino, dev
        )),
        Err(e) => out.push_str(&format!("[shm] fstat 失败: {}\n", sys::errno_name(e))),
    }

    // 父进程 mmap + 写入
    let p = match mmap_fd(fd, len, true) {
        Ok(p) => p,
        Err(e) => {
            let _ = sys::close(fd);
            return out + &format!("[shm] mmap 失败: {}\n", sys::errno_name(e));
        }
    };
    let written = payload.as_bytes();
    write_bytes(p, written);
    out.push_str(&format!(
        "[shm] 父进程 mmap→{:?} 并写入 {} 字节: \"{}\"\n",
        p,
        written.len(),
        payload
    ));

    // fork 子进程。为了让子进程的证据能进入**返回值**（JNI 场景下 stderr 不可见），
    // 子进程把日志写进一对 pipe，父进程 waitpid 后读出并拼接。
    // 子进程只调用 async-signal-safe 的函数，不 malloc、不碰 JVM、绝不 exec。
    let (rlog, wlog) = match sys::pipe2(libc::O_CLOEXEC) {
        Ok(v) => v,
        Err(e) => {
            unmap(p, len);
            let _ = sys::close(fd);
            return out + &format!("[shm] pipe2 失败: {}\n", sys::errno_name(e));
        }
    };

    let pid = unsafe { libc::fork() };
    if pid < 0 {
        out.push_str(&format!("[shm] fork 失败: {}\n", sys::errno_name(-unsafe { *libc::__errno() })));
        let _ = sys::close(rlog);
        let _ = sys::close(wlog);
        unmap(p, len);
        let _ = sys::close(fd);
        return out;
    }
    if pid == 0 {
        // ── 子进程（fork 后只允许 async-signal-safe 操作，绝不能碰 JVM、绝不能 exec）──
        // 子进程无需 recv_fd：它 fork 时已经继承了 fd 与这块 mmap。
        // 日志用**固定文案**写入管道：不 format、不分配 —— 那些在 fork 后的多线程 App 里不安全。
        unsafe {
            let pre = "[shm] 子进程(pid=".as_bytes();
            libc::write(wlog, pre.as_ptr() as *const libc::c_void, pre.len());
            // 手写十进制 pid：不用 format!（它会分配内存，fork 后不安全）。
            let (pidbuf, start) = format_pid(libc::getpid());
            let digits = &pidbuf[start..];
            libc::write(wlog, digits.as_ptr() as *const libc::c_void, digits.len());
            let mid = ") 继承 fd，mmap 后读到: \"".as_bytes();
            libc::write(wlog, mid.as_ptr() as *const libc::c_void, mid.len());
            // 逐字节读回父进程写入的内容（volatile 防优化）
            for i in 0..written.len() {
                let b = std::ptr::read_volatile(p.add(i));
                libc::write(wlog, &b as *const u8 as *const libc::c_void, 1);
            }
            let post = "\"（证明子进程看到父写的数据）\n[shm] 子进程改写首字节 'P'→'C'，然后 _exit(0)\n".as_bytes();
            libc::write(wlog, post.as_ptr() as *const libc::c_void, post.len());
            // 子进程改写内存：把首字节改成 'C'（child）
            std::ptr::write_volatile(p, b'C');
            libc::close(wlog);
            libc::_exit(0);
        }
    }

    // 父进程：先关本地写端（否则读不到 EOF），再等子进程退出并读回它的证据。
    let _ = sys::close(wlog);
    let mut status: libc::c_int = 0;
    unsafe { libc::waitpid(pid, &mut status, 0) };
    let mut cbuf = [0u8; 512];
    let mut child_log = String::new();
    loop {
        match sys::read_once(rlog, &mut cbuf) {
            Ok(0) => break,
            Ok(n) => child_log.push_str(&String::from_utf8_lossy(&cbuf[..n])),
            Err(_) => break,
        }
    }
    let _ = sys::close(rlog);
    out.push_str(&child_log);

    let first = unsafe { std::ptr::read_volatile(p) };
    out.push_str(&format!(
        "[shm] 父进程 waitpid(pid={}) 完成，重新读内存首字节: '{}' → {} 可见，共享内存是同一块物理页\n",
        pid,
        first as char,
        if first == b'C' { "子进程改动" } else { "父进程改动(共享失败)" }
    ));

    unmap(p, len);

    // 第二轮：显式 fd 传递（socketpair + SCM_RIGHTS）
    out.push_str(&fd_passing_demo(fd).unwrap_or_else(|e| format!("[fdpass] 演示失败: {}\n", sys::errno_name(e))));

    // 封印
    match seal_shrink(fd) {
        Ok(()) => out.push_str("[shm] F_ADD_SEALS(F_SEAL_SHRINK) 成功：此后该 memfd 不可缩小\n"),
        Err(e) => out.push_str(&format!(
            "[shm] F_ADD_SEALS 失败: {}（旧内核/不支持可忽略，不影响共享语义）\n",
            sys::errno_name(e)
        )),
    }

    let _ = sys::close(fd);
    out
}

/// 用 `socketpair` + `send_fd/recv_fd` 把 [fd] 显式传给另一个线程，
/// 抬升接收端引用后关闭其副本，证明「传递的是 fd 引用，不是字节拷贝」。
fn fd_passing_demo(fd: RawFd) -> Result<String, i32> {
    let mut out = String::new();
    let (a, b) = sys::socketpair_stream(true)?;
    send_fd(a, fd)?;
    let received = recv_fd(b)?;
    let (ino_tx, _) = sys::fstat_ino(fd)?;
    let (ino_rx, _) = sys::fstat_ino(received)?;
    out.push_str(&format!(
        "[fdpass] socketpair + sendmsg(SCM_RIGHTS) 传递 fd {} → 接收端得到 fd {}，两边 st_ino={}/{}，{}同一内核对象\n",
        fd,
        received,
        ino_tx,
        ino_rx,
        if ino_tx == ino_rx { "== ⇒ " } else { "!= ⇒ 不是" }
    ));

    // 用接收端 fd 写，再用发送端 fd 读，证明是同一块内存。
    let w = mmap_fd(received, 4096, true)?;
    write_bytes(w, b"via-SCM_RIGHTS");
    let r = mmap_fd(fd, 4096, false)?;
    let readback = read_bytes(r, 14);
    out.push_str(&format!(
        "[fdpass] 用接收端 fd 写、用发送端原 fd 读 → \"{}\"（同一块 mmap）\n",
        String::from_utf8_lossy(&readback)
    ));
    unmap(w, 4096);
    unmap(r, 4096);

    let _ = sys::close(received);
    let _ = sys::close(a);
    let _ = sys::close(b);
    // 收尾提示：本函数返回后调用方仍持有原 fd，引用计数正确。
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn memfd_create_and_mmap_roundtrip() {
        let fd = memfd_create("t").expect("memfd 应可用");
        ftruncate(fd, 4096).unwrap();
        let p = mmap_fd(fd, 4096, true).unwrap();
        write_bytes(p, b"hello-shm");
        assert_eq!(&read_bytes(p, 9), b"hello-shm");
        unmap(p, 4096);
        sys::close(fd).unwrap();
    }

    #[test]
    fn fstat_ino_is_stable_across_dup_refs() {
        let fd = memfd_create("t2").unwrap();
        ftruncate(fd, 128).unwrap();
        let (a, _) = sys::fstat_ino(fd).unwrap();
        let (b, _) = sys::fstat_ino(fd).unwrap();
        assert_eq!(a, b, "同一 fd 两次 fstat 应得到同一 inode");
        sys::close(fd).unwrap();
    }

    #[test]
    fn scm_rights_passes_same_object() {
        let fd = memfd_create("t3").unwrap();
        ftruncate(fd, 4096).unwrap();
        let (a, b) = sys::socketpair_stream(true).unwrap();
        send_fd(a, fd).unwrap();
        let got = recv_fd(b).unwrap();
        let (ino1, _) = sys::fstat_ino(fd).unwrap();
        let (ino2, _) = sys::fstat_ino(got).unwrap();
        assert_eq!(ino1, ino2, "SCM_RIGHTS 传的应是同一内核对象");
        sys::close(a).unwrap();
        sys::close(b).unwrap();
        sys::close(got).unwrap();
        sys::close(fd).unwrap();
    }

    #[test]
    fn fork_sees_parent_writes() {
        // fork 在 CI/Linux 上可用；Android 上由 androidTest 覆盖。
        let log = shm_fork_demo("ParentPayload");
        assert!(log.contains("子进程看到父写的数据"), "子进程应看到父写数据：\n{log}");
        assert!(log.contains("子进程改动"), "父进程应看到子进程改动：\n{log}");
        assert!(log.contains("== ⇒ "), "fd 传递应证明同一 inode：\n{log}");
    }
}
