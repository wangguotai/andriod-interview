package com.interview.稳定性监控

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Time: 2026/10/7
 * Author: wgt
 * Description: 崩溃落盘日志（last-will / journal）—— 解决「进程死了，内存里的数据也没了」这个硬问题
 *
 * ─── 为什么必须落盘，而不是只靠上报接口 ───
 *
 * 上报是**异步**的：崩溃 handler 里最多做到「把事件塞进队列」，真正的序列化与
 * 网络发送在后台线程。而主线程崩溃的默认行为是**打完日志就 killProcess** ——
 * 后台线程大概率来不及跑完。实测过的对照（见 `thread/README.md` 的性量表）：
 * 装了 `UncaughtExceptionHandler` 之后 logcat 里**照样有 FATAL EXCEPTION**，
 * 但进程死活取决于异常是否逃逸出 handler。
 *
 * 也就是说：**「上报了」这件事本身不可信**。所以采用「遗嘱」语义：
 *
 * ```
 *   崩溃 handler 第一步：写一条记录到磁盘（fsync），标记 done=false
 *   上报完成且进程确定存活：把记录标记 done=true
 *   下次启动：凡是 done=false 的记录 → 上次没走完 → 补报
 * ```
 *
 * 这样「补报」不需要任何网络/平台的配合，也不需要假设上报一定成功。
 *
 * ─── 两个能力 ───
 *
 * **A. 崩溃遗嘱（[writePending] / [recoverPending]）**
 *    卡在「上报完成之前进程就没了」的窗口里的事件，下次启动补报。
 *
 * **B. 非正常退出探测（[beginSession] / [endSession] / [previousSessionWasClean]）**
 *    「上次会话有没有正常收尾」。这是**唯一**能在 API 30 以下（没有
 *    ApplicationExitInfo）拿到「进程被系统静默杀掉」线索的手段，也是
 *    「ANR 被系统杀」与「用户主动杀」的分界线之一。
 *    ⚠️ 它只能给出「不干净」这个结论，**给不出原因** —— 原因要靠
 *    ApplicationExitInfo / logcat / 平台侧数据来补。别把它当因果证据。
 *
 * ─── 工程约束（都会在线上的真实机型上咬人）───
 *
 * 1. **崩溃路径上不能有任何会抛的东西**：本类所有公开方法都吞异常。
 *    在 handler 里抛异常 = 崩溃处理变崩溃源。
 * 2. **必须 fsync**：只 write 不 sync，进程被 kill 时数据还在 page cache 里，
 *    落盘日志就失去了意义（而崩溃恰恰总是伴随进程被杀）。
 * 3. **文件必须有界**：崩溃循环（启动即崩）时，日志会无限增长。
 *    这里用固定行数上限 + 超限截断。
 * 4. **必须线程安全**：可能同时有多个线程在崩（主线程 + 后台）。
 */
object CrashJournal {

    private const val TAG = "CrashJournal"

    /** 单条记录上限（崩溃循环保护）。超出后丢弃最旧的 */
    private const val MAX_RECORDS = 20

    private const val FILE_PENDING = "crash-journal.pending"
    private const val FILE_SESSION = "session.state"

    private val lock = Any()
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private fun dir(ctx: android.content.Context): File =
        File(ctx.filesDir, "stability").apply { if (!exists()) mkdirs() }

    // ─────────────────────────────────────────────
    // A. 崩溃遗嘱
    // ─────────────────────────────────────────────

    /** 一条待确认的崩溃记录 */
    data class Record(
        val timestamp: Long,
        val kind: String,
        val name: String,
        val threadName: String,
        val isMainThread: Boolean,
        val isForeground: Boolean,
        val pid: Int,
        val detail: String,
        val stack: String,
    ) {
        /** 序列化成单行（tab 分隔），换行在写入前被转义 —— 见 [escape] */
        fun serialize(): String = listOf(
            timestamp.toString(), kind, name, escape(threadName),
            if (isMainThread) "1" else "0",
            if (isForeground) "1" else "0",
            pid.toString(), escape(detail.take(1024)), escape(stack.take(8192)),
        ).joinToString("\t")

        fun describe(): String =
            "${fmt.format(Date(timestamp))} [$kind] $name pid=$pid main=$isMainThread thread=$threadName\n" +
                    "  $detail\n" +
                    stack.lineSequence().take(12).joinToString("\n") { "    $it" }
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "").replace("\n", "\\n")

