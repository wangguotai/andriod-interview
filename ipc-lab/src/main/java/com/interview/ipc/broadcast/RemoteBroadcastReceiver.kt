package com.interview.ipc.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import com.interview.ipc.ProcName

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 广播接收器（跑在 `:ipc_remote` 进程）。
 *
 * ─── 广播作为 IPC 的定位 ───
 *
 * 广播是 **一对多、发布-订阅、无返回值** 的跨进程通知。它由 AMS 派发，天然解耦发送方
 * 与接收方；但也因此**拿不到返回值**，且系统对隐式广播的投递有诸多限制。
 *
 * ─── 本演示覆盖的两种形态 ───
 *
 * 1. **动态注册**（[com.interview.ipc.ui.IpcLabActivity] 里注册）：接收方在运行时注册，
 *    生命周期跟随注册者。演示跨进程动态广播的往返：结果通过一次**回执广播**送回。
 * 2. **显式广播 + 显式组件**：发送时带上 component，确保投递到本 receiver
 *    （避免 Android 8+ 对隐式广播的投递限制）。
 *
 * ─── 为什么要有「回执广播」───
 *
 * 广播没有返回值。要让发送方知道接收方跑在哪个进程、做了什么，标准做法就是
 * 让接收方**再发一条广播回去**。本接收器收到 [ACTION_PING] 后，用
 * [ACTION_PONG] 回执，并把「自己的 pid」放进 extra —— 往返两边都带上 pid，
 * 就能证明这确实是两个进程在对话。
 */
class RemoteBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val myPid = Process.myPid()
        val proc = ProcName.current()
        Log.i(TAG, "onReceive ${intent.action} pid=$myPid proc=$proc")

        when (intent.action) {
            ACTION_PING -> {
                val payload = intent.getStringExtra(EXTRA_PAYLOAD) ?: ""
                val senderPid = intent.getIntExtra(EXTRA_SENDER_PID, -1)
                // 回执：带着「服务端 pid + 我理解的载荷」，让发送方形成闭环证据。
                val reply = Intent(ACTION_PONG).apply {
                    setPackage(context.packageName)
                    putExtra(EXTRA_REPLY_PID, myPid)
                    putExtra(EXTRA_REPLY_PROCESS, proc)
                    putExtra(EXTRA_ECHO, "pong(payload=\"$payload\", senderPid=$senderPid)")
                }
                context.sendBroadcast(reply)
                Log.i(TAG, "已回执 $ACTION_PONG，replyPid=$myPid")
            }
        }
    }

    companion object {
        private const val TAG = "IpcLab/Broadcast"

        /** 客户端发给接收器的 ping。 */
        const val ACTION_PING = "com.interview.ipc.action.PING"

        /** 接收器回给客户端的 pong。 */
        const val ACTION_PONG = "com.interview.ipc.action.PONG"

        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_SENDER_PID = "sender_pid"
        const val EXTRA_REPLY_PID = "reply_pid"
        const val EXTRA_REPLY_PROCESS = "reply_process"
        const val EXTRA_ECHO = "echo"
    }
}
