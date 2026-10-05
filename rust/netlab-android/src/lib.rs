//! `netlab_android` —— `netlab` 的 JNI 绑定层。
//!
//! ─── 这一层的职责边界（与 `imagepipeline_android` 完全同一纪律）───
//!
//! **这里没有传输逻辑**。它只做四件事：
//!   1. 把 JNI 的类型（`JString` / `JByteArray` / `jlong`）翻译成 `netlab` 的纯 Rust 视图；
//!   2. 把 Rust 的结果编码成线格式字节，交给 Kotlin 侧解码；
//!   3. 管理**取消令牌句柄**的创建 / 转交 / 释放（跨 FFI 的唯一状态）；
//!   4. 守住一条纪律：**任何 panic 都不得跨过 FFI 边界**。
//!
//! 第 4 条是 native 工程最常见的事故源：Rust 的 panic 跨过 `extern "C"` 是 UB，
//! 在 Android 上表现为整个进程被 kill。所以所有对外函数都包在 [`guard`] 里，
//! 把 panic 转成可诊断的错误返回。
//!
//! ─── ⚠️ 一个真实的一致性陷阱（写在这里，因为这里是最容易犯的地方）───
//!
//! `jni` crate 0.21 的 `JNIEnv::get_string` 返回的是 `JavaStr`，它的字节是
//! **Modified UTF-8**（CESU-8 变体），而 Rust 侧我们的线格式与 URL 都是**标准 UTF-8**。
//! 对本 demo 用到的 ASCII/常规 BMP 文本两者一致；但遇到增补平面字符（emoji）或
//! 含 NUL 的串时会不一致。第一版据此**约束输入为 ASCII 安全的 URL/方法**（见
//! [`netlab::request::validate`]），并把这条限制如实写在这里，不假装不存在。
//!
//! ─── 里程碑说明（诚实标注）───
//!
//! 本层实现的是**协议边界与控制面**：校验、取消、计时、线格式，以及
//! **真正的 QUIC/HTTP-3 `fetch`**（见 [android_impl::`Java_..._fetch`]）。
//!
//! 关于线程治理这个曾经的前置问题，现已解决：`netlab::h3::fetch` 用
//! **current-thread runtime + block_on**，IO 由**调用方线程**驱动，
//! runtime 不额外起 worker 线程 —— 因此网络任务落在 `ThreadPools` 的
//! net 泳道线程上，命名/配额/背压继续生效（见 netlab/src/h3.rs 模块头）。
//!
//! 尚未接入的（缺口，如实列出，见 NETLAB 文档缺口表）：
//!   · **证书固定（pinning）经 JNI 配置**：kernel 已实现并端到端验证，
//!     但「把 pin 从 Java 传进 Rust」的通道还没接 —— 当前用默认链校验；
//!   · QUIC 连接复用（`reused_connection` 恒为 false）；
//!   · 流式 body（v1 只支持已完整读入内存的小 body）；
//!   · Android 系统 CA 注入。

use std::panic::{catch_unwind, AssertUnwindSafe};

#[cfg(target_os = "android")]
mod android_impl;

/// 对外 FFI 函数的统一包装：捕获 panic，转成 `i32` 错误码。
///
/// 约定：`>= 0` 表示成功（具体含义由各函数自定），`< 0` 表示失败。
// 宿主平台编译时没有 FFI 调用方，会被判 dead_code；这是预期的。
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
pub(crate) fn guard<F>(f: F) -> i32
where
    F: FnOnce() -> i32,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(code) => code,
        Err(_) => ERR_PANIC,
    }
}

/// 入参非法（空串、缓冲区太小、句柄为空等）。
pub const ERR_BAD_ARGUMENT: i32 = -1;
/// Rust 侧发生 panic，已被拦截。
pub const ERR_PANIC: i32 = -2;

/// 稳定的 ABI 版本号，与 `netlab::ABI_VERSION` 一致。
///
/// Kotlin 侧加载库后应比对一次：不匹配时宁可「明确降级回 OkHttp」，
/// 也不要「用错线格式静默解析」。这是 native 升级的常规防线。
///
/// 变更记录：
///   1 → M1：abiVersion / versionString / validateRequest / CancelToken 句柄
///           / 线格式编解码 / probeHandleRoundTrip
///   2 → M2：新增 **fetch**（真实 HTTP/3）+ spkiSha256Hex；
///           线格式 headers 由 map 改为**有序可重复**（否则 Set-Cookie 会被静默覆盖），
///           并新增 spki 字段。**格式变更 ⇒ ABI 必须 +1**，让旧 Kotlin 明确降级。
///   3 → M3：fetch 新增 `pins` 参数（证书固定的配置通道，安全红线）；
///           仅 `fetch` 的**参数列表**变化，线格式未变。但 JNI 签名变更同样必须
///           让旧 Kotlin 明确降级（否则会按旧签名调用 → 未定义行为），故 ABI +1。
pub const ABI_VERSION: i32 = 3;

/// 日志前缀，便于 logcat 过滤。
pub const LOG_TAG: &str = netlab::LOG_TAG;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn guard_converts_success_code() {
        assert_eq!(guard(|| 0), 0);
        assert_eq!(guard(|| 42), 42);
    }

    #[test]
    fn guard_converts_panic_to_error_code() {
        // 这条测试的价值：证明「panic 不跨 FFI」这条纪律是被机制保证的，
        // 而不是靠每个函数作者自觉。
        let code = guard(|| panic!("boom"));
        assert_eq!(code, ERR_PANIC, "panic 必须被拦下，绝不能跨 FFI");
    }

    #[test]
    fn abi_version_matches_kernel() {
        assert_eq!(ABI_VERSION, netlab::ABI_VERSION);
        assert_eq!(LOG_TAG, netlab::LOG_TAG);
    }
}
