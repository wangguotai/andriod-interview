package com.interview.ipc

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 本 Lab 的**唯一演示登记处**（与主 app 的 HomeCatalog 同一套约定）。
 *
 * 新增一个演示 = 在 [DEMOS] 里加一条 + 在 [IpcDemoRunner] 里注册实现。
 * 首页与汇总页都只读这里，不硬编码任何演示。
 *
 * 本文件随里程碑逐步生长：M1 登记 AF_UNIX（abstract/filesystem）+ TCP loopback。
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
    )

    /** 汇总页按「层级 → 模型」分组展示用。 */
    fun byLayer(layer: IpcLayer): List<IpcDemo> = DEMOS.filter { it.layer == layer }
}