    private fun unescape(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            when (val c = s[i]) {
                '\\' -> {
                    when (s.getOrNull(i + 1)) {
                        't' -> { append('\t'); i++ }
                        'n' -> { append('\n'); i++ }
                        '\\' -> { append('\\'); i++ }
                        else -> append(c)
                    }
                }
                else -> append(c)
            }
            i++
        }
    }

    /**
     * 写入一条「未确认」记录。**崩溃 handler 的第一动作**。
     *
     * @return true 表示已落盘。false 时不要重试、不要抛 —— 崩溃路径上必须继续往下走。
     */
    fun writePending(ctx: android.content.Context, event: StabilityReporter.Event, stack: String): Boolean =
        runCatching {
            synchronized(lock) {
                val f = File(dir(ctx), FILE_PENDING)
                val record = Record(
                    timestamp = event.timestamp,
                    kind = event.kind.name,
                    name = event.name,
                    threadName = event.threadName,
                    isMainThread = event.isMainThread,
                    isForeground = event.isForeground,
                    pid = event.pid,
                    detail = event.detail,
                    stack = stack,
                )
                // 读旧 + 追加 + 截断 + 一次覆盖写。
                // 崩溃时刻追求最少 syscall：一次 O_TRUNC 写 + 一次 fsync，不用 RandomAccessFile 追加。
                val lines = (readLines(f) + record.serialize()).takeLast(MAX_RECORDS)
                FileOutputStream(f, false).use { out ->
                    out.write(lines.joinToString("\n").toByteArray())
                    out.flush()
                    // ⚠️ fsync 是这条设计成立的前提：不 sync 的话数据还在 page cache，
                    //    进程被杀时和没写一样。
                    out.fd.sync()
                }
                true
            }
        }.getOrElse {
            Log.w(TAG, "写遗嘱失败（已忽略，不影响崩溃处理）", it)
            false
        }

    /**
     * 上报成功后「销账」：整份清空。
     *
     * 为什么不按条删：崩溃 handler 里做精细删除的成本 > 收益，而崩溃本身是稀有事件。
     * 语义上「这一批已经报出去了」就足够 —— 代价是同时刻并发崩溃可能重复报一次，
     * 平台侧按 (pid, timestamp) 去重即可。
     */
    fun acknowledge(ctx: android.content.Context) {
        runCatching { synchronized(lock) { File(dir(ctx), FILE_PENDING).delete() } }
    }

    /**
     * 下次启动时读出「上次没走完」的记录。
     *
     * ⚠️ 调用后**记录仍在磁盘上**，必须先尝试上报、上报成功再 [acknowledge]，
     *    否则「启动即崩」会把遗嘱自己吃掉，永远看不到。
     */
    fun recoverPending(ctx: android.content.Context): List<Record> = runCatching {
        synchronized(lock) { readLines(File(dir(ctx), FILE_PENDING)).mapNotNull { parse(it) } }
    }.getOrElse {
        Log.w(TAG, "读遗嘱失败", it)
        emptyList()
    }

    private fun readLines(f: File): List<String> = runCatching {
        if (!f.exists()) emptyList() else f.readLines().filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun parse(line: String): Record? = runCatching {
        val p = line.split("\t")
        Record(
            timestamp = p[0].toLong(),
            kind = p[1],
            name = p[2],
            threadName = unescape(p[3]),
            isMainThread = p[4] == "1",
            isForeground = p[5] == "1",
            pid = p[6].toInt(),
            detail = unescape(p[7]),
            stack = unescape(p[8]),
        )
    }.getOrNull()

    // ─────────────────────────────────────────────
    // B. 会话标记（非正常退出探测）
    // ─────────────────────────────────────────────

    private const val STATE_RUNNING = "running"

    /**
     * 进程启动时调用一次：先读上次状态（返回给调用方判断），再把自己标成 running。
     *
     * 顺序很重要 —— **先读后写**。反过来就永远读到自己的 running。
     */
    fun beginSession(ctx: android.content.Context): SessionInfo = runCatching {
        val f = File(dir(ctx), FILE_SESSION)
        val previous = f.takeIf { it.exists() }?.readText()?.trim()
        val clean = previous == null || previous == STATE_CLEAN
        val wasRunning = previous == STATE_RUNNING
        val lines = f.takeIf { it.exists() }?.readLines().orEmpty()
        f.writeText(
            listOf(
                STATE_RUNNING,
                System.currentTimeMillis().toString(),
                lines.getOrNull(1) ?: "0", // 累计不干净退出次数
            ).joinToString("\n")
        )
        SessionInfo(previousSessionClean = clean, previousWasRunning = wasRunning)
    }.getOrElse {
        Log.w(TAG, "会话标记失败", it)
        SessionInfo(previousSessionClean = true, previousWasRunning = false)
    }

    /**
     * 正常退出时调用（`Application.onTerminate` 只在模拟器/极端情况下触发，
     * 所以这里的「正常」实际主要靠进程被回收前的 `onTrimMemory` 或用户主动退出路径）。
     *
     * ⚠️ **诚实边界**：Android 上应用几乎没有「一定会被调用的退出钩子」。所以
     *    「上次不干净」这个信号**偏高（误报多）**：用户从最近任务划掉、系统低内存
     *    回收、手动停止，都会被算成不干净。它的用法是**看趋势**（同一版本前后
     *    对比），不是拿单次结论去定位问题。
     */
    fun endSession(ctx: android.content.Context) {
        runCatching {
            synchronized(lock) {
                val f = File(dir(ctx), FILE_SESSION)
                val lines = if (f.exists()) f.readLines() else emptyList()
                val startedAt = lines.getOrNull(1) ?: "0"
                val unclean = lines.getOrNull(2)?.toIntOrNull() ?: 0
                f.writeText(listOf(STATE_CLEAN, startedAt, unclean.toString()).joinToString("\n"))
            }
        }
    }

    private const val STATE_CLEAN = "clean"

    data class SessionInfo(
        /** 上次会话是否正常收尾。false = 进程被静默杀掉（native 崩 / ANR 被杀 / 低内存） */
        val previousSessionClean: Boolean,
        /** 上次退出时状态仍是 running —— 即「有 begin 没有 end」 */
        val previousWasRunning: Boolean,
    ) {
        fun describe(): String = when {
            !previousWasRunning -> "上次没有残留 running 标记（可能被系统清理过状态文件，或首次安装）"
            previousSessionClean -> "上次正常收尾"
            else -> "⚠️ 上次**未正常收尾** —— 进程在标记 running 期间消失：" +
                    "native 崩溃 / ANR 被系统杀 / 低内存回收 / 用户划掉。"
        }
    }
}
