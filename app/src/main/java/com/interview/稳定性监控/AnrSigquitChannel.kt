package com.interview.稳定性监控

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.interview.anrsignal.AnrSigquitHook
import java.util.ArrayDeque

/**
 * Time: 2026/10/8
 * Author: wgt
 * Description: 通道 4 的业务层 —— SIGQUIT 的「二次判定 + 上报」
 *
 * ══════════════════════════════════════════════════════════════════════
 * 本类存在的唯一理由：**收到 SIGQUIT ≠ 发生了 ANR**
 * ══════════════════════════════════════════════════════════════════════
 *
 * 把「收到信号」直接当成 ANR 上报，会立刻得到一堆假阳性，来源有两类：
 *
 * | 误报来源 | 机制 |
 * |---|---|
 * | **别的进程 ANR，你被顺带 dump** | `appNotResponding` 会 dump 一批进程（`firstPids`/`nativePids`/`extraPids`），发生 ANR 的那个只是**第一个**，不是唯一一个 |
 * | **谁都能给你发 SIGQUIT** | Java 层 `Process.sendSignal`、native 层 `kill/tgkill` 都行，调试器、热修框架、测试工具都会发 |
 *
 * 所以本类在收到信号后做两件判定（互相独立，都保留在事件 detail 里当证据）：
 *
 * ```
 *   ① si_pid == getpid()   →  信号是自己发的，**一定不是 ANR**（最便宜、几乎全版本适用）
 *   ② 自家进程处于 NOT_RESPONDING  →  AMS 已判定我们无响应（权威）
 *         依据：AMS 在弹 ANR 框前会先 makeAppNotRespondingLocked() 打这个 flag，
 *               可用 ActivityManager.getProcessesInErrorState() 读到。
 * ```
 *
 * ─── 一个必须说清的取舍：**判定不是纯函数，是一次「采样」** ───
 *
 * `getProcessesInErrorState()` 读的是「**此刻**的 flag」，而它只是「此刻的真相」。
 * 时序上有两个方向的错：
 *   · **早到**：flag 可能还没被打上（AMS 打 flag 与发 SIGQUIT 的先后在版本间不保证）
 *     → 真 ANR 被判成误报；
 *   · **晚到**：进程可能已经开始被杀（SilentAnr / 部分 ROM 的「闪退 ANR」直接 kill，
 *     **根本不会打 flag**）→ 真 ANR 被判成误报。
 *
 * 微信 Matrix 的做法是：**收到 SIGQUIT 后在 20s 窗口内轮询**这个 flag
 * （20s 是系统 dump 所有进程栈的总预算，源码里是 `remainingTime = 20 * 1000`）。
 * 本项目**不做轮询**，理由与 [AnrMonitor] 拒绝反射 `MessageQueue.mMessages` 是同一条：
 * 不做「投入产出比差且跨版本不稳」的事。代价写在这里，不藏在代码里：
 *
 * > **本通道会漏报**（后台 ANR、部分 ROM 的闪退 ANR），
 * > **权威口径仍然是通道 1（`ApplicationExitInfo`）**，本通道是它的**补充**而不是替代。
 *
 * ─── 为什么业务逻辑单独一层（而不是写在 JNI 回调里）───
 *
 * JNI 回调 [AnrSigquitHook.onSigquitFromNative] 跑在 native 的 dumper 线程上，
 * 而此刻 AMS 正在**等本进程写完 trace**。所以那里只能做「转交」，
 * 所有判定/抓栈/上报都得在 Java 侧，并且要能被 JVM 单测覆盖（[decide] 是纯函数）。
 */
object AnrSigquitChannel {

    private const val TAG = "AnrSigquit"

    // ═══════════════════════════════════════════════════════════════
    // 载荷与判定（纯逻辑，可单测）
    // ═══════════════════════════════════════════════════════════════

