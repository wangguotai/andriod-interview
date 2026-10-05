package com.interview.ipc

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 本 Lab 的**唯一演示登记处**（与主 app 的 HomeCatalog 同一套约定）。
 *
 * 新增一个演示 = 在 [DEMOS] 里加一条 + 在 [IpcDemoRunner] 里注册实现。
 * 首页与汇总页都只读这里，不硬编码任何演示。
 *
 * ─── 覆盖矩阵（Android 主流 + Linux 原生）───
 *
 * Android framework 线：
 *   - AIDL/Binder 同步调用、Binder 线程池、oneway + 反向回调、linkToDeath 死亡通知
 *   - Messenger（基于 Message 的双向通信）
 *   - ContentProvider（结构化数据 call + openFile 传 fd）
 *   - Broadcast（一对多通知 + 回执广播）
 *   - LocalSocket（路径 / 抽象两种命名空间）↔ Rust AF_UNIX 服务端
 *   - TCP loopback（与上面同属 socket 家族，用于对照抽象命名空间）
 *
 * Linux 原生线（JNI/Rust）：
 *   - 匿名 pipe、命名 FIFO
 *   - memfd 共享内存 + SCM_RIGHTS fd 传递 + fork
 *   - POSIX 信号、flock 文件锁
 *
 * 本文件随里程碑逐步生长：M6 追加 Binder 家族四件套（AIDL / Messenger / Provider / Broadcast）。
 */
object IpcLabCatalog {

    val DEMOS: List<IpcDemo> = listOf(
        // ───────────── Android framework：Binder 主线 ─────────────
        IpcDemo(
            id = "binder_sync",
            title = "AIDL · 同步调用",
            subtitle = "跨进程方法调用：参数 Parcel 过去、结果 Parcel 回来，返回服务端 pid 自证",
            layer = IpcLayer.FRAMEWORK,
            model = IpcModel.RPC,
        ),
        IpcDemo(
            id = "binder_threadpool",
            title = "AIDL · Binder 线程池",
            subtitle = "并发发起多次「耗时」调用，观察落在不同 binder 线程 ⇒ 服务端并行处理",
            layer = IpcLayer.FRAMEWORK,
            model = IpcModel.RPC,
        ),
        IpcDemo(
            id = "binder_async_callback",
            title = "AIDL · oneway + 反向回调",
            subtitle = "oneway 立刻返回，服务端用客户端传去的 Binder 反向上报进度与结果（双向 Binder）",
            layer = IpcLayer.FRAMEWORK,
            model = IpcModel.RPC,
        ),
        IpcDemo(
            id = "binder_death",
            title = "AIDL · linkToDeath 死亡通知",
            subtitle = "杀掉远端进程，客户端在 onBindingDied/linkToDeath 里收到通知并自动重连",
            layer = IpcLayer.FRAMEWORK,
            model = IpcModel.RPC,
        ),

        // ───────────── Android framework：其它三种 IPC ─────────────
        IpcDemo(
            id = "messenger",
            title = "Messenger",
            subtitle = "基于 Message + replyTo 的双向通信：无需 AIDL，天然异步",
            layer = IpcLayer.FRAMEWORK,
            model = IpcModel.RPC,
        ),
        IpcDemo(
            id = "provider",
            title = "ContentProvider",
            subtitle = "query 返回结构化 Cursor、call 做轻量 RPC、openFile 用 ParcelFileDescriptor 传 fd",
            layer = IpcLayer.FRAMEWORK,
            model = IpcModel.DATA_ACCESS,
        ),
        IpcDemo(
            id = "broadcast",
            title = "Broadcast",
            subtitle = "一对多通知 + 回执广播：发送与接收各带 pid，形成往返证据",
            layer = IpcLayer.FRAMEWORK,
            model = IpcModel.PUBSUB,
        ),

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

        // ───────────── Linux 原生 ─────────────
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
            id = "native_shm",
            title = "共享内存 memfd + SCM_RIGHTS (Rust)",
            subtitle = "memfd_create + mmap + fd 传递 + fork：证明两端 mmap 的是同一块物理内存",
            layer = IpcLayer.LINUX,
            model = IpcModel.SHARED_MEMORY,
        ),
        IpcDemo(
            id = "native_signal",
            title = "POSIX 信号 (Rust)",
            subtitle = "sigaction + SA_SIGINFO 读 si_pid、实时信号不合并、sigsuspend 消除竞态",
            layer = IpcLayer.LINUX,
            model = IpcModel.SIGNAL,
        ),
        IpcDemo(
            id = "native_flock",
            title = "flock 文件锁 (Rust)",
            subtitle = "fork 出子进程争锁：父持锁则子 LOCK_EX|LOCK_NB 回 EWOULDBLOCK（单实例原理）",
            layer = IpcLayer.LINUX,
            model = IpcModel.LOCK,
        ),
    )

    /** 汇总页按「层级 → 模型」分组展示用。 */
    fun byLayer(layer: IpcLayer): List<IpcDemo> = DEMOS.filter { it.layer == layer }
}
