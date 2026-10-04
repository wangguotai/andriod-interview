//! Android 目标下的 JNI 实现。只在 `cfg(target_os = "android")` 时编译。
//!
//! 符号命名规则：`Java_<包名下划线化>_<类名>_<方法名>`。
//! 对应 Kotlin 类：`com.interview.image.nativebridge.ImagePipelineNative`。

#![allow(non_snake_case)]

use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jint, jstring};
use jni::JNIEnv;

use crate::{guard, ABI_VERSION, ERR_BAD_ARGUMENT, ERR_PANIC, LOG_TAG};

/// 读取 direct buffer 的全部字节。
///
/// 安全性：调用方保证 `buf` 是一个 direct `ByteBuffer`（Kotlin 侧由
/// `Bitmap.copyPixelsToBuffer` 的入参类型约束）。返回的指针在调用期间有效，
/// 我们只读、不持有。
unsafe fn direct_bytes<'a>(env: &JNIEnv, buf: &JByteBuffer) -> Option<&'a [u8]> {
    let addr = unsafe { env.get_direct_buffer_address(buf) }.ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    if addr.is_null() || cap == 0 {
        return None;
    }
    Some(unsafe { std::slice::from_raw_parts(addr, cap) })
}

/// `abiVersion(): int` —— ABI 版本号，Kotlin 侧加载后比对用。
#[no_mangle]
pub extern "system" fn Java_com_interview_image_nativebridge_ImagePipelineNative_abiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    guard(|| ABI_VERSION)
}

/// `probeLayout(ByteBuffer): int` —— 布局自证探针（M1 的验收核心）。
///
/// 把 buffer 前 4 个字节按 **R,G,B,A** 解释并打包成 `0xRRGGBBAA` 返回。
/// Kotlin 侧对同一张已知颜色的 Bitmap，用 `Color.red/green/blue/alpha` 读出期望值，
/// 两者必须相等。
///
/// 这个探针存在的意义：ARGB_8888 的命名会诱导人按 `0xAARRGGBB` 去解读内存，
/// 一旦错位，**所有滤镜都会静默偏色**（R/B 互换），而且看起来「图还能显示」，
/// 极难发现。所以把「通道顺序」变成一条可断言的证据，而不是一条注释里的假设。
///
/// 返回：`>= 0` 为打包后的像素值；`ERR_BAD_ARGUMENT` 表示缓冲区太小/非法。
#[no_mangle]
pub extern "system" fn Java_com_interview_image_nativebridge_ImagePipelineNative_probeLayout(
    env: JNIEnv,
    _class: JClass,
    buf: JByteBuffer,
) -> jint {
    guard(|| {
        let bytes = match unsafe { direct_bytes(&env, &buf) } {
            Some(b) if b.len() >= 4 => b,
            _ => return ERR_BAD_ARGUMENT,
        };
        let (r, g, b, a) = (bytes[0], bytes[1], bytes[2], bytes[3]);
        ((r as i32) << 24) | ((g as i32) << 16) | ((b as i32) << 8) | (a as i32)
    })
}

/// `versionString(): String` —— 人类可读的版本信息，便于在设备上确认「加载的确实是这一版」。
#[no_mangle]
pub extern "system" fn Java_com_interview_image_nativebridge_ImagePipelineNative_versionString(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let text = format!(
        "imagepipeline-native abi={} tag={} target={}",
        ABI_VERSION,
        LOG_TAG,
        std::env::consts::ARCH,
    );
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| env.new_string(text))) {
        Ok(Ok(s)) => s.into_raw(),
        // 造 jstring 都失败时只能返回 null；Kotlin 侧对 null 做降级
        _ => {
            let _ = ERR_PANIC;
            std::ptr::null_mut()
        }
    }
}
