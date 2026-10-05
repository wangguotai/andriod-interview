package com.interview.thread

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Time: 2026/10/2
 * Author: wgt
 * Description: 线程异常统一收集器
 *
 * ─── 为什么线程池的异常特别容易丢 ───
 *
 * 三层，一层比一层隐蔽：
 *
 * 1. **`submit()` 提交的任务，异常被吞进 Future**
 *    最经典的坑。`Future.get()` 不调用就永远不知道任务炸了。
 *    池本身不会报错，日志里也没有，静默失败。
 *
 * 2. **`execute()` 提交的任务，异常走 UncaughtExceptionHandler**
 *    在 Android 上，默认 handler 是 `RuntimeInit$KillApplicationHandler`，
 *    **它会直接杀掉整个进程** —— 而这不是主线程！很容易被误判成
 *    「随机崩溃」「设备兼容性问题」。
 *    若事先设了自定义 handler 把异常吞掉，则又变成静默失败。
 *
 * 3. **被拒绝的任务**（队列满 / 配额超限）
 *    如果不检查 `execute` 的返回值，任务就凭空消失了 —— 连异常都没有。
 *
 * ─── 设计要点 ───
 *
 * · **按 (泳道, 调用方, 异常类型) 聚合**，而不是逐条打日志。
 *   线上真正想知道的是「哪个 caller 在抛什么异常、抛了多少次」，
 *   而不是 10 万行堆栈。
 *
 * · **日志限流**：同一个 site 只完整打前 N 次，之后静默但继续计数。
 *   异常循环时打日志本身就是二次伤害（logcat 也有开销）。
 *
 * · **不吞异常**：默认上报后可继续抛出（见 [failFast]），
 *   由调用方决定语义。
 */
object ThreadErrorReporter {

    private const val TAG = "ThreadError"

    /** 同一 site 完整打日志的次数上限，超出只计数 */
    private const val LOG_LIMIT_PER_SITE = 5L

    /** 异常归属：泳道 + 调用方 + 异常类型 */
    data class Site(
        val lane: String,
        val caller: String,
        val type: String,
    ) {
        override fun toString(): String = "[$lane/$caller] $type"
    }

    private val counts = ConcurrentHashMap<Site, AtomicLong>()
    private val loggedCounts = ConcurrentHashMap<Site, AtomicLong>()
    private val rejectedCounts = ConcurrentHashMap<Pair<String, String>, AtomicLong>()

    private val totalErrors = AtomicLong(0)
    private val totalRejected = AtomicLong(0)

    /**
     * 可插拔的上报出口。接你自己的崩溃平台 / 监控 SDK 时在这里注入即可。
     * 签名：(site, throwable) -> Unit
     */
    @Volatile
    var sink: ((Site, Throwable?) -> Unit)? = null

    /**
     * 是否在「上报之后继续把异常抛出去」。
     *
     * - false（默认，生产）：上报后**吞掉**，任务失败不牵连进程与 worker。
     *   理由：线程池里一个任务失败是「该任务」的失败，不该
     *   变成「整个 App 崩溃」或「销毁并重建 worker」的成本。
     * - true（调试）：上报后继续抛，便于在开发期让问题立刻暴露。
     */
    @Volatile
    var failFast: Boolean = false

    /** 任务执行期抛出的异常 */
    fun onTaskError(lane: String, caller: String, e: Throwable) {
        val site = Site(lane, caller, e.javaClass.simpleName)
        counts.computeIfAbsent(site) { AtomicLong() }.incrementAndGet()
        val n = totalErrors.incrementAndGet()

        // 日志限流：同一个 site 只完整打前 LOG_LIMIT_PER_SITE 次
        val logged = loggedCounts.computeIfAbsent(site) { AtomicLong() }.incrementAndGet()
        if (logged <= LOG_LIMIT_PER_SITE) {
            Log.e(TAG, "$site 任务异常（累计第 $n 次）: ${e.message}", e)
            if (logged == LOG_LIMIT_PER_SITE) {
                Log.e(TAG, "$site 同类异常已完整记录 $LOG_LIMIT_PER_SITE 次，后续静默（计数继续）")
            }
        }

        runCatching { sink?.invoke(site, e) }
    }

    /** 任务被拒绝（队列满 / 配额超限），根本没执行 */
    fun onRejected(lane: String, caller: String, reason: String) {
        val key = lane to caller
        rejectedCounts.computeIfAbsent(key) { AtomicLong() }.incrementAndGet()
        totalRejected.incrementAndGet()
        Log.w(TAG, "[$lane/$caller] 任务被拒绝（$reason）—— 注意：任务未执行，不是失败")
        runCatching { sink?.invoke(Site(lane, caller, "Rejected:$reason"), null) }
    }

    /** worker 线程创建失败（通常是 OOM / 线程数触顶） */
    fun onWorkerCreateFailed(lane: String, caller: String, e: Throwable) {
        onTaskError(lane, caller, e)
    }

    /** 聚合快照，按次数降序 */
    fun snapshot(limit: Int = 15): List<Triple<Site, Long, Long>> =
        counts.entries
            .sortedByDescending { it.value.get() }
            .take(limit)
            .map { Triple(it.key, it.value.get(), loggedCounts[it.key]?.get() ?: 0L) }

    fun rejectedSnapshot(limit: Int = 10): List<Pair<Pair<String, String>, Long>> =
        rejectedCounts.entries
            .sortedByDescending { it.value.get() }
            .take(limit)
            .map { it.key to it.value.get() }

    fun totalErrors(): Long = totalErrors.get()
    fun totalRejected(): Long = totalRejected.get()

    fun reset() {
        counts.clear()
        loggedCounts.clear()
        rejectedCounts.clear()
        totalErrors.set(0)
        totalRejected.set(0)
    }

    /** 供 Demo 展示的格式化报告 */
    fun formatReport(): String = buildString {
        appendLine("累计任务异常：${totalErrors()} 次")
        appendLine("累计任务拒绝：${totalRejected()} 次")
        val sites = snapshot()
        if (sites.isEmpty() && totalRejected() == 0L) {
            appendLine("（暂无异常）")
        }
        if (sites.isNotEmpty()) {
            appendLine()
            appendLine("── 异常分布（泳道/调用方/类型）──")
            sites.forEach { (site, count, _) ->
                appendLine("  ×$count  $site")
            }
        }
        val rejected = rejectedSnapshot()
        if (rejected.isNotEmpty()) {
            appendLine()
            appendLine("── 被拒绝（未执行）──")
            rejected.forEach { (key, count) ->
                appendLine("  ×$count  [${key.first}/${key.second}]")
            }
        }
    }
}
