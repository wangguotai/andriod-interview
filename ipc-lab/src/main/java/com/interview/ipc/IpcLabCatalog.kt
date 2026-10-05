package com.interview.ipc

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 本 Lab 的**唯一演示登记处**（与主 app 的 HomeCatalog 同一套约定）。
 *
 * 新增一个演示 = 在 [DEMOS] 里加一条 + 在 [IpcDemoRunner] 里注册实现。
 * 首页与汇总页都只读这里，不硬编码任何演示。
 *
 * 本文件随里程碑逐步生长：M3 追加 POSIX 信号。
 */
object IpcLabCatalog {

    val DEMOS: List<IpcDemo> = listOf(
        // ───────────── Socket 家族 ─────────────
        IpcDemo(
            id = "local_socket_abstract",
            title = "LocalSocket · 抽象命名空间",
            subtitle = "Java LocalSocket(ABSTRACT) ↔ Rust AF_UNIX 服务端，并读 SO_PEERCRED 对端身份",
            layer = IpcLayer.FRAMEWORK,
            model = IpcModel.BYTE_STREAM,
        ),
        IpcDemo(
            id = "local_socket_rust_selftest",
            title = "AF_UNIX 原生自测（Rust）",
            subtitle = "在 native 内把 abstract 与 filesystem 两种命名空间都跑通（含 peercred）",
            layer = IpcLayer.LINUX,
            model = IpcModel.BYTE_STREAM,
        ),
        IpcDemo(
            id = "tcp_loopback",
            title = "TCP loopback",
            subtitle = "127.0.0.1 上的 TCP 连接：印证「socket 不一定是跨机的」，并对照 AF_UNIX",
            layer = IpcLayer.FRAMEWORK,
            model = IpcModel.BYTE_STREAM,
        ),

        // ───────────── Linux 原生：管道 ─────────────
        IpcDemo(
            id = "native_pipe",
            title = "匿名管道 pipe (Rust)",
            subtitle = "pipe2 一对 fd、原子写、EOF、EPIPE：字节流没有消息边界的实证",
            layer = IpcLayer.LINUX,
            model = IpcModel.PIPE,
        ),
        IpcDemo(
            id = "native_fifo",
            title = "命名管道 FIFO (Rust)",
            subtitle = "mkfifo + 阻塞/非阻塞打开语义：无读者时 O_WRONLY|O_NONBLOCK 回 ENXIO",
            layer = IpcLayer.LINUX,
            model = IpcModel.PIPE,
        ),
        IpcDemo(
            id = "native_signal",
            title = "POSIX 信号 (Rust)",
            subtitle = "sigaction + SA_SIGINFO 读 si_pid、实时信号不合并、sigsuspend 消除竞态",
            layer = IpcLayer.LINUX,
            model = IpcModel.SIGNAL,
        ),
    )

    /** 汇总页按「层级 → 模型」分组展示用。 */
    fun byLayer(layer: IpcLayer): List<IpcDemo> = DEMOS.filter { it.layer == layer }
}
