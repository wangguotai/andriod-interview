package com.interview.ipc

import android.content.Context

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 一条 IPC 演示的元数据 + 「归属哪一层」的标注。
 *
 * ─── 为什么要有这个模型 ───
 *
 * 首页 [IpcLabActivity] 不硬编码任何演示，只读 [IpcLabCatalog]；新增一个演示只改
 * Catalog 一处。这与主 app 的 `HomeCatalog` 是同一套约定（显式登记、编译期可查），
 * 只是作用域收在本 Lab 内部。
 *
 * ─── framework 与 Linux 原生必须分开标 ───
 *
 * 「Android 主流」与「Linux 原生」是本 Lab 要对照的两条线。用 [layer] 明确标注，
 * 汇总页才能诚实地展示「每种机制的层级、是否跨进程、是否真跑过」。
 */
data class IpcDemo(
    /** 稳定 id，日志/结果键用它，不用中文标题（避免标题改了导致引用失效）。 */
    val id: String,
    /** 展示名。 */
    val title: String,
    /** 一句话说明这个演示要证明什么。 */
    val subtitle: String,
    /** 归属层级。 */
    val layer: IpcLayer,
    /** 该演示的「承载对象」属于哪一类通信模型。 */
    val model: IpcModel,
) {
    /** 演示的 id 前缀，用于按 id 找到对应 runner。 */
    val key: String get() = "$layer/$id"
}

/**
 * 层级：Android framework 提供的机制 vs Linux 内核原生机制。
 *
 * 这个划分是本 Lab 的核心论点之一：Android 的跨进程能力**全部建立在 Linux 之上**，
 * Binder 驱动、ashmem、Unix socket、fd 传递都是内核设施；framework 只是封装。
 */
enum class IpcLayer(val label: String) {
    FRAMEWORK("Android framework"),
    LINUX("Linux 原生 (JNI/Rust)"),
}

/**
 * 通信模型：决定「适不适合某类需求」的关键属性。
 *
 * 这是汇总对比页的主轴 —— 不看机制名，而看它属于哪种模型，就能推出适用场景。
 */
enum class IpcModel(val label: String) {
    /** 请求-应答、有返回值、通常同步（Binder/AIDL、Messenger 的部分用法、Provider.call）。 */
    RPC("请求-应答 (RPC)"),
    /** 一对多、无返回值、发布订阅（Broadcast）。 */
    PUBSUB("发布-订阅 (Pub/Sub)"),
    /** 结构化数据 / 文件描述符访问（ContentProvider）。 */
    DATA_ACCESS("数据/文件访问"),
    /** 全双工字节流（LocalSocket、TCP）。 */
    BYTE_STREAM("字节流 (Stream)"),
    /** 单向字节管道（pipe / FIFO）。 */
    PIPE("字节管道 (Pipe)"),
    /** 共享内存（memfd / ashmem）——靠内存本身通信，fd 只是搬运手段。 */
    SHARED_MEMORY("共享内存"),
    /** 信号：极少量信息的通知。 */
    SIGNAL("信号通知 (Signal)"),
    /** 文件锁：互斥，不传数据。 */
    LOCK("文件锁 (Mutex)"),
}

/** 一次演示的运行结果。 */
sealed class DemoResult {
    /** 跑完并拿到完整日志（[log] 为原始多行文本）。 */
    data class Success(val log: String) : DemoResult()

    /** 跑不了 / 出错。[reason] 说明为什么，必须如实上屏。 */
    data class Failure(val reason: String) : DemoResult()
}

/** 演示的执行入口。所有 runner 都用同一签名，便于统一在后台线程跑。 */
typealias DemoRunner = (Context) -> DemoResult
