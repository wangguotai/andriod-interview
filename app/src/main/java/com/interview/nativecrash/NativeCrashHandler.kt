package com.interview.nativecrash

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.myapplication.BuildConfig
import java.io.File

/**
 * Time: 2026/10/16
 * Author: wgt
 * Description: Native 崩溃取证的 **JNI 声明与降级层**（安装/卸载/主动崩溃/读数）
 *
 * ══════════════════════════════════════════════════════════════════════
 * 一、为什么这个类必须放在 ASCII 包（而不是 `com.interview.稳定性监控`）
 * ══════════════════════════════════════════════════════════════════════
 *
 * 这是本仓库的既有结论（`AnrSigquitHook.kt:12-26` 的类注释写得很清楚）：
 *
 * > JNI 的导出符号命名规则不允许非 ASCII 包名。
 * > `Java_com_interview_稳定性监控_AnrSigquitHook_installNative` 的 `稳定性监控`
 * > 段在 C++ 标识符里非法（只允许 `[A-Za-z0-9_]`，且 `_` 还要转义成 `_1`）。
 *
 * 本模块**第一版不信这个邪**，试了一条"看起来更省事"的路：在 C++ 里
 * `extern "C"` 直接声明带 UTF-8 中文包名的符号（clang 确实宽容地编译过了，
 * `llvm-nm` 也能看到导出符号），赌 ART 查找符号时用的是同样的原始字节。
 *
 * **实测把这条路证伪了**（Android 12 真机 + API 36 模拟器一致）：
 *
 * ```
 * UnsatisfiedLinkError: No implementation found for
 *   void com.interview.稳定性监控.NativeCrashHandler.installNative(...)
 * (tried Java_com_interview__07a33_05b9a_06027_076d1_063a7_NativeCrashHandler_installNative ...)
 *                                          ^^^^^^^^^^^^^^^^^^^^^^^^^^^^
 * ```
 *
 * ART 查找符号时把类名里的**每个非 ASCII 字节按 JNI 规范转义成 `_0XX`**
 * （`_07a33` 等），而 clang 导出的符号里是**原始 UTF-8 字节** —— 两者永远对不上。
 * 也就是说：**能编译 ≠ 能链接**，这条约束不是编译器的宽容性问题，是 JNI 的
 * 命名规范本身。C++ 侧"看起来合法"的符号名只是自欺。
 *
 * ⇒ 结论与 `AnrSigquitHook` 一致：**JNI 边界放 ASCII 包，业务放中文包**。
 *   分工：
 *     · 本类（`com.interview.nativecrash`）—— 只做 loadLibrary/安装/卸载/主动崩溃/读数
 *     · `com.interview.稳定性监控.NativeCrashCollector` —— 回捞、解析、接上报出口
 *   中间多一跳转发是**刻意的代价**：换来的是"改中文业务包名不需要动 native 侧"。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 二、优雅降级：`loadLibrary` 失败是一种**正常状态**
 * ══════════════════════════════════════════════════════════════════════
 *
 * 与 `AnrSigquitHook` / `NativeThreadHook` / `VmpNative` 同一约定：native 库不可用时
 * **不抛**，返回 false，由调用方决定降级。本机 `abiFilters = arm64-v8a`，
 * 32 位设备上这个 .so 压根不存在 —— 那时 native 取证直接不可用，
 * 但绝不能影响 Java 层的崩溃监控（那是本模块的主力兜底）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 三、诚实边界：`install()` 必须是**进程内最早**的动作之一
 * ══════════════════════════════════════════════════════════════════════
 *
 * 装了 handler 之后才发生的崩溃才抓得到。`MyApplication.attachBaseContext` 里
 * `StabilityMonitor.installEarly` 是现有的最早时机，native 取证挂在那里。
 * 但 `Application` 的**静态初始化**（类加载、`<clinit>`）发生在 attachBaseContext
 * **之前** —— 那段时间的 native 崩溃（例如某个 SDK 的 static 初始化里崩了）
 * 抓不到，只能靠 `ApplicationExitInfo` 回捞（那正是 `AnrMonitor.ExitInfoCollector`
 * 存在的意义，两条链路是互补而不是替代）。
 */
