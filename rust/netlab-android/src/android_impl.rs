//! Android 目标下的 JNI 实现。只在 `cfg(target_os = "android")` 时编译。
//!
//! 符号命名规则：`Java_<包名下划线化>_<类名>_<方法名>`。
//! 对应 Kotlin 类：`com.interview.net.nativebridge.NetLabNative`。

#![allow(non_snake_case)]

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use std::panic::AssertUnwindSafe;

use netlab::cancel::CancelToken;
use netlab::request;
use netlab::wire;
use std::sync::Arc;

use crate::{guard, ABI_VERSION, ERR_BAD_ARGUMENT, ERR_PANIC, LOG_TAG};

/// 把 `JavaStr`（Modified UTF-8）转成 Rust `String`；失败返回 `None`。
///
/// ⚠️ Modified UTF-8 与标准 UTF-8 在 BMP 之外（emoji）及 NUL 上不一致。
/// 第一版据此把输入约束为 ASCII 安全的 URL/方法，见 crate 头注释。
fn read_string(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(|js| js.to_string_lossy().into_owned())
}

/// `abiVersion(): int` —— ABI 版本号，Kotlin 侧加载后比对用。
#[no_mangle]
pub extern "system" fn Java_com_interview_net_nativebridge_NetLabNative_abiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    guard(|| ABI_VERSION)
}

/// `validateRequest(String url, String method, boolean hasProxy): int`
///
/// 请求准入校验。返回 `0` 表示放行，`> 0` 为 [`netlab::request::RejectReason::code`]。
///
/// 这是「安全闸门在跨语言边界内侧再设一道」的落点（见 netlab::request 模块注释）：
/// 即便调用方绕过 Kotlin 侧路由直接调这里，明文 http / 不支持的方法也会被拒。
#[no_mangle]
pub extern "system" fn Java_com_interview_net_nativebridge_NetLabNative_validateRequest(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    method: JString,
    has_proxy: jboolean,
) -> jint {
    guard(|| {
        let Some(url) = read_string(&mut env, &url) else {
            return ERR_BAD_ARGUMENT;
        };
        let Some(method) = read_string(&mut env, &method) else {
            return ERR_BAD_ARGUMENT;
        };
        match request::validate(&url, &method, has_proxy != 0) {
            Ok(()) => 0,
            Err(reason) => reason.code(),
        }
    })
}

// ─────────────────────────────────────────
// 取消令牌句柄（唯一跨 FFI 的状态）
// ─────────────────────────────────────────

/// `cancelTokenNew(): long`
///
/// 创建一个取消令牌，返回其裸指针作为句柄（`0` 表示失败）。
/// 由 [`Java_com_interview_net_nativebridge_NetLabNative_cancelTokenFree`] 释放。
///
/// ⚠️ 借用/所有权契约：返回的是 `Box::into_raw` 的指针，**所有权移交 Kotlin 侧**。
/// 必须恰好释放一次；重复释放或使用已释放句柄是 UB。
/// 用 `jlong` 而非 Java 对象：值语义、无 GC 交互、跨 FFI 不需要管引用。
#[no_mangle]
pub extern "system" fn Java_com_interview_net_nativebridge_NetLabNative_cancelTokenNew(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    // 这里不用 guard（guard 返回 i32）；用 catch_unwind 单独包一层，保证分配 panic 不跨 FFI。
    match std::panic::catch_unwind(|| Box::into_raw(Box::new(CancelToken::new())) as jlong) {
        Ok(p) => p,
        Err(_) => 0,
    }
}

/// `cancelTokenFree(long handle): void`
///
/// 释放令牌。对 `0` 句柄是安全的空操作（允许 Kotlin 侧无脑调用，不必先判空）。
#[no_mangle]
pub extern "system" fn Java_com_interview_net_nativebridge_NetLabNative_cancelTokenFree(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle == 0 {
        return;
    }
    // 释放必须包在 catch_unwind 里：析构 panic 同样不能跨 FFI。
    let _ = std::panic::catch_unwind(AssertUnwindSafe(|| {
        unsafe { drop(Box::from_raw(handle as *mut CancelToken)) };
    }));
}

/// `cancelTokenCancel(long handle): boolean` —— 请求取消；返回「本次是否真正翻转了状态」。
///
/// 已处于终态（DONE）时返回 false 且不改变状态 —— 这条语义由 netlab::cancel 的
/// 状态机保证，并有并发测试覆盖。
#[no_mangle]
pub extern "system" fn Java_com_interview_net_nativebridge_NetLabNative_cancelTokenCancel(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    if handle == 0 {
        return 0;
    }
    let token = unsafe { &*(handle as *const CancelToken) }; // 只借用，不取所有权
    if token.cancel() { 1 } else { 0 }
}

