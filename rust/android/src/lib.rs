//! `imagepipeline_android` —— `imagepipeline` 的 JNI 绑定层。
//!
//! ─── 这一层的职责边界（很重要）───
//!
//! 这里**没有算法**。它只做三件事：
//!   1. 把 JNI 的类型（`JByteBuffer` / `jint`）翻译成 `imagepipeline` 的纯 Rust 视图；
//!   2. 把 Rust 的错误翻译成 JNI 能理解的错误码 / Java 异常；
//!   3. 守住一条边界纪律：**任何 panic 都不得跨过 FFI 边界**。
//!
//! 第 3 条是 native 工程最常见的事故源：Rust 的 panic 跨过 `extern "C"` 是 UB，
//! 在 Android 上表现为整个进程被 kill，而且 logcat 里往往只剩一行 SIGABRT，
//! 完全看不出是哪张图、哪个参数触发的。所以所有对外函数都包在 [`guard`] 里，
//! 把 panic 转成可诊断的错误返回。

use std::panic::{catch_unwind, AssertUnwindSafe};

#[cfg(target_os = "android")]
mod android_impl;

/// 对外 FFI 函数的统一包装：捕获 panic，转成 `i32` 错误码。
///
/// 约定：`>= 0` 表示成功（具体含义由各函数自定），`< 0` 表示失败。
// 宿主平台编译时没有 FFI 调用方，会被判 dead_code；这是预期的。
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn guard<F>(f: F) -> i32
where
    F: FnOnce() -> i32,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(code) => code,
        Err(_) => ERR_PANIC,
    }
}

/// 入参非法（尺寸与缓冲长度不符、空图等）。
pub const ERR_BAD_ARGUMENT: i32 = -1;
/// Rust 侧发生 panic，已被拦截。
pub const ERR_PANIC: i32 = -2;

/// 稳定的 ABI 版本号。
///
/// Kotlin 侧在加载库后应比对一次：native 与 Java 版本不匹配时，
/// 宁可「明确报错」也不要「用错布局静默算错」。这是 native 升级的常规防线。
pub const ABI_VERSION: i32 = 1;

/// 本 crate 的日志前缀，便于 logcat 过滤。
pub const LOG_TAG: &str = "ImagePipelineNative";

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
        let code = guard(|| panic!("boom"));
        assert_eq!(code, ERR_PANIC, "panic 必须被拦下，绝不能跨 FFI");
    }
}
