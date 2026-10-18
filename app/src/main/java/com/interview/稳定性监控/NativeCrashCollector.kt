package com.interview.稳定性监控

import java.io.File
import com.interview.nativecrash.NativeCrashHandler

/**
 * Time: 2026/10/16
 * Author: wgt
 * Description: 采集器 —— 把「native 报告落盘」这件事接进**既有**的两条落盘链路
 *
 * ══════════════════════════════════════════════════════════════════════
 * 一、为什么不是新写一套，而是复用 CrashJournal 的两条链路
 * ══════════════════════════════════════════════════════════════════════
 *
 * `CrashJournal` 已经解决了两件事，而**两件事对 native 崩溃同样成立**：
 *
 * ```
 *   A. 遗嘱（writePending / recoverPending / acknowledge）
 *      —— 解决"进程死了，内存里的数据也丢了"
 *      native 崩溃更极端：进程**一定**死，而且是主动 abort/segv，
 *      内存里的缓冲区必然原地蒸发。所以"同步落盘"对它不是优化，是唯一出路。
 *      ⚠️ 但 native 侧**自己**已经完成了落盘（信号处理器里 write+fsync），
 *         所以这里落的是"**一条待上报的事件**"（含报告文件路径），
 *         而不是报告正文 —— 正文那是一份可能有几 MB 的文件，
 *         塞进按行存的遗嘱文件会把 MAX_RECORDS=20 条的结构撑爆。
 *
 *   B. 会话标记（beginSession / endSession）
 *      —— 「上次有没有正常收尾」是 API 30 以下**唯一**能拿到
 *         "进程被静默杀掉"线索的手段（本仓库 minSdk 24，有大量 API<30 设备）。
 *      native 崩溃正是"静默杀掉"的典型形态：Java 层 handler 根本没机会跑。
 * ```
 *
 * ⇒ 结论：**不新建链路**。native 崩溃复用 A 落"一条待上报事件"，
 *   并让 B 的读数参与归因（见 [describeRecovered] 里"上次未正常收尾"的提示）。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 二、与「ExitInfo 回捞」的分工（两条链路是互补，不是重复）
 * ══════════════════════════════════════════════════════════════════════
 *
 * | | 本模块（进程内取证） | `AnrMonitor.ExitInfoCollector`（系统回捞） |
 * |---|---|---|
 * | 数据来源 | 崩溃现场自己抓的 | 系统 tombstone 的 proto trace |
 * | 可用版本 | **API 24+**（全版本） | API 30+ |
 * | 寄存器/fault_addr | ✅ 有 | ✅ 有（proto 里，但本仓库不解析 proto） |
 * | 全线程栈 | ✅ 有（尽力而为） | ✅ 有（更完整，系统侧抓） |
 * | fd 表 / 进程状态 | ✅ 有 | ❌ 无 |
 * | 能否在设备上直接读 | ✅ 文本，`cat` 就能看 | ❌ 二进制 proto |
 * | 依赖 | 我们的 handler 活着 | debuggerd 活着 + 系统愿意给 |
 *
 * **两条都要留**：
 *   · API<30 上本模块是唯一的 native 证据来源；
 *   · 而我们 handler 万一死锁（见 native 侧边界 1），还靠 ExitInfo 兜底。
 * 这正是不删旧链路、只加新链路的理由。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 三、清理策略：为什么"上传成功也不删报告"
 * ══════════════════════════════════════════════════════════════════════
 *
 * Java 崩溃有遗嘱 + 环形缓冲两份内存态副本，native 报告**只有磁盘这一份**。
 * 所以清理口径是"保留最近 N 份"，而不是"报完即删"（详见
 * `NativeCrashReport.prune` 的注释）。上限存在的唯一理由是防止
 * **崩溃循环**（启动即崩）把用户存储写满 —— 与 `CrashJournal.MAX_RECORDS` 同源。
 */
object NativeCrashCollector {

    private const val TAG = "NativeCrash"

    /** 保留最近多少份报告。超过才删最旧的。按单份 ~200KB 估，20 份 ≈ 4MB 上限 */
    private const val KEEP_REPORTS = 20

