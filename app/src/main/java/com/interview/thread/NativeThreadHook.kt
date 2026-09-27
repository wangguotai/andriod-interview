package com.interview.thread

import android.util.Log

/**
 * Time: 2026/9/27
 * Author: wgt
 * Description: 第 4 步「兜底」—— Native Hook pthread_create 的 Java 侧入口
 *
 * 这是唯一能捕获**所有**线程创建（含三方 SDK）的手段，因为
 * `Thread.start() → nativeCreate → pthread_create` 是绕不过去的路径。
 *
 * ⚠️ 能力边界（面试常被追问）：
 * Hook 点只能知道「有新线程被创建」，但**拿不到创建它的 Java 堆栈**——
 * 因为此刻新线程刚创建、尚未 attach 到 JVM。
 * 要拿到堆栈需要「Native 反查 Java 栈」，实现复杂且不稳定，
 * 实践中通常配合：
 *   1. ASM 插桩补线程名（编译期，最可靠）
 *   2. Java 层 Thread.getAllStackTraces() 采样（运行期，拿得到堆栈）
 * 本 Demo 只做「计数 + 打点」，展示 Hook 能力本身。
 *
 * ─── 方案选型 ───
 * 本项目采用 GOT Hook（兼容性优，可上线）。
 * 若作为**线下排查工具**，可换成 Inline Hook 以获得更广的覆盖范围。
 */
object NativeThreadHook {

    private const val TAG = "ThreadHook"
    private const val LIB_NAME = "threadhook"

    /** 是否已加载 native 库（设备/ABI 不支持时为 false，退化为不可用） */
    private var libraryLoaded = false

    /** Native 层观测到的线程创建次数 */
    val nativeCreatedCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 安装 Hook。
     * @return true 表示安装成功；false 表示当前环境不支持（不抛异常，优雅降级）
     */
    fun install(): Boolean {
        if (!loadLibrary()) {
            Log.w(TAG, "native 库不可用，Hook 跳过（功能优雅降级）")
            return false
        }
        return try {
            installNative()
            Log.i(TAG, "pthread_create Hook 安装成功")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Hook 安装失败", e)
            false
        }
    }

    fun uninstall() {
        if (!libraryLoaded) return
        runCatching { uninstallNative() }
    }

    private fun loadLibrary(): Boolean {
        if (libraryLoaded) return true
        return try {
            System.loadLibrary(LIB_NAME)
            libraryLoaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "loadLibrary($LIB_NAME) 失败：${e.message}")
            false
        }
    }

    /** Native 层回调入口。必须为 public static，供 JNI 调用。 */
    @JvmStatic
    fun onThreadCreatedFromNative(threadName: String) {
        val count = nativeCreatedCount.incrementAndGet()
        // 只做轻量记录：Hook 点绝不能做重活，否则会拖慢线程创建本身
        if (count <= 30) {
            Log.d(TAG, "Native 捕获到线程创建 #$count name=$threadName")
        } else if (count == 31) {
            Log.d(TAG, "Native 捕获超过 30 次，后续静默（避免日志刷屏影响性能）")
        }
    }

    private external fun installNative()
    private external fun uninstallNative()
}
