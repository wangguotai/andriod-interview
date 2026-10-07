//! Android 目标下的 JNI 实现。只在 `cfg(target_os = "android")` 时编译。
//!
//! 符号命名：`Java_<包名下划线化>_<类名>_<方法名>`。
//! 对应 Kotlin 类：`com.interview.vmp.nativebridge.VmpNative`。
//!
//! ⚠️ 子包 `nativebridge` **必须**出现在符号名里。第一版漏了它、写成
//! `Java_com_interview_vmp_VmpNative_*`，编译与打包全绿，直到真机上跑测试才
//! 报 `UnsatisfiedLinkError` —— 这正是 JNI 最典型的失败模式：**没有编译期检查**。
//! 改 Kotlin 侧的包名/类名时，必须同时改这里的每一处。
//!
//! ─── 每个算子的**两条**导出 ───
//!
//! 每个算子都导出两个函数，后缀区分路径：
//!   - `*Vm`     → 走 VMP 虚拟机（字节码是加密的，见 `vmp::programs`）；
//!   - `*Native` → 走原生机器码（未加固，作为对拍与基准的对照）。
//!
//! 为什么要在这个 `.so` 里同时提供两条路径（而不是让 Kotlin 去调另一个库）：
//! 基准要回答的是「同一个算法，加固前后差多少」。两条路径若分属两个 `.so`，
//! 编译选项、内联、LTO 都可能不同，测出来的差异就不再是「加固的代价」。
//! 放在同一个 binary 里，"唯一的差别"就只剩「解释执行 vs 直接执行」。
//!
//! ─── 线程模型 ───
//!
//! VM 是**有状态**的（栈、aux、页缓存），因此执行器必须是「每线程一份」。
//! 这里用 `thread_local!`，与 `ImagePipelineBridge.RgbaBufferPool` 的动机一致：
//! 免锁、天然隔离，且**不新建线程** —— 本仓库规定 native 计算必须由 Kotlin 侧
//! 经 `ThreadPools` 泳道调度（见 `rust/README.md`）。

#![allow(non_snake_case)]

use std::cell::RefCell;

use jni::objects::{JByteBuffer, JClass};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_TRUE};
use jni::JNIEnv;

use crate::exec::{
    all_programs_intact, hardening_status, native, VmExec, ABI_VERSION, ERR_BAD_ARGUMENT,
};
use crate::{guard, LOG_TAG};

thread_local! {
    /// 每线程一份执行器：VM 有状态，不能跨线程共享。
    static EXEC: RefCell<VmExec> = RefCell::new(VmExec::new());
}

/// 读取 direct buffer 的只读字节视图（与 `imagepipeline_android` 同一套做法）。
///
/// 安全性：调用方保证 `buf` 是 direct `ByteBuffer`（Kotlin 侧由
/// `Bitmap.copyPixelsToBuffer` / `allocateDirect` 约束）。返回的切片在调用期间有效，
/// 我们只读、不持有。
unsafe fn direct_bytes<'a>(env: &JNIEnv, buf: &JByteBuffer) -> Option<&'a [u8]> {
    let addr = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    if addr.is_null() || cap == 0 {
        return None;
    }
    Some(unsafe { std::slice::from_raw_parts(addr, cap) })
}

/// 读取 direct buffer 的可写字节视图。额外要求调用方保证无别名（输入输出是不同 buffer）。
unsafe fn direct_bytes_mut<'a>(env: &JNIEnv, buf: &JByteBuffer) -> Option<&'a mut [u8]> {
    let addr = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    if addr.is_null() || cap == 0 {
        return None;
    }
    Some(unsafe { std::slice::from_raw_parts_mut(addr, cap) })
}

// ─────────────────────────────────────────────────────────────────────────────
// 元信息
// ─────────────────────────────────────────────────────────────────────────────

/// `abiVersion(): int` —— Kotlin 侧加载后比对，不匹配则显式降级。
#[no_mangle]
pub extern "system" fn Java_com_interview_vmp_nativebridge_VmpNative_abiVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    guard(|| ABI_VERSION)
}

/// `versionString(): String` —— 人类可读的版本信息（含 target），便于确认加载的是哪一版。
#[no_mangle]
pub extern "system" fn Java_com_interview_vmp_nativebridge_VmpNative_versionString(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let text = format!(
        "vmp-native abi={} tag={} target={}",
        ABI_VERSION,
        LOG_TAG,
        std::env::consts::ARCH
    );
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| env.new_string(text))) {
        Ok(Ok(s)) => s.into_raw(),
        _ => std::ptr::null_mut(),
    }
}

