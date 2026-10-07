package com.interview.vmp.nativebridge

import android.util.Log
import java.nio.ByteBuffer

/**
 * Time: 2026/10/6
 * Author: wgt
 * Description: VMP 加固 Lab 的 JNI 入口 —— Kotlin 侧唯一直接与 native 打交道的类。
 *
 * ─── 这个类的定位 ───
 *
 * 它是整个 Lab 的收口点：上层（UI、基准测试）只认这里的 `xxxVm` / `xxxNative` 两套方法，
 * 不关心底层是字节码虚拟机还是机器码。库加载失败（例如在 x86 模拟器上跑，本 Lab 只编
 * arm64-v8a）时必须**安静降级**，而不是让 App 崩在 `UnsatisfiedLinkError` 上 ——
 * 与 `ImagePipelineBridge` 同一套纪律。
 *
 * ─── 每个算子为什么有两套方法 ───
 *
 * 这不是「为了兼容」，而是本 Lab 的**测量设计**：
 *   - `xxxVm`     → 走加密字节码的虚拟机；
 *   - `xxxNative` → 走未加固的机器码（对照物）。
 * 两者编在**同一个 `.so`** 里（见 `rust/vmp-android/src/exec.rs` 的 `native` 模块），
 * 所以它们的差异只来自「解释执行 vs 直接执行」，不含编译选项、LTO、库加载的差别。
 * 如果把它们放在两个 `.so` 里，测出来的数字就不再是「加固的代价」。
 *
 * ─── 符号名一旦改了要三处同步 ───
 *
 * 1. 本类的**包名与类名**（`com.interview.vmp.nativebridge.VmpNative`）；
 * 2. `rust/vmp-android/src/android_impl.rs` 里的
 *    `Java_com_interview_vmp_nativebridge_VmpNative_*`（**带** `nativebridge` 子包）；
 * 3. 方法签名（参数顺序与类型）。
 * 这三处任何一处不一致，症状都是运行期 `UnsatisfiedLinkError`，而不是编译错误 ——
 * 所以每次改这里都要顺手跑一次 `VmpNativeSelfTest`（androidTest）确认。
 */
object VmpNative {

    private const val TAG = "VmpLabNative"

    /**
     * 库名：`System.loadLibrary("vmp_android")` → 实际加载 `libvmp_android.so`。
     *
     * ⚠️ 名字与「加载路径」的这一层映射（去掉 `lib` 前缀、去掉 `.so` 后缀）没有编译期
     * 检查，是本仓库 native 接入的经典坑之一。产物名由 CMake 的 `LIB_NAME` 决定，
     * 见 `vmp-core/src/main/cpp/CMakeLists.txt`。
     */
    private const val LIB_NAME = "vmp_android"

    /** 加载失败的原因（供 UI/日志展示）。加载成功时为 null。 */
    var loadError: String? = null
        private set

    /** 库是否加载成功。 */
    val loaded: Boolean

    init {
        loaded = try {
            System.loadLibrary(LIB_NAME)
            true
        } catch (t: Throwable) {
            loadError = "${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "加载 lib$LIB_NAME.so 失败：$loadError（若在 x86 模拟器上跑属预期，本 Lab 只编 arm64-v8a）")
            false
        }
    }

    // ───────────────────────── 元信息 ─────────────────────────

    external fun abiVersion(): Int
    external fun versionString(): String?
    external fun hardeningStatus(): String?

    /** VM 内建自检：算术语义与 ISA 规范是否一致。**可在设备上随时重放**。 */
    external fun selftest(): Boolean

    /** 三个受保护程序的容器头是否完好（未解密即可验，能抓出字节被改坏）。 */
    external fun programsIntact(): Boolean

    /** 上次运行惰性解密的页数（未命中）—— 基准用它取证「不是启动时全解密」。 */
    external fun lastFetchPages(): Int

    /** 上次运行命中页缓存的次数。 */
    external fun lastCacheHits(): Int

    /**
     * 上次 VM 运行执行的指令条数。
     *
     * ⚠️ 必须声明成 `Long`：256×192 的 dominant 就是千万级指令，大图上亿。
     * 若 JNI 侧返回 `jint` 或这里声明成 `Int`，会**静默回绕**成一个「看起来合理」的
     * 小数字 —— 比直接崩掉难查得多。
     */
    external fun lastSteps(): Long

    // ───────────────────────── 三算子 × 两路径 ─────────────────────────

    external fun dominantColorVm(src: ByteBuffer, w: Int, h: Int): Int
    external fun dominantColorNative(src: ByteBuffer, w: Int, h: Int): Int

    external fun downscaleVm(
        src: ByteBuffer, sw: Int, sh: Int, dst: ByteBuffer, dw: Int, dh: Int,
    ): Int

    external fun downscaleNative(
        src: ByteBuffer, sw: Int, sh: Int, dst: ByteBuffer, dw: Int, dh: Int,
    ): Int

    external fun blurVm(src: ByteBuffer, w: Int, h: Int, dst: ByteBuffer, radius: Int): Int
    external fun blurNative(src: ByteBuffer, w: Int, h: Int, dst: ByteBuffer, radius: Int): Int
}
