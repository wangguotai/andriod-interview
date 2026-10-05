//! 演示编排层：把 [`crate::sys`] 的原始调用组合成「可观测、可断言」的一段日志。
//!
//! ─── 分层原则 ───
//!
//! - 这里**不做** JNI、不碰 Android API，只产出纯文本证据；JNI 层（`android_impl.rs`）
//!   只负责把 `String` 转成 `jstring`。这样同一批演示既能在 Android 上跑，
//!   也能在宿主 `cargo test` 里跑（宿主为 Linux 时），测试与线上走的是**同一段代码**。
//! - 每个演示返回 `(可用, 成功, 日志)`：
//!     - 第 1 项 `available`：当前平台是否支持该演示（macOS 上整组为 false）；
//!     - 第 2 项 `ok`：演示过程是否正常跑完（不等于「结论符合预期」，结论由人/测试读）；
//!     - 第 3 项：整段日志（含 `[tag]` 前缀），上层原样上屏。
//! - 不在 Kotlin 侧二次拼接日志 —— 证据的可信度来自「它就是 native 打出来的原文」。
//!
//! 本文件随里程碑逐步生长：M1 引入 AF_UNIX（abstract / filesystem）自测与服务端。

/// 按名字分发到具体演示。名字与 Kotlin 侧 `IpcNativeDemo` 的枚举一一对应。
///
/// - [kind]：`unix`（自测）/ `unix_serve`（服务一条 Java LocalSocket 连接）
/// - [arg]：`unix` 传 filesystem socket 路径；`unix_serve` 传 abstract 名字
#[cfg(ipc_linux)]
pub fn run(kind: &str, arg: &str) -> (bool, bool, String) {
    // 先把 SIGPIPE 设为忽略（dlopen 进来的 cdylib 不会自动做这件事），
    // 否则「向已断开的 socket 写」会直接杀掉 App 进程而不是回 EPIPE。
    crate::ensure_init();
    match kind {
        "unix" => (true, true, crate::stream::unix_socket_selftest(arg)),
        "unix_serve" => {
            // arg = abstract 名字；服务一条 Java LocalSocket 连接（默认 5s 超时）。
            match crate::stream::serve_abstract_once(arg, 5000) {
                Ok(log) => (true, true, log),
                Err(e) => (true, false, format!("[unix] 服务端失败: {}\n", crate::sys::errno_name(e))),
            }
        }
        other => (false, false, format!("[ipclab] 未知演示类型: {}\n", other)),
    }
}

/// 宿主非 Linux（如 macOS）时的降级：明确告知「不支持」，而不是编译失败或静默跳过。
///
/// 这条分支的存在本身就是一条证据：本 crate 的演示依赖 Linux syscall，
/// 在 macOS 上无法伪造。要验证请用 `cargo check --target aarch64-linux-android`
/// 或直接在设备上跑（见 module 的 NOTES）。
#[cfg(not(ipc_linux))]
pub fn run(kind: &str, _arg: &str) -> (bool, bool, String) {
    let _ = kind;
    (
        false,
        false,
        "[ipclab] 当前宿主平台非 Linux/Android，原生演示不可用；\
         请在设备上运行或使用 --target aarch64-linux-android 做类型检查\n"
            .to_string(),
    )
}

/// 支持的演示类型列表（供 Kotlin 侧做能力探测 / 展示）。
#[cfg(ipc_linux)]
pub fn supported() -> Vec<&'static str> {
    vec!["unix", "unix_serve"]
}

#[cfg(not(ipc_linux))]
pub fn supported() -> Vec<&'static str> {
    vec![]
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 这条在任何平台都成立：未知类型要被明确拒绝，而不是 panic。
    #[test]
    fn dispatch_unknown_is_graceful() {
        let (_available, ok, log) = run("nope", "");
        assert!(!ok);
        assert!(log.contains("未知演示类型"));
    }

    #[cfg(ipc_linux)]
    #[test]
    fn supported_lists_all() {
        assert_eq!(supported().len(), 2);
    }
}
