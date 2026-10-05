package com.interview.ipc.messenger

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.interview.ipc.ProcName

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: Messenger 服务端（跑在 `:ipc_remote` 进程）。
 *
 * ─── Messenger 在 IPC 谱系里的位置 ───
 *
 * 它是 AIDL 之上的一层**薄封装**：内部仍然是 Binder，但接口只有一个 ——
 * `send(Message)`。Message 通过 `replyTo` 携带一个反向 Messenger，于是天然支持
 * 「请求 → 应答」而不必自己写 AIDL。适合**轻量、消息驱动**的场景。
 *
 * 相比 AIDL：
 *   优点：无需写 .aidl；消息可携带 Bundle（含 Parcelable）；天然异步。
 *   缺点：只支持 `Message` 一种载荷；无法做「同步取返回值」；一次一个消息，吞吐低。
 *
 * ─── 本演示要暴露的关键点 ───
 *
 * 1. `msg.replyTo` 是**双向**的关键：客户端发消息时带上自己的 Messenger，
 *    服务端即可用它回消息。这就是「Messenger 版的双向 Binder」。
 * 2. 消息里的 `arg1`（客户端 pid）与应答里的「服务端 pid」不同 —— 证明跨进程。
 * 3. 服务端用**独立的 HandlerThread** 处理消息（不在主线程），避免拖慢 Service 主线程。
 */
class RemoteMessengerService : Service() {

    // 用独立的 HandlerThread 承载消息处理：Messenger 的 handleMessage 默认跑在
    // 创建 Handler 时的 Looper 上，若直接用无参 Handler() 就是**主线程**，
    // 会把 Service 主线程拖住。这与本演示「服务端有自己工作线程」的说明一致。
    private lateinit var workerThread: HandlerThread
    private lateinit var incoming: IncomingHandler

    /** 暴露给客户端的服务端 Messenger。 */
    private val messenger: Messenger by lazy { Messenger(incoming) }

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("ipc-messenger-worker").also { it.start() }
        incoming = IncomingHandler(workerThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "onBind pid=${Process.myPid()} proc=$processName thread=${Thread.currentThread().name}")
        return messenger.binder
    }

    override fun onDestroy() {
        workerThread.quitSafely()
        super.onDestroy()
    }

    private inner class IncomingHandler(looper: android.os.Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_PING -> reply(
                    msg,
                    MSG_PONG,
                    "pong from pid=${Process.myPid()} thread=${Thread.currentThread().name}",
                )
                MSG_HELLO -> {
                    val name = msg.data?.getString(KEY_NAME) ?: "(no name)"
                    reply(msg, MSG_HELLO_ACK, "hello, $name! from pid=${Process.myPid()}")
                }
                MSG_SUM -> {
                    val a = msg.arg1
                    val b = msg.arg2
                    val answer = Message.obtain(null, MSG_SUM_RESULT).apply {
                        data = Bundle().apply {
                            putInt(KEY_A, a)
                            putInt(KEY_B, b)
                            putInt(KEY_SUM, a + b)
                            putInt(KEY_REMOTE_PID, Process.myPid())
                        }
                    }
                    sendReply(msg, answer)
                }
                else -> super.handleMessage(msg)
            }
        }
    }

    /** 用 `msg.replyTo` 把结果送回**客户端**。 */
    private fun reply(original: Message, what: Int, text: String) {
        val out = Message.obtain(null, what).apply {
            data = Bundle().apply {
                putString(KEY_TEXT, text)
                putInt(KEY_REMOTE_PID, Process.myPid())
                putInt(KEY_CLIENT_PID, original.arg1)
            }
        }
        sendReply(original, out)
    }

    private fun sendReply(original: Message, out: Message) {
        val replyTo = original.replyTo
        if (replyTo == null) {
            Log.w(TAG, "消息未带 replyTo，无法应答 —— 双向通道不成立")
            return
        }
        try {
            replyTo.send(out)
        } catch (e: RemoteException) {
            // 客户端进程已死：这里是**必须**处理的常见情况，不能让 Service 崩。
            Log.w(TAG, "应答失败（客户端可能已退出）: $e")
        }
    }

    private val processName: String get() = ProcName.current()

    companion object {
        private const val TAG = "IpcLab/Messenger"

        const val ACTION_BIND = "com.interview.ipc.action.BIND_MESSENGER"

        const val MSG_PING = 1
        const val MSG_PONG = 2
        const val MSG_HELLO = 3
        const val MSG_HELLO_ACK = 4
        const val MSG_SUM = 5
        const val MSG_SUM_RESULT = 6

        const val KEY_TEXT = "text"
        const val KEY_NAME = "name"
        const val KEY_A = "a"
        const val KEY_B = "b"
        const val KEY_SUM = "sum"
        const val KEY_REMOTE_PID = "remote_pid"
        const val KEY_CLIENT_PID = "client_pid"
    }
}
