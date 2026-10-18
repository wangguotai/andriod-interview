package com.interview.稳定性监控

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Time: 2026/10/16
 * Author: wgt
 * Description: Native 崩溃报告的**解析器** —— 把 `native_crash_handler.cpp` 落盘的行文本
 *              还原成结构化对象，并映射成 `StabilityReporter.Event` *
 * ══════════════════════════════════════════════════════════════════════
 * 一、本类与写入方的关系：**一份契约，两个实现**
 * ══════════════════════════════════════════════════════════════════════
 *
 * 格式定义在 `NATIVE_CRASH_FORMAT.md`，三方共用：
 *
 * ```
 *   写入方  native_crash_handler.cpp   —— 信号处理器里落盘
 *   解析方  本文件                     —— 下次启动回捞
 *   符号化  tools/native-mem-symbolize.sh —— 抓 `xxx.so+0xNNN`，**零改动**
 * ```
 *
 * ⚠️ 所以本类**不是**自由的：字段名、段落分隔符都跟 C++ 侧硬绑。
 *    改任何一处都要同步改 `NATIVE_CRASH_FORMAT.md` + C++ 侧，否则会出现
 *    "报告写出来了但解析不到"——而且不会报错，只会静默少字段。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 二、最重要的一条设计：**容忍缺失，但给每个缺失标注它意味着什么**
 * ══════════════════════════════════════════════════════════════════════
 *
 * 这份报告的每个段落都**可能**缺失，而且缺失的含义各不相同 ——
 * 把"本该有却没有"和"本就不该有"混为一谈，就是监控变成狼来了的开始
 * （这正是 `AnrMonitor.ExitInfoCollector` 第 552 行那条判断的同一个道理）：
 *
 * | 缺失的段落 | 含义 | 是否该警告 |
 * |---|---|---|
 * | 其他线程栈 | 那线程在 400ms 预算内没回信号（D 态 / 正在退出 / 线程太多） | ❌ 不警告，只记 "抓到 N/M" |
 * | 崩溃线程栈 | 极少见。可能是 unwind 死锁或报告被截断 | ✅ 警告 |
 * | `--- END ---` | **报告被截断**（进程写到一半被 debuggerd 杀掉） | ✅ 必须警告 |
 * | `--- MAPS ---` | 极少 | ✅ 警告（没有 maps 就无法判断 fault_addr 落在哪个区间） |
 * | `--- FD ---` / `--- STATUS ---` | 常见，且不影响归因 | ❌ 静默 |
 *
 * ══════════════════════════════════════════════════════════════════════
 * 三、时间戳：native 只给 uptime，墙钟由本类换算
 * ══════════════════════════════════════════════════════════════════════
 *
 * native 侧刻意只写 `uptime_ms`（`CLOCK_MONOTONIC`）。理由是信号处理器里换算墙钟
 * 要么调 `gettimeofday`（非 async-signal-safe），要么依赖启动时缓存的偏移 ——
 * 而"缓存偏移"这件事本身又需要 `gettimeofday`。所以换算责任放在这里：
 *
 * ```
 *   epochMillis = 回捞时刻的墙钟 - (回捞时刻的 uptime - 崩溃时的 uptime)
 * ```
 *
 * ⚠️ **诚实边界**：这个换算假设"设备在崩溃与下次启动之间没有被改过系统时间、
 *    也没有经历长时休眠"。对"崩溃后立刻重启"的常见路径误差极小；
 *    但如果用户关机 8 小时后才再打开，`CLOCK_MONOTONIC` 在 Android 上**不包含
 *    休眠时间**（那是 `CLOCK_BOOTTIME`），所以这段时间差会被算进去 → 时间戳偏移。
 *    ⇒ 因此本类同时**保留原始 `uptimeMs`**，让服务端在需要时可自行判断，
 *      而不是只给一个看起来精确、实则可能有偏的墙钟值。
 */
object NativeCrashReport {

    private const val TAG = "NativeCrashReport"

