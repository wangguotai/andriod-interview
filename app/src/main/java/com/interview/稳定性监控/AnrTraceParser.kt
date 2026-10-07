package com.interview.稳定性监控

import java.nio.charset.StandardCharsets

/**
 * Time: 2026/10/7
 * Author: wgt
 * Description: ANR / Native-crash trace 解析 —— 把系统给的 trace 变成「一条能看的摘要」
 *
 * ══════════════════════════════════════════════════════════════════════
 * 第一个要判断的事：**这份 trace 是文本还是二进制 proto**
 * ══════════════════════════════════════════════════════════════════════
 *
 * `ApplicationExitInfo.getTraceInputStream()` 给的东西**不是一种格式**：
 *
 * | 场景 | 格式 | 内容 |
 * |---|---|---|
 * | ANR（Java） | **文本**（与 /data/anr/ 同格式） | 全部线程的堆栈 + subject |
 * | Native 崩溃 | **protobuf**（二进制） | native 堆栈、寄存器、内存信息 |
 *
 * ⚠️ 把二进制按文本读，你会得到一堆 `\uFFFD` 乱码，然后误判成「trace 损坏」；
 *    把文本按 proto 解析，会解析失败然后误判成「系统没给数据」。
 *    **两种误判都会让人放弃这条链路** —— 所以格式探测是这里的第一步，
 *    而不是一个可选优化。
 *
 * 探测方法：文本 trace 必定含 ASCII 结构行（`----- pid`、`Cmd line:`、`"main"`），
 * 二进制 proto **不会**在同一个后缀位置出现这些标记；同时统计不可打印字符比例。
 * 两者都要看 —— 只用其中一个会有边界误判（例如二进制里恰好有可打印的 tag 字节）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 第二个要说清的事：**ANR trace 里最值钱的是哪几行**
 * ══════════════════════════════════════════════════════════════════════
 *
 * 一次 ANR 的 trace 有几千行（几十个线程 × 每个上百帧），全量上报既贵又没用。
 * 真正的判据只有四处：
 *
 * **1. subject 行** —— 系统给出的**触发原因**，例如：
 * ```
 * ----- pid 12345 at 2026-10-07 12:00:00 -----
 * Cmd line: com.example.myapplication
 * ...
 * ```
 * ⚠️ 这里有个大坑：**subject 行（"Reason:"）在不同版本、不同触发路径下位置不同**，
 *    而且在 `ApplicationExitInfo` 拿到的 trace 里，**它常常被裁掉了**
 *    （系统只给"与本进程相关"的子集）。所以不要把「解析出 Reason」当成必然。
 *
 * **2. main 线程块** —— "主线程在做什么"。注意：**它在等锁时，堆栈会显示
 *    `- waiting to lock <0x...> (a java.lang.Object) held by thread 12`**。
 *    这一行直接指向**锁的持有者**，比看堆栈顶部有用得多。
 *
 * **3. 锁等待图** —— 主线程等 T12，T12 又在等 T7……串起来就是死锁链。
 *    本类用 [analyzeLocks] 做一次「谁在等谁」的简单图遍历。
 *
 * **4. 线程状态分布** —— 是否大量线程同时处于 RUNNABLE 抢 CPU（说明是
 *    线程爆炸/CPU 打满，不是单点阻塞）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 诚实边界（这些是**能力上限**，不是实现缺陷）
 * ══════════════════════════════════════════════════════════════════════
 *
 * 1. **相当比例的 ANR trace 拿不到线程堆栈**（本类会在解析后如实指出）。
 *    原因包括 self ANR（Android 12+ 自报不带全量 trace）、读取超时、
 *    系统侧 trace 被截断。此时你只有「发生过 ANR」这个事实。
 *    ⚠️ 业界常引用「约 30% 缺 trace」这个数字，但**本仓库没有验证过它**，
 *    因此不把它当结论、也不写进任何阈值判断 —— 只依赖"可能缺失"这个定性事实。
 *    → **不要为了"看起来有数据"而编造堆栈**。本类在缺失时明确输出「无堆栈」。
 * 2. **二进制 proto 无法在 App 内解析出有用信息** —— 没有 `.proto` 契约、
 *    且需要跨版本适配。这里只做**结构自检**（确认它是 proto、估出字段数量），
 *    真正的解析交给平台侧的符号化（ndk-stack / Crashpad）。
 *    在 App 内自行实现 proto 解析是**负收益**：脆弱且没有符号表。
 * 3. **"等待锁"的 holder 是 tid，不是线程名**。不同 ROM 的 tid 复用会让
 *    「holder=12」指向一个已经不存在的线程 → 图遍历要处理这种情况（本类做了）。
 */
