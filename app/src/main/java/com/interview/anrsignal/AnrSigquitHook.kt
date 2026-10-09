package com.interview.anrsignal

import android.util.Log
import com.interview.稳定性监控.AnrSigquitChannel

/**
 * Time: 2026/10/8
 * Author: wgt
 * Description: 通道 4 —— SIGQUIT 拦截的 **JNI 声明层**（只做加载与降级，不含业务）
 *
 * ══════════════════════════════════════════════════════════════════════
 * 为什么单独一个包，而不是放进 `com.interview.稳定性监控`
 * ══════════════════════════════════════════════════════════════════════
 *
 * **因为 JNI 的导出符号命名规则不允许非 ASCII 包名。**
 *
 * ```
 *   Java_com_interview_稳定性监控_AnrSigquitHook_installNative
 *                    ^^^^^^^^^^
 *   C++ 标识符只允许 [A-Za-z0-9_]。JNI 规范里包名里的 '_' 还要转义成 "_1"，
 *   中文包名则**根本没有对应的合法符号名**。
 * ```
 *
 * 这是实测/规范级别的硬约束，不是风格问题 —— 所以本模块把 JNI 边界放在
 * ASCII 包 `com.interview.anrsignal`，业务层（[AnrSigquitChannel]）仍在
 * `com.interview.稳定性监控`，中间由这一个类隔开。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 优雅降级：`loadLibrary` 失败不是异常，是一种**正常状态**
 * ══════════════════════════════════════════════════════════════════════
 *
 * 本仓库的既有惯例（见 `NativeThreadHook.loadLibrary` / `VmpNative`）：
 * native 库不可用时不抛，返回 false，由调用方决定降级。
 * 这里同样 —— 本机 `abiFilters = arm64-v8a`，32 位设备/非 arm 模拟器上
 * 这个 .so 压根不存在，通道 4 直接不可用，但绝不能影响通道 1/2/3。
 */
object AnrSigquitHook {

    private const val TAG = "AnrSigquitHook"

    private const val LIB_NAME = "anrsignal"

    @Volatile
    private var libraryLoaded = false

    /**
     * 安装 SIGQUIT 拦截。**必须在主线程调用**。
     *
     * ⚠️ 主线程这一条不是洁癖，是已知现象的直接后果：
     *    `pthread_sigmask` 改的是**调用线程**的掩码，而内核只会把信号投递给
     *    「不阻塞它」的线程。若在子线程安装，被解除屏蔽的只有那个子线程，
     *    主线程（ANR 时最常被投递的目标）依然屏蔽着 SIGQUIT。
     *
     *    对照证据：xCrash 有过「在子线程初始化，ANR 功能不生效；挪到主线程就好」的
     *    已知问题记录。本项目把这条固化成 API 约束 —— 调用方不守规矩时给出警告。
     *
     * @return true = 安装成功；false = 环境不支持或安装失败（已降级，不抛）
     */
    fun install(): Boolean {
        if (!loadLibrary()) {
            Log.w(TAG, "native 库不可用，通道 4 跳过（其余通道不受影响）")
            return false
        }
        if (!isMainThread()) {
            // ⚠️ 仍然继续执行，但必须把风险说清楚：这次安装可能形同虚设
            Log.w(
                TAG,
                "⚠️ 非主线程调用 install()：信号掩码是 per-thread 的，" +
                        "此刻只有当前线程解除了 SIGQUIT 屏蔽 —— 主线程仍屏蔽，" +
                        "拦截大概率不生效。请在主线程调用。"
            )
        }
        return try {
            installNative()
            Log.i(TAG, "SIGQUIT 拦截已安装")
            true
        } catch (e: Throwable) {
            // 用 Throwable 而不是 Exception：JNI 侧的 UnsatisfiedLinkError/NoSuchMethodError
            // 都是 Error 子类，漏掉它们会让演示页直接崩在按钮里。
            Log.e(TAG, "安装失败", e)
            false
        }
    }

    fun uninstall() {
        if (!libraryLoaded) return
        runCatching { uninstallNative() }
    }

    /** 诊断读数（实验页用）；native 不可用时返回 null */
    fun stats(): Stats? = runCatching {
        if (!libraryLoaded) return null
        Stats.parse(statsNative())
    }.getOrNull()

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

