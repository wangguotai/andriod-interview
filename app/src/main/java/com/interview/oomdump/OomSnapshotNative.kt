package com.interview.oomdump

import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Time: 2026/10/17
 * Author: wgt
 * Description: JVM OOM 现场取证的 **JNI 声明层 + 加载/降级**（不含业务逻辑）
 *
 * ══════════════════════════════════════════════════════════════════════
 * 为什么单独一个 ASCII 包，而不是放进 `com.interview.内存`
 * ══════════════════════════════════════════════════════════════════════
 *
 * 与 [com.interview.memtrace.MemTraceNative] /
 * [com.interview.anrsignal.AnrSigquitHook] 同一条硬约束：
 *
 * ```
 *   Java_com_interview_内存_OomSnapshotNative_forkDumpNative
 *                     ^^^^  JNI 导出符号只能是 [A-Za-z0-9_]
 * ```
 *
 * 中文包名**没有合法的符号名可写**（ART 会把每个非 ASCII 字节转义成 `_0XX`，
 * 而 clang 导出的是原始 UTF-8 字节，两者永远对不上 —— 详见 `proguard-rules.pro`
 * 第 3 节留档的那次 UnsatisfiedLinkError）。
 * ⇒ JNI 边界放 ASCII 包 `com.interview.oomdump`，业务层
 * （`com.interview.内存.OomSnapshotMonitor`）在中文包，中间由这类隔一跳转发。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 降级的语义
 * ══════════════════════════════════════════════════════════════════════
 *
 * 这个库动的是 **ART 的运行时同步原语**（SuspendAll / GC 临界区），
 * 所以"不可用"必须是**一等公民状态**，而不是异常：
 *
 *   1. 加载失败 / 符号没命中 / ABI 不支持 ⇒ [isUsable] 为 false，
 *      调用方保留原有的取证手段（`Debug.dumpHprofData`），**不抛**；
 *   2. 符号是否命中**要能看见**（[probe]）—— 这是"为什么不可用"的唯一线索，
 *      绝不能只躺在 logcat 里。
 *
 * ⚠️ 本机（Redmi K40 / Android 12 / arm64）实测可用；其它 ROM/版本
 * 必须先看 [probe] 的输出再下结论。
 */
internal object OomSnapshotNative {

    private const val TAG = "OomSnapshot"

    private const val LIB_NAME = "oomsnapshot"

    @Volatile
    private var libraryLoaded = false

    /** 加载失败原因（成功时为 null）；页面要显示它，否则"为什么不可用"只能靠猜 */
    var loadError: String? = null
        private set

    private val loadAttempted = AtomicBoolean(false)

    /**
     * 行为开关。默认三项全开 —— 这是实测**缺一不可**的组合，
     * 逐项做过对照实验（见 `oom_snapshot.cpp` 文件头【2】）：
     *
     * | flags | 父进程挂起 | 子进程 patch SuspendAll | 子进程 patch GC | 结果 |
     * |---|---|---|---|---|
     * | 0x04 | – | – | ✅ | 卡在 GC 等待，超时 |
     * | 0x02\|0x04 | – | ✅ | ✅ | 卡在 InternTable 锁，超时 |
     * | 0x01\|0x04 | ✅ | – | ✅ | 卡在 SuspendAll，超时 |
     * | **0x07** | ✅ | ✅ | ✅ | **成功** |
     *
     * 留出这个开关是给**对照实验**用的：实验页可以逐项关掉来复现那三种卡死，
     * 从而证明"三个动作都必要"，而不是只相信注释。
     */
    object Flags {
        /** ① fork 前在父进程里 SuspendAll（让锁全部处于无人持有状态） */
        const val PARENT_SUSPEND = 0x01

        /** ② 子进程里 SuspendAll / ResumeAll 变空操作（否则等不存在的线程） */
        const val CHILD_PATCH_SUSPEND = 0x02

        /** ③ 子进程里禁止 GC（否则等一个不可能完成的 GC） */
        const val CHILD_PATCH_GC = 0x04

        /** 实测唯一能跑通的组合 */
        const val DEFAULT = PARENT_SUSPEND or CHILD_PATCH_SUSPEND or CHILD_PATCH_GC
    }

