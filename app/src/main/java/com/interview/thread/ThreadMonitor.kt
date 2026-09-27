package com.interview.thread

import android.util.Log

/**
 * Time: 2026/9/27
 * Author: wgt
 * Description: 第 2 步「监控层」—— Java 层线程采样与快照
 *
 * 为什么先做这个：没有测量就上插桩，等于不知道自己在优化什么。
 *
 * 能力：
 * 1. 快照：当前全部线程 + 状态 + 堆栈
 * 2. 命名率统计：默认命名（Thread-N / pool-N-thread-M）的占比 —— 这个比例就是「不可溯源」的线程比例
 * 3. 按前缀聚类：找出哪个 SDK / 模块造的线程最多
 */
object ThreadMonitor {

    private const val TAG = "ThreadMonitor"

    /** 默认命名模式，命中即视为「不可溯源」：Thread-7、pool-1-thread-2、pool-3-thread-1 */
    private val DEFAULT_NAMES = Regex("""^(Thread-\d+|pool-\d+-thread-\d+)$""")

    /** 平台 / 系统线程前缀，统计业务线程时应排除 */
    private val SYSTEM_PREFIXES = listOf(
        "main", "RenderThread", "Signal Catcher", "Jit thread pool",
        "HeapTaskDaemon", "FinalizerDaemon", "FinalizerWatchdogDaemon",
        "ReferenceQueueDaemon", "Binder:", "Profile Saver", "perfetto",
        "adb-", "JDWP", "ConnectivityThread", "hwuiTask", "binder:",
    )

    data class ThreadInfo(
        val name: String,
        val state: Thread.State,
        val isDaemon: Boolean,
        val priority: Int,
        val stackTop: String,
    ) {
        val isDefaultNamed: Boolean get() = DEFAULT_NAMES.matches(name)
    }

    data class Snapshot(
        val total: Int,
        val business: Int,
        val defaultNamed: Int,
        val threads: List<ThreadInfo>,
    ) {
        val defaultNamedRatio: Float
            get() = if (business == 0) 0f else defaultNamed.toFloat() / business

        fun formatTop(limit: Int = 20): String = buildString {
            appendLine("────── 线程快照 ──────")
            appendLine("总线程数：$total（业务线程：$business）")
            appendLine("默认命名（不可溯源）：$defaultNamed 个，占比 ${"%.1f".format(defaultNamedRatio * 100)}%")
            appendLine("按名称聚类：")
            groupByPrefix().entries.sortedByDescending { it.value }.take(15).forEach { (prefix, count) ->
                appendLine("  $prefix  ×$count")
            }
            appendLine("线程明细（前 $limit 条）：")
            threads.take(limit).forEach { t ->
                val flag = if (t.isDefaultNamed) "⚠️不可溯源" else "  "
                appendLine("  $flag [${t.state}] ${t.name}  pri=${t.priority} daemon=${t.isDaemon}")
                if (t.stackTop.isNotEmpty()) appendLine("        at ${t.stackTop}")
            }
        }

        /** 按前缀聚类：app-io / sdk-pay / leaked-pool 等，用于定位「谁造的线程」 */
        fun groupByPrefix(): Map<String, Int> =
            threads.groupingBy { t ->
                when {
                    t.name.contains('-') -> t.name.substringBeforeLast('-')
                    else -> t.name
                }
            }.eachCount()
    }

    /**
     * 采集快照。
     *
     * 注意：Thread.getAllStackTraces() 本身有一定开销（要暂停线程取栈或读取安全点），
     * 线上不要高频调用，建议：调试时手动触发 / 线上低采样率（如每 30s 一次）或按需。
     */
    fun snapshot(): Snapshot {
        val all = Thread.getAllStackTraces()
        val infos = all.map { (thread, stack) ->
            ThreadInfo(
                name = thread.name,
                state = thread.state,
                isDaemon = thread.isDaemon,
                priority = thread.priority,
                stackTop = stack.firstOrNull { it.className !in SKIP_FRAMES }
                    ?.let { "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                    ?: "",
            )
        }
        val business = infos.filterNot { info -> SYSTEM_PREFIXES.any { info.name.startsWith(it) } }
        return Snapshot(
            total = infos.size,
            business = business.size,
            defaultNamed = business.count { it.isDefaultNamed },
            threads = infos.sortedBy { it.name },
        )
    }

    /** 打印快照到 logcat */
    fun dump(tag: String = TAG) {
        val snap = snapshot()
        snap.formatTop().lines().forEach { Log.d(tag, it) }
        Log.i(tag, "线程总数=${snap.total} 业务=${snap.business} 不可溯源=${snap.defaultNamed}")
    }

    private val SKIP_FRAMES = setOf(
        "java.lang.Thread", "java.lang.Object", "dalvik.system.VMStack",
    )
}
