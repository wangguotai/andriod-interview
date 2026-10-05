//! Android 目标下的 JNI 实现。只在 `cfg(target_os = "android")` 时编译。
//!
//! 符号命名规则：`Java_<包名下划线化>_<类名>_<方法名>`，
//! 对应 Kotlin 类：`com.interview.ipc.nativebridge.IpcNative`。
//!
//! ─── 这一层的职责边界 ───
//!
//! 只做三件事，和 `imagepipeline_android` 的纪律完全一致：
//!   1. JNI 类型 ↔ Rust 类型的翻译（`JString` ↔ `String`）；
//!   2. Rust 错误 → JNI 可理解的返回值；
//!   3. 守住「**panic 绝不跨 FFI**」这条线 —— 否则整个 App 进程会被 SIGABRT 掉，
//!      而 logcat 里只剩一行无从定位的 abort。
//!
//! 演示的本体在 `ipclab::demo`，这里不做任何算法。

#![allow(non_snake_case)]

use jni::objects::{JClass, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use std::panic::{catch_unwind, AssertUnwindSafe};

use crate::{ABI_VERSION, LOG_TAG};
use crate::{demo, sys};

/// 把 `jstring` 参数转成 Rust `String`；失败（null / 非法 UTF-8）返回空串。
///
/// 这里选择「失败即空串」而不是抛异常：演示的入参缺失不该让整个调用崩掉，
/// 空串在各演示里都有合理默认行为（见 `demo::run`）。
fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|js| js.into()).unwrap_or_default()
}

/// `runDemo(kind: String, arg: String): String`
///
/// 返回整段演示日志（多行，`[tag]` 前缀）。**永不返回 null**（除非造 jstring 本身失败）。
///
/// 为什么一次返回整段而不是逐行回调：演示是**有序证据链**，逐行回调会让
/// 「顺序」变成跨 FFI 的第二个契约；一次返回整段，顺序与内容都是原生保证的。
///
/// 返回约定：日志首行若为 `[ipclab] 未知演示类型: xxx`，表示 kind 不受支持。
#[no_mangle]
pub extern "system" fn Java_com_interview_ipc_nativebridge_IpcNative_runDemo(
    mut env: JNIEnv,
    _class: JClass,
    kind: JString,
    arg: JString,
) -> jstring {
    let kind = jstr(&mut env, &kind);
    let arg = jstr(&mut env, &arg);
    let (available, ok, log) = catch_unwind(AssertUnwindSafe(|| demo::run(&kind, &arg)))
        .unwrap_or_else(|_| (false, false, format!("[{}] native panic，已被拦截\n", LOG_TAG)));
    let _ = available;
    let _ = ok;
    match env.new_string(log) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// `supportedDemos(): String` —— 逗号分隔的能力列表，供 Kotlin 侧探测。
#[no_mangle]
pub extern "system" fn Java_com_interview_ipc_nativebridge_IpcNative_supportedDemos(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let list = demo::supported().join(",");
    match env.new_string(list) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// `abiVersion(): int` —— 加载后比对，不匹配即显式降级。
#[no_mangle]
pub extern "system" fn Java_com_interview_ipc_nativebridge_IpcNative_abiVersion(
    _env: JNIEnv,    _class: JClass,
) -> jint {
    ABI_VERSION
}

/// `nativeGetpid(): int` / `nativeGettid(): int` —— 让 Kotlin 侧能把「谁在跑」写进证据。
#[no_mangle]
pub extern "system" fn Java_com_interview_ipc_nativebridge_IpcNative_nativeGetpid(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    sys::getpid()
}

#[no_mangle]
pub extern "system" fn Java_com_interview_ipc_nativebridge_IpcNative_nativeGettid(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    sys::gettid()
}

/// `errnoName(code: int): String` —— 把负 errno 翻译成符号名，让证据更可读。
#[no_mangle]
pub extern "system" fn Java_com_interview_ipc_nativebridge_IpcNative_errnoName(
    env: JNIEnv,
    _class: JClass,
    code: jint,
) -> jstring {
    let name = sys::errno_name(code);
    match env.new_string(name) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// `versionString(): String` —— 人类可读版本，便于在设备上确认「加载的确实是这一版」。
#[no_mangle]
pub extern "system" fn Java_com_interview_ipc_nativebridge_IpcNative_versionString(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let text = format!(
        "ipclab-native abi={} tag={} target={} arch={}",
        ABI_VERSION,
        LOG_TAG,
        std::env::consts::OS,
        std::env::consts::ARCH,
    );
    match catch_unwind(AssertUnwindSafe(|| env.new_string(text))) {
        Ok(Ok(s)) => s.into_raw(),
        _ => std::ptr::null_mut(),
    }
}