    private fun loadLibrary(): Boolean {
        if (libraryLoaded) return true
        // 只尝试一次：dlopen 失败通常是 ABI/打包问题，重试没有意义
        if (!loadAttempted.compareAndSet(false, true)) return false
        return try {
            System.loadLibrary(LIB_NAME)
            libraryLoaded = true
            true
        } catch (t: Throwable) {
            // 用 Throwable：ABI 不匹配时抛的是 UnsatisfiedLinkError（Error 子类），
            // 漏掉它会让实验页直接崩在按钮里（本仓库既有约定）。本机 abiFilters
            // 只有 arm64-v8a —— 32 位设备上这个 .so 压根不存在，必须能优雅降级。
            loadError = "${t.javaClass.simpleName}: ${t.message} (${deviceAbiSummary()})"
            Log.w(TAG, "loadLibrary($LIB_NAME) 失败：$loadError")
            false
        }
    }

    /** 设备 ABI 摘要 —— 降级信息里必须带上，否则"为什么不可用"要靠猜。 */
    private fun deviceAbiSummary(): String = runCatching {
        "SUPPORTED_ABIS=${Build.SUPPORTED_ABIS.joinToString(",")} SDK=${Build.VERSION.SDK_INT}"
    }.getOrDefault("ABI 读取失败")

    /**
     * 本库能否使用（= 加载成功 + 必需符号全部命中）。
     *
     * ⚠️ 用 [probe] 缓存的结果，而不是"loadLibrary 成功"——
     *    实测过"库能加载但符号没命中"的情形（换 ART 版本时这是**默认预期**，
     *    因为 hprof 入口的签名变过）。把这两件事混为一谈会得到一个
     *    看起来可用、一调就失败的库。
     */
    fun isUsable(): Boolean = probe()?.usable ?: false

    /** 库是否已加载（诊断用；不代表符号命中） */
    fun isLoaded(): Boolean = libraryLoaded

    /**
     * 能力探测。**页面/PR 的第一件事应该是看这个**：
     * 它回答"这台设备上能不能用、用的哪个 hprof 入口签名"。
     *
     * 结果缓存（探测本身很便宜，但没必要每次触发都做一遍 ELF 遍历）。
     * native 不可用时返回 null，**不抛**。
     */
    fun probe(): Probe? {
        probeResult?.let { return it }
        synchronized(this) {
            probeResult?.let { return it }
            if (!loadLibrary()) return null
            val parsed = runCatching { Probe.parse(probeNative()) }
                .onFailure { Log.w(TAG, "probe 失败", it) }
                .getOrNull()
            probeResult = parsed
            return parsed
        }
    }

    @Volatile
    private var probeResult: Probe? = null

    /**
     * fork 子进程 dump 快照。**必须在后台线程调用**（阻塞到子进程写完或超时）。
     *
     * @param path       hprof 输出路径（父进程私有目录；父进程会先 open 好再 fork）
     * @param timeoutMs  有界等待上限；超时会 SIGKILL 子进程并**删掉半成品文件**
     * @param flags      [Flags] 组合，默认 [Flags.DEFAULT]
     * @return 结果；库不可用时返回 [Result.unavailable]（不抛）
     */
    fun forkDump(path: String, timeoutMs: Long = 20_000L, flags: Int = Flags.DEFAULT): Result {
        if (!loadLibrary()) {
            return Result.unavailable(loadError ?: "native 库未加载")
        }
        return runCatching { Result.parse(forkDumpNative(path, timeoutMs, flags)) }
            .getOrElse { t ->
                // 用 Throwable：JNI 侧问题抛 Error 子类很常见
                Log.e(TAG, "forkDump 调用失败", t)
                Result.unavailable("${t.javaClass.simpleName}: ${t.message}")
            }
    }

    // ─────────────────────────────────────────────
    // JNI 导出（实现见 app/src/main/cpp/oom_snapshot.cpp）
    // ⚠️ 方法名/签名必须与 C++ 侧逐字对应，改一处必须同步改另一处。
    // ─────────────────────────────────────────────

    private external fun probeNative(): String

    private external fun forkDumpNative(path: String, timeoutMs: Long, flags: Int): String