/// `cancelTokenIsCancelled(long handle): boolean`
#[no_mangle]
pub extern "system" fn Java_com_interview_net_nativebridge_NetLabNative_cancelTokenIsCancelled(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jboolean {
    if handle == 0 {
        return 0;
    }
    let token = unsafe { &*(handle as *const CancelToken) };
    if token.is_cancelled() { 1 } else { 0 }
}

// ─────────────────────────────────────────
// 线格式编解码（对拍与回传的咽喉）
// ─────────────────────────────────────────

/// `wireDecode(byte[] buf): String` —— 把线格式字节流解码成人类可读摘要。
///
/// 为什么返回 `String` 而不是逐个字段的多符号返回：**跨 FFI 的往返次数越少，
/// 出错面越小**。传输结果本身字段多，一次编解码比十几个 getter 调用更不易错位。
///
/// 返回 `null` 表示解码失败（Kotlin 侧据此走降级）。
///
/// 该函数目前主要服务于**对拍测试**：Kotlin 侧把 Rust 编码的结果解回来，
/// 与 Kotlin 侧自己的期望逐字段比对，确保两端对线格式的理解一致。
#[no_mangle]
pub extern "system" fn Java_com_interview_net_nativebridge_NetLabNative_wireDecode(
    env: JNIEnv,
    _class: JClass,
    buf: JByteArray,
) -> jstring {
    let result = std::panic::catch_unwind(AssertUnwindSafe(|| -> Option<String> {
        let bytes = env.convert_byte_array(buf).ok()?;
        let (resp, body) = wire::decode(&bytes).ok()?;
        Some(format!(
            "status={} proto={} reused={} connect_ms={:?} first_byte_ms={:?} total_ms={} body_bytes={} headers={}",
            resp.status,
            resp.proto,
            resp.reused_connection,
            resp.connect_ms,
            resp.first_byte_ms,
            resp.total_ms,
            resp.body_bytes,
            // body 摘要：只给长度与首字节，避免把大 body 塞进日志字符串
            body.len(),
        ))
    }));
    match result {
        Ok(Some(text)) => match env.new_string(text) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        // 解码失败 / panic：返回 null，Kotlin 侧降级
        _ => std::ptr::null_mut(),
    }
}

/// `versionString(): String` —— 人类可读的版本信息，便于在设备上确认「加载的确实是这一版」。
#[no_mangle]
pub extern "system" fn Java_com_interview_net_nativebridge_NetLabNative_versionString(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let text = format!(
        "netlab-native abi={} tag={} target={}",
        ABI_VERSION,
        LOG_TAG,
        std::env::consts::ARCH,
    );
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| env.new_string(text))) {
        Ok(Ok(s)) => s.into_raw(),
        _ => {
            let _ = ERR_PANIC;
            std::ptr::null_mut()
        }
    }
}

/// `probeHandleRoundTrip(long handle): int` —— 句柄往返自证（对拍用）。
///
/// 给一个令牌句柄，创建→取消→判定，返回 `1` 表示「创建、取消语义、句柄读写」全部正常。
/// 存在的意义与图像侧的 `probeLayout` 相同：**把一条跨 FFI 的假设变成可断言的证据**，
/// 而不是只在文档里写「句柄是这么用的」。句柄用错（重复释放/悬垂）是 native 最阴的错法，
/// 不会崩在调用点，只会在之后再崩。
#[no_mangle]
pub extern "system" fn Java_com_interview_net_nativebridge_NetLabNative_probeHandleRoundTrip(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    guard(|| {
        if handle == 0 {
            return ERR_BAD_ARGUMENT;
        }
        let token: &CancelToken = unsafe { &*(handle as *const CancelToken) };
        if token.is_cancelled() {
            // 新令牌不应处于取消态
            return ERR_BAD_ARGUMENT;
        }
        if !token.cancel() {
            return ERR_BAD_ARGUMENT;
        }
        if !token.is_cancelled() {
            return ERR_BAD_ARGUMENT;
        }
        1
    })
}

/// 让编译器知道 `Arc` 被用到（若将来句柄管理改用 Arc，此处保留导入以免反复改动）。
#[allow(dead_code)]
fn _keep_arc_import() -> Arc<CancelToken> {
    Arc::new(CancelToken::new())
}
