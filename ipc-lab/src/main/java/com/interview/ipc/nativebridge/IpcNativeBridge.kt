package com.interview.ipc.nativebridge

import android.util.Log
import java.io.File

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: Rust 原生 IPC 原语的 Kotlin 收口点 —— 上层只认这里的 [available] 与 [runXxx]。
 *
 * ─── 与 imagepipeline 的同构设计 ───
 *
 * `ImagePipelineBridge`：native 可用走 Rust，否则回退 Java，且**两种实现必须结果一致**。
 * 这里没有「Java 回退」——因为 Linux 原生 syscall 在 Java 层无法等价模拟（`SCM_RIGHTS`、
 * `memfd_create`、`fork` 都没有 Java 门）。所以策略是：
 *   - native 不可用时，**明确报告不可用并说明原因**，绝不假装跑过；
 *   - 文末「结论」里如实标注哪些演示是原生、哪些是 framework 层实现。
 *
 * 这比「造一个假的回退」诚实得多：IPC 这个题目里，把「其实没跑到」说成「跑过了」，
 * 会让整份证据链失效。
 */
object IpcNativeBridge {

    private const val TAG = "IpcLab/Native"

    /** 与 Rust 侧 `ipclab::ABI_VERSION` 对齐；不匹配说明 APK 里是旧 .so。 */
    const val EXPECTED_ABI_VERSION = 1

    /**
     * native 库是否加载且 ABI 匹配。
     *
     * 只编 arm64-v8a（与 app 侧同一取舍，见 build.gradle.kts 的 ndk.abiFilters）。
     * 在非 arm64 设备上这里为 false，原生演示会在结论里标注为「不可用」。
     */
    val available: Boolean by lazy {
        if (!IpcNative.loaded) {
            Log.w(TAG, "native 库未加载：${IpcNative.loadError}")
            false
        } else {
            val abi = runCatching { IpcNative.abiVersion() }.getOrDefault(-1)
            if (abi != EXPECTED_ABI_VERSION) {
                Log.e(TAG, "native ABI 版本不匹配：so=$abi, kotlin=$EXPECTED_ABI_VERSION")
                false
            } else {
                true
            }
        }
    }

    /** 人类可读的运行时信息。 */
    fun describe(): String = buildString {
        append("native=").append(if (available) "Rust" else "unavailable")
        if (IpcNative.loaded) {
            append(" abi=").append(runCatching { IpcNative.abiVersion() }.getOrDefault(-1))
            IpcNative.versionString()?.let { append(" (").append(it).append(')') }
        } else {
            append(" reason=").append(IpcNative.loadError)
        }
    }

    // ── 演示类型常量（与 Rust `ipclab::demo::supported` 一一对应）──
    const val KIND_PIPE = "pipe"
    const val KIND_FIFO = "fifo"
    const val KIND_SHM = "shm"
    const val KIND_SIGNAL = "signal"
    const val KIND_FLOCK = "flock"

    /**
     * 跑一个原生演示。
     *
     * [arg] 的语义随 [kind] 变化：
     *   - [KIND_FIFO] / [KIND_FLOCK]：需要一个**本应用可写目录**下的路径（用 [workDir] 生成）；
     *   - [KIND_SHM]：待共享的文本；
     *   - 其余忽略。
     *
     * 返回三态：
     *   - `Success(log)`：跑完了，log 为原生原文；
     *   - `Failure(msg)`：native 不可用或调用异常，msg 说明原因；
     *   - 不会抛异常给上层 —— 演示失败不该让 UI 崩。
     */
    fun run(kind: String, arg: String = ""): Result<String> {
        if (!available) return Result.failure(IllegalStateException(describe()))
        return runCatching {
            IpcNative.runDemo(kind, arg)
                ?: throw IllegalStateException("native 返回 null（符号缺失？）")
        }
    }

    /** 原生演示支持的类型列表（native 不可用时为空）。 */
    fun supported(): List<String> {
        if (!available) return emptyList()
        return runCatching {
            IpcNative.supportedDemos()?.split(',')?.filter { it.isNotBlank() } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    /**
     * 为 FIFO / flock 生成一个位于**本应用私有目录**下的路径。
     *
     * 为什么必须用 filesDir 而不是 /data/local/tmp 或 /sdcard：
     *   - SELinux：shell 域的进程无权在 /data/local/tmp 建 FIFO（实测 EACCES），
     *     应用自己的域则对自己 filesDir 有完整权限；
     *   - Scoped Storage：/sdcard 需要存储权限且不保证支持 FIFO 语义。
     * 用 filesDir 既免权限，又能在经验上保证 mkfifo 成功。
     *
     * [name] 只传文件名（不含目录）。
     */
    fun workDir(filesDir: File, name: String): String {
        val dir = File(filesDir, "ipc-native")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, name).absolutePath
    }
}
