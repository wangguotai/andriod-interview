//! `vmp_android` —— VMP 加固 Lab 的 JNI 绑定层。
//!
//! ─── 这一层的职责边界（与 `imagepipeline_android` 同一套纪律）───
//!
//! 这里**没有算法**，也**没有 VM 语义**。它只做四件事：
//!   1. 把 JNI 类型（`JByteBuffer` / `jint`）翻译成字节视图；
//!   2. 装配 [`exec::VmExec`] 需要的内存与输入槽，并守住尺寸上限；
//!   3. 把 Rust 错误翻译成 JNI 能理解的负错误码；
//!   4. 守住 FFI 纪律：**任何 panic 都不得跨过 FFI 边界**。
//!
//! ─── 与 `imagepipeline_android` 的关系 ───
//!
//! 两者是**独立产物**（`libvmp_android.so` vs `libimagepipeline.so`），可以同时装、
//! 同时比。本 Lab 的「原生对照」刻意编在**同一个** `.so` 里（见 `exec::native`），
//! 这样基准测的是「同一个二进制的两条代码路径」，而不是「两个库」。
//! 理由见 `NOTES-vmp-lab.md` 的方法论一节。

use std::panic::{catch_unwind, AssertUnwindSafe};

pub mod exec;

#[cfg(target_os = "android")]
mod android_impl;

/// FFI 函数的统一包装：捕获 panic，转成错误码。
///
/// 约定：`>= 0` 表示成功（具体含义由各函数自定），`< 0` 表示失败。
/// 宿主平台编译时没有 FFI 调用方，会被判 dead_code；这是预期的。
///
/// ─── 为什么对 `i64` 泛型化 ───
///
/// 早先只服务 `i32`，是因为所有 FFI 返回值都是错误码或颜色。加了 `lastSteps`
/// （指令条数，会到亿级）之后必须支持 `i64`。做成 trait 而不是「再写一个
/// guard_i64」：两份 panic 处理逻辑只要有一处漏掉 `catch_unwind`，
/// 就是一个能掀掉整个进程的 UB 出口，而重复代码恰恰最容易漏。
#[cfg_attr(not(target_os = "android"), allow(dead_code))]
trait GuardCode: Copy {
    /// 失败时返回的错误码（负数域里挑，避免与成功值相撞）。
    const ERR: Self;
}

impl GuardCode for i32 {
    const ERR: i32 = exec::ERR_PANIC;
}

impl GuardCode for i64 {
    const ERR: i64 = exec::ERR_PANIC as i64;
}

#[cfg_attr(not(target_os = "android"), allow(dead_code))]
fn guard<F, T>(f: F) -> T
where
    F: FnOnce() -> T,
    T: GuardCode,
{
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(code) => code,
        Err(_) => T::ERR,
    }
}

/// 日志前缀，便于 logcat 过滤。
pub const LOG_TAG: &str = "VmpLabNative";

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
        // 泛型化后 `panic!` 的 `!` 类型推不出 `T`，必须显式给 —— 与真实 JNI 调用点
        // 一样写 `i32`（错误码）或 `i64`（指令数）。
        assert_eq!(guard::<_, i32>(|| panic!("boom")), exec::ERR_PANIC, "panic 绝不能跨 FFI");
        assert_eq!(
            guard::<_, i64>(|| panic!("boom")),
            exec::ERR_PANIC as i64,
            "i64 分支同样不得放 panic 跨 FFI",
        );
    }

    #[test]
    fn guard_passes_through_i64_values() {
        // 指令数会到亿级：确认 i64 通道不被截断。
        assert_eq!(guard(|| 123_456_789_012_i64), 123_456_789_012_i64);
    }
}
