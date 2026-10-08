package com.interview.memtrace

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Time: 2026/10/8
 * Author: wgt
 * Description: Native 内存归因的 **JNI 声明层 + 加载/降级**（不含业务逻辑）
 *
 * ══════════════════════════════════════════════════════════════════════
 * 为什么单独一个 ASCII 包，而不是放进 `com.interview.内存`
 * ══════════════════════════════════════════════════════════════════════
 *
 * **JNI 的导出符号命名规则不允许非 ASCII 包名**（与
 * `com.interview.anrsignal.AnrSigquitHook` 同一约束，那里有完整推导）：
 *
 * ```
 *   Java_com_interview_内存_MemTraceNative_installNative
 *                     ^^^^
 *   C++ 标识符只允许 [A-Za-z0-9_] ⇒ 没有合法符号名可写。
 * ```
 *
 * 所以 JNI 边界落在 ASCII 包 `com.interview.memtrace`，
 * 业务层（`com.interview.内存.MemoryAllocTracker`）在中文包，
 * 中间由这一个类隔开。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 降级的语义（比 anrsignal 更要紧一层）
 * ══════════════════════════════════════════════════════════════════════
 *
 * memtrace hook 的是**全局内存分配热路径**。它不可用时（非 arm64、
 * prefab 没通、bytehook 初始化失败…）必须做到：
 *
 *   1. **不抛异常**（返回 false / null，调用方看到"不可用"而不是崩溃）；
 *   2. **不拖累指标层**：`MemoryMetrics.capture()` 每次都会读分配器数字，
 *      拿到 null 就退化为"不可用" —— 内存监控的其余部分（JVM 水位、trim、
 *      位图、hprof）完全不受影响。
 *
 * 这就是为什么本类的读数 API 全部返回可空/带 available 标志，
 * 而不是"保证有值"。
 */
internal object MemTraceNative {

    private const val TAG = "MemTrace"

    private const val LIB_NAME = "memtrace"

    @Volatile
    private var libraryLoaded = false

    /** 加载失败原因（成功时为 null）；页面要把它显示出来，否则"为什么不可用"只能靠猜 */
    var loadError: String? = null
        private set

    /** 上一次 install 失败的原因（成功时为 null） */
    var installError: String? = null
        private set

    private val loadAttempted = AtomicBoolean(false)

    private fun loadLibrary(): Boolean {
        if (libraryLoaded) return true
        // 只尝试一次：dlopen 失败往往是 ABI/包体问题，重试没有意义，
        // 但**每次都试**会拖慢热路径（本方法被每 2s 一次的采样路径调用）。
        if (!loadAttempted.compareAndSet(false, true)) return false
        return try {
            System.loadLibrary(LIB_NAME)
            libraryLoaded = true
            true
        } catch (t: Throwable) {
            // 用 Throwable：prefab/ABI 不匹配时抛的是 UnsatisfiedLinkError（Error 子类），
            // 漏掉它会让演示页在按钮里直接崩（本仓库既有约定，见 AnrSigquitHook）。
            loadError = "${t.javaClass.simpleName}: ${t.message} (${deviceAbiSummary()})"
            Log.w(TAG, "loadLibrary($LIB_NAME) 失败：$loadError")
            false
        }
    }

    /** 设备 ABI 摘要 —— 降级信息里必须带上，否则"为什么不可用"要靠猜。 */
    private fun deviceAbiSummary(): String = runCatching {
        val abis = android.os.Build.SUPPORTED_ABIS.joinToString(",")
        "SUPPORTED_ABIS=$abis SDK=${android.os.Build.VERSION.SDK_INT}"
    }.getOrDefault("ABI 读取失败")

