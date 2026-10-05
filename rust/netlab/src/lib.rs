//! `netlab` —— Rust 传输实验内核（纯逻辑，零 Android / 零 JNI）。
//!
//! ─── 这一层的边界（很重要，与 `imagepipeline` 同一纪律）───
//!
//! 这里**没有网络 IO**。它只回答四类「能脱离设备验证」的问题：
//!
//!   1. [`request`]：这次请求是否允许由 Rust 传输处理？（方法/URL 策略 + 明文拒绝）
//!   2. [`cancel`]：取消标志的状态机——并发路径最容易写错的地方
//!   3. [`timing`]：阶段耗时累加，字段与 Kotlin `NetMetrics.Stage` 对齐
//!   4. [`wire`]：JNI 回传的线格式编解码（对拍用；字段错位是最阴的错法）
//!
//! 真正的 QUIC 传输（quinn/rustls）挂在 `quic` feature 后，见 crate 文档。
//!
//! ─── 为什么把「校验/取消/计时」放在 Rust 而不是 Kotlin ───
//!
//! 这些问题在 Kotlin 里也能写。放这里的**唯一理由**是：它们必须与传输实现共享
//! 同一份状态（尤其是取消标志与计时点），若跨语言各写一份，就会出现
//! 「Kotlin 认为已取消、Rust 还在跑」这类两端不一致、且只在真机偶发的问题。
//! 逻辑留在 Rust、由窄 JNI 暴露判定结果，是让两端**只有一个真相**的做法。

pub mod cancel;
pub mod request;
pub mod timing;
pub mod wire;

/// 稳定的 ABI 版本号。
///
/// 与 `imagepipeline_android::ABI_VERSION` 完全同一套约定：Kotlin 侧加载库后
/// 必须比对一次，不匹配就**显式降级**（回退 OkHttp），绝不「用错布局静默算错」。
///
/// 变更记录：
///   1 → M1：abiVersion / versionString / validateRequest / CancelToken / timing / wire
pub const ABI_VERSION: i32 = 1;

/// 本 crate 的版本字符串，供 Kotlin 侧 diagnostics 展示。
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// 日志前缀，便于 logcat 过滤（与 ImagePipelineNative 的做法一致）。
pub const LOG_TAG: &str = "NetLabNative";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn abi_and_version_are_stable() {
        assert_eq!(ABI_VERSION, 1, "ABI 版本变更必须同步 Kotlin 侧 EXPECTED_ABI_VERSION");
        assert!(!VERSION.is_empty());
        assert_eq!(LOG_TAG, "NetLabNative");
    }
}
