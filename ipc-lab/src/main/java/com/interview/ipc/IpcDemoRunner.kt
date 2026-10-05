package com.interview.ipc

import android.content.Context
import android.util.Log

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 所有演示的执行体 —— 把 [IpcLabCatalog] 里的 id 映射到具体实现。
 *
 * ─── 执行约定 ───
 *
 * - 每个 runner 都可能在**后台线程**上跑（UI 层统一用线程池调度），因此：
 *   严禁在这里碰 UI；所有结果通过返回值（[DemoResult]）交给上层。
 * - runner 必须**有界**：涉及等待的地方一律带超时，绝不允许把演示线程挂死。
 * - 证据日志的第一原则：**带上 pid/tid**。没有 pid 的「跨进程」结论不成立。
 *
 * 本文件随里程碑逐步生长：当前为骨架，尚无任何演示实现。
 */
object IpcDemoRunner {

    private const val TAG = "IpcLab/Runner"

    /** 一次演示的统一入口。 */
    fun run(context: Context, id: String): DemoResult = try {
        when (id) {
            else -> DemoResult.Failure("未知演示 id: $id")
        }
    } catch (t: Throwable) {
        // 任何异常都要变成「可读的失败」，不能让 UI 崩。
        Log.e(TAG, "演示 $id 抛异常", t)
        DemoResult.Failure("${t.javaClass.simpleName}: ${t.message}")
    }

    private fun StringBuilder.line(s: String) = append(s).append('\n')

    /** 给一条结论加上明确的「成立 / 不成立」判定，避免读者自己猜。 */
    private fun StringBuilder.verdict(ok: Boolean, pass: String, fail: String) {
        append(if (ok) "✅ " else "❌ ").append(if (ok) pass else fail).append('\n')
    }
}