    /**
     * native 侧透传的信号现场。
     *
     * ⚠️ `code` **只作为线索**，不能当判据：ANR 流程里高版本用 `sigqueue`（SI_QUEUE）、
     * 某些低版本用 `kill`（SI_USER），**并不统一**（微信原文实测）。
     */
    data class Payload(
        /** 发信号者的 pid */
        val senderPid: Int,
        /** 发信号者的 uid */
        val senderUid: Int,
        /** SI_USER(0) / SI_QUEUE(-1) / 其他 */
        val code: Int,
        /** native 侧记录的「接收者 pid」，用于自校验 */
        val selfPidFromNative: Int,
    ) {
        fun describe(): String =
            "senderPid=$senderPid senderUid=$senderUid si_code=$code(${codeText()}) " +
                    "selfPid(native)=$selfPidFromNative"

        fun codeText(): String = when (code) {
            0 -> "SI_USER(kill)"
            -1 -> "SI_QUEUE(sigqueue)"
            else -> "SI_?"
        }
    }

    /**
     * 判定结果。
     *
     * @param confirmed 是否判定为「本进程发生了 ANR」
     * @param reason    判定依据（**必须能被人读懂**，不要输出自己都解释不了的结论）
     */
    data class Decision(
        val confirmed: Boolean,
        val reason: String,
        val payload: Payload,
    )

    /** 供测试替换（生产恒为 [selfNotRespondingCondition]） */
    internal var conditionSampler: (Int) -> Int? = { selfPid -> selfNotRespondingCondition(selfPid) }

    /** 测试还原用：暴露真实的系统态读取（否则 `@After` 里无法把 sampler 复原） */
    internal fun systemConditionOf(pid: Int): Int? = selfNotRespondingCondition(pid)

    /**
     * 等待确认的轮询参数 —— **由真机实测定出来的，不是拍脑袋**。
     *
     * ⚠️ 实测（Redmi K40 / Android 12）暴露了一个致命顺序问题：
     *    一次真 ANR 收到 4 条 SIGQUIT，其中**前 3 条**判定为「非 ANR」，
     *    直到第 4 条（距第 1 条约 **12 秒**）才第一次读到 `NOT_RESPONDING`。
     *
     *    原因在 AMS 的流程里：`appNotResponding()` 是
     *      **先 dumpStackTraces（发 SIGQUIT）→ 再 makeAppNotRespondingLocked（打 flag）→ 弹框**。
     *    也就是说「系统发信号」**先于**「flag 可读」 —— 这与直觉相反，
     *    而它意味着：**只采样一次的实现，会在每一次真 ANR 上都漏掉最关键的信号**
     *    （恰恰是系统 dump 我们的那一条）。若某 ROM 只发一次 SIGQUIT，
     *    单次采样就是 100% 漏报。
     *
     *    所以必须等 flag：这里给出**有界**轮询窗口。
     *    窗口取 20s 的依据是源码常量而非猜测 ——
     *    `ActivityManagerService.dumpStackTraces()` 里 `long remainingTime = 20 * 1000`
     *    （dump 所有进程栈的总预算），flag 一定在这个窗口内落上。
     *
     * ─── 为什么这次「轮询」可接受，而拒绝反射 `MessageQueue.mMessages` 不是 ───
     *   · 读的是**公开 API**（`getProcessesInErrorState`），不依赖隐藏 API/反射；
     *   · 轮询跑在 **native dumper 线程**上，不占主线程、不参与锁竞争；
     *   · 有界（20s 上限，且同一轮信号只轮一次，见 [POLL_CACHE_MS]）。
     *
     * ⚠️ 代价必须说清：**本通道的上报会比事件本身晚最多 20 秒**。
     *    这是定位决定的 —— 通道 4 负责「**权威确认**」，
     *    「及时」由通道 2 哨兵负责（它是估算值，见 [AnrMonitor] 的诚实边界）。
     */
    const val POLL_WINDOW_MS = 20_000L

    /** 轮询间隔：250ms → 20s 内最多 80 次 binder 调用，可忽略 */
    const val POLL_INTERVAL_MS = 250L

    /**
     * 「已确认 ANR」的缓存窗口：窗口内后续信号直接复用结论，不再等。
     * （实测一次 ANR 来 4 条；每条都等满 20s 会让 dumper 线程被同一个事件占住 80s。）
     */
    const val POLL_CACHE_MS = 20_000L

    /**
     * 解析 native 的载荷 `"senderPid:uid:code:selfPid"`。
     *
     * 段数不对或数字非法一律返回 null —— **宁可显示「拿到但解析不了」，
     * 也不要显示一个瞎猜的现场**（同 [AnrSigquitHook.Stats.parse] 的态度）。
     */
    fun parsePayload(raw: String?): Payload? {
        val parts = raw?.trim()?.split(':') ?: return null
        if (parts.size != 4) return null
        val nums = parts.map { it.trim().toIntOrNull() ?: return null }
        return Payload(
            senderPid = nums[0],
            senderUid = nums[1],
            code = nums[2],
            selfPidFromNative = nums[3],
        )
    }

