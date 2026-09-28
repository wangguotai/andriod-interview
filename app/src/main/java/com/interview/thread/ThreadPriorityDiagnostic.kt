package com.interview.thread

import android.os.Process
import android.util.Log
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 诊断：`Thread.setPriority` 与 `Process.setThreadPriority` 的能力边界
 *
 * 目的：判断「只用 this.priority = ...」是否足够，即二者能否互相替代。
 *
 * 观测内核态地面真相：
 * - nice   : /proc/self/task/<tid>/stat 第 19 字段
 * - cgroup : /proc/self/task/<tid>/cgroup 原始内容（含控制器名）
 */
object ThreadPriorityDiagnostic {

    private const val TAG = "ThreadPriority"

    data class Result(val label: String, val javaPriority: Int, val nice: Int?, val cgroup: String)

    private fun readNice(tid: Int): Int? = runCatching {
        val stat = File("/proc/self/task/$tid/stat").readText()
        stat.substringAfterLast(") ").trim().split(" ")[16].toInt()
    }.getOrNull()

    /** 原始 cgroup 内容：控制器名 + 路径，用于判断调度分组是否变化 */
    private fun readCgroupRaw(tid: Int): String = runCatching {
        File("/proc/self/task/$tid/cgroup").readLines().joinToString("; ") { it.trim() }
    }.getOrDefault("读取失败")

    fun run(): String {
        val sb = StringBuilder()

        sb.appendLine("══ 实验 1：Thread.setPriority 的完整映射表（java 1..10）══")
        sb.appendLine("关注这个 API 能否精确表达你想要的优先级：")
        val javaMap = mutableListOf<Pair<Int, Int?>>()
        for (p in 1..10) {
            val r = probe("java=$p", javaBefore = p, androidInRun = null)
            javaMap += p to r.nice
            sb.appendLine("  Thread.setPriority(%2d)  →  nice=%s".format(p, r.nice ?: "?"))
        }

        val javaNices = javaMap.mapNotNull { it.second }.distinct().sorted()
        sb.appendLine()
        sb.appendLine("  java 能取到的 nice 值集合：$javaNices")
        sb.appendLine("  共 ${javaNices.size} 档 —— 注意是否连续")
        sb.appendLine()

        sb.appendLine("══ 实验 2：Process.setThreadPriority 的映射 ══")
        val androidCases = listOf(
            "URGENT_AUDIO" to Process.THREAD_PRIORITY_URGENT_AUDIO,
            "URGENT_DISPLAY" to Process.THREAD_PRIORITY_URGENT_DISPLAY,
            "DISPLAY" to Process.THREAD_PRIORITY_DISPLAY,
            "FOREGROUND" to Process.THREAD_PRIORITY_FOREGROUND,
            "DEFAULT" to Process.THREAD_PRIORITY_DEFAULT,
            "BACKGROUND" to Process.THREAD_PRIORITY_BACKGROUND,
            "LOWEST" to Process.THREAD_PRIORITY_LOWEST,
        )
        androidCases.forEach { (name, value) ->
            val r = probe(name, javaBefore = null, androidInRun = value)
            sb.appendLine("  THREAD_PRIORITY_%-14s(%-4d) →  nice=%s".format(name, value, r.nice ?: "?"))
        }
        sb.appendLine()

        sb.appendLine("══ 实验 3：cgroup 调度分组是否受二者影响 ══")
        listOf(
            probe("基线", null, null),
            probe("Thread.setPriority(4)", 4, null),
            probe("Process.setThreadPriority(BACKGROUND)", null, Process.THREAD_PRIORITY_BACKGROUND),
        ).forEach { r ->
            sb.appendLine("  ${r.label}")
            sb.appendLine("      nice=${r.nice ?: "?"}")
            sb.appendLine("      cgroup=${r.cgroup}")
        }

        Log.i(TAG, "优先级诊断：\n$sb")
        return sb.toString()
    }

    private fun probe(label: String, javaBefore: Int?, androidInRun: Int?): Result {
        val started = CountDownLatch(1)
        val tidRef = AtomicInteger(-1)

        val target = Runnable {
            androidInRun?.let { runCatching { Process.setThreadPriority(it) } }
            tidRef.set(Process.myTid())
            started.countDown()
            runCatching { Thread.sleep(400) }
        }

        val t = Thread(target, "prio-probe")
        javaBefore?.let { t.priority = it }
        t.start()

        if (!started.await(2, TimeUnit.SECONDS)) return Result(label, t.priority, null, "未启动")
        val tid = tidRef.get()
        val nice = readNice(tid)
        val cgroup = readCgroupRaw(tid)
        runCatching { t.join(1200) }
        return Result(label, t.priority, nice, cgroup)
    }
}
