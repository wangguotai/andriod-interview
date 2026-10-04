//! Android 目标下的 JNI 实现。只在 `cfg(target_os = "android")` 时编译。
//!
//! 符号命名规则：`Java_<包名下划线化>_<类名>_<方法名>`。
//! 对应 Kotlin 类：`com.interview.image.nativebridge.ImagePipelineNative`。

#![allow(non_snake_case)]

use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jint, jstring};
use jni::JNIEnv;

use crate::{guard, ABI_VERSION, ERR_BAD_ARGUMENT, ERR_PANIC, LOG_TAG};

/// 读取 direct buffer 的只读字节视图。
///
/// 安全性：调用方保证 `buf` 是一个 direct `ByteBuffer`（Kotlin 侧由
/// `Bitmap.copyPixelsToBuffer` 之类的入参类型约束）。返回的指针在调用期间有效，
/// 我们只读、不持有。
unsafe fn direct_bytes<'a>(env: &JNIEnv, buf: &JByteBuffer) -> Option<&'a [u8]> {
    let addr = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    if addr.is_null() || cap == 0 {
        return None;
    }
    Some(unsafe { std::slice::from_raw_parts(addr, cap) })
}

/// 读取 direct buffer 的可写字节视图（用于把结果写回调用方的缓冲）。
///
/// 安全性：同 [`direct_bytes`]。额外要求调用方保证没有其他别名同时读写这段内存 ——
/// 这正是流水线「一次调用、输入输出分离」的契约：Kotlin 侧传入不同的两个 buffer。
unsafe fn direct_bytes_mut<'a>(env: &JNIEnv, buf: &JByteBuffer) -> Option<&'a mut [u8]> {
    let addr = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    if addr.is_null() || cap == 0 {
        return None;
    }
    Some(unsafe { std::slice::from_raw_parts_mut(addr, cap) })
}

/// `downscaleArea(ByteBuffer src, int sw, int sh, ByteBuffer dst, int dw, int dh): int`
///
/// 区域平均降采样。成功返回 0，失败返回 [`ERR_BAD_ARGUMENT`]。
///
/// 设计要点：**输入与输出都是调用方持有的 direct buffer**，本函数不做任何
/// Java 堆分配、不创建新对象。这样每张图只有 1 次 JNI 调用、0 次拷贝，
/// 也是「Rust 版为什么可能更快」的主要来源之一（而不是「Rust 语言本身快」）。
#[no_mangle]
pub extern "system" fn Java_com_interview_image_nativebridge_ImagePipelineNative_downscaleArea(
    env: JNIEnv,
    _class: JClass,
    src: JByteBuffer,
    sw: jint,
    sh: jint,
    dst: JByteBuffer,
    dw: jint,
    dh: jint,
) -> jint {
    guard(|| {
        if sw <= 0 || sh <= 0 || dw <= 0 || dh <= 0 {
            return ERR_BAD_ARGUMENT;
        }
        let src_bytes = match unsafe { direct_bytes(&env, &src) } {
            Some(b) => b,
            None => return ERR_BAD_ARGUMENT,
        };
        let dst_bytes = match unsafe { direct_bytes_mut(&env, &dst) } {
            Some(b) => b,
            None => return ERR_BAD_ARGUMENT,
        };
        match imagepipeline::downscale::downscale_area(
            src_bytes,
            sw as u32,
            sh as u32,
            dst_bytes,
            dw as u32,
            dh as u32,
        ) {
            Ok(()) => 0,
            Err(_) => ERR_BAD_ARGUMENT,
        }
    })
}

/// `dominantColor(ByteBuffer src, int w, int h): int`
///
/// 主色调提取。成功返回 `0x00RRGGBB`，失败返回负错误码。
///
/// 返回 `jint` 而非对象：避免在 JNI 边界上构造/回收 Java 对象。颜色值本身
/// 只占 24 位，塞得进 `int`；用负数表示错误码，调用方判断 `< 0` 即可。
#[no_mangle]
pub extern "system" fn Java_com_interview_image_nativebridge_ImagePipelineNative_dominantColor(
    env: JNIEnv,
    _class: JClass,
    src: JByteBuffer,
    w: jint,
    h: jint,
) -> jint {
    guard(|| {
        if w <= 0 || h <= 0 {
            return ERR_BAD_ARGUMENT;
        }
        let bytes = match unsafe { direct_bytes(&env, &src) } {
            Some(b) if b.len() >= (w as usize * h as usize * 4) => b,
            _ => return ERR_BAD_ARGUMENT,
        };
        let needed = w as usize * h as usize * 4;
        let d = imagepipeline::dominant::dominant_color(&bytes[..needed]);
        d.rgb as jint
    })
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
    env: JNIEnv,
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