/// `hardeningStatus(): String` —— 加固状态摘要（程序密文长度、头部完好性等）。
#[no_mangle]
pub extern "system" fn Java_com_interview_vmp_nativebridge_VmpNative_hardeningStatus(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        env.new_string(hardening_status())
    })) {
        Ok(Ok(s)) => s.into_raw(),
        _ => std::ptr::null_mut(),
    }
}

/// `selftest(): boolean` —— 跑 VM 内建自检（算术语义与 ISA 规范是否一致）。
///
/// 这是加固方案**唯一**能在现场证明「VM 自己没坏」的通道，所以必须导出。
#[no_mangle]
pub extern "system" fn Java_com_interview_vmp_nativebridge_VmpNative_selftest(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    guard(|| {
        EXEC.with(|e| {
            let ok = e.borrow_mut().selftest();
            if ok {
                JNI_TRUE as jint
            } else {
                0
            }
        })
    }) as jboolean
}

/// `programsIntact(): boolean` —— 三个受保护程序的容器头是否完好（未解密即可验）。
#[no_mangle]
pub extern "system" fn Java_com_interview_vmp_nativebridge_VmpNative_programsIntact(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    guard(|| {
        if all_programs_intact() {
            JNI_TRUE as jint
        } else {
            0
        }
    }) as jboolean
}

/// `lastFetchPages(): int` —— 上次运行惰性解密的页数（未命中）。基准取证用。
#[no_mangle]
pub extern "system" fn Java_com_interview_vmp_nativebridge_VmpNative_lastFetchPages(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    guard(|| EXEC.with(|e| e.borrow().last_fetch_pages() as jint))
}

/// `lastCacheHits(): int` —— 上次运行命中页缓存的次数。基准取证用。
#[no_mangle]
pub extern "system" fn Java_com_interview_vmp_nativebridge_VmpNative_lastCacheHits(
    _env: JNIEnv,
    _class: JClass,
) -> jint {
    guard(|| EXEC.with(|e| e.borrow().last_cache_hits() as jint))
}

/// `lastSteps(): long` —— 上次 VM 运行执行的指令条数。
///
/// 返回 `jlong` 而不是 `jint`：dominant 在 256×192 上就是千万级指令，
/// 更大的图能上亿，`jint` 会**静默回绕**。回绕后的数字看起来
/// 「挺小挺合理」，比直接报错难查得多 —— 这是本条符号唯一值得强调的点，
/// 也是「加固代价」里唯一与设备无关的量（毫秒数换台机器就变，指令数不会）。
#[no_mangle]
pub extern "system" fn Java_com_interview_vmp_nativebridge_VmpNative_lastSteps(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    guard(|| EXEC.with(|e| e.borrow().last_steps() as jlong))
}

// ─────────────────────────────────────────────────────────────────────────────
// 三个算子 × 两条路径
// ─────────────────────────────────────────────────────────────────────────────

/// `dominantColorVm(ByteBuffer src, int w, int h): int`
///
/// VMP 路径：走加密字节码。成功返回 `0x00RRGGBB`（恒非负），失败返回负错误码。
#[no_mangle]
pub extern "system" fn Java_com_interview_vmp_nativebridge_VmpNative_dominantColorVm(
    env: JNIEnv,
    _class: JClass,
    src: JByteBuffer,
    w: jint,
    h: jint,
) -> jint {
    guard(|| {
        let bytes = match unsafe { direct_bytes(&env, &src) } {
            Some(b) => b,
            None => return ERR_BAD_ARGUMENT,
        };
        EXEC.with(|e| match e.borrow_mut().dominant(bytes, w as u32, h as u32) {
            Ok(rgb) => rgb as jint,
            Err(code) => code,
        })
    })
}