    /**
     * 报告头部的格式版本。**与 `native_crash_handler.cpp` 里的字面量必须一致**。
     *
     * 为什么它必须是一个双向钉死的常量，而不是随手写在两处的字符串：
     * C++ 侧和 Kotlin 侧都不可能 import 对方，所以"版本"是**唯一的契约锚点**。
     * 若哪天格式真的升到 v2，而解析方还在匹配 v1，现象是
     * **报告写出来了但一条都解析不到** —— 静默失败，且 debug 构建下也一样静默。
     * 把它写成常量后，`NativeCrashFormatConsistencyTest` 会直接读 C++ 源码文件
     * 比对这一行；改一边漏一边 = 单测红，而不是线上静默丢数据。
     *
     * 格式变更的正确步骤（三步缺一不可）：
     *   ① 改 `native_crash_handler.cpp` 的输出 + 本常量（或本类整体）
     *   ② 改 `NATIVE_CRASH_FORMAT.md`
     *   ③ 跑 `NativeCrashFormatConsistencyTest`
     */
    const val FORMAT_VERSION = "NCREPORT v1"

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    // ─────────────────────────────────────────────
    // 数据结构
    // ─────────────────────────────────────────────

    /** 一帧。`module` 为空表示匿名映射（无对应模块名） */
    data class Frame(
        /** 绝对地址（native 侧输出的左列），用于与 tombstone 对照 */
        val address: Long,
        /** 模块 basename，如 `libnative_crash.so`；null = 匿名映射 */
        val module: String?,
        /** 模块内偏移。**符号化的输入**（`module+offset`） */
        val offset: Long,
    ) {
        /** 还原成符号化脚本能吃的 token（`libxxx.so+0x1234`） */
        fun symbolToken(): String? = module?.let { "%s+0x%x".format(it, offset) }

        fun describe(): String =
            if (module != null) "%s+0x%x (0x%016x)".format(module, offset, address)
            else "(anonymous) 0x%016x".format(address)
    }

    /** 一个线程的栈 */
    data class ThreadBacktrace(
        val name: String,
        val tid: Int,
        val frames: List<Frame>,
        /** 是否是崩溃线程（写入方用线程名做段标题，本类靠 tid 与头部 `tid=` 比对） */
        val isCrashed: Boolean,
    )

    /** 一段 VMA（`/proc/self/maps` 的一行） */
    data class Vma(
        val start: Long,
        val end: Long,
        val perms: String,
        val path: String?,
    ) {
        val sizeKb: Long get() = (end - start) / 1024
        fun contains(addr: Long) = addr >= start && addr < end
        fun describe(): String =
            "0x%x-0x%x %s %s".format(start, end, perms, path ?: "(anonymous)")
    }

    /** 解析结果 */
    data class Report(
        val pid: Int,
        val tid: Int,
        val signal: Int,
        val signalName: String,
        val code: Int,
        val codeName: String,
        val faultAddr: Long,
        val senderPid: Int,
        val senderUid: Int,
        val threadName: String,
        val abortMessage: String,
        val sdk: Int,
        val androidRelease: String,
        val uptimeMs: Long,
        val versionName: String,
        val versionCode: Int,
        val processName: String,
        val registers: Map<String, Long>,
        val backtraces: List<ThreadBacktrace>,
        val maps: List<Vma>,
        val fdLines: List<String>,
        val statusLines: List<String>,
        val cmdline: String,
        /** 是否看到了 `--- END ---`（没有 = 报告被截断） */
        val complete: Boolean,
        /** 原始文本（未做任何裁剪）—— 上传时必须原样带上，符号化要靠它 */
        val raw: String,
        /** 回捞时刻的墙钟（`System.currentTimeMillis()`），用于换算崩溃时刻 */
        val recoveredAtMillis: Long,
    ) {

        /** 崩溃线程的栈（若抓到了） */
        val crashedBacktrace: ThreadBacktrace?
            get() = backtraces.firstOrNull { it.isCrashed }

        /** 崩溃时刻的估算墙钟（换算口径见类注释 §三，含已知偏差） */
        val estimatedEpochMillis: Long
            get() = estimateEpoch(recoveredAtMillis, uptimeMs, android.os.SystemClock.uptimeMillis())

        /** `fault_addr` 落在哪个 VMA（这是 native 归因最有用的一步） */
        val faultVma: Vma?
            get() = maps.firstOrNull { it.contains(faultAddr) }

        fun describe(): String = buildString {
            appendLine("═══ Native 崩溃报告 ═══")
            appendLine("pid=$pid tid=$tid 进程=$processName")
            appendLine("版本=$versionName($versionCode) Android $androidRelease (SDK $sdk)")
            appendLine("崩溃时刻≈${fmt.format(Date(estimatedEpochMillis))}（由 uptime=${uptimeMs}ms 换算）")
            appendLine()
            appendLine("信号：$signal $signalName  code=$code $codeName")
            appendLine("fault_addr=${"0x%016x".format(faultAddr)}")
            if (faultAddr != 0L) {
                val v = faultVma
                appendLine("  ↳ 落在：${v?.describe() ?: "**任何 VMA 之外**（野指针 / 已 munmap 的地址）"}")
            } else {
                appendLine("  ↳ 为 0：典型的空指针解引用（SEGV_MAPERR + addr=0）")
            }
            appendLine("线程：$threadName  abort message：$abortMessage")
            appendLine()
            appendLine("── 寄存器（${registers.size} 个）──")
            listOf("x0", "x1", "x2", "x8", "x29", "x30", "sp", "pc").forEach { r ->
                registers[r]?.let { appendLine("  %-5s 0x%016x".format(r, it)) }
            }
            appendLine()
            appendLine("── 栈（${backtraces.size} 个线程）──")
            backtraces.forEach { bt ->
                appendLine("  ${if (bt.isCrashed) "★崩溃线程 " else ""}${bt.name} (tid=${bt.tid}) ${bt.frames.size} 帧")
                if (bt.isCrashed) {
                    bt.frames.take(12).forEach { appendLine("      $it") }
                }
            }
            appendLine()
            appendLine("── 完整性 ──")
            if (complete) {
                appendLine("  报告完整（含 --- END ---）")
            } else {
                appendLine("  ⚠️ **报告不完整**（缺 --- END ---）：进程在写完之前被 debuggerd 杀掉了。")
                appendLine("     已解析到的字段仍然可信，但**不要**把它当成完整现场 —— 尤其是栈可能被截断。")
            }
            appendLine("  maps=${maps.size} 个 VMA  fd=${fdLines.size} 条  status=${statusLines.size} 行")
            if (crashedBacktrace == null) {
                appendLine("  ⚠️ **没有崩溃线程的栈** —— 这是少见情形（unwind 死锁或报告截断）")
            }
        }
    }