    /**
     * 能力探测结果。
     *
     * 解析 native 返回的两行文本：第一行 `键=值` 空格分隔，第二行是 `#` 开头的
     * 命中签名说明（含空格/中文，单独一行才解析得动）。
     *
     * 拆成纯函数是为了**可被 JVM 单测覆盖**（本 object 在宿主 JVM 上不可用：
     * System.loadLibrary 必失败）。拿不到必需字段就返回 null —— 宁可显示"不可用"，
     * 也不要显示一个瞎猜的结论。
     */
    data class Probe(
        val usable: Boolean,
        val libartFound: Boolean,
        val libartBase: Long,
        val dynsymCount: Int,
        val dumpHeapAddr: Long,
        /** 命中的 hprof 入口签名说明（未命中为 null） */
        val dumpHeapNote: String?,
        /** 必需符号是否齐全（缺哪个一目了然，便于换 ROM 后定位） */
        val missingSymbols: List<String>,
    ) {
        fun describe(): String = buildString {
            appendLine("可用：${if (usable) "✅ 是" else "❌ 否"}")
            appendLine("libart.so 已映射：${if (libartFound) "是（base=0x${java.lang.Long.toHexString(libartBase)}）" else "否"}")
            appendLine("libart 动态符号数：$dynsymCount")
            if (dumpHeapAddr != 0L) {
                appendLine("hprof 入口地址：0x${java.lang.Long.toHexString(dumpHeapAddr)}")
                appendLine("命中签名：${dumpHeapNote ?: "（未记录）"}")
            } else {
                appendLine("⚠️ **hprof 入口（art::hprof::DumpHeap）未命中**")
                appendLine("   原因通常是 ART 版本改了签名 —— 见 oom_snapshot.cpp 的候选表。")
            }
            if (missingSymbols.isNotEmpty()) {
                appendLine("⚠️ 缺失的必需符号：${missingSymbols.joinToString(", ")}")
                // ⚠️ 这里必须和第一行的 usable 对齐：如果 usable=true 却报"缺符号"，
                //    一定是**解析出错**而不是真的缺 —— 直接指出来，不要让人自己怀疑人生。
                if (usable) {
                    appendLine("   ❗ 但 usable=true —— 两者矛盾，说明**本地解析有 bug**（地址解析失败）")
                    appendLine("      而不是设备真的缺符号。请核对 OomSnapshotNative.Probe.parse 的进制处理。")
                } else {
                    appendLine("   缺任一个 ⇒ 默认 flags 不可用（会死锁，而不是降级。）")
                }
            }
        }

        companion object {
            fun parse(raw: String?): Probe? {
                if (raw.isNullOrBlank()) return null
                val lines = raw.trim().split('\n')
                val note = lines.drop(1)
                    .firstOrNull { it.startsWith("#") }
                    ?.removePrefix("#")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() && it != "DumpHeap 符号未命中" }

                val kv = mutableMapOf<String, String>()
                lines.firstOrNull()?.trim()?.split(' ')?.forEach { token ->
                    val i = token.indexOf('=')
                    if (i > 0) kv[token.substring(0, i)] = token.substring(i + 1)
                }
                if (kv.isEmpty()) return null

                // ⚠️ native 侧按 `0x%lx` 打印地址，所以这里**必须先去掉 0x 前缀**：
                //    Kotlin 的 toULongOrNull(radix) 不接受 "0x"（它只吃纯数字），
                //    第一版漏了这一步，导致**每一个地址都被解析成 0** —— 于是
                //    "可用：✅ 是" 和 "⚠️ 缺失的必需符号：7 个全缺" 同时出现在页面上。
                //    这种自相矛盾的输出比单纯的错更坏：它让人不再相信任何一行诊断。
                fun long(k: String) = kv[k]?.removePrefix("0x")?.toULongOrNull(16)?.toLong() ?: 0L
                fun bool(k: String) = kv[k] == "1"
                fun int(k: String) = kv[k]?.toIntOrNull() ?: 0

                // 缺哪些必需符号 —— 逐个说清，别只给一个 false
                val missing = buildList {
                    if (long("dumpHeap") == 0L) add("art::hprof::DumpHeap")
                    if (long("scopedSuspendAll") == 0L) add("ScopedSuspendAll ctor")
                    if (long("scopedSuspendAllDtor") == 0L) add("ScopedSuspendAll dtor")
                    if (long("threadListSuspendAll") == 0L) add("ThreadList::SuspendAll")
                    if (long("threadListResumeAll") == 0L) add("ThreadList::ResumeAll")
                    if (long("heapStartGc") == 0L) add("Heap::StartGC")
                    if (long("heapFinishGc") == 0L) add("Heap::FinishGC")
                }

                return Probe(
                    usable = bool("usable"),
                    libartFound = bool("libartFound"),
                    libartBase = long("libart"),
                    dynsymCount = int("dynsym"),
                    dumpHeapAddr = long("dumpHeap"),
                    dumpHeapNote = note,
                    missingSymbols = missing,
                )
            }
        }
    }

    /**
     * fork dump 的结果。
     *
     * ⚠️ 这里刻意**不接受"文件存在即成功"**：这类方案最典型的假成功就是
     *    留下一个 0 字节或半截的 hprof。所以 [ok] 同时要求
     *    "子进程正常退出 + 文件有内容"。
     */
    data class Result(
        val ok: Boolean,
        /** 子进程结束方式：`exited_ok` / `timeout_sigkilled` / `signaled` / … */
        val how: String,
        val exitCode: Int,
        val sizeBytes: Long,
        /** 父进程"挂起全部线程 → fork → 恢复"这一段里，**挂起**占用的时间 */
        val suspendMs: Long,
        /** fork + 恢复线程 的耗时 */
        val forkMs: Long,
        val totalMs: Long,
        val flags: Int,
        val parentSuspended: Boolean,
        val resumed: Boolean,
        val childPid: Int,
        /** 不可用/失败的原因（成功时为 null） */
        val error: String?,
    ) {
        fun describe(): String = buildString {
            appendLine("结果：${if (ok) "✅ 成功" else "❌ 失败"}")
            appendLine("子进程结束方式：$how（exit=$exitCode, pid=$childPid）")
            appendLine("hprof 大小：${fmt(sizeBytes)}")
            appendLine("耗时：挂起=${suspendMs}ms  到 fork+恢复=${forkMs}ms  合计=${totalMs}ms")
            appendLine("flags：0x${Integer.toHexString(flags)}" +
                    "（父进程挂起=${parentSuspended}，已恢复=$resumed）")
            error?.let { appendLine("⚠️ 失败原因：$it") }
            if (!ok && how == "timeout_sigkilled") {
                appendLine("   超时通常意味着子进程卡在 ART 的某个锁上 ——")
                appendLine("   对照实验显示：三个 flags 少任何一个都会这样（见 oom_snapshot.cpp）。")
            }
            if (!ok && how == "child_patch_failed") {
                appendLine("   ⚠️ 这个原因**和超时完全不同**，别混为一谈：")
                appendLine("   子进程想把 SuspendAll/StartGC 改成 ret，但**回读校验失败** ——")
                appendLine("   说明该 ROM 的这些入口无法被改写（被保护 / 是 ABI 垫片）。")
                appendLine("   这不是「偶发超时」，重试没用；本机应视为不可用。")
            }
        }

        companion object {
            private const val ERR_UNAVAILABLE = "native 不可用"

            fun unavailable(reason: String) = Result(
                ok = false, how = "unavailable_native", exitCode = -1, sizeBytes = -1,
                suspendMs = -1, forkMs = -1, totalMs = -1, flags = 0,
                parentSuspended = false, resumed = false, childPid = -1, error = reason,
            )

            fun parse(raw: String?): Result {
                if (raw.isNullOrBlank()) return unavailable("native 返回空")
                val kv = mutableMapOf<String, String>()
                raw.trim().split(' ').forEach { token ->
                    val i = token.indexOf('=')
                    if (i > 0) kv[token.substring(0, i)] = token.substring(i + 1)
                }
                if (kv.isEmpty()) return unavailable("无法解析 native 返回：$raw")
                return Result(
                    ok = kv["ok"] == "1",
                    how = kv["how"] ?: "unknown",
                    exitCode = kv["exit"]?.toIntOrNull() ?: -1,
                    sizeBytes = kv["size"]?.toLongOrNull() ?: -1,
                    suspendMs = kv["suspendMs"]?.toLongOrNull() ?: -1,
                    forkMs = kv["forkMs"]?.toLongOrNull() ?: -1,
                    totalMs = kv["totalMs"]?.toLongOrNull() ?: -1,
                    flags = kv["flags"]?.removePrefix("0x")?.toIntOrNull(16) ?: 0,
                    parentSuspended = kv["parentSuspended"] == "1",
                    resumed = kv["resumed"] == "1",
                    childPid = kv["childPid"]?.toIntOrNull() ?: -1,
                    error = kv["err"],
                )
            }

            private fun fmt(bytes: Long): String = when {
                bytes < 0 -> "未知"
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
                else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            }
        }
    }
}
