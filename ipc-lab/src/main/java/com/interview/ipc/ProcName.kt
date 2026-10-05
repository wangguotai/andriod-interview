package com.interview.ipc

import android.os.Process
import java.io.File

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 取「当前进程真实的名字」。
 *
 * ─── 为什么不能直接用 applicationInfo.processName ───
 *
 * `Context.applicationInfo.processName` 返回的是**清单里 <application> 节点声明的主进程名**
 * （即包名 `com.interview.ipclab`），**无论你在哪个进程里调用它**。也就是说，跑在
 * `:ipc_remote` 里的 Service/Provider/Receiver 调它，也会得到 `com.interview.ipclab`。
 *
 * 这会直接毁掉本 Lab 的证据链：每个跨进程组件的日志都写 `proc=com.interview.ipclab`，
 * 看起来就像「自己调自己」，而演示要证明的恰恰是「远端组件真实名是
 * `com.interview.ipclab:ipc_remote`」。
 *
 * ─── 正确做法 ───
 *
 * 读 `/proc/self/cmdline` —— 它是内核记录的、**当前进程**实际被赋予的名字
 * （Android 会把非默认进程的 cmdline 设为 `<pkg>:<suffix>`）。读不出时退回基于 pid 推断，
 * 再退回进程名，保证任何情况下都返回一个**诚实的、带当前 pid 的**名字。
 */
object ProcName {

    /** 当前进程的真实进程名；读不到时返回一个仍可区分的兜底名。 */
    fun current(): String {
        // 优先 /proc/self/cmdline：内核视角的事实来源。
        runCatching {
            val line = File("/proc/self/cmdline").readBytes()
                .takeWhile { it != 0.toByte() }          // cmdline 以 NUL 结尾，取第一段
                .toByteArray()
                .toString(Charsets.UTF_8)
                .trim()
            if (line.isNotEmpty()) return line
        }
        // 兜底：拿不到 cmdline 时，至少给一个带 pid 的可辨识名字，而不是谎报主进程名。
        return "pid:${Process.myPid()}"
    }

    /** 便于日志使用：`pid=123 proc=com.x:remote`。 */
    fun describe(): String = "pid=${Process.myPid()} proc=${current()}"
}