    // ─────────────────────────────────────────────
    // 时间戳换算
    // ─────────────────────────────────────────────

    /**
     * 从 `uptime_ms` 换算崩溃时刻的墙钟。
     *
     * ```
     *   epochMillis = 回捞时刻墙钟 - (回捞时的 monotonic - 崩溃时的 monotonic)
     * ```
     *
     * ⚠️ **必须是 `uptimeMillis()`（即 `CLOCK_MONOTONIC`），不能用 `elapsedRealtime()`**
     *    （那是 `CLOCK_BOOTTIME`，**含休眠**）。native 侧写的是 `CLOCK_MONOTONIC`，
     *    两者在设备睡过觉之后会差出整个休眠时长 —— 是个静默的错值。
     *    （这也是本类的 `estimatedEpochMillis` 必须调用本函数、而不是自己算的理由：
     *      只有一个地方选时钟源，就不会出现"一处对一处错"。）
     *
     * 抽成纯函数是**刻意的**：`SystemClock` 在宿主 JVM 单测里被 mock 成
     * `isReturnDefaultValues = true` → 返回 0。若直接把两条式子写成一个 getter，
     * 单测里 `nowMonotonic=0`，`delta = -uptimeMs`，换算结果变成
     * `recoveredAt + uptimeMs` —— **未来时间**。那种写法在设备上是对的、
     * 在测试里必错，于是"测试失败"会被误判成"断言写错了"。
     * 分开之后，测试直接传三个真实数字，验证的是**算术方向**本身。
     *
     * @param recoveredAtMillis 回捞时的墙钟（`System.currentTimeMillis()`）
     * @param crashUptimeMs     崩溃时的 monotonic（报告里的 `uptime_ms`）
     * @param nowUptimeMs       回捞时的 monotonic（`SystemClock.uptimeMillis()`）
     */
    fun estimateEpoch(
        recoveredAtMillis: Long,
        crashUptimeMs: Long,
        nowUptimeMs: Long,
    ): Long = recoveredAtMillis - (nowUptimeMs - crashUptimeMs)

    // ─────────────────────────────────────────────
    // 解析
    // ─────────────────────────────────────────────

    /** 从文件解析。读失败/格式不对返回 null（**不抛** —— 这是启动路径上的代码） */
    fun parseFile(f: File): Report? = runCatching {
        if (!f.exists() || f.length() == 0L) return null
        parse(f.readText(), System.currentTimeMillis())
    }.getOrElse {
        Log.w(TAG, "解析失败：${f.name}", it)
        null
    }