    /**
     * 冷启动回捞。**必须在后台泳道调用**（读文件 + 解析 maps 是 IO + CPU）。
     *
     * @return 本次回捞并已交给上报出口的报告数
     */
    fun recoverAndReport(ctx: android.content.Context): Int {
        val dir = NativeCrashHandler.reportDir(ctx)
        if (!dir.exists()) return 0

        val files = dir.listFiles { f -> f.name.startsWith("report-") && f.name.endsWith(".txt") }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        if (files.isEmpty()) return 0

        var reported = 0
        for (f in files) {
            val report = NativeCrashReport.parseFile(f)
            if (report == null) {
                // ⚠️ 解析失败也要**说清楚是哪一种失败**：
                //   · 空文件 / 无 NCREPORT 头 → 多半是 native 侧 open 成功但 write 全失败
                //     （磁盘满 / 权限），属于"本该有报告却没有"
                //   · 有头但格式对不上 → 版本不匹配（见 FORMAT_VERSION 的注释）
                // 两者都不该静默。但也**不能**在这里抛 —— 这是启动路径。
                android.util.Log.w(
                    TAG,
                    "报告无法解析：${f.name}（${f.length()} 字节）—— " +
                            "若是空文件/无 NCREPORT 头，说明 native 落盘失败；" +
                            "若头存在，说明格式版本不匹配（期望 ${NativeCrashReport.FORMAT_VERSION}）",
                )
                continue
            }

            val event = NativeCrashReport.toEvent(report)

            // ① 复用 Java 崩溃的出口：入环形缓冲（会被 StabilityMonitor.flush 汇总上报）
            StabilityReporter.forceReport(event)

            // ② 复用 Java 崩溃的遗嘱：落一条"待确认"记录
            //
            // ⚠️ 这里是**关键的一步**：报告文件现在已经在磁盘上了，所以这一条遗嘱
            //    不是"防止报告丢失"（它已经安全了），而是防止 **"这条事件的上报
            //    没走完，而下次启动时没人再想起它"**。
            //
            //    具体场景：本次回捞 → 入缓冲 → 进程在 flush 之前又被杀（比如
            //    连续崩溃）。没有遗嘱的话，下次启动时 `recoverAndReport` 会再解析
            //    同一个文件（因为文件还在），其实也能补上 —— 也就是说遗嘱在这里
            //    的真正价值是**记录"已回捞过"这件事**，避免同一个报告被反复入缓冲。
            //
            //    ⚠️ 诚实标注：本仓库**没有**实现"按文件去重"（那需要把文件名写进
            //       遗嘱并比对，而 CrashJournal 的 acknowledge 是整份清空、不按条删，
            //       见其第 175 行的注释）。所以在"A 次启动回捞了但没 flush 完 → 又崩"
            //       这个窗口里，同一个报告可能被入缓冲两次。
            //       上限是 1 次重入（下次启动前文件若仍被 prune 保留就会再读一遍）。
            //       上报侧按 (pid, uptime_ms) 去重即可 —— 这也是 CrashJournal
            //       既有的"平台侧按 (pid, timestamp) 去重"口径。
            CrashJournal.writePending(
                ctx = ctx,
                event = event,
                stack = buildString {
                    appendLine(event.stack)
                    appendLine("── 完整报告文件：${f.absolutePath}")
                    appendLine("   （内容见附件；符号化用 tools/native-mem-symbolize.sh）")
                },
            )

            reported++
            android.util.Log.e(TAG, "回捞到 native 崩溃报告：${f.name} → ${event.oneLine()}")
        }

        // ③ 清理（保留最近 N 份 + 删掉所有 .tmp 残留）
        val removed = NativeCrashReport.prune(dir, KEEP_REPORTS)
        if (removed > 0) {
            android.util.Log.i(TAG, "清理了 $removed 份过期的 native 报告（保留最近 $KEEP_REPORTS 份）")
        }
        return reported
    }

    /** 供总览页展示：目录里现有多少份报告、总占用 */
    fun describeStatus(ctx: android.content.Context): String = buildString {
        val dir = NativeCrashHandler.reportDir(ctx)
        val files = dir.listFiles { f -> f.name.endsWith(".txt") }.orEmpty()
        val tmps = dir.listFiles { f -> f.name.endsWith(".tmp") }.orEmpty()
        appendLine("── Native 崩溃取证 ──")
        appendLine("  安装状态：${if (NativeCrashHandler.isInstalled()) "已安装" else "未安装（可能非 arm64 设备）"}")
        NativeCrashHandler.stats()?.let { append(it.describe()) }
        appendLine("  报告目录：${dir.absolutePath}")
        appendLine("  现有报告：${files.size} 份，共 ${files.sumOf { it.length() } / 1024} KB（保留上限 $KEEP_REPORTS）")
        if (tmps.isNotEmpty()) {
            // .tmp 只会在"写到一半"时留下 —— 它出现本身就说明有一次落盘不完整
            appendLine("  ⚠️ 有 ${tmps.size} 个 .tmp 残留：说明上次落盘**不完整**" +
                    "（进程在写完前被 debuggerd 杀了）。那份现场只能在 tombstone 里找。")
        }
        appendLine("  与 ExitInfo 的分工：本通道 API 24+ 可用；ExitInfo 需 API 30+")
    }

    /** 供实验页/排查：读出最近一份报告的完整文本 */
    fun latestReportText(ctx: android.content.Context): String? {
        val dir = NativeCrashHandler.reportDir(ctx)
        return dir.listFiles { f -> f.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }
            ?.let { runCatching { it.readText() }.getOrNull() }
    }

    fun latestReportFile(ctx: android.content.Context): File? {
        val dir = NativeCrashHandler.reportDir(ctx)
        return dir.listFiles { f -> f.name.endsWith(".txt") }?.maxByOrNull { it.lastModified() }
    }
}