    /**
     * 安装 hook。**幂等**。
     *
     * ⚠️ 刻意**不**要求在主线程调用（对比 `AnrSigquitHook.install` 有主线程约束）：
     *    malloc hook 是对所有线程生效的 PLT/GOT 改写，与信号掩码无关。
     */
    fun install(): Boolean {
        if (!loadLibrary()) return false
        return try {
            val ok = installNative()
            if (!ok) installError = "bytehook 安装返回 false（见 logcat TAG=MemTrace）"
            ok
        } catch (t: Throwable) {
            installError = "${t.javaClass.simpleName}: ${t.message}"
            Log.e(TAG, "安装失败", t)
            false
        }
    }

    fun isInstalled(): Boolean =
        libraryLoaded && runCatching { isInstalledNative() }.getOrDefault(false)

    /**
     * 暂停/恢复记账。出报告时暂停，避免报告自己的文本组装污染数字。
     * 库未加载时是**无操作**，不抛。
     */
    fun pause(paused: Boolean) {
        if (!libraryLoaded) return
        runCatching { pauseNative(paused) }
    }

    fun reset() {
        if (!libraryLoaded) return
        runCatching { resetNative() }
    }

    /**
     * 出归因报告（**可能耗时数十毫秒**：遍历站点表 + dl_iterate_phdr + 组装文本）。
     * ⚠️ 调用方必须在后台线程调用，绝不能挂在每帧/每次采样上。
     */
    fun report(maxSites: Int = 20): String? {
        if (!libraryLoaded) return null
        return runCatching { reportNative(maxSites) }.getOrElse {
            Log.w(TAG, "report 失败", it)
            null
        }
    }

    /** 安装状态读数（便宜，可随时调）。库未加载时返回 null。 */
    fun status(): String? {
        if (!libraryLoaded) return null
        return runCatching { statusNative() }.getOrNull()
    }

    /**
     * **演示用**：分配 `count` 块 `size` 字节的 native 内存，返回指针数组。
     *
     * 为什么不让 Java 侧用 `ByteBuffer.allocateDirect()`：那样调用栈落在
     * libart/libjavacore 里，归因报告看到的是 ART 内部函数 —— 能证明"工具有效"，
     * 但**证明不了它能定位到我们自己的代码**。本函数的栈帧在 libmemtrace.so 里，
     * 符号化后就是**我们自己的函数名**（`libmemtrace.so+0x...`）。
     *
     * @return 指针数组；环境不支持或分配失败时返回空数组
     */
    fun allocBlocks(count: Int, size: Int): LongArray =
        runCatching { if (libraryLoaded) allocBlocksNative(count, size) ?: LongArray(0) else LongArray(0) }
            .getOrDefault(LongArray(0))

    fun freeBlocks(blocks: LongArray) {
        if (!libraryLoaded || blocks.isEmpty()) return
        runCatching { freeBlocksNative(blocks) }
    }

    /**
     * 分配器视角的轻量读数（**每次采样都会调，必须便宜**）。
     *
     * 格式见 `memtrace.cpp::allocatorStatsNative`：
     * `used=<B> free=<B> mmap=<B> arena=<B> ordblks=<N>|sc:<size>:<count>,...`
     */
    fun allocatorStats(): String? {
        if (!libraryLoaded) return null
        return runCatching { allocatorStatsNative() }.getOrNull()
    }

    // ─────────────────────────────────────────────
    // JNI 导出（实现见 app/src/main/cpp/memtrace.cpp）
    // ⚠️ 方法名/签名必须与 C++ 侧符号名逐字对应，改一处必须同步改另一处。
    // ─────────────────────────────────────────────

    private external fun installNative(): Boolean

    @Suppress("unused") // 保留声明：native 侧刻意不支持运行时卸载（见 memtrace.cpp 注释）
    private external fun uninstallNative(): Boolean

    private external fun isInstalledNative(): Boolean

    private external fun pauseNative(paused: Boolean)

    private external fun resetNative()

    private external fun reportNative(maxSites: Int): String

    private external fun statusNative(): String

    private external fun allocatorStatsNative(): String

    private external fun allocBlocksNative(count: Int, size: Int): LongArray?

    private external fun freeBlocksNative(blocks: LongArray)
}
