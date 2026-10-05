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
        let (resp, _body) = wire::decode(&bytes).ok()?;
        Some(format!(
            "status={} proto={} reused={} connect_ms={:?} first_byte_ms={:?} total_ms={} body_bytes={} header_count={} spki={}",
            resp.status,
            resp.proto,
            resp.reused_connection,
            resp.connect_ms,
            resp.first_byte_ms,
            resp.total_ms,
            resp.body_bytes,
            // header 条数：重复头各自计数（Vec，不再被 map 合并）。
            // 修复：此处原先误传 body.len()，字段标签与值不符（诊断字符串会误导）。
            resp.headers.len(),
            resp.spki_sha256.as_deref().unwrap_or("none"),
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
/// 给一个令牌句柄，走一遍「判定未取消 → 取消 → 判定已取消」。
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

// ─────────────────────────────────────────
// fetch —— 真正的 QUIC/HTTP-3 传输（阻塞式，跑在调用方线程上）
// ─────────────────────────────────────────

/// `fetch(String url, String method, byte[] headers, byte[] body, long timeoutMs, long cancelHandle, byte[] pins): byte[]`
///
/// 阻塞式发起一次 HTTP/3 请求，返回[线格式](netlab::wire)字节；`null` 表示参数/内部错误。
///
/// ─── 线程治理（本符号最重要的约定）───
/// 本函数在**调用方线程**上完成全部 IO：netlab::h3 用 current-thread runtime +
/// block_on，不额外起 worker 线程。所以 Kotlin 侧调用时应落在 ThreadPools 的
/// net 泳道线程上。ConcurrentHashMap 里那句「不得起游离线程」的约束由此继续成立。
///
/// ─── 取消 ───
/// `cancel_handle` 是 `cancelTokenNew` 返回的句柄（可为 0 = 不取消）。
/// 取消是**协作式**的：block_on 内每 50ms 轮询标志，命中即丢弃 future；
/// 无法硬中断正在进行的 socket syscall（见 DESIGN §4.3，已知缺口）。
///
/// ─── 安全：证书固定（pinning）───
/// `pins` 是行格式 `<host|*>\t<64位hex>\r\n`（见 `netlab::wire::parse_pin_lines`）。
/// 非空时，rustls 在**完整链校验之后**再校验叶证书 SPKI-SHA256；
/// 不匹配会被标记为 `SPKI-PIN-MISMATCH`，供上层识别为安全事件。
/// 为 null/空 = 不做 pin（仍做完整链校验，**不是**不校验）。
#[no_mangle]
pub extern "system" fn Java_com_interview_net_nativebridge_NetLabNative_fetch(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    method: JString,
    headers: JByteArray,
    body: JByteArray,
    timeout_ms: jlong,
    cancel_handle: jlong,
    pins: JByteArray,
) -> jni::sys::jbyteArray {
    use netlab::h3::{self, FetchRequest, TlsConfig};

    // 全部包在 catch_unwind 里：panic 绝不跨 FFI。
    let encoded = std::panic::catch_unwind(AssertUnwindSafe(|| {
        let url = read_string(&mut env, &url)?;
        let method = read_string(&mut env, &method)?;
        let header_bytes = if headers.is_null() { Vec::new() } else { env.convert_byte_array(&headers).ok()? };
        let body_bytes = if body.is_null() { None } else { Some(env.convert_byte_array(&body).ok()?) };
        let pin_bytes = if pins.is_null() { Vec::new() } else { env.convert_byte_array(&pins).ok()? };

        // 取取消标志：句柄为 0 表示不取消。只借用，不取所有权。
        // 克隆出 Arc 捕获进闭包，保证 fetch 期间句柄被外部释放也不会悬垂。
        let token: Option<CancelToken> = if cancel_handle == 0 {
            None
        } else {
            Some(unsafe { &*(cancel_handle as *const CancelToken) }.clone())
        };

        // 解析 pin 行格式并记日志：被拒的行**必须可见**（安全配置写错不能静默）。
        let (pin_list, rejected) = wire::parse_pin_lines(&pin_bytes);
        if !rejected.is_empty() {
            log_w(&mut env, &format!("{} 条 pin 格式非法、已忽略：{:?}", rejected.len(), rejected));
        }
        let tls = TlsConfig { pins: pin_list };

        let req = FetchRequest {
            url,
            method,
            has_proxy: false,
            timeout_ms: if timeout_ms > 0 { timeout_ms as u64 } else { 15_000 },
            headers: wire::decode_header_lines(&header_bytes),
            body: body_bytes,
        };

        let result = h3::fetch(req, &tls, move || {
            token.as_ref().is_some_and(|t| t.is_cancelled())
        });

        Some(match result {
            Ok(resp) => {
                let leaf_spki = resp
                    .peer_certificates
                    .first()
                    .and_then(|der| h3::spki_sha256_hex(der));
                let wire_resp = wire::WireResponse {
                    status: resp.status,
                    proto: "HTTP_3".to_string(),
                    reused_connection: resp.reused_connection,
                    connect_ms: Some(resp.connect_ms),
                    first_byte_ms: Some(resp.first_byte_ms),
                    total_ms: resp.total_ms,
                    body_bytes: resp.body.len(),
                    spki_sha256: leaf_spki,
                    headers: resp.headers,
                };
                wire::encode(&wire_resp, &resp.body)
                    .unwrap_or_else(|e| wire::encode_error(ERR_WIRE, &format!("{e:?}")))
            }
            Err(e) => wire::encode_error(e.code(), &e.message()),
        })
    }));

    match encoded {
        Ok(Some(bytes)) => match env.byte_array_from_slice(&bytes) {
            Ok(arr) => arr.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        _ => std::ptr::null_mut(),
    }
}

/// 通过 `android.util.Log.w` 打一条日志。
///
/// 为什么值得为它写代码：跨 FFI 的错误最难查，而**这里恰恰是「配置写错了但没人知道」
/// 的高发区**（例如 pin 格式不对）。让被拒的配置出现在 logcat 里，比在文档里写
/// 「请注意格式」有效得多。日志失败不影响主流程（整体 runCatching 吞掉）。
#[cfg(target_os = "android")]
fn log_w(env: &mut JNIEnv, msg: &str) {
    let _ = (|| -> jni::errors::Result<()> {
        let tag = env.new_string(LOG_TAG)?;
        let text = env.new_string(msg)?;
        let class = env.find_class("android/util/Log")?;
        env.call_static_method(
            class,
            "w",
            "(Ljava/lang/String;Ljava/lang/String;)I",
            &[(&tag).into(), (&text).into()],
        )?;
        Ok(())
    })();
}

/// 线编码失败的内部错误码（不应发生；发生即为实现 bug）。
const ERR_WIRE: i32 = -3;

/// `probeSpki(String host): String` —— **不连网**的 SPKI 计算自证。
///
/// 给一个已被 fetch 过的证书 DER 的十六进制串，返回其 SPKI-SHA256。
/// 与图像侧 probeLayout 同一用途：把「SPKI 提取这一段是跨语言可信的」
/// 变成设备上可断言的证据（Kotlin 侧可用同一证书跑 OkHttp CertificatePinner 对拍）。
#[no_mangle]
pub extern "system" fn Java_com_interview_net_nativebridge_NetLabNative_spkiSha256Hex(
    env: JNIEnv,
    _class: JClass,
    cert_der: JByteArray,
) -> jstring {
    let result = std::panic::catch_unwind(AssertUnwindSafe(|| -> Option<String> {
        let der = env.convert_byte_array(cert_der).ok()?;
        netlab::h3::spki_sha256_hex(&der)
    }));
    match result {
        Ok(Some(hex)) => match env.new_string(hex) {
            Ok(s) => s.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        _ => std::ptr::null_mut(),
    }
}
