package com.interview.ipc.binder

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.RemoteCallbackList
import android.os.SystemClock
import android.util.Log

import com.interview.ipc.ProcName

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 跑在**:ipc_remote 独立进程**里的 AIDL 服务端（Binder 主线）。
 *
 * ─── 它演示了什么 ───
 *
 * 1. **同步方法调用**：`add` / `echo` / `getRemoteInfo` —— 客户端阻塞，Binder 驱动
 *    把参数 Parcel 到本进程、执行、再把结果 Parcel 回去。返回值里带 pid，证明跨进程。
 * 2. **Binder 线程池**：`probeCallingThread` 会 sleep 一小下并返回处理它的线程信息。
 *    客户端并发发起多次调用，若返回的 binderThread/tid 不止一个，即证明服务端用
 *    **线程池**并行处理（而不是单线程串行）—— 这是「Binder 调用不要占着主线程」的实证。
 * 3. **oneway 单向调用 + 反向回调**：`computeAsync` 把活儿丢给一个后台 HandlerThread，
 *    立刻返回；结果通过 `IRemoteCallback`（客户端传来的另一个 Binder）**反向**送回客户端。
 *    这条链路完整展示了「双向 Binder」。
 * 4. **RemoteCallbackList**：管理多个客户端回调，并在客户端死亡时自动清理 ——
 *    比手写 `ArrayList<IBinder>` 正确，因为它内部对 Binder 死亡做了处理。
 * 5. **死亡演示**：`crashRemoteProcess` 让本进程直接退出，供客户端验证 linkToDeath。
 *
 * ─── 为什么后台任务用 HandlerThread 而不是协程 ───
 *
 * Service 可能被系统在**没有 Looper 的 binder 线程**里回调，且本 module 的演示要
 * 明确展示「服务端有自己的工作线程」。HandlerThread 语义直白、生命周期好收，
 * 与其它 Lab（线程治理）的观测手段也能对上。
 */
class RemoteComputeService : Service() {

    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler

    /** 多客户端回调管理：内部按 Binder 身份去重，并在对端死亡时移除。 */
    private val callbacks = RemoteCallbackList<IRemoteCallback>()

    override fun onCreate() {
        super.onCreate()
        workerThread = HandlerThread("ipc-remote-worker").also { it.start() }
        worker = Handler(workerThread.looper)
        Log.i(TAG, "RemoteComputeService.onCreate pid=${Process.myPid()} proc=${processName()}")
    }

    override fun onBind(intent: Intent?): IBinder {
        // 注意：这里必须用 binderCallingPid()（发起 bindService 的客户端 pid），
        // 而不是 Process.myPid()（=服务端自己）。否则日志看起来像「自己 bind 自己」，
        // 恰好把本演示要证明的跨进程事实说反了。
        Log.i(TAG, "onBind from callerPid=${binderCallingPid()} action=${intent?.action}")
        return binder
    }

    override fun onDestroy() {
        callbacks.kill()
        workerThread.quitSafely()
        Log.i(TAG, "RemoteComputeService.onDestroy")
        super.onDestroy()
    }

    private val binder = object : IRemoteCompute.Stub() {

        override fun getRemoteInfo(): RemoteInfo {
            // remoteInfo() 统一构造，保证所有返回的元信息口径一致
            return remoteInfo()
        }

        override fun add(a: Int, b: Int): Int {
            Log.i(TAG, "add($a,$b) on ${Thread.currentThread().name}")
            return a + b
        }

        override fun echo(input: String): String {
            return "echo from ${remoteInfo()} : $input"
        }

        override fun probeCallingThread(holdMillis: Int): RemoteInfo {
            // 故意占用当前 binder 线程一小会儿：客户端并发调用时，若返回多个不同
            // binderThread，就证明这些调用是**并行**落在不同池线程上的。
            if (holdMillis > 0) SystemClock.sleep(holdMillis.toLong())
            val info = remoteInfo()
            Log.i(TAG, "probeCallingThread → $info")
            return info
        }

        override fun registerCallback(callback: IRemoteCallback?) {
            if (callback == null) return
            callbacks.register(callback)
            Log.i(TAG, "registerCallback, size=${callbacks.registeredCallbackCount}")
            // 注册成功给一次即时反馈，让客户端能确认「反向通道已建立」
            callback.onProgress(0, "registered@${remoteInfo().pid}")
        }

        override fun unregisterCallback(callback: IRemoteCallback?) {
            if (callback == null) return
            val removed = callbacks.unregister(callback)
            Log.i(TAG, "unregisterCallback removed=$removed, size=${callbacks.registeredCallbackCount}")
        }

        override fun computeAsync(input: Int, callback: IRemoteCallback?) {
            // oneway：本方法调用方**不等**它执行完。但 Stub 里这段代码仍会先跑完才返回，
            // 所以真正耗时的部分必须丢到 worker，否则 oneway 也白搭。
            if (callback == null) return
            val caller = binderCallingPid()
            worker.post {
                try {
                    val step = 10
                    for (p in step..100 step step) {
                        SystemClock.sleep(80)
                        callback.onProgress(p, "step-$p@${Process.myPid()}")
                    }
                    val result = "async($input) = ${input * input} computed by ${remoteInfo()}"
                    callback.onResult(remoteInfo(), result)
                    Log.i(TAG, "computeAsync done for callerPid=$caller")
                } catch (t: Throwable) {
                    // 回调本身可能因为客户端死亡而抛 RemoteException，必须接住：
                    // 服务端不能因为某个客户端没了就崩。
                    Log.w(TAG, "callback 失败（客户端可能已死亡）: $t")
                    runCatching { callback.onError("${t.javaClass.simpleName}: ${t.message}") }
                }
            }
        }

        override fun crashRemoteProcess() {
            Log.w(TAG, "crashRemoteProcess: 主动杀死本进程 pid=${Process.myPid()} 以演示 linkToDeath")
            // 用 exitProcess 而非 throw：要的是「进程消失」，且不留半死状态。
            Process.killProcess(Process.myPid())
        }
    }

    private fun remoteInfo(): RemoteInfo = RemoteInfo.here(processName())

    private fun processName(): String = ProcName.current()

    private fun binderCallingPid(): Int = android.os.Binder.getCallingPid()

    companion object {
        private const val TAG = "IpcLab/Binder"

        /** 客户端绑定用的显式 Action（比 component 直连更能体现「跨进程寻址」）。 */
        const val ACTION_BIND = "com.interview.ipc.action.BIND_COMPUTE"
    }
}
