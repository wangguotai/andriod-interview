//! AF_UNIX 套接字服务端：**同一套实现**同时支持 Linux 的两种「命名空间」。
//!
//! ─── 为什么这个文件值得单独存在 ───
//!
//! 面经里常问「Android 跨进程有哪几种方式」，标准答案会列到「Socket」，
//! 但很少有人说清 **Unix domain socket 的两种命名空间**，而这正是 Android
//! `LocalSocket` 的设计核心：
//!
//! 1. **filesystem namespace**：socket 是文件系统里的一个特殊文件（有路径）。
//!    优点：有权限位、可被 `ls` 看到、可持久化（进程退出后文件仍在，需手动 unlink）。
//!    缺点：受路径长度限制、留下残留文件。
//! 2. **abstract namespace**（Linux 特有）：名字只是内核里的一个字符串，**不对应文件**。
//!    优点：无需清理、不受文件系统权限与路径长度限制、没有残留。
//!    缺点：只在**同一个 network namespace** 内可见（Android 应用进程共享 netns，够用）。
//!
//! 关键实现差异只在 `sockaddr_un` 的填法：
//!   - filesystem：`sun_path` 写入路径 + `NUL`，`addrlen = 2 + len + 1`；
//!   - abstract  ：`sun_path[0] = 0`（长度前缀为零），名字从 `sun_path[1]` 开始，
//!                 `addrlen = 2 + 1 + len`。
//! 编译期完全看不出区别，写错了表现是 `connect` 时 `ECONNREFUSED` 或 `ENOENT`。
//!
//! ─── 与 Java `LocalSocket` 的互操作 ───
//!
//! 本文件的服务端由 Rust 建；客户端在 Kotlin 侧用 `android.net.LocalSocket`
//! （`Namespace.ABSTRACT` / `Namespace.FILESYSTEM`）连接 —— 跨语言、跨进程、
//! 内核层面走的是同一个对象。这比「两边都用 Java」更能说明协议是标准的。

use crate::sys;
use std::ffi::CString;
use std::os::unix::io::RawFd;

/// `SO_PEERCRED` 取到的对端身份。
///
/// libc 的 Android 目标没有导出 `ucred` 结构体，这里按内核 ABI 自行定义：
/// `struct ucred { pid_t pid; uid_t uid; gid_t gid; }`。三字段都是 32 位，布局稳定。
#[repr(C)]
#[derive(Debug, Clone, Copy)]
pub struct PeerCred {
    pub pid: i32,
    pub uid: u32,
    pub gid: u32,
}

/// AF_UNIX 监听套接字。`Drop` 时自动关闭并（对 filesystem 命名空间）unlink 路径。
pub struct AfUnixServer {
    listen_fd: RawFd,
    /// filesystem 命名空间下需要清理的路径；abstract 下为 None。
    cleanup_path: Option<String>,
    /// 人类可读的端点描述，上屏用。
    pub endpoint: String,
}

impl AfUnixServer {
    /// 绑定一个 **abstract namespace** 服务端。[name] 不含前导 NUL（本函数负责补）。
    pub fn bind_abstract(name: &str) -> Result<Self, i32> {
        let fd = new_unix_socket()?;
        let mut addr: libc::sockaddr_un = unsafe { std::mem::zeroed() };
        addr.sun_family = libc::AF_UNIX as libc::sa_family_t;

        let bytes = name.as_bytes();
        // sun_path 最大 108；abstract 需要 1 个前导 NUL + 名字。
        if bytes.len() + 1 > addr.sun_path.len() {
            let _ = sys::close(fd);
            return Err(-libc::ENAMETOOLONG);
        }
        // sun_path[0] 保持 0（zeroed），名字从下标 1 开始
        for (i, b) in bytes.iter().enumerate() {
            addr.sun_path[i + 1] = *b as libc::c_char;
        }
        // addrlen = offsetof(sun_path) + 1 + name.len()；sun_path 之前的字段只有
        // sa_family_t(2 字节)，故 offset = 2。
        let addrlen = 2 + 1 + bytes.len();
        bind_listen(fd, &addr, addrlen)?;

        Ok(AfUnixServer {
            listen_fd: fd,
            cleanup_path: None,
            endpoint: format!("abstract:{}", name),
        })
    }