/// `dominantColorNative(ByteBuffer src, int w, int h): int`
///
/// 原生路径（未加固）。仅用于对拍与基准对照，不参与加固目标。
#[no_mangle]
pub extern "system" fn Java_com_interview_vmp_nativebridge_VmpNative_dominantColorNative(
    env: JNIEnv,
    _class: JClass,
    src: JByteBuffer,
    w: jint,
    h: jint,
) -> jint {
    guard(|| {
        // 校验全部下沉到 `exec::native`，与 VM 路径**共用同一套闸门**。
        // 这里不再本地判 `w<=0||h<=0`：两处判会漂，而漂了之后对拍就有盲区。
        let bytes = match unsafe { direct_bytes(&env, &src) } {
            Some(b) => b,
            None => return ERR_BAD_ARGUMENT,
        };
        match native::dominant(bytes, w as u32, h as u32) {
            Ok(rgb) => rgb as jint,
            Err(code) => code,
        }
    })
}

/// `downscaleVm(ByteBuffer src, int sw, int sh, ByteBuffer dst, int dw, int dh): int`
///
/// VMP 路径。成功 0，失败负错误码。输入输出必须是**不同**的 direct buffer（无别名契约）。
#[no_mangle]
pub extern "system" fn Java_com_interview_vmp_nativebridge_VmpNative_downscaleVm(
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
        let s = match unsafe { direct_bytes(&env, &src) } {
            Some(b) => b,
            None => return ERR_BAD_ARGUMENT,
        };
        let d = match unsafe { direct_bytes_mut(&env, &dst) } {
            Some(b) => b,
            None => return ERR_BAD_ARGUMENT,
        };
        EXEC.with(|e| {
            match e
                .borrow_mut()
                .downscale(s, sw as u32, sh as u32, d, dw as u32, dh as u32)
            {
                Ok(()) => 0,
                Err(code) => code,
            }
        })
    })
}

/// `downscaleNative(ByteBuffer src, int sw, int sh, ByteBuffer dst, int dw, int dh): int`
#[no_mangle]
pub extern "system" fn Java_com_interview_vmp_nativebridge_VmpNative_downscaleNative(
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
        let s = match unsafe { direct_bytes(&env, &src) } {
            Some(b) => b,
            None => return ERR_BAD_ARGUMENT,
        };
        let d = match unsafe { direct_bytes_mut(&env, &dst) } {
            Some(b) => b,
            None => return ERR_BAD_ARGUMENT,
        };
        match native::downscale(s, sw as u32, sh as u32, d, dw as u32, dh as u32) {
            Ok(()) => 0,
            Err(code) => code,
        }
    })
}

/// `blurVm(ByteBuffer src, int w, int h, ByteBuffer dst, int radius): int`
#[no_mangle]
pub extern "system" fn Java_com_interview_vmp_nativebridge_VmpNative_blurVm(
    env: JNIEnv,
    _class: JClass,
    src: JByteBuffer,
    w: jint,
    h: jint,
    dst: JByteBuffer,
    radius: jint,
) -> jint {
    guard(|| {
        let s = match unsafe { direct_bytes(&env, &src) } {
            Some(b) => b,
            None => return ERR_BAD_ARGUMENT,
        };
        let d = match unsafe { direct_bytes_mut(&env, &dst) } {
            Some(b) => b,
            None => return ERR_BAD_ARGUMENT,
        };
        EXEC.with(|e| {
            match e.borrow_mut().blur(s, w as u32, h as u32, d, radius as u32) {
                Ok(()) => 0,
                Err(code) => code,
            }
        })
    })
}

/// `blurNative(ByteBuffer src, int w, int h, ByteBuffer dst, int radius): int`
#[no_mangle]
pub extern "system" fn Java_com_interview_vmp_nativebridge_VmpNative_blurNative(
    env: JNIEnv,
    _class: JClass,
    src: JByteBuffer,
    w: jint,
    h: jint,
    dst: JByteBuffer,
    radius: jint,
) -> jint {
    guard(|| {
        // `radius < 0` 在这里挡住：`radius as u32` 会把 -1 变成 4294967295，
        // 那是**静默**把非法输入变成巨量工作，比报错危险得多。
        if radius < 0 {
            return ERR_BAD_ARGUMENT;
        }
        let s = match unsafe { direct_bytes(&env, &src) } {
            Some(b) => b,
            None => return ERR_BAD_ARGUMENT,
        };
        let d = match unsafe { direct_bytes_mut(&env, &dst) } {
            Some(b) => b,
            None => return ERR_BAD_ARGUMENT,
        };
        match native::blur(s, w as u32, h as u32, d, radius as u32) {
            Ok(()) => 0,
            Err(code) => code,
        }
    })
}