object AnrTraceParser {

    /** 单次解析最多保留多少线程块（trace 可能有几百个线程） */
    private const val MAX_THREADS = 40

    /** 每个线程最多保留多少帧 */
    private const val MAX_FRAMES_PER_THREAD = 30

    data class ThreadBlock(
        val name: String,
        /** `prio=5 tid=1 Native` 这种属性行 */
        val attrs: String,
        val frames: List<String>,
        /** 等待锁的地址 → holder tid（来自 `- waiting to lock <0x..> ... held by thread N`） */
        val waitingLock: String?,
        val holderTid: Int?,
        /** 本线程持有的锁 */
        val heldLocks: List<String>,
        val isMain: Boolean,
    )

    data class Result(
        val isBinary: Boolean,
        /** 供人看的摘要（已做长度控制） */
        val summary: String,
        val threads: List<ThreadBlock> = emptyList(),
        /** 解析出的 subject / reason（拿不到时为 null —— 拿不到就是拿不到） */
        val subject: String? = null,
        val pid: Int? = null,
        /** 是否**确实**解析到了线程堆栈。false 时 summary 会说明原因 */
        val hasThreadStacks: Boolean = false,
        /** 死锁环（若分析出） */
        val deadlockChain: List<String> = emptyList(),
    )

    /**
     * 解析。**不做任何抛异常的可能** —— 它运行在回捞路径上，崩在这里等于丢证据。
     */
    fun parse(bytes: ByteArray): Result = runCatching { doParse(bytes) }
        .getOrElse { Result(false, "trace 解析失败（已保留长度信息）：${bytes.size} 字节，原因：$it") }

    private fun doParse(bytes: ByteArray): Result {
        if (bytes.isEmpty()) return Result(false, "trace 为空（0 字节）—— 系统没给数据")
        val binary = looksBinary(bytes)
        if (binary) return parseBinaryStructure(bytes)
        val text = String(bytes, StandardCharsets.UTF_8)
        // 有些 ROM 会用 UTF-16；这里给一个兜底：UTF-8 解出来大量替换字符就换一种解
        val clean = if (text.count { it == '\uFFFD' } > text.length / 10) {
            String(bytes, StandardCharsets.UTF_16)
        } else text
        return parseText(clean)
    }

    // ─────────────────────────────────────────────
    // 格式探测
    // ─────────────────────────────────────────────

    /**
     * 判断是不是二进制 proto。
     *
     * 两个依据**同时**看（只用其一会有边界误判）：
     *  ① 不可打印字节比例：文本 trace 的不可打印字符应极少（换行/制表/CR 除外）
     *  ② 是否出现文本 trace 必有的结构标记
     */
    private fun looksBinary(bytes: ByteArray): Boolean {
        val head = bytes.take(4096)
        var nonPrintable = 0
        head.forEach { b ->
            val c = b.toInt() and 0xFF
            // 允许 9(tab) 10(LF) 13(CR)
            if (c < 0x20 && c != 9 && c != 10 && c != 13) nonPrintable++
        }
        val ratio = nonPrintable.toDouble() / head.size
        val asText = String(head.toByteArray(), StandardCharsets.UTF_8)
        val hasTextMarker = asText.contains("----- pid") ||
                asText.contains("Cmd line:") ||
                asText.contains("\"main\"") ||
                asText.contains("prio=")
        // 文本标记存在 → 一律当文本，哪怕有几个怪字节
        if (hasTextMarker) return false
        return ratio > 0.05
    }