    /// 绑定一个 **filesystem namespace** 服务端。[path] 必须位于本应用可写目录。
    ///
    /// 已存在的旧 socket 文件会先被 unlink —— 否则 `bind` 返回 `EADDRINUSE`。
    /// 这是 filesystem 命名空间的经典操作纪律。
    pub fn bind_filesystem(path: &str) -> Result<Self, i32> {
        // 旧文件残留会挡住 bind，先清掉（不存在时忽略）。
        let _ = sys::unlink(path);

        let fd = new_unix_socket()?;
        let mut addr: libc::sockaddr_un = unsafe { std::mem::zeroed() };
        addr.sun_family = libc::AF_UNIX as libc::sa_family_t;

        let c_path = CString::new(path).map_err(|_| -libc::EINVAL)?;
        let bytes = c_path.as_bytes_with_nul(); // 含结尾 NUL
        if bytes.len() > addr.sun_path.len() {
            let _ = sys::close(fd);
            return Err(-libc::ENAMETOOLONG);
        }
        for (i, b) in bytes.iter().enumerate() {
            addr.sun_path[i] = *b as libc::c_char;
        }
        let addrlen = 2 + bytes.len();
        match bind_listen(fd, &addr, addrlen) {
            Ok(()) => Ok(AfUnixServer {
                listen_fd: fd,
                cleanup_path: Some(path.to_string()),
                endpoint: format!("filesystem:{}", path),
            }),
            Err(e) => {
                let _ = sys::close(fd);
                Err(e)
            }
        }
    }

    /// 阻塞式 accept，返回已连接的 fd（带 CLOEXEC）。调用方负责 close。
    ///
    /// 用 `poll` 加超时而非纯阻塞 `accept`：演示里不能让 worker 线程永远挂住，
    /// 超时返回 `ETIMEDOUT` 让上层能如实报告「没等到连接」。
    pub fn accept_timeout(&self, timeout_ms: i32) -> Result<RawFd, i32> {
        if !sys::poll_readable(self.listen_fd, timeout_ms)? {
            return Err(-libc::ETIMEDOUT);
        }
        let fd = unsafe { libc::accept(self.listen_fd, std::ptr::null_mut(), std::ptr::null_mut()) };
        if fd < 0 {
            return Err(-unsafe { *libc::__errno() });
        }
        // accept() 不会给新 fd 设 CLOEXEC（没有 accept4 时只能补一刀）。
        unsafe { libc::fcntl(fd, libc::F_SETFD, libc::FD_CLOEXEC) };
        Ok(fd)
    }

    pub fn raw_fd(&self) -> RawFd {
        self.listen_fd
    }
}

impl Drop for AfUnixServer {
    fn drop(&mut self) {
        let _ = sys::close(self.listen_fd);
        if let Some(p) = &self.cleanup_path {
            // filesystem 命名空间的 socket 文件不会随 fd 关闭消失，必须显式清理。
            let _ = sys::unlink(p);
        }
    }
}

/// 取已连接 fd 的对端身份（`SO_PEERCRED`）。这是「内核告诉服务端是谁连进来的」，
/// 对端无法伪造 —— 也是 AF_UNIX 比 TCP 更适合「本机可信通信」的原因之一。
pub fn peer_cred(fd: RawFd) -> Result<PeerCred, i32> {
    let mut cred = PeerCred { pid: 0, uid: 0, gid: 0 };
    let mut len = std::mem::size_of::<PeerCred>() as libc::socklen_t;
    let rc = unsafe {
        libc::getsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            &mut cred as *mut PeerCred as *mut libc::c_void,
            &mut len,
        )
    };
    if rc != 0 {
        return Err(-unsafe { *libc::__errno() });
    }
    Ok(cred)
}