    /**
     * 解析一份报告文本。
     *
     * 实现方式是**一次遍历 + 段落状态机**，而不是"按段落 split 再逐段正则" ——
     * 后者在缺段落时容易把下一段的开头吃进来（例如 maps 缺失时把 `--- FD ---`
     * 当成 maps 的一行）。
     */
    fun parse(text: String, recoveredAtMillis: Long): Report? {
        if (!text.startsWith(FORMAT_VERSION)) return null
        val lines = text.lines()

        // ── 头部字段（`key=value`，直到第一个 `--- ` 段落行）──
        val head = HashMap<String, String>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("--- ")) break
            val eq = line.indexOf('=')
            if (eq > 0) {
                val k = line.substring(0, eq).trim()
                val v = line.substring(eq + 1)
                // signal / code 带名字（`11 SIGSEGV`），这里只取数值部分
                head[k] = v
            }
            i++
        }

        // ── 段落 ──
        val backtraces = ArrayList<ThreadBacktrace>()
        val maps = ArrayList<Vma>()
        val fdLines = ArrayList<String>()
        val statusLines = ArrayList<String>()
        val registers = LinkedHashMap<String, Long>()
        var cmdline = ""
        var complete = false

        i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("--- BACKTRACE ") -> {
                    // 段标题形如 `--- BACKTRACE RenderThread (tid=12347) ---`
                    val header = line.removePrefix("--- BACKTRACE ").removeSuffix(" ---").trim()
                    val tidIdx = header.lastIndexOf("(tid=")
                    val name = if (tidIdx > 0) header.substring(0, tidIdx).trim() else header
                    val tid = if (tidIdx > 0) {
                        header.substring(tidIdx + 5).removeSuffix(")").trim().toIntOrNull() ?: 0
                    } else 0
                    val frames = ArrayList<Frame>()
                    i++
                    while (i < lines.size && !lines[i].startsWith("--- ")) {
                        parseFrameLine(lines[i])?.let { frames.add(it) }
                        i++
                    }
                    backtraces.add(ThreadBacktrace(name, tid, frames, tid == head["tid"]?.toIntOrNull()))
                    continue
                }
                line == "--- REGISTERS ---" -> {
                    i++
                    while (i < lines.size && !lines[i].startsWith("--- ")) {
                        val p = lines[i].trim().split(Regex("\\s+"))
                        if (p.size >= 2) {
                            // ⚠️ 必须 removePrefix("0x")：Kotlin 的 `toULongOrNull(radix)`
                            //    **不接受 0x 前缀**（'x' 不是合法数字）→ 会静默返回 null。
                            //    本文件第一版就漏了这一步，现象是「寄存器一个都解析不到」，
                            //    而报告里明明有 —— 静默丢数据的典型形态。
                            p[1].removePrefix("0x").toULongOrNull(16)?.let { registers[p[0]] = it.toLong() }
                        }
                        i++
                    }
                    continue
                }
                line == "--- MAPS ---" -> {
                    i++
                    while (i < lines.size && !lines[i].startsWith("--- ")) {
                        parseMapsLine(lines[i])?.let { maps.add(it) }
                        i++
                    }
                    continue
                }
                line == "--- FD ---" -> {
                    i++
                    while (i < lines.size && !lines[i].startsWith("--- ")) {
                        if (lines[i].isNotBlank()) fdLines.add(lines[i].trim())
                        i++
                    }
                    continue
                }
                line == "--- STATUS ---" -> {
                    i++
                    while (i < lines.size && !lines[i].startsWith("--- ")) {
                        if (lines[i].isNotBlank()) statusLines.add(lines[i].trim())
                        i++
                    }
                    continue
                }
                line == "--- CMDLINE ---" -> {
                    i++
                    val sb = StringBuilder()
                    while (i < lines.size && !lines[i].startsWith("--- ")) {
                        sb.append(lines[i]).append('\n')
                        i++
                    }
                    // cmdline 是 NUL 分隔的，取第一段
                    cmdline = sb.toString().split('\u0000', '\n').firstOrNull { it.isNotBlank() }?.trim() ?: ""
                    continue
                }
                line == "--- END ---" -> {
                    complete = true
                    i++
                    continue
                }
            }
            i++
        }

        fun headInt(k: String, def: Int = 0) = head[k]?.trim()?.substringBefore(' ')?.toIntOrNull() ?: def
        fun headLong(k: String, def: Long = 0L) =
            head[k]?.trim()?.removePrefix("0x")?.toULongOrNull(16)?.toLong()
                ?: head[k]?.trim()?.toLongOrNull() ?: def

        return Report(
            pid = headInt("pid"),
            tid = headInt("tid"),
            signal = headInt("signal"),
            signalName = head["signal"]?.trim()?.substringAfter(' ', "?") ?: "?",
            code = headInt("code"),
            codeName = head["code"]?.trim()?.substringAfter(' ', "?") ?: "?",
            faultAddr = headLong("fault_addr"),
            senderPid = headInt("sender_pid"),
            senderUid = headInt("sender_uid"),
            threadName = head["thread_name"]?.trim() ?: "?",
            abortMessage = head["abort_message"]?.trim() ?: "(无)",
            sdk = headInt("sdk"),
            androidRelease = head["android_release"]?.trim() ?: "?",
            uptimeMs = head["uptime_ms"]?.trim()?.toLongOrNull() ?: 0L,
            versionName = head["version_name"]?.trim() ?: "?",
            versionCode = headInt("version_code"),
            processName = head["process_name"]?.trim() ?: cmdline.ifBlank { "?" },
            registers = registers,
            backtraces = backtraces,
            maps = maps,
            fdLines = fdLines,
            statusLines = statusLines,
            cmdline = cmdline,
            complete = complete,
            raw = text,
            recoveredAtMillis = recoveredAtMillis,
        )
    }

    /**
     * 解析一行帧：`  #00 0x0000007f12345678 libfoo.so+0x1a3c`
     * 或匿名映射形态：`  #00 0x0000007f12345678 (anonymous)`
     */
    private fun parseFrameLine(line: String): Frame? {
        val t = line.trim()
        if (!t.startsWith("#")) return null
        val parts = t.split(Regex("\\s+"))
        if (parts.size < 3) return null
        val addr = parts[1].removePrefix("0x").toULongOrNull(16)?.toLong() ?: return null
        val sym = parts[2]
        val plus = sym.lastIndexOf('+')
        if (plus <= 0) return Frame(addr, null, 0L)  // `(anonymous)`
        val module = sym.substring(0, plus)
        val off = sym.substring(plus + 1).removePrefix("0x").toULongOrNull(16)?.toLong() ?: 0L
        return Frame(addr, module, off)
    }

    /**
     * 解析一行 maps：`7f0000-7f1000 r-xp 00000000 fd:00 1234 /path/lib.so`
     *
     * ⚠️ 注意与 native 侧的解析器**不是**同一个：那边要快且能容忍半行，
     *    这边可以慢、可以用 String API。两处独立实现是刻意的 ——
     *    让契约里的格式成为唯一的耦合点，而不是共享一份"谁都改不动"的代码。
     */
    private fun parseMapsLine(line: String): Vma? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val parts = t.split(Regex("\\s+"))
        if (parts.size < 2) return null
        val dash = parts[0].indexOf('-')
        if (dash <= 0) return null
        val start = parts[0].substring(0, dash).toULongOrNull(16)?.toLong() ?: return null
        val end = parts[0].substring(dash + 1).toULongOrNull(16)?.toLong() ?: return null
        // 路径在末尾（可能含空格，所以用 substring 而不是 parts[5]）
        val perms = parts[1]
        val pathStart = t.indexOf('/', parts[0].length + parts[1].length)
        val path = if (pathStart > 0) t.substring(pathStart) else null
        return Vma(start, end, perms, path)
    }

    // ─────────────────────────────────────────────
    // 映射到统一上报出口
    // ─────────────────────────────────────────────

    /**
     * 转成 `StabilityReporter.Event`，使 native 崩溃与 Java 崩溃**走同一条出口**。
     *
     * ⚠️ 这里有一个刻意的信息取舍：`Event.stack` 只放**崩溃线程的帧**
     *    （外加"共抓到 N 个线程"这一行），完整报告放 `Event.detail` 的引用，
     *    原始文本由回捞方另行落盘/上传。
     *    理由：`Event` 会被塞进 320 条的环形缓冲并被逐条 logcat，
     *    把几十个线程的栈 + 2000 行 maps 塞进去会把缓冲挤爆、把日志刷没 ——
     *    `StabilityReporter` 的类注释第 3 条（"事件必须能自证"）要的是**可聚合的字段**，
     *    不是把原始现场当字符串搬来搬去。
     *    ⇒ 完整现场的去处是 `detail` 里的文件路径 + 上报出口的附件。
     */
    fun toEvent(r: Report): StabilityReporter.Event {
        val crashed = r.crashedBacktrace
        val stackText = buildString {
            appendLine("=== native backtrace (crashed thread: ${r.threadName}) ===")
            if (crashed != null) {
                crashed.frames.forEachIndexed { idx, f ->
                    appendLine("#%02d %s".format(idx, f.describe()))
                }
            } else {
                appendLine("⚠️ 未抓到崩溃线程的栈（见报告完整性说明）")
            }
            appendLine()
            appendLine("--- 其他线程（共 ${r.backtraces.size - (if (crashed != null) 1 else 0)} 个）---")
            r.backtraces.filter { !it.isCrashed }.take(12).forEach { bt ->
                appendLine("${bt.name}: ${bt.frames.take(4).joinToString(" ← ") { it.symbolToken() ?: "?(anon)" }}")
            }
        }

        val detail = buildString {
            appendLine("signal=${r.signal} ${r.signalName} code=${r.code} ${r.codeName}")
            appendLine("fault_addr=0x%016x".format(r.faultAddr))
            appendLine("  ↳ VMA: ${r.faultVma?.describe() ?: "**不在任何 VMA 内**"}")
            appendLine("abort_message=${r.abortMessage}")
            appendLine("process=${r.processName} version=${r.versionName}(${r.versionCode})")
            appendLine("android=${r.androidRelease} sdk=${r.sdk} uptime=${r.uptimeMs}ms")
            appendLine("threads=${r.backtraces.size} maps=${r.maps.size} vmav=${r.maps.sumOf { it.sizeKb }}KB")
            appendLine("registers=${r.registers.entries.take(8).joinToString(" ") { "${it.key}=0x%x".format(it.value) }}")
            if (!r.complete) {
                appendLine("⚠️ 报告不完整（缺 --- END ---），字段可能有截断")
            }
            appendLine("（完整现场见原始报告，符号化：tools/native-mem-symbolize.sh）")
        }

        return StabilityReporter.Event(
            // ⚠️ 用 CRASH_NATIVE 而不是 CRASH：这个 kind 本来就存在于
            //    StabilityReporter.Kind 里（见其第 95-96 行），且服务端要按 kind
            //    聚合出"原生崩溃占比"这个维度 —— 混进 CRASH 会让这个维度消失。
            kind = StabilityReporter.Kind.CRASH_NATIVE,
            timestamp = r.estimatedEpochMillis,
            name = "${r.signalName}/${r.codeName}",
            valueMs = 0,
            threadName = r.threadName,
            // 崩溃线程 tid == 主线程 tid？用 pid 比对（主线程 tid 恒等于 pid）
            isMainThread = r.tid == r.pid,
            isForeground = StabilityReporter.isForeground(),
            pid = r.pid,
            detail = detail,
            stack = stackText,
        )
    }

    // ─────────────────────────────────────────────
    // 目录清理
    // ─────────────────────────────────────────────

    /**
     * 回捞后的清理策略。
     *
     * ⚠️ **不能"上传成功就删"**：native 报告是**唯一**的记录（Java 侧的崩溃还有
     *    `CrashJournal` 遗嘱兜底，native 没有第二份）。所以这里的口径是"保留最近 N 份"：
     *
     *   · 上传成功后仍留档（供实验页当场查看 / 排查上传链路的失败）；
     *   · 超过 [keep] 份才删最旧的 —— 上限存在的理由是防止"崩溃循环"把存储写满
     *     （与 `CrashJournal` 的 `MAX_RECORDS` 同一个动机）。
     *
     * 返回被删掉的文件数。
     */
    fun prune(dir: File, keep: Int = 20): Int {
        return runCatching {
            val files = dir.listFiles { f -> f.name.startsWith("report-") && f.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() } ?: return 0
            var removed = 0
            files.drop(keep).forEach {
                if (it.delete()) removed++
            }
            // `.tmp` 是"写到一半"的残留（native 侧写不全才留下），**永远删掉**：
            // 它一定是不完整的，留着只会让人误以为有一份没解析的报告
            dir.listFiles { f -> f.name.endsWith(".tmp") }?.forEach { it.delete() }
            removed
        }.getOrElse {
            Log.w(TAG, "清理报告目录失败", it)
            0
        }
    }
}