    /**
     * 二进制 proto 的**结构自检**（不解析语义）。
     *
     * 为什么不做真正解析：见类注释「诚实边界 2」。这里只做三件事：
     * 确认它像 protobuf、估出顶层字段数、给出正确的下一步（平台侧符号化）。
     *
     * 手法：protobuf 的线格式是 `tag = (field_number << 3) | wire_type`，
     * 顺序扫描一遍顶层字段，能把 tag 读通就说明结构合理。
     */
    private fun parseBinaryStructure(bytes: ByteArray): Result {
        val fields = mutableListOf<String>()
        var i = 0
        var guard = 0
        while (i < bytes.size && guard < 200) {
            guard++
            val tag = readVarint(bytes, i) ?: break
            val fieldNo = (tag ushr 3).toInt()
            val wire = (tag and 0x7).toInt()
            if (fieldNo <= 0 || fieldNo > 512) break
            fields += "field#$fieldNo wire=$wire @$i"
            val consumed = skipField(bytes, i, wire) ?: break
            i = consumed
        }
        val summary = buildString {
            appendLine("格式：**protobuf 二进制**（原生崩溃 trace），共 ${bytes.size} 字节")
            appendLine("可读出的顶层字段（前 ${fields.size} 个）：")
            fields.take(12).forEach { appendLine("  $it") }
            appendLine()
            appendLine("⚠️ App 内不解析语义 —— 原因：")
            appendLine("  · 没有稳定的 .proto 契约，字段号跨版本会变；")
            appendLine("  · 没有符号表，即便解出来也是裸地址；")
            appendLine("  · 正确做法是把原始字节**原样**上传（注意：**不能按文本保存/传输**，")
            appendLine("    会被编码层改写字节），在平台侧用 ndk-stack / Crashpad 符号化。")
        }
        return Result(isBinary = true, summary = summary)
    }

    private fun readVarint(b: ByteArray, start: Int): Long? {
        var result = 0L
        var shift = 0
        var i = start
        while (i < b.size) {
            val byte = b[i].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
            if (shift > 63) return null
            i++
        }
        return null
    }

    /** 跳过当前字段，返回下一个字段的起始下标；结构不合理时返回 null */
    private fun skipField(b: ByteArray, start: Int, wire: Int): Int? {
        return when (wire) {
            0 -> { // varint
                var i = start
                while (i < b.size) {
                    val byte = b[i].toInt() and 0xFF
                    i++
                    if (byte and 0x80 == 0) return i
                }
                null
            }
            1 -> (start + 1 + 8).takeIf { it <= b.size }   // 64-bit
            2 -> {                                        // length-delimited
                val len = readVarint(b, start + 1) ?: return null
                val dataStart = start + 1 + varintSize(len)
                (dataStart + len).toInt().takeIf { it <= b.size && it >= dataStart }
            }
            5 -> (start + 1 + 4).takeIf { it <= b.size }   // 32-bit
            else -> null
        }
    }

    private fun varintSize(v: Long): Int {
        var n = 1
        var x = v
        while (x >= 0x80) { x = x ushr 7; n++ }
        return n
    }

    // ─────────────────────────────────────────────
    // 文本 trace 解析
    // ─────────────────────────────────────────────

    private val THREAD_HEADER = Regex("""^"([^"]+)"\s*(.*)$""")
    private val PID_LINE = Regex("""^----- pid (\d+) at (.*) -----\s*$""")
    private val REASON_LINE = Regex("""^(Reason|Subject):\s*(.*)$""")
    private val WAIT_LOCK = Regex("""-\s*waiting to lock <(0x[0-9a-fA-F]+)>.*held by thread (\d+)""")
    private val WAIT_LOCK_NOHOLDER = Regex("""-\s*waiting to lock <(0x[0-9a-fA-F]+)>""")
    private val HOLD_LOCK = Regex("""-\s*locked <(0x[0-9a-fA-F]+)>""")