/// 在一段文本前后加 `\n` 作为简单帧（演示用；真实协议应带长度前缀，见 NOTES）。
pub fn write_line(fd: RawFd, s: &str) -> Result<usize, i32> {
    let mut buf = Vec::with_capacity(s.len() + 1);
    buf.extend_from_slice(s.as_bytes());
    buf.push(b'\n');
    sys::write_all(fd, &buf)
}

/// 读一行（读到 `\n` 或 EOF/超时）。返回去掉换行后的内容。
///
/// 说明：这是**演示级**的按字节读，用于说明「字节流需要自己定帧」。
/// 真实实现应预分配缓冲并按块读。
pub fn read_line(fd: RawFd, capacity: usize, timeout_ms: i32) -> Result<String, i32> {
    let mut out = Vec::with_capacity(capacity);
    let mut b = [0u8; 1];
    loop {
        if !sys::poll_readable(fd, timeout_ms)? {
            return Err(-libc::ETIMEDOUT);
        }
        match sys::read_once(fd, &mut b) {
            Ok(0) => break, // EOF
            Ok(_) => {
                if b[0] == b'\n' {
                    break;
                }
                out.push(b[0]);
                if out.len() >= capacity {
                    break;
                }
            }
            Err(e) => return Err(e),
        }
    }
    Ok(String::from_utf8_lossy(&out).into_owned())
}

/// `socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0)`。
fn new_unix_socket() -> Result<RawFd, i32> {
    let fd = unsafe { libc::socket(libc::AF_UNIX, libc::SOCK_STREAM | libc::SOCK_CLOEXEC, 0) };
    if fd < 0 {
        return Err(-unsafe { *libc::__errno() });
    }
    Ok(fd)
}

/// `bind` + `listen` 的公共部分。
fn bind_listen(fd: RawFd, addr: &libc::sockaddr_un, addrlen: usize) -> Result<(), i32> {
    let rc = unsafe {
        libc::bind(
            fd,
            addr as *const libc::sockaddr_un as *const libc::sockaddr,
            addrlen as libc::socklen_t,
        )
    };
    if rc != 0 {
        return Err(-unsafe { *libc::__errno() });
    }
    // backlog 用 4：演示最多两三个客户端，够用且能说明「有排队上限」这件事。
    let rc = unsafe { libc::listen(fd, 4) };
    if rc != 0 {
        return Err(-unsafe { *libc::__errno() });
    }
    Ok(())
}

/// 自测演示：在**一个进程内**把 abstract 与 filesystem 两种 AF_UNIX 都跑一遍
/// （bind → connect → 收 / 发 → 读对端身份）。用于 `ipc_peer` 在设备上验证，
/// 以及在 androidTest 里不依赖 JVM 客户端地证明这条链路可用。
pub fn unix_socket_selftest(fs_path: &str) -> String {
    let mut out = String::new();
    out.push_str(&format!("[unix] 自测开始 pid={} tid={}\n", sys::getpid(), sys::gettid()));

    out.push_str(&selftest_one("abstract", || {
        AfUnixServer::bind_abstract("ipclab.selftest.abst")
    }, None).unwrap_or_else(|e| format!("[unix] abstract 自测失败: {}\n", sys::errno_name(e))));

    let fs = fs_path.to_string();
    out.push_str(&selftest_one("filesystem", || {
        AfUnixServer::bind_filesystem(&fs)
    }, Some(fs_path)).unwrap_or_else(|e| {
        format!(
            "[unix] filesystem 自测失败: {}（路径 {} 可能不可写）\n",
            sys::errno_name(e),
            fs_path
        )
    }));

    out
}