    private fun isMainThread(): Boolean =
        runCatching { android.os.Looper.myLooper() === android.os.Looper.getMainLooper() }
            .getOrDefault(false)

    // ─────────────────────────────────────────────
    // JNI 导出（native 侧实现见 app/src/main/cpp/anr_signal_hook.cpp）
    // ─────────────────────────────────────────────

    private external fun installNative()

    private external fun uninstallNative()

    private external fun statsNative(): String

    /**
     * ⚠️ **native 回调入口**，执行在 native 的 dumper 线程上。
     *
     * 这个方法会被 JNI 直接调用，签名必须与 `anr_signal_hook.cpp` 里
     * `GetStaticMethodID(..., "onSigquitFromNative", "(Ljava/lang/String;)V")` 一致。
     *
     * 本方法**只做一件事**：把载荷原样转给业务层。
     *   · 不能在这里做 IO / 抓栈（native 侧才刚收完信号，AMS 正在等我们写 trace）；
     *   · 异常必须自己吞掉 —— 抛出去会顺着 `CallStaticVoidMethod` 传回 native，
     *     虽然那边清了异常，但事件就丢了。
     *
     * @param payload 定长格式 `"senderPid:uid:code:selfPid"`（见 native 侧 `snprintf`）
     */
    @JvmStatic
    fun onSigquitFromNative(payload: String?) {
        runCatching {
            if (payload == null) return
            AnrSigquitChannel.onSigquit(payload)
        }.onFailure { Log.e(TAG, "处理 SIGQUIT 载荷失败：$payload", it) }
    }

    /**
     * 诊断读数。
     *
     * @param hit           handler 命中次数（**收到过几次 SIGQUIT**）
     * @param rethrow       转发给 SignalCatcher 成功的次数
     * @param rethrowFail   转发失败次数（重点看：非 0 说明系统取证会被拖慢）
     * @param catcherTid    安装时缓存的 SignalCatcher tid（-1 = 没找到，转发必然失败）
     * @param lastCatcherTid 最近一次收到信号时的 catcher tid
     * @param active        native 侧当前是否处于安装态
     */
    data class Stats(
        val hit: Long,
        val rethrow: Long,
        val rethrowFail: Long,
        val catcherTid: Int,
        val lastCatcherTid: Int,
        val active: Boolean,
    ) {
        fun describe(): String = buildString {
            appendLine("handler 命中：$hit 次")
            appendLine("转发 SignalCatcher：成功 $rethrow / 失败 $rethrowFail")
            appendLine("SignalCatcher tid：安装时=$catcherTid，最近一次收到信号时=$lastCatcherTid")
            appendLine("生效状态：${if (active) "已安装" else "未安装"}")
            if (catcherTid <= 0) {
                appendLine("⚠️ **未找到 SignalCatcher 线程** —— 本通道只剩「拦截」，没有「转发」。")
                appendLine("   后果：AMS 收不到本进程的堆栈，会阻塞到 20s 超时才继续（系统取证被拖慢）。")
                appendLine("   此时应当用 uninstall() 立即卸载，让系统回到原状。")
            }
        }

        companion object {
            /**
             * 解析 native 的 `"hit:rethrow:rethrowFail:catcherTid:lastCatcherTid:active"`。
             *
             * 拆成纯函数是为了**可被 JVM 单测覆盖**（`AnrSigquitHook` 本身在宿主 JVM
             * 上不可用：Looper/System.loadLibrary 都会是默认值或抛错）。
             * 拿不到 6 段就返回 null —— 宁可显示「不可用」，也不要显示一个瞎猜的数字。
             */
            fun parse(raw: String?): Stats? {
                val parts = raw?.trim()?.split(':') ?: return null
                if (parts.size != 6) return null
                val nums = parts.map { it.trim().toLongOrNull() ?: return null }
                return Stats(
                    hit = nums[0],
                    rethrow = nums[1],
                    rethrowFail = nums[2],
                    catcherTid = nums[3].toInt(),
                    lastCatcherTid = nums[4].toInt(),
                    active = nums[5] != 0L,
                )
            }
        }
    }
}