    private fun parseText(text: String): Result {
        val lines = text.lines()
        var pid: Int? = null
        var subject: String? = null
        var cmdLine: String? = null
        val blocks = mutableListOf<ThreadBlock>()

        var curName: String? = null
        var curAttrs = ""
        var curFrames = mutableListOf<String>()
        var curWaitLock: String? = null
        var curHolder: Int? = null
        var curHeld = mutableListOf<String>()

        fun flush() {
            val name = curName ?: return
            if (blocks.size >= MAX_THREADS) return
            blocks += ThreadBlock(
                name = name,
                attrs = curAttrs,
                frames = curFrames.take(MAX_FRAMES_PER_THREAD),
                waitingLock = curWaitLock,
                holderTid = curHolder,
                heldLocks = curHeld.toList(),
                isMain = name == "main",
            )
            curName = null
            curFrames = mutableListOf()
            curWaitLock = null
            curHolder = null
            curHeld = mutableListOf()
        }

        for (raw in lines) {
            val line = raw.trimEnd()
            PID_LINE.find(line)?.let {
                pid = it.groupValues[1].toIntOrNull()
                return@let
            }
            if (pid == null && line.startsWith("----- pid")) {
                pid = line.substringAfter("pid ").substringBefore(" ").toIntOrNull()
            }
            // ⚠️ subject/reason：ApplicationExitInfo 给的子集里**经常没有**这一行。
            //    拿不到就留 null，不要伪造。
            REASON_LINE.find(line)?.let {
                if (subject == null) subject = it.groupValues[1] + ": " + it.groupValues[2]
                return@let
            }
            if (line.startsWith("Cmd line:")) {
                cmdLine = line.removePrefix("Cmd line:").trim()
            }

            val header = THREAD_HEADER.find(line)
            // 线程头："main" prio=5 tid=1 Native
            if (header != null && !line.startsWith(" ") && line.contains("prio=")) {
                flush()
                curName = header.groupValues[1]
                curAttrs = header.groupValues[2].trim()
                continue
            }
            if (curName == null) continue

            when {
                WAIT_LOCK.containsMatchIn(line) -> {
                    val m = WAIT_LOCK.find(line)!!
                    curWaitLock = m.groupValues[1]
                    curHolder = m.groupValues[2].toIntOrNull()
                    curFrames += line.trim()
                }
                WAIT_LOCK_NOHOLDER.containsMatchIn(line) -> {
                    curWaitLock = WAIT_LOCK_NOHOLDER.find(line)!!.groupValues[1]
                    curFrames += line.trim()
                }
                HOLD_LOCK.containsMatchIn(line) -> {
                    curHeld += HOLD_LOCK.find(line)!!.groupValues[1]
                    curFrames += line.trim()
                }
                line.startsWith("  at ") || line.startsWith("  | ") || line.startsWith("  native:") -> {
                    curFrames += line.trim()
                }
                line.startsWith("  - ") || line.startsWith("  waiting on") -> {
                    curFrames += line.trim()
                }
            }
        }
        flush()

        if (blocks.isEmpty()) {
            return Result(
                isBinary = false,
                summary = buildString {
                    appendLine("格式：文本，但**未解析出任何线程堆栈**（${text.length} 字符）")
                    appendLine("可能原因：")
                    appendLine("  · self ANR（Android 12+ 自报的 ANR 不带全量 trace）；")
                    appendLine("  · 系统侧 trace 被截断/裁剪，只留下了进程信息；")
                    appendLine("  · 非 ANR 场景（如纯 Java 崩溃）本就不带线程 dump。")
                    appendLine()
                    appendLine("原始开头 20 行（供人工判断）：")
                    appendLine(text.lines().take(20).joinToString("\n") { "  $it" })
                },
                subject = subject,
                pid = pid,
                hasThreadStacks = false,
            )
        }

        val main = blocks.firstOrNull { it.isMain }
        val deadlock = analyzeLocks(blocks)
        val summary = buildString {
            appendLine("格式：文本（Java/ANR trace）")
            appendLine("pid=$pid cmdLine=${cmdLine ?: "?"}")
            // subject 缺失要说清，不要静默
            appendLine(if (subject != null) "subject: $subject"
            else "subject: ⚠️ 本次 trace 未包含 Reason/Subject 行（ExitInfo 子集常被裁剪）")
            appendLine("线程数：${blocks.size}${if (blocks.size >= MAX_THREADS) "+（已截断）" else ""}")
            appendLine()
            appendLine("── 线程状态分布 ──")
            blocks.groupingBy { stateOf(it.attrs) }.eachCount()
                .entries.sortedByDescending { it.value }
                .forEach { (state, n) -> appendLine("  ${state.padEnd(14)} $n") }
            appendLine()
            if (main != null) {
                appendLine("── main 线程（判据核心）──")
                appendLine("  \"${main.name}\" ${main.attrs}")
                main.frames.take(20).forEach { appendLine("    $it") }
                if (main.waitingLock != null) {
                    appendLine()
                    appendLine("  ⚠️ 主线程在**等锁** ${main.waitingLock}" +
                            (main.holderTid?.let { "，持有者 tid=$it" } ?: "（holder 未知或已退出）"))
                }
                if (main.heldLocks.isNotEmpty()) {
                    appendLine("  主线程持有锁：${main.heldLocks.joinToString()}")
                }
            } else {
                appendLine("⚠️ 未找到名为 \"main\" 的线程块 —— 主线程不在被 dump 的集合里")
            }
            appendLine()
            appendLine("── 其他线程持有锁的分布（找「谁在挡路」）──")
            val lockHolders = blocks.filter { it.heldLocks.isNotEmpty() }
            if (lockHolders.isEmpty()) {
                appendLine("  无线程持有显式对象锁 → 大概率不是锁竞争，")
                appendLine("  更可能是：主线程单点耗时（IO/DB/网络同步调用）、binder 阻塞、或 GC。")
            } else {
                lockHolders.take(10).forEach { b ->
                    appendLine("  \"${b.name}\"（${b.attrs}）持有 ${b.heldLocks.size} 把锁")
                }
            }
            if (deadlock.isNotEmpty()) {
                appendLine()
                appendLine("── ⚠️ 检测到循环等待（死锁）──")
                deadlock.forEach { appendLine("  $it") }
            }
        }

        return Result(
            isBinary = false,
            summary = summary,
            threads = blocks,
            subject = subject,
            pid = pid,
            hasThreadStacks = true,
            deadlockChain = deadlock,
        )
    }