/// 作为一个 **AF_UNIX abstract 服务端**，只服务一条连接：accept → 读一行请求 →
/// 回一行应答 → 关闭。供 Kotlin 侧用 `android.net.LocalSocket(Namespace.ABSTRACT)`
/// 作为「另一端」来对接 —— 真正的 Java ↔ Rust 跨语言、跨进程验证。
///
/// [name] 为 abstract 名字（不含前导 NUL）。
pub fn serve_abstract_once(name: &str, timeout_ms: i32) -> Result<String, i32> {
    let mut out = String::new();
    let server = AfUnixServer::bind_abstract(name)?;
    out.push_str(&format!(
        "[unix] Rust 服务端已监听 {} (pid={})\n",
        server.endpoint,
        sys::getpid()
    ));

    let conn = match server.accept_timeout(timeout_ms) {
        Ok(fd) => fd,
        Err(e) => return Err(e),
    };
    if let Ok(cred) = peer_cred(conn) {
        out.push_str(&format!(
            "[unix] 已 accept，SO_PEERCRED: pid={} uid={} gid={}\n",
            cred.pid, cred.uid, cred.gid
        ));
    }

    let req = read_line(conn, 512, timeout_ms)?;
    out.push_str(&format!("[unix] 收到客户端请求: \"{}\"\n", req));
    let reply = format!("rust-pong(len={})", req.chars().count());
    write_line(conn, &reply)?;
    out.push_str(&format!("[unix] 已回复: \"{}\"\n", reply));

    let _ = sys::close(conn);
    Ok(out)
}

