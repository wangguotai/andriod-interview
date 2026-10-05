package com.interview.thread

import android.os.Build

/**
 * Time: 2026/10/2
 * Author: wgt
 * Description: 全局未捕获异常兜底安装器
 *
 * ─── 为什么必须装这个 ───
 *
 * Android 上 `ThreadPoolExecutor.execute()` 提交的任务抛异常时，
 * 异常会走到该**worker 线程**的 `UncaughtExceptionHandler`。
 * Android 的默认 handler 是 `RuntimeInit$KillApplicationHandler`，
 * 它的行为是：打日志 → 调 `Process.killProcess` **杀掉整个 App**。
 *
 * 也就是说：一个后台任务的小 bug，会把用户正在用的 App 直接干掉。
 * 而且因为不是主线程崩溃，很容易被误判成「随机崩溃」「机型问题」。
 *
 * ⚠️ 但装了这个 handler 之后要非常克制：
 *   · 不能对**主线程**异常也一律吞掉（那是必须崩的，吞了会变成假死/错乱）
 *   · 上报要限流（见 ThreadErrorReporter）
 *   · 系统进程 / 非本 App 线程不要碰
 */
object CrashGuard {

    private const val TAG = "CrashGuard"

    private var installed = false
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    /**
     * 安装全局兜底。
     *
     * @param swallowBackground true = 后台线程异常上报后吞掉（App 存活，推荐）
     *                          false = 上报后交回原 handler（保持崩溃可见）
     */
    fun install(swallowBackground: Boolean = true) {
        if (installed) return
        installed = true

        previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            val isMain = thread === android.os.Looper.getMainLooper().thread
            val lane = classify(thread)

            ThreadErrorReporter.onTaskError(lane, "uncaught:${thread.name}", e)
            android.util.Log.e(
                TAG,
                "未捕获异常：thread=${thread.name} isMain=$isMain lane=$lane" +
                        " sdk=${Build.VERSION.SDK_INT}"
            )

            if (isMain || !swallowBackground) {
                // 主线程异常必须让它崩 —— 吞掉会留下状态不一致的 App，比崩溃更难查
                previousHandler?.uncaughtException(thread, e)
                    ?: android.os.Process.killProcess(android.os.Process.myPid())
            } else {
                // 后台线程：已上报，吞掉。该线程会结束，但 App 存活。
                // ⚠️ 注意：这会让「线程死亡」，线程池会补一个新 worker，属于可接受代价。
                android.util.Log.w(TAG, "后台线程异常已上报并吞掉，App 继续运行：${thread.name}")
            }
        }

        android.util.Log.i(TAG, "全局未捕获异常兜底已安装（swallowBackground=$swallowBackground）")
    }

    /** 按线程名归类，便于和泳道对应 */
    private fun classify(thread: Thread): String {
        val name = thread.name
        return when {
            name.startsWith("app-net") -> "net"
            name.startsWith("app-disk") -> "disk"
            name.startsWith("app-db") -> "db"
            name.startsWith("app-bg") -> "bg"
            name.startsWith("app-cpu") -> "cpu"
            name.startsWith("app-single") -> "single"
            name.startsWith("app-scheduled") -> "scheduled"
            name.startsWith("app-converged") -> "converged"
            name.startsWith("pool-") -> "unnamed-pool"
            name.startsWith("Thread-") -> "raw-thread"
            else -> "other"
        }
    }
}