object NativeCrashHandler {

    private const val TAG = "NativeCrash"

    private const val LIB_NAME = "nativecrash"

    /** 报告目录名（`filesDir/native-crash/`）。与 CrashJournal 的 `stability/` 分开，
     *  因为两者的生命周期不同：遗嘱是"报完即删"，报告是"回捞后按需清理" */
    const val REPORT_DIR_NAME = "native-crash"

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var installed = false

    /**
     * 安装 native 崩溃取证。
     *
     * @return true = 安装成功；false = 环境不支持或安装失败（已降级，不抛）
     */
    fun install(ctx: Context): Boolean {
        if (installed) return true
        if (!loadLibrary()) {
            Log.w(TAG, "native 库不可用（可能是非 arm64 设备），native 取证跳过；Java 层监控不受影响")
            return false
        }
        return try {
            val dir = reportDir(ctx)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "报告目录创建失败：${dir.absolutePath}")
                return false
            }
            // ⚠️ 版本信息必须传进去：它是**符号化的前提**。
            //    服务端拿到一堆 libxxx.so+0x1234，必须知道该配哪一份未 strip 的 .so。
            //    少了它，符号表对不上会给出"看起来合理但错误"的函数名 ——
            //    比解析不出来更危险（这也是 native-mem-symbolize.sh 头部那条警告的道理）。
            installNative(
                dir.absolutePath,
                BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE,
                processName(ctx),
                Build.VERSION.RELEASE ?: "?",
                Build.VERSION.SDK_INT,
            )
            installed = true
            Log.i(TAG, "native 崩溃取证已安装：${dir.absolutePath}")
            true
        } catch (e: Throwable) {
            // 用 Throwable 而不是 Exception：JNI 侧的 UnsatisfiedLinkError /
            // NoSuchMethodError 都是 Error 子类，漏掉它们会让实验页直接崩在按钮里
            // （`AnrSigquitHook` 已经踩过这个，见其注释）
            Log.e(TAG, "安装失败（native 取证不可用，其余链路不受影响）", e)
            false
        }
    }

    /**
     * 卸载（把系统 handler 原样还原）。
     *
     * 为什么需要它：实验要求"对照"——「装了捕获」与「没装捕获」的行为差异必须能演示
     * （与 `CrashMonitor.uninstallForExperiment` 同一动机：一次实验不该永久改变进程行为）。
     */
    fun uninstall() {
        if (!libraryLoaded) return
        runCatching { uninstallNative() }
        installed = false
    }

    fun isInstalled(): Boolean = installed && runCatching { isInstalledNative() }.getOrDefault(false)

    /**
     * 设置 abort message。**主动**崩溃前调用，让原因同时出现在：
     *   · 系统 tombstone（经 `android_set_abort_message`）
     *   · 我们自己的报告（native 侧自缓存，因为 `android_get_abort_message` 不存在）
     */
    fun setAbortMessage(msg: String) {
        if (!libraryLoaded) return
        runCatching { setAbortMessageNative(msg) }
    }

    // ─────────────────────────────────────────────
    // 实验入口：三种崩溃形态，用于 si_code 归因对照
    // ─────────────────────────────────────────────

    /** 主动 SIGABRT（`si_code = SI_TKILL`，来源可辨 —— 见 native 侧注释） */
    fun crashByAbort(msg: String): Boolean {
        if (!loadLibrary()) return false
        crashNowNative(msg)  // 不返回
        return true
    }

    /** 空指针 → SIGSEGV / SEGV_MAPERR，fault_addr = 0x0 */
    fun crashByNullDeref(): Boolean {
        if (!loadLibrary()) return false
        nullDerefNative()  // 不返回
        return true
    }

    /** 栈溢出 → SIGSEGV，**fault_addr 贴着栈底而不是 0** */
    fun crashByStackOverflow(): Boolean {
        if (!loadLibrary()) return false
        stackOverflowNative()  // 不返回
        return true
    }

    /** 诊断读数；native 不可用时返回 null */
    fun stats(): Stats? = runCatching {
        if (!libraryLoaded) return null
        Stats.parse(statsNative())
    }.getOrNull()

    // ─────────────────────────────────────────────
    // 目录与进程名
    // ─────────────────────────────────────────────

    fun reportDir(ctx: Context): File =
        File(ctx.filesDir, REPORT_DIR_NAME)

    /**
     * 当前进程名。
     *
     * ⚠️ 不用 `Application.getProcessName()`（API 28+，本仓库 minSdk 24 用不了），
     *    也不用 `ActivityThread.currentProcessName()`（@hide）。
     *    退回 `/proc/self/cmdline` —— 这是唯一在 API 24 上可靠且公开的方式。
     *    多进程 App（`:remote`/`:push`）里这个字段是区分"崩的是哪个进程"的唯一依据。
     */
    fun processName(ctx: Context): String = runCatching {
        File("/proc/self/cmdline").readText().trimEnd('\u0000', '\n', ' ')
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: ctx.packageName

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

    // ─────────────────────────────────────────────
    // JNI 导出（native 实现见 app/src/main/cpp/native_crash_handler.cpp）
    // ─────────────────────────────────────────────

    private external fun installNative(
        dir: String,
        versionName: String,
        versionCode: Int,
        processName: String,
        release: String,
        sdkInt: Int,
    )

    private external fun uninstallNative()

    private external fun isInstalledNative(): Boolean

    private external fun setAbortMessageNative(msg: String)

    private external fun crashNowNative(msg: String)

    private external fun nullDerefNative()

    private external fun stackOverflowNative()

    private external fun statsNative(): String

    /**
     * 诊断读数解析。
     *
     * 抽成纯函数是为了**可被 JVM 单测覆盖** —— 与 `AnrSigquitHook.Stats.parse`
     * 完全同一约定：本类在宿主 JVM 上不可用（`System.loadLibrary` 必抛），
     * 所以解析逻辑必须与它分离。
     * 拿不到 3 段就返回 null —— 宁可显示"不可用"，也不要显示一个瞎猜的数字。
     */
    data class Stats(
        /** native 侧是否处于安装态 */
        val installed: Boolean,
        /** 累计进入 handler 的次数（正常应为 0 或 1；>1 说明发生过多次崩溃/重入） */
        val crashCount: Int,
        /** 实际接管成功的崩溃信号个数（应为 7，见 native 侧的 kSignals） */
        val savedSignals: Int,
    ) {
        fun describe(): String = buildString {
            appendLine("安装状态：${if (installed) "已安装（系统 handler 已保存，崩溃后会链式交回）" else "未安装"}")
            appendLine("接管信号数：$savedSignals（预期 7：SEGV/ABRT/BUS/ILL/FPE/TRAP/SYS）")
            if (savedSignals < 7 && installed) {
                appendLine("  ⚠️ 少于 7 个说明有信号的 sigaction 失败了，那些信号的崩溃抓不到")
            }
            appendLine("handler 进入次数：$crashCount" + if (crashCount > 1) "（>1 说明发生过重入或多线程同时崩）" else "")
        }

        companion object {
            fun parse(raw: String?): Stats? {
                val parts = raw?.trim()?.split(':') ?: return null
                if (parts.size != 3) return null
                return Stats(
                    installed = parts[0].trim() == "1",
                    crashCount = parts[1].trim().toIntOrNull() ?: return null,
                    savedSignals = parts[2].trim().toIntOrNull() ?: return null,
                )
            }
        }
    }
}