/// 单个命名空间的自测：起 server → 客户端 connect → 双向一句话 → 读 peercred。
fn selftest_one<F>(kind: &str, bind: F, _path: Option<&str>) -> Result<String, i32>
where
    F: FnOnce() -> Result<AfUnixServer, i32>,
{
    let server = bind()?;
    let mut out = String::new();
    out.push_str(&format!("[unix] {} 服务端已监听：{}\n", kind, server.endpoint));

    // 客户端：与 Java LocalSocket 走同一条内核路径（AF_UNIX + SOCK_STREAM）。
    let client = new_unix_socket()?;
    let mut addr: libc::sockaddr_un = unsafe { std::mem::zeroed() };
    addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
    let (addrlen, _hold) = if let Some(rest) = server.endpoint.strip_prefix("abstract:") {
        let b = rest.as_bytes();
        for (i, byte) in b.iter().enumerate() {
            addr.sun_path[i + 1] = *byte as libc::c_char;
        }
        (2 + 1 + b.len(), 0usize)
    } else {
        let p = server.endpoint.strip_prefix("filesystem:").unwrap_or("");
        let c = CString::new(p).map_err(|_| -libc::EINVAL)?;
        let b = c.as_bytes_with_nul();
        for (i, byte) in b.iter().enumerate() {
            addr.sun_path[i] = *byte as libc::c_char;
        }
        (2 + b.len(), 0)
    };

    let rc = unsafe {
        libc::connect(
            client,
            &addr as *const libc::sockaddr_un as *const libc::sockaddr,
            addrlen as libc::socklen_t,
        )
    };
    if rc != 0 {
        let e = -unsafe { *libc::__errno() };
        let _ = sys::close(client);
        return Err(e);
    }
    out.push_str(&format!("[unix] {} 客户端 connect 成功\n", kind));

    // 服务端 accept（限时，避免自测挂死）
    let conn = server.accept_timeout(1000)?;
    if let Ok(cred) = peer_cred(conn) {
        out.push_str(&format!(
            "[unix] {} 服务端 accept，SO_PEERCRED: pid={} uid={} gid={}（内核提供的对端身份）\n",
            kind, cred.pid, cred.uid, cred.gid
        ));
    }

    write_line(client, "ping-from-client")?;
    let got = read_line(conn, 256, 1000)?;
    out.push_str(&format!("[unix] {} 服务端收到: \"{}\"\n", kind, got));

    write_line(conn, "pong-from-server")?;
    let back = read_line(client, 256, 1000)?;
    out.push_str(&format!("[unix] {} 客户端收到: \"{}\"\n", kind, back));

    let _ = sys::close(conn);
    let _ = sys::close(client);
    // server 在 drop 时关闭并（filesystem）清理路径
    drop(server);
    match kind {
        "filesystem" => out.push_str(&format!("[unix] {} 服务端已关闭并 unlink 路径\n", kind)),
        _ => out.push_str(&format!("[unix] {} 服务端已关闭（abstract 无文件可清理）\n", kind)),
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn abstract_selftest_roundtrip() {
        // 用带 pid 的独特名字，避免并行测试互相抢名
        let name = format!("ipclab.test.{}", std::process::id());
        let server = AfUnixServer::bind_abstract(&name).expect("bind abstract");
        let client = new_unix_socket().unwrap();
        let mut addr: libc::sockaddr_un = unsafe { std::mem::zeroed() };
        addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
        let b = name.as_bytes();
        for (i, byte) in b.iter().enumerate() {
            addr.sun_path[i + 1] = *byte as libc::c_char;
        }
        let rc = unsafe {
            libc::connect(
                client,
                &addr as *const libc::sockaddr_un as *const libc::sockaddr,
                (2 + 1 + b.len()) as libc::socklen_t,
            )
        };
        assert_eq!(rc, 0, "connect 应成功");
        let conn = server.accept_timeout(1000).unwrap();
        write_line(client, "hi").unwrap();
        assert_eq!(read_line(conn, 64, 1000).unwrap(), "hi");
        sys::close(conn).unwrap();
        sys::close(client).unwrap();
    }

    #[test]
    fn filesystem_selftest_cleans_up_path() {
        let path = format!("/tmp/ipclab_test_{}.sock", std::process::id());
        {
            let _server = AfUnixServer::bind_filesystem(&path).expect("bind filesystem");
            assert!(std::path::Path::new(&path).exists(), "socket 文件应存在");
        }
        // Drop 之后路径应被清理
        assert!(!std::path::Path::new(&path).exists(), "Drop 应 unlink socket 文件");
    }

    /// `serve_abstract_once` 供 Java `LocalSocket` 对接；这里用 Rust 客户端先把它验证一遍。
    #[test]
    fn serve_abstract_once_roundtrips() {
        let name = format!("ipclab.serve.{}", std::process::id());
        let name_for_server = name.clone();
        let server = std::thread::spawn(move || serve_abstract_once(&name_for_server, 2000));

        // 客户端：重试连接，避免服务端还没 bind 完就 connect 导致的偶发 ECONNREFUSED。
        let mut client = -1;
        for _ in 0..100 {
            let c = new_unix_socket().unwrap();
            let mut addr: libc::sockaddr_un = unsafe { std::mem::zeroed() };
            addr.sun_family = libc::AF_UNIX as libc::sa_family_t;
            let b = name.as_bytes();
            for (i, byte) in b.iter().enumerate() {
                addr.sun_path[i + 1] = *byte as libc::c_char;
            }
            let rc = unsafe {
                libc::connect(
                    c,
                    &addr as *const libc::sockaddr_un as *const libc::sockaddr,
                    (2 + 1 + b.len()) as libc::socklen_t,
                )
            };
            if rc == 0 {
                client = c;
                break;
            }
            let _ = sys::close(c);
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        assert!(client >= 0, "始终无法连接到服务端");

        write_line(client, "hello-from-java-like-client").unwrap();
        let reply = read_line(client, 256, 2000).unwrap();
        assert!(reply.starts_with("rust-pong"), "应答应来自 Rust 服务端：{reply}");
        sys::close(client).unwrap();

        let log = server.join().unwrap().expect("服务端应成功服务一条连接");
        assert!(log.contains("收到客户端请求"), "服务端应记录收到的请求：\n{log}");
    }
}
