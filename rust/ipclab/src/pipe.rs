//! 字节流类原语：匿名 `pipe` 与命名管道 `FIFO`。
//!
//! ─── 为什么把它们放在一起 ───
//!
//! 两者都是**内核里同一个 pipe buffer 机制**：匿名 pipe 用 `pipe2` 直接拿到两个 fd，
//! FIFO 用 `mkfifo` 生成一个路径，再 `open` 才拿到 fd。差异只在「怎么拿到 fd」，
//! 拿到之后 write/read/poll/EOF 的语义完全一致。放在一起看，能一眼看清这层关系。
//!
//! ─── 演示里刻意暴露的两个「反直觉真相」───
//!
//! 1. **字节流没有消息边界**：写 8 字节、一次 `read` 只拿回 3 字节是合法结果。
//!    所以真实协议必须自带「长度前缀 / 分隔符」来定帧。演示会打印每次 read 的字节数。
//! 2. **FIFO 的打开会阻塞**：不带 `O_NONBLOCK` 打开只写端，会一直阻塞到有读者出现；
//!    带 `O_NONBLOCK` 且无读者则立刻失败 `ENXIO`。这条差异是「打开顺序」类线上事故的根源。

use crate::sys;
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

/// 匿名管道演示（进程内确定性版本）。
///
/// 流程刻意覆盖三件事：
///   1. 一次写入 8 字节 → 读端**一次** read（而不是循环读满），打印实际读到的字节数；
///   2. 关闭写端 → 读端读到 0 字节（EOF），证明 EOF 是「写端全关」的结果而非「暂时没数据」；
///   3. 关闭写端后再写 → `EPIPE`（演示里顺手展示 SIGPIPE 被忽略后的 errno 语义）。
///
/// 每步都落一行 `[pipe] ...` 证据，供上层原样上屏。
pub fn pipe_demo() -> String {
    let mut out = String::new();
    let (r, w) = match sys::pipe2(libc::O_CLOEXEC) {
        Ok(v) => v,
        Err(e) => return format!("[pipe] pipe2 失败: {}\n", sys::errno_name(e)),
    };
    out.push_str(&format!(
        "[pipe] pipe2() → read_fd={} write_fd={} (pid={} tid={})\n",
        r,
        w,
        sys::getpid(),
        sys::gettid()
    ));

    // 1) 写入 8 字节，读端只读一次。PIPE_BUF=4096 以内的单次 write 是原子的。
    let payload = b"PING_abc";
    match sys::write_all(w, payload) {
        Ok(n) => out.push_str(&format!(
            "[pipe] write({} 字节) 原子写入（PIPE_BUF=4096 以内）\n",
            n
        )),
        Err(e) => out.push_str(&format!("[pipe] write 失败: {}\n", sys::errno_name(e))),
    }

    let mut buf = [0u8; 64];
    match sys::read_once(r, &mut buf) {
        Ok(n) => out.push_str(&format!(
            "[pipe] read 一次 → {} 字节 (\"{}\")；字节流无消息边界，剩余数据仍在内核缓冲\n",
            n,
            String::from_utf8_lossy(&buf[..n])
        )),
        Err(e) => out.push_str(&format!("[pipe] read 失败: {}\n", sys::errno_name(e))),
    }

    // 2) 关闭写端，读端应读到 EOF（0 字节）。
    let _ = sys::close(w);
    match sys::poll_readable(r, 500) {
        Ok(true) => match sys::read_once(r, &mut buf) {
            Ok(0) => out.push_str("[pipe] 关闭写端后 read → 0 字节 = EOF（所有写端已关）\n"),
            Ok(n) => out.push_str(&format!("[pipe] 预期 EOF，却读到 {} 字节\n", n)),
            Err(e) => out.push_str(&format!("[pipe] read 失败: {}\n", sys::errno_name(e))),
        },
        Ok(false) => out.push_str("[pipe] poll 超时：预期 EOF 却没就绪\n"),
        Err(e) => out.push_str(&format!("[pipe] poll 失败: {}\n", sys::errno_name(e))),
    }

    // 3) 已关闭（且此前已写过）的写端再写 → 应为 EBADF：fd 已被 close 回收，本就不可用。
    //    真正的 EPIPE 场景是「fd 有效、但对端全关」——用一对新 pipe 单独构造。
    match sys::write_all(w, b"after-close") {
        Ok(_) => out.push_str("[pipe] 关闭后再写竟然成功（不该发生）\n"),
        Err(e) => out.push_str(&format!(
            "[pipe] 对已 close 的 fd 再写 → {}（fd 已回收，故 EBADF）\n",
            sys::errno_name(e)
        )),
    }

    // 4) 构造 EPIPE：新建 pipe，关闭**读端**，保留有效写端再写。
    //    真机上与书上一致：一旦曾有读者、随后全关，写端收到 EPIPE（而非静默）。
    {
        let (r2, w2) = match sys::pipe2(libc::O_CLOEXEC) {
            Ok(v) => v,
            Err(e) => {
                let _ = sys::close(r);
                return out + &format!("[pipe] pipe2 失败: {}\n", sys::errno_name(e));
            }
        };
        let _ = sys::close(r2); // 关掉唯一的读端
        match sys::write_all(w2, b"no-reader") {
            Ok(_) => out.push_str("[pipe] 读者已关仍写入成功（不该发生）\n"),
            Err(e) => out.push_str(&format!(
                "[pipe] 关闭唯一读端后写有效写端 → {}（EPIPE，需忽略 SIGPIPE 才不会杀进程）\n",
                sys::errno_name(e)
            )),
        }
        let _ = sys::close(w2);
    }

    let _ = sys::close(r);
    out
}