    /**
     * 从 `prio=5 tid=1 Native` 里取状态词。
     *
     * ⚠️ 注意：这是 **Java 线程状态**（`Native`/`Runnable`/`Blocked`/`Waiting`/
     *    `TimedWaiting`/`Sleeping`/`Suspended`/`Unknown`），与 `Thread.getState()` 的
     *    枚举值不同名，而且有 `Native` 这个 Java 侧没有的状态
     *    （表示**栈顶是 native 帧**，不代表"在 native 就没问题"）。
     *    —— 面试常问「trace 里的 Native 状态意味着什么」，
     *    答案不是「没问题」，而是「Java 层看不到它在等什么，去看 native 栈」。
     */
    private fun stateOf(attrs: String): String {
        val known = listOf(
            "Runnable", "Blocked", "Waiting", "TimedWaiting", "Sleeping",
            "Native", "Suspended", "Unknown", "Kernel", "Zombie",
        )
        return known.firstOrNull { Regex("""\b$it\b""").containsMatchIn(attrs) } ?: "其它"
    }

    /**
     * 锁等待图遍历：main 等 T12、T12 等 T7、T7 等 main → 环。
     *
     * 只在**存在 holder 信息**的边上走。⚠️ tid 在 trace 生成后可能已被复用/退出，
     * 所以「找不到 holder 节点」是正常情况，遍历必须能优雅终止（本实现用 visited 集）。
     */
    private fun analyzeLocks(blocks: List<ThreadBlock>): List<String> {
        // name → holderTid 的映射（trace 里线程块有 name 也有 attrs 里的 tid）
        val tidOf = blocks.associate { b -> b.name to tidOfAttr(b.attrs) }
        val byTid = tidOf.entries.filter { it.value != null }.associate { it.value!! to it.key }
        val chain = mutableListOf<String>()
        var current = blocks.firstOrNull { it.isMain } ?: return emptyList()
        val visited = mutableSetOf<String>()
        repeat(8) {
            if (!visited.add(current.name)) {
                chain += "↻ 回到 \"${current.name}\" —— **构成循环等待**"
                return chain
            }
            val holder = current.holderTid ?: return chain
            val holderName = byTid[holder] ?: run {
                chain += "→ 主线程等 tid=$holder，但该线程不在 dump 中（已退出/tid 复用）"
                return chain
            }
            chain += "\"${current.name}\" 等 \"$holderName\" (tid=$holder)"
            current = blocks.first { it.name == holderName }
        }
        return chain
    }

    private fun tidOfAttr(attrs: String): Int? =
        Regex("""tid=(\d+)""").find(attrs)?.groupValues?.get(1)?.toIntOrNull()
}