    /**
     * 二次判定（**纯函数**，JVM 单测直接覆盖）。
     *
     * 判据顺序即「可靠性」顺序：
     *   ① 自报 → 直接否（不需要查系统）
     *   ② 自家 NOT_RESPONDING → 确认
     *   ③ 都不满足 → 不确认（**记录**但不当作 ANR 上报）
     */
    fun decide(payload: Payload, selfPid: Int, sampledCondition: Int?): Decision {
        val selfSent = payload.senderPid == selfPid
        return when {
            selfSent -> Decision(
                confirmed = false,
                reason = "发信号者就是本进程（pid=$selfPid）—— 自己给自己发 SIGQUIT " +
                        "一定不是 ANR（调试器/热修/测试工具的常见行为），记录但不计入 ANR。",
                payload = payload,
            )

            sampledCondition == PROCESS_ERROR_NOT_RESPONDING -> Decision(
                confirmed = true,
                reason = "本进程已被 AMS 标记为 NOT_RESPONDING（getProcessesInErrorState 读到）。" +
                        "这是系统自己的判定，不是我们的推断。",
                payload = payload,
            )

            sampledCondition != null -> Decision(
                confirmed = false,
                reason = "本进程有错误状态，但不是 NOT_RESPONDING（condition=$sampledCondition）—— " +
                        "可能是崩溃等其它错误态，不计入 ANR。",
                payload = payload,
            )

            else -> Decision(
                confirmed = false,
                reason = "**采样那一刻**本进程没有 NOT_RESPONDING 标记。可能是：\n" +
                        "  · 别的进程 ANR，本进程只是被顺带 dump 的那一批之一；\n" +
                        "  · 后台 ANR（SilentAnr）→ AMS 直接 kill、**不打 flag**；\n" +
                        "  · 部分 ROM 的前台 ANR 也直接杀（闪退），同样没有 flag；\n" +
                        "  · AMS 打 flag 与发 SIGQUIT 存在时序竞争，flag 还没落上。\n" +
                        "⚠️ 后两种是**漏报**，本通道的已知缺口（见类注释）。",
                payload = payload,
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 入口：native → 判定 → 上报
    // ═══════════════════════════════════════════════════════════════

    /** 进程内审计流水（有界）。误报**不进** [StabilityReporter]，但必须看得见 */
    private const val AUDIT_CAPACITY = 30
    private val audit = ArrayDeque<String>()

    /**
     * 边沿去重的载体：上次**确认并上报** ANR 的时刻。
     *
     * ⚠️ 窗口常量是 [POLL_CACHE_MS]（20s）—— 它的用途有两个，且都来自真机实测：
     *   ① 一次 ANR 会来多条 SIGQUIT（本机 4 条），窗口内直接复用结论、不重复上报；
     *   ② 已被确认的 ANR 不必再等 flag（见 POLL_WINDOW_MS 的注释）。
     */
    @Volatile
    private var lastReportedAt = 0L

    /** 被边沿去重丢弃的次数（实验页读数：证明去重真的在生效，而不是没收到） */
    @Volatile
    private var dedupDropped = 0

    /** 供实验页/单测观察每次判定（可选） */
    @Volatile
    var decisionSink: ((Decision) -> Unit)? = null

    /**
     * native 回调入口（经 [AnrSigquitHook.onSigquitFromNative] 转来）。
     *
     * 执行在 native dumper 线程上，因此：
     *   · 不做任何阻塞 IO；
     *   · 抓主线程栈用 `runCatching`（可能撞 ART mutex，见 [AnrMonitor.dumpMainThread]）。
     */
    fun onSigquit(rawPayload: String) {
        val payload = parsePayload(rawPayload)
        if (payload == null) {
            Log.w(TAG, "载荷无法解析：$rawPayload")
            record("⚠️ 载荷无法解析：$rawPayload")
            return
        }

        val selfPid = Process.myPid()
        val now = System.currentTimeMillis()

        // 自报信号（调试器/热修/测试工具）**不进复用窗口**：
        // 它可能正好落在上次真 ANR 的窗口内，若被"复用"掉就会把一条真实的
        // 误报证据藏起来。判定纪律是「先看发送者」，这一步必须最先做。
        if (payload.senderPid == selfPid) {
            val d = decide(payload, selfPid, sampledCondition = null)
            decisionSink?.invoke(d)
            Log.w(TAG, "收到 SIGQUIT：${payload.describe()}\n判定=非 ANR：${d.reason}")
            record("收到 SIGQUIT（自报）\n  判定=非 ANR（senderPid == 自己）")
            return
        }

        // ── ① 已确认过的 ANR：窗口内直接复用结论（不重复等、也不重复报）──
        val sinceConfirm = now - lastReportedAt
        if (lastReportedAt != 0L && sinceConfirm < POLL_CACHE_MS) {
            dedupDropped++
            Log.w(TAG, "边沿复用：距上次确认仅 ${sinceConfirm}ms（< ${POLL_CACHE_MS}ms），" +
                    "判定为同一次 ANR 的后续信号，不重复上报（累计复用 $dedupDropped 条）")
            record("收到 SIGQUIT（sender=${payload.senderPid}）\n" +
                    "  同一次 ANR 的后续信号 → 复用结论，不重复上报")
            return
        }

        // ── ② 等 flag：见 POLL_WINDOW_MS 的注释（「先发信号、后打 flag」是实测事实）──
        // ⚠️ 双重上界（轮数 + 墙上时间）：只靠 deadline 的话，
        //    在 `SystemClock` 不可用的环境（宿主 JVM 单测里 uptimeMillis() 恒为 0）
        //    会让 deadline 永远追不上 → **死循环**。轮数上界让它在任何环境下都有界。
        val maxPolls = (POLL_WINDOW_MS / POLL_INTERVAL_MS).toInt()
        val armDeadline = SystemClock.uptimeMillis() + POLL_WINDOW_MS
        var sampled = runCatching { conditionSampler(selfPid) }.getOrNull()
        var polls = 0
        while (sampled != PROCESS_ERROR_NOT_RESPONDING &&
            polls < maxPolls &&
            SystemClock.uptimeMillis() < armDeadline
        ) {
            try {
                Thread.sleep(POLL_INTERVAL_MS)   // 本线程是 native dumper 的专职线程，睡在这里是设计
            } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
            polls++
            sampled = runCatching { conditionSampler(selfPid) }.getOrNull()
        }

        val decision = decide(payload, selfPid, sampled)
        decisionSink?.invoke(decision)

        val head = "收到 SIGQUIT：${payload.describe()}"
        if (!decision.confirmed) {
            // ⚠️ 误报**不发给 StabilityReporter**：Kind.ANR 会被平台当作真实 ANR 统计，
            //    污染口径比少报更糟（这是本项目反复出现的同一条原则：
            //    「不要用监控手段改变被监控对象」）。
            //    ⚠️ 但这里**不能**对「等满 20s 仍无 flag」下"非 ANR"的结论就完事 ——
            //       SilentAnr / 部分 ROM 的闪退 ANR 本来就不打 flag（见 decide 的说明），
            //       那是本通道的已知缺口，已在 reason 里写明。
            Log.w(TAG, "$head\n判定=非 ANR（轮询 $polls 次后仍无确认）：${decision.reason}")
            record("$head\n  判定=非 ANR（等了 ${polls * POLL_INTERVAL_MS}ms 仍未确认）\n" +
                    "  ${decision.reason.lines().joinToString("\n  ")}")
            return
        }

        val stats = AnrSigquitHook.stats()
        lastReportedAt = now
        Log.e(TAG, "$head\n判定=ANR（轮询 $polls 次后确认）：${decision.reason}")

        // 通道 4 的**增量价值**在这里：当次进程内立刻上报，且自带堆栈。
        // 通道 1（ExitInfo）要等到下次启动才能回捞，且 self ANR 场景没有全量 trace。
        StabilityReporter.report(
            StabilityReporter.Event(
                kind = StabilityReporter.Kind.ANR,
                timestamp = now,
                name = "sigquit:系统发来 SIGQUIT 且本进程 NOT_RESPONDING",
                // ⚠️ 阻塞时长**未知**：SIGQUIT 只告诉你「系统在等」，不告诉你等了多久。
                //    要时长请用通道 2 哨兵（它是估算值，见 AnrMonitor 的诚实边界）。
                //    这里填 0 而不是硬填 5000，"不知道"就如实写"不知道"。
                valueMs = 0,
                threadName = "main",
                isMainThread = true,
                isForeground = StabilityReporter.isForeground(),
                pid = selfPid,
                detail = AnrMonitor.withBlockSite(buildString {
                    appendLine("── 判定依据（系统口径，非我们的推断）──")
                    appendLine(decision.reason)
                    appendLine("确认耗时：轮询 $polls 次 / 约 ${polls * POLL_INTERVAL_MS}ms")
                    appendLine("  （⚠️ 这是实测事实：系统**先发 SIGQUIT、后打 NOT_RESPONDING flag**，")
                    appendLine("    所以确认必然滞后；上报延迟就来自这里）")
                    appendLine()
                    appendLine("── 信号现场 ──")
                    appendLine(payload.describe())
                    appendLine()
                    appendLine("── 通道 4 状态 ──")
                    appendLine("handler 命中 ${stats?.hit ?: "?"} 次，" +
                            "转发 SignalCatcher 成功 ${stats?.rethrow ?: "?"} / " +
                            "失败 ${stats?.rethrowFail ?: "?"}")
                    appendLine("已复用/去重丢弃 ${dedupDropped} 条同一次 ANR 的后续信号")
                    appendLine("⚠️ 阻塞时长**未知**：SIGQUIT 只表示「系统在等你」，不含时长。")
                    appendLine("   （时长请看通道 2 哨兵的估算值，两者判据不同）")
                    appendLine()
                    appendLine(AnrMonitor.dumpMainThread())
                }),
            )
        )
        // 卡顿边沿 → 让采样器切到加密采样，这次卡顿期间的热点会被采得更细
        runCatching { MainThreadSampler.noteStuck() }
        record("$head\n  判定=**ANR**（已上报，带主线程堆栈）")
    }

    private fun record(line: String) {
        synchronized(audit) {
            audit.addLast("[${internalTime(System.currentTimeMillis())}] $line")
            while (audit.size > AUDIT_CAPACITY) audit.removeFirst()
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 安装 / 读数（与实验页的接缝）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 安装通道 4。
     *
     * ⚠️ **刻意不放进 `StabilityMonitor.installOnCreate`**：
     * 这是本仓库里**唯一一处「去抢系统既有行为」**的探针（其它通道都是旁听/回捞）。
     * 默认不装，只在实验页手动装 —— 让「动了平台行为」这件事是显式、可审计、可撤销的。
     */
    fun install(): Boolean = AnrSigquitHook.install()

    /** 实验页按钮用：安装 + 立即回读状态（把「装没装上、转发通不通」一次说清） */
    fun describeInstall(): String {
        val ok = install()
        return buildString {
            appendLine(if (ok) "通道 4 已安装" else "通道 4 安装失败（已优雅降级，其它通道不受影响）")
            appendLine()
            appendLine(describe())
            appendLine("── 下一步怎么验 ──")
            appendLine("  ① 点「⚠真ANR(15S)」→ 本页读数「handler 命中」应 +1；")
            appendLine("     且 `adb shell dumpsys window | grep lastanr` 应有记录、")
            appendLine("     `adb shell ls /data/anr`（需 root）或下次 ExitInfo 回捞应有完整 trace")
            appendLine("     —— 后者证明「转发 SignalCatcher」生效。")
            appendLine("  ② 用 **App 自己的 uid** 发信号（adb 的 shell uid 会 Operation not permitted）：")
            appendLine("       adb shell run-as com.example.myapplication /system/bin/kill -3 <pid>")
            appendLine("     → handler 命中 +1，且此刻 AMS 会真的来 dump；")
            appendLine("       采样点在 NOT_RESPONDING 打上之前 → 判为**非 ANR**（误报被过滤）")
            appendLine("       若正处于 NOT_RESPONDING 期间（如正在跑真 ANR 演示）→ 判为 ANR")
            appendLine()
            appendLine("── 实测已确认（Redmi K40 / Android 12）──")
            appendLine("  · handler 确实收到系统发的 SIGQUIT（sender=system_server(1901), SI_QUEUE）")
            appendLine("  · 转发生效：logcat 有 Signal Catcher 的 reacting to signal 3 与")
            appendLine("    Wrote stack traces to tombstoned，且**没有 20s 停顿**")
            appendLine("  · 一次真 ANR 会来 **4 条** SIGQUIT → 已按 ${POLL_CACHE_MS}ms 窗口复用结论，不重复上报")
            appendLine("  · ⚠️ 系统**先发 SIGQUIT、后打 NOT_RESPONDING flag**（实测：第 1 条信号与")
            appendLine("    确认之间相差约 12s）⇒ 本通道的上报天生滞后，且必须轮询等 flag")
            appendLine("    （窗口 ${POLL_WINDOW_MS}ms，依据是 AMS 的 dumpStackTraces 20s 预算）")
        }
    }

    fun uninstall() = AnrSigquitHook.uninstall()

    /** 总览用的一行状态（避免 `formatOverview` 依赖 [describe] 的完整正文） */
    fun shortStatus(): String {
        val s = AnrSigquitHook.stats() ?: return "native 不可用（未加载/已卸载）"
        return if (!s.active) "未安装"
        else "已装（命中 ${s.hit} 次，转发 成功${s.rethrow}/失败${s.rethrowFail}）"
    }

    /** 实验页用的可读读数（读数 + 进程内审计流水） */
    fun describe(): String = buildString {
        appendLine("══════ 通道 4：SIGQUIT 拦截 ══════")
        val stats = AnrSigquitHook.stats()
        if (stats == null) {
            appendLine("native 侧不可用（库未加载 / 已卸载 / 读数解析失败）")
            appendLine("⚠️ 本机 abiFilters 只保留 arm64-v8a → 非 arm64 设备上本通道必然不可用，")
            appendLine("   此时通道 1/2/3 不受任何影响（降级是设计的一部分）。")
        } else {
            appendLine(stats.describe())
        }
        appendLine()
        appendLine("── 判定纪律 ──")
        appendLine("  确认 ANR = 本进程被 AMS 标记 NOT_RESPONDING（系统口径）")
        appendLine("  自报信号（senderPid == 自己）一律否掉")
        appendLine("  判为误报的**不会**进上报管道（否则会污染平台的 ANR 率）")
        appendLine("  真 ANR 的后续信号按 ${POLL_CACHE_MS}ms 窗口复用结论（不重复上报）")
        appendLine("  等 flag 的有界窗口：${POLL_WINDOW_MS}ms（实测「先发信号后打 flag」，所以必须等）")
        appendLine("  已去重丢弃：$dedupDropped 条（只被 native dumper 线程写，读一次即可）")
        appendLine()
        appendLine("── 进程内审计流水（最近 ${audit.size} 条）──")
        synchronized(audit) {
            if (audit.isEmpty()) {
                appendLine("  （空）")
            } else {
                audit.toList().asReversed().forEach { appendLine("  $it") }
            }
        }
    }

    fun resetAudit() {
        synchronized(audit) { audit.clear() }
        lastReportedAt = 0L
        dedupDropped = 0
    }

    // ═══════════════════════════════════════════════════════════════
    // 与系统的接缝（唯一依赖 Android 框架的地方，便于单测隔离）
    // ═══════════════════════════════════════════════════════════════

    /** `ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING` = 2（公开常量） */
    internal const val PROCESS_ERROR_NOT_RESPONDING =
        ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING

    /**
     * 读「本进程此刻的错误状态」。
     *
     * 返回 null 表示**没有找到本进程的错误态**（不是「读失败」与「正常」的区分 ——
     * 两者在这里是同一件事：没有 NOT_RESPONDING 就是没有）。
     *
     * ⚠️ 这是公开 API，不需要反射、不需要权限。它与 ANR 弹框同源：
     *    `makeAppNotRespondingLocked()` 打的就是这个 flag。
     */
    private fun selfNotRespondingCondition(selfPid: Int): Int? = runCatching {
        // 同包，直接取 StabilityMonitor 持有的 applicationContext
        // （由 installOnCreate 赋值；实验页与 Application 都会走到那里）
        val ctx = StabilityMonitor.context() ?: return null
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        am.getProcessesInErrorState()?.firstOrNull { it.pid == selfPid }?.condition
    }.getOrNull()
}