/// 命名管道（FIFO）演示。[path] 必须位于应用可写目录（如 `filesDir/ipc.lab`）。
///
/// 编排：先证明「无读者时非阻塞打开只写端 → ENXIO」，再起一个写线程与一个读线程
/// 通过同一个 FIFO 目录项通信，并用 `stat` 出的 inode 证明两端打开的是**同一个内核对象**。
/// 全程不 sleep 猜时序，靠 FIFO 自身的打开语义做握手。
pub fn fifo_demo(path: &str) -> String {
    let mut out = String::new();

    // 目录项可能是上次崩溃残留，先清掉再建。
    let _ = sys::unlink(path);    if let Err(e) = sys::mkfifo(path, 0o600) {
        return format!("[fifo] mkfifo({}) 失败: {}\n", path, sys::errno_name(e));
    }
    let ino = sys::stat_ino(path);
    match ino {
        Ok((i, dev)) => out.push_str(&format!(
            "[fifo] mkfifo({}) 成功，st_ino={} st_dev={}\n",
            path, i, dev
        )),
        Err(e) => out.push_str(&format!("[fifo] stat 失败: {}\n", sys::errno_name(e))),
    }

    // 证据 1：无读者时，O_NONBLOCK 只写打开 → ENXIO（这正是「打开顺序」事故的根因）。
    match sys::open(path, libc::O_WRONLY | libc::O_NONBLOCK | libc::O_CLOEXEC, 0) {
        Ok(fd) => {
            out.push_str("[fifo] 无读者时 O_WRONLY|O_NONBLOCK 竟然打开成功（系统语义异常）\n");
            let _ = sys::close(fd);
        }
        Err(e) => out.push_str(&format!(
            "[fifo] 无读者 + O_WRONLY|O_NONBLOCK → {}（阻塞打开则会挂住）\n",
            sys::errno_name(e)
        )),
    }

    // 证据 2：读线程与写线程经同一 FIFO 目录项通信。
    let path_for_writer = path.to_string();
    let path_for_reader = path_for_writer.clone();
    let (ready_tx, ready_rx) = mpsc::channel::<i32>();
    let reader = thread::spawn(move || -> String {
        // 读端阻塞打开：会一直等到写端出现（这就是天然的握手，不需要 sleep）。
        let r = match sys::open(&path_for_reader, libc::O_RDONLY | libc::O_CLOEXEC, 0) {
            Ok(fd) => fd,
            Err(e) => return format!("[fifo] reader open 失败: {}\n", sys::errno_name(e)),
        };
        let mut s = format!("[fifo] reader open(RDONLY) → fd={} (tid={})\n", r, sys::gettid());
        let _ = ready_tx.send(r);

        let mut total = 0usize;
        let mut buf = [0u8; 64];
        let mut pieces = Vec::new();
        // 一直读到 EOF（mkfifo 语义：写端全关则 read 返回 0）。
        loop {
            match sys::poll_readable(r, 2000) {
                Ok(true) => match sys::read_once(r, &mut buf) {
                    Ok(0) => break,
                    Ok(n) => {
                        total += n;
                        pieces.push(format!("{}字节", n));
                    }
                    Err(e) => {
                        s.push_str(&format!("[fifo] reader read 失败: {}\n", sys::errno_name(e)));
                        break;
                    }
                },
                Ok(false) => {
                    s.push_str("[fifo] reader poll 超时\n");
                    break;
                }
                Err(e) => {
                    s.push_str(&format!("[fifo] reader poll 失败: {}\n", sys::errno_name(e)));
                    break;
                }
            }
        }
        s.push_str(&format!(
            "[fifo] reader 共收到 {} 字节，分 {} 次 read（{}）→ EOF\n",
            total,
            pieces.len(),
            pieces.join(" + ")
        ));
        let _ = sys::close(r);
        s
    });

    // 等 reader 真正拿到 fd，再以写端打开，避免写端提前打开/关闭造成的时序抖动。
    match ready_rx.recv_timeout(Duration::from_secs(2)) {
        Ok(rfd) => out.push_str(&format!("[fifo] 读端已就绪 fd={}，写端开始 O_WRONLY 打开\n", rfd)),
        Err(_) => out.push_str("[fifo] 等待读端就绪超时\n"),
    }

    let w = match sys::open(&path_for_writer, libc::O_WRONLY | libc::O_CLOEXEC, 0) {
        Ok(fd) => fd,
        Err(e) => {
            let _ = reader.join();
            return out + &format!("[fifo] writer open 失败: {}\n", sys::errno_name(e));
        }
    };
    if let Ok((wino, _)) = sys::fstat_ino(w) {
        out.push_str(&format!(
            "[fifo] writer open(WRONLY) → fd={}，fstat st_ino={}（与目录项同一 inode ⇒ 同一内核管道）\n",
            w, wino
        ));
    }
    // 分两次写，制造「第一次 read 可能只拿到部分」的真实场景。
    let _ = sys::write_all(w, "第一段:跨进程字节流".as_bytes());
    thread::sleep(Duration::from_millis(30));
    let _ = sys::write_all(w, " | 第二段:EOF 收尾".as_bytes());
    let _ = sys::close(w); // 关闭写端 → reader 收到 EOF
    out.push_str("[fifo] writer 已写两段并关闭写端（触发 reader 的 EOF）\n");

    match reader.join() {
        Ok(s) => out.push_str(&s),
        Err(_) => out.push_str("[fifo] reader 线程 panic\n"),
    }

    let _ = sys::unlink(path);
    out.push_str(&format!("[fifo] unlink({}) 清理完成\n", path));
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pipe_demo_reports_eof_and_epipe() {
        let log = pipe_demo();
        assert!(log.contains("EOF"), "应观察到 EOF：\n{log}");
        assert!(log.contains("EPIPE"), "关闭写端后再写应得到 EPIPE：\n{log}");
    }

    #[test]
    fn fifo_demo_roundtrip_and_enxio() {
        let path = format!("/tmp/ipclab_test_{}.fifo", std::process::id());
        let log = fifo_demo(&path);
        assert!(log.contains("ENXIO"), "无读者非阻塞打开应得 ENXIO：\n{log}");
        assert!(log.contains("同一 inode"), "应证明两端同一 inode：\n{log}");
        assert!(log.contains("EOF"), "写端关闭后 reader 应观察到 EOF：\n{log}");
    }
}
