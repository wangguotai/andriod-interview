package com.interview.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import com.interview.ipc.binder.IRemoteCallback
import com.interview.ipc.binder.IRemoteCompute
import com.interview.ipc.binder.RemoteInfo
import com.interview.ipc.broadcast.RemoteBroadcastReceiver
import com.interview.ipc.messenger.RemoteMessengerService
import com.interview.ipc.nativebridge.IpcNativeBridge
import com.interview.ipc.provider.IpcDemoProvider
import java.io.BufferedReader
import java.io.DataInputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
 */
object IpcDemoRunner {

    private const val TAG = "IpcLab/Runner"

    /**
     * 回执广播的 action。
     *
     * 这里刻意写成**字面量常量**而不是引用 `RemoteBroadcastReceiver.ACTION_PONG`：
     * 那样写会直接引用另一个进程的类（触发类加载），而广播的核心特征正是
     * 「发送方与接收方**不需要**知道彼此的类」。用字符串就是一个诚实的示范。
     */
    private const val REMOTE_ACTION_PONG = "com.interview.ipc.action.PONG"

    /** 一次演示的统一入口。 */
    fun run(context: Context, id: String): DemoResult = try {
        when (id) {
            "binder_sync" -> binderSync(context)
            "binder_threadpool" -> binderThreadPool(context)
            "binder_async_callback" -> binderAsyncCallback(context)
            "binder_death" -> binderDeath(context)
            "messenger" -> messengerDemo(context)
            "provider" -> providerDemo(context)
            "broadcast" -> broadcastDemo(context)
            "local_socket_abstract" -> localSocketAbstract(context)
            "local_socket_rust_selftest" -> nativeDemo(context, "unix", rustUnixPath(context))
            "tcp_loopback" -> tcpLoopback()
            "native_pipe" -> nativeDemo(context, IpcNativeBridge.KIND_PIPE, "")
            "native_fifo" -> nativeDemo(context, IpcNativeBridge.KIND_FIFO, workPath(context, "demo.fifo"))
            "native_shm" -> nativeDemo(context, IpcNativeBridge.KIND_SHM, "父进程写入的共享数据 pid=${Process.myPid()}")
            "native_signal" -> nativeDemo(context, IpcNativeBridge.KIND_SIGNAL, "")
            "native_flock" -> nativeDemo(context, IpcNativeBridge.KIND_FLOCK, workPath(context, "demo.lock"))
            else -> DemoResult.Failure("未知演示 id: $id")
        }
    } catch (t: Throwable) {
        // 任何异常都要变成「可读的失败」，不能让 UI 崩。
        Log.e(TAG, "演示 $id 抛异常", t)
        DemoResult.Failure("${t.javaClass.simpleName}: ${t.message}")
    }

    // ══════════════════════════════════════════════════════════════════
    // 1. Binder / AIDL
    // ══════════════════════════════════════════════════════════════════

    private fun binderSync(context: Context): DemoResult {
        val sb = StringBuilder()
        sb.line("【目标】证明 AIDL 是一次真正的跨进程方法调用，而非同进程调用")
        sb.line("客户端 pid=${Process.myPid()} 进程=${context.currentProcess()}")
        sb.line("")

        val conn = bindCompute(context)
        val remote = conn.await(5000)
            ?: return DemoResult.Failure("绑定 RemoteComputeService 超时或失败：${conn.failure}")

        val info = remote.getRemoteInfo()
        sb.line("① getRemoteInfo() → $info")
        sb.verdict(
            info.pid != Process.myPid(),
            "服务端 pid=${info.pid} 与客户端 pid=${Process.myPid()} 不同 ⇒ **确实跨进程**",
            "服务端 pid 与客户端相同 ⇒ 其实是同进程调用（进程声明可能没生效）",
        )

        val sum = remote.add(17, 25)
        sb.line("② add(17, 25) → $sum")
        sb.verdict(sum == 42, "结果正确，且是远端算出来的", "结果异常：$sum")

        val echo = remote.echo("hello-ipc")
        sb.line("③ echo(\"hello-ipc\") → $echo")

        sb.line("")
        sb.line("【Binder 的三个同步语义】都走通了：参数序列化过去、远端执行、结果序列化回来。")
        conn.unbind()
        return DemoResult.Success(sb.toString())
    }

    private fun binderThreadPool(context: Context): DemoResult {
        val sb = StringBuilder()
        sb.line("【目标】证明 Binder 服务端用**线程池**并行处理调用，而非单线程串行")
        sb.line("客户端 pid=${Process.myPid()}")
        sb.line("")

        val conn = bindCompute(context)
        val remote = conn.await(5000)
            ?: return DemoResult.Failure("绑定失败：${conn.failure}")

        val concurrency = 6
        val holdMs = 120
        val pool = Executors.newFixedThreadPool(concurrency)
        val latch = CountDownLatch(concurrency)
        val infos = java.util.Collections.synchronizedList(mutableListOf<RemoteInfo>())
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())

        sb.line("并发发起 $concurrency 次 probeCallingThread(hold=$holdMs ms)，每次都会占住处理它的 binder 线程…")
        val start = System.currentTimeMillis()
        repeat(concurrency) { i ->
            pool.execute {
                try {
                    infos.add(remote.probeCallingThread(holdMs))
                } catch (t: Throwable) {
                    errors.add("call#$i: ${t.javaClass.simpleName}")
                } finally {
                    latch.countDown()
                }
            }
        }
        val finished = latch.await(10, TimeUnit.SECONDS)
        val elapsed = System.currentTimeMillis() - start
        pool.shutdown()

        val distinctTids = infos.map { it.tid }.toSet()
        val distinctBinderThreads = infos.map { it.binderThread }.toSet()
        sb.line("")
        sb.line("总耗时=${elapsed}ms（若严格串行应约 ${concurrency * holdMs}ms；完全并行约 ${holdMs}ms）")
        sb.line("返回的独特 tid 数量=${distinctTids.size}，tids=$distinctTids")
        sb.line("返回的 binderThread 集合=$distinctBinderThreads")
        if (errors.isNotEmpty()) sb.line("错误：$errors")
        sb.line("")
        // 判据：至少落在 2 个不同线程上，且总耗时明显小于「全串行」。
        val parallel = distinctTids.size > 1 && elapsed < concurrency * holdMs
        sb.verdict(
            finished && parallel,
            "并发调用落在 ${distinctTids.size} 个不同 binder 线程上、总耗时 ${elapsed}ms ≈ 单次而非累加 ⇒ **服务端是线程池并行处理**",
            if (!finished) "并发调用未在超时内完成" else "只观察到 ${distinctTids.size} 个线程或耗时偏串行（${elapsed}ms）⇒ 需要复核",
        )
        sb.line("")
        sb.line("【结论】这正是「不要把耗时活放在 Binder 调用里阻塞调用方」的原因之一：")
        sb.line("服务端并行能力有限（默认线程池上限），长任务应改成 oneway + 回调（见下一个演示）。")
        conn.unbind()
        return DemoResult.Success(sb.toString())
    }

    private fun binderAsyncCallback(context: Context): DemoResult {
        val sb = StringBuilder()
        sb.line("【目标】oneway 单向调用 + 服务端通过客户端传去的 Binder 反向回调（双向 Binder）")
        sb.line("客户端 pid=${Process.myPid()}")
        sb.line("")

        val conn = bindCompute(context)
        val remote = conn.await(5000)
            ?: return DemoResult.Failure("绑定失败：${conn.failure}")

        val progress = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(1)
        // 结果在 binder 线程回调里到达；用它把控制权交回本演示线程。
        val callback = object : IRemoteCallback.Stub() {
            override fun onProgress(percent: Int, stage: String) {
                progress.add(percent)
                events.add("onProgress($percent, $stage) @本地tid=${Process.myTid()}")
            }
            override fun onResult(info: RemoteInfo, payload: String) {
                events.add("onResult @远端计算线程=$info")
                events.add("payload=$payload")
                done.countDown()
            }
            override fun onError(message: String) {
                events.add("onError: $message")
                done.countDown()
            }
        }

        remote.registerCallback(callback)
        val t0 = System.currentTimeMillis()
        sb.line("调用 computeAsync(11, callback)（接口声明为 oneway）…")
        remote.computeAsync(11, callback)
        val returnedAfter = System.currentTimeMillis() - t0
        sb.line("computeAsync 返回耗时=${returnedAfter}ms ⇒ oneway **没有**等待计算完成")
        sb.verdict(returnedAfter < 100, "立即返回，符合 oneway 语义", "返回耗时偏大，oneway 语义可能未生效")

        sb.line("")
        sb.line("等待服务端通过 IRemoteCallback 反向送回结果（最多 8s）…")
        val ok = done.await(8, TimeUnit.SECONDS)
        sb.line("收到的进度回调：$progress")
        events.forEach { sb.line("  $it") }
        sb.line("")
        sb.verdict(ok, "完整收到进度 + 结果 ⇒ **双向 Binder 成立**", "超时未收到结果")
        sb.line("")
        sb.line("【要点】IRemoteCallback 本身是一个跨进程 Binder：客户端把它传给服务端后，")
        sb.line("服务端就能回调客户端 —— 这就是「Binder 可以双向」的含义。方法标 oneway 后，")
        sb.line("服务端上报进度时不阻塞、也不占用自己的 binder 线程等待客户端处理。")
        runCatching { remote.unregisterCallback(callback) }
        conn.unbind()
        return DemoResult.Success(sb.toString())
    }

    private fun binderDeath(context: Context): DemoResult {
        val sb = StringBuilder()
        sb.line("【目标】观察 Binder 对端死亡通知（linkToDeath / onBindingDied）")
        sb.line("客户端 pid=${Process.myPid()}")
        sb.line("")

        val conn = bindCompute(context)
        val first = conn.await(5000)
            ?: return DemoResult.Failure("绑定失败：${conn.failure}")
        val remotePidBefore = first.getRemoteInfo().pid
        sb.line("绑定成功，远端 pid=$remotePidBefore")

        // 1) 显式 linkToDeath：客户端主动监听 binder 对象死亡
        val died = CountDownLatch(1)
        val deathRecipient = IBinder.DeathRecipient {
            sb.line("🔔 DeathRecipient.binderDied() 被调用 ⇒ 客户端**显式注册的死亡通知**收到")
            died.countDown()
        }
        first.asBinder().linkToDeath(deathRecipient, 0)
        sb.line("已对远端 IBinder 调用 linkToDeath()")

        // 2) 命令远端进程自杀
        sb.line("")
        sb.line("调用 crashRemoteProcess() 让远端进程退出…")
        runCatching { first.crashRemoteProcess() }
            .onFailure { sb.line("（调用本身抛异常也正常，因为对端随即消失：${it.javaClass.simpleName}）") }

        sb.line("等待死亡通知（最多 5s）…")
        val got = died.await(5, TimeUnit.SECONDS)
        sb.line("")
        sb.verdict(got, "收到 linkToDeath 通知 ⇒ 客户端能感知对端进程消失", "超时未收到死亡通知")

        // 3) 重新绑定，证明「死亡后可恢复」
        sb.line("")
        sb.line("重新绑定服务（系统会拉起新的 :ipc_remote 进程）…")
        runCatching { first.asBinder().unlinkToDeath(deathRecipient, 0) }
        conn.unbind()
        val conn2 = bindCompute(context)
        val second = conn2.await(8000)
        if (second != null) {
            val pidAfter = second.getRemoteInfo().pid
            sb.line("重连成功，新远端 pid=$pidAfter（旧 pid=$remotePidBefore）")
            sb.verdict(
                pidAfter != remotePidBefore,
                "新进程 pid 与旧的不同 ⇒ 确实是**新的**远端进程在服务",
                "pid 相同：可能旧进程没真正退出",
            )
        } else {
            sb.line("重连失败：${conn2.failure}")
        }
        conn2.unbind()
        sb.line("")
        sb.line("【要点】Binder 死亡通知是「客户端对远端存活状态的订阅」。Android 用它做")
        sb.line("服务重启、连接自愈；比轮询「对端还在不在」高效且及时。")
        return DemoResult.Success(sb.toString())
    }

    // ══════════════════════════════════════════════════════════════════
    // 2. Messenger
    // ══════════════════════════════════════════════════════════════════

    private fun messengerDemo(context: Context): DemoResult {
        val sb = StringBuilder()
        sb.line("【目标】用 Messenger（Message + replyTo）完成跨进程的双向请求-应答")
        sb.line("客户端 pid=${Process.myPid()}")
        sb.line("")

        var remote: Messenger? = null
        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                remote = Messenger(service); latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        val bound = context.bindService(
            Intent(context, RemoteMessengerService::class.java).setAction(RemoteMessengerService.ACTION_BIND),
            conn, Context.BIND_AUTO_CREATE,
        )
        if (!bound) return DemoResult.Failure("bindService 返回 false")
        if (!latch.await(5, TimeUnit.SECONDS)) {
            runCatching { context.unbindService(conn) }
            return DemoResult.Failure("等待 onServiceConnected 超时")
        }

        // 客户端自己的 Messenger：随 msg.replyTo 发给服务端，用于接收应答。
        val replies = java.util.concurrent.LinkedBlockingQueue<Message>()
        val clientMessenger = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) { replies.offer(Message.obtain(msg)) }
        })

        fun sendAndAwait(what: Int, build: (Message) -> Unit, timeoutMs: Long): Message? {
            val msg = Message.obtain(null, what).apply {
                replyTo = clientMessenger
                arg1 = Process.myPid()
                build(this)
            }
            remote?.send(msg)
            return replies.poll(timeoutMs, TimeUnit.MILLISECONDS)
        }

        sb.line("① 发 MSG_PING（带 replyTo），等服务端应答…")
        val pong = sendAndAwait(RemoteMessengerService.MSG_PING, {}, 3000)
        if (pong != null) {
            val text = pong.data?.getString(RemoteMessengerService.KEY_TEXT)
            val remotePid = pong.data?.getInt(RemoteMessengerService.KEY_REMOTE_PID, -1)
            sb.line("   收到 MSG_PONG: \"$text\"（服务端 pid=$remotePid）")
            sb.verdict(
                remotePid != Process.myPid(),
                "应答来自 pid=$remotePid ≠ 客户端 pid ⇒ 跨进程往返成立",
                "应答 pid 与客户端相同 ⇒ 未跨进程",
            )
        } else {
            sb.line("   超时未收到 MSG_PONG")
        }

        sb.line("")
        sb.line("② 发 MSG_HELLO 携带 Bundle 数据…")
        val ack = sendAndAwait(RemoteMessengerService.MSG_HELLO, { m ->
            m.data = Bundle().apply { putString(RemoteMessengerService.KEY_NAME, "面试官") }
        }, 3000)
        sb.line("   ${ack?.data?.getString(RemoteMessengerService.KEY_TEXT) ?: "超时未应答"}")

        sb.line("")
        sb.line("③ 发 MSG_SUM：arg1/arg2 传参，Bundle 带回结果…")
        val sumMsg = sendAndAwait(RemoteMessengerService.MSG_SUM, { m ->
            m.arg1 = 100; m.arg2 = 23
        }, 3000)
        val data = sumMsg?.data
        if (data != null) {
            val a = data.getInt(RemoteMessengerService.KEY_A)
            val b = data.getInt(RemoteMessengerService.KEY_B)
            val sum = data.getInt(RemoteMessengerService.KEY_SUM)
            sb.line("   服务端算得 $a + $b = $sum")
            sb.verdict(sum == a + b, "结果正确", "结果错误")
        } else {
            sb.line("   超时未收到 MSG_SUM_RESULT")
        }

        runCatching { context.unbindService(conn) }
        sb.line("")
        sb.line("【要点】Messenger 是 AIDL 的薄封装：只暴露 send(Message)，靠 msg.replyTo 实现双向。")
        sb.line("适合轻量消息驱动；需要「同步取返回值」或复杂类型时，AIDL 更合适。")
        return DemoResult.Success(sb.toString())
    }

    // ══════════════════════════════════════════════════════════════════
    // 3. ContentProvider
    // ══════════════════════════════════════════════════════════════════

    private fun providerDemo(context: Context): DemoResult {
        val sb = StringBuilder()
        sb.line("【目标】ContentProvider 的三条跨进程链路：结构化数据 / call RPC / openFile 传 fd")
        sb.line("客户端 pid=${Process.myPid()}")
        sb.line("")

        val uri = Uri.parse("content://${IpcDemoProvider.AUTHORITY}/demo")
        val resolver = context.contentResolver

        // ① query → Cursor
        sb.line("① query() 取结构化数据（MatrixCursor）…")
        val cursor: Cursor? = resolver.query(uri, null, null, null, null)
        if (cursor != null) {
            cursor.use {
                sb.line("   列：${it.columnNames.joinToString()}")
                while (it.moveToNext()) {
                    val row = it.columnNames.indices.joinToString(", ") { i -> "${it.columnNames[i]}=${it.getString(i)}" }
                    sb.line("   行：$row")
                }
            }
        } else {
            sb.line("   query 返回 null")
        }

        // ② call → Bundle（轻量 RPC）
        sb.line("")
        sb.line("② call(\"sum\") 走 Bundle 传参 / 返回…")
        val callResult = resolver.call(uri, "sum", null, Bundle().apply {
            putInt(IpcDemoProvider.KEY_A, 8)
            putInt(IpcDemoProvider.KEY_B, 34)
        })
        if (callResult != null) {
            val sum = callResult.getInt(IpcDemoProvider.KEY_SUM)
            val rPid = callResult.getInt(IpcDemoProvider.KEY_REMOTE_PID)
            val rProc = callResult.getString(IpcDemoProvider.KEY_REMOTE_PROCESS)
            sb.line("   sum=8+34=$sum，来自 pid=$rPid 进程=$rProc")
            sb.verdict(
                rPid != Process.myPid(),
                "Provider 在 pid=$rPid 的独立进程 ⇒ 跨进程 call 成立",
                "Provider 与客户端同进程 ⇒ 未跨进程",
            )
        } else {
            sb.line("   call 返回 null")
        }

        // ③ openFile → ParcelFileDescriptor（framework 层的「传 fd」）
        sb.line("")
        sb.line("③ openFile() 取 ParcelFileDescriptor（framework 层的传 fd）…")
        try {
            val pfd: ParcelFileDescriptor = resolver.openFileDescriptor(uri, "r")
                ?: return DemoResult.Failure("openFileDescriptor 返回 null")
            pfd.use {
                val content = BufferedReader(InputStreamReader(DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(it)))).readText()
                sb.line("   通过 fd 读到内容：")
                content.trim().lines().forEach { l -> sb.line("     $l") }
                sb.verdict(
                    content.isNotBlank(),
                    "客户端按 fd 读到了 Provider 进程写的文件 ⇒ **传 fd 而非传路径**成立",
                    "内容为空",
                )
            }
        } catch (t: Throwable) {
            sb.line("   openFile 失败：${t.javaClass.simpleName}: ${t.message}")
        }

        sb.line("")
        sb.line("【要点】Provider 擅长「结构化数据 + fd 访问」；这三条链路分别对应")
        sb.line("query（数据）、call（轻量 RPC）、openFile（零拷贝文件/大块数据的 fd 通道）。")
        return DemoResult.Success(sb.toString())
    }

    // ══════════════════════════════════════════════════════════════════
    // 4. Broadcast
    // ══════════════════════════════════════════════════════════════════

    private fun broadcastDemo(context: Context): DemoResult {
        val sb = StringBuilder()
        sb.line("【目标】一对多的跨进程广播通知 + 回执广播形成往返证据")
        sb.line("客户端 pid=${Process.myPid()}")
        sb.line("")

        val latch = CountDownLatch(1)
        val received = java.util.Collections.synchronizedList(mutableListOf<String>())
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                if (intent.action == REMOTE_ACTION_PONG) {
                    val pid = intent.getIntExtra(RemoteBroadcastReceiver.EXTRA_REPLY_PID, -1)
                    val proc = intent.getStringExtra(RemoteBroadcastReceiver.EXTRA_REPLY_PROCESS)
                    val echo = intent.getStringExtra(RemoteBroadcastReceiver.EXTRA_ECHO)
                    received.add("回执来自 pid=$pid 进程=$proc：$echo")
                    latch.countDown()
                }
            }
        }
        // Android 13+ 动态注册需显式 flag；用 ContextCompat 风格的可选参数保持兼容。
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver,
            android.content.IntentFilter(RemoteBroadcastReceiver.ACTION_PONG),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sb.line("已动态注册 PONG 回执接收器")

        sb.line("")
        sb.line("发送**显式**广播（带 component）给 :ipc_remote 里的 RemoteBroadcastReceiver…")
        val ping = Intent(RemoteBroadcastReceiver.ACTION_PING).apply {
            // 用显式 component 定向投递：接收器声明在另一个进程、且没有 intent-filter，
            // Android 8+ 对隐式广播有限制，显式投递是最可靠的做法，也最贴合「我就是要发给它」。
            component = ComponentName(context, RemoteBroadcastReceiver::class.java)
            putExtra(RemoteBroadcastReceiver.EXTRA_PAYLOAD, "hi-from-client")
            putExtra(RemoteBroadcastReceiver.EXTRA_SENDER_PID, Process.myPid())
        }
        context.sendBroadcast(ping)

        val got = latch.await(5, TimeUnit.SECONDS)
        sb.line("")
        if (got) received.forEach { sb.line("  $it") }
        sb.verdict(got, "收到接收器进程的回执 ⇒ 跨进程广播往返成立", "超时未收到回执")

        runCatching { context.unregisterReceiver(receiver) }
        sb.line("")
        sb.line("【要点】广播是一对多、无返回值；要用它做「请求-应答」，就得靠**回执广播**。")
        sb.line("注意 Android 8+ 对隐式广播的投递限制 —— 这里用 setPackage/显式 action 保证可达。")
        return DemoResult.Success(sb.toString())
    }

    // ══════════════════════════════════════════════════════════════════
    // 5. LocalSocket（抽象命名空间）↔ Rust AF_UNIX 服务端
    // ══════════════════════════════════════════════════════════════════

    private fun localSocketAbstract(context: Context): DemoResult {
        val sb = StringBuilder()
        sb.line("【目标】Java LocalSocket(ABSTRACT) ↔ Rust AF_UNIX 服务端，跨语言跨进程字节流")
        sb.line("客户端 pid=${Process.myPid()}")
        sb.line("")

        if (!IpcNativeBridge.available) {
            return DemoResult.Failure("需要 native 库（libipclab.so）来提供 Rust 服务端；${IpcNativeBridge.describe()}")
        }

        val name = "ipclab.demo.${Process.myPid()}.${System.nanoTime()}"
        sb.line("抽象命名空间 socket 名：$name（不对应任何文件，内核里的字符串）")
        sb.line("")

        // Rust 服务端在后台线程起，只服务一条连接。
        val serverLog = java.util.concurrent.atomic.AtomicReference<String>()
        val serverError = java.util.concurrent.atomic.AtomicReference<String>()
        val server = Thread {
            val r = IpcNativeBridge.run("unix_serve", name)
            r.onSuccess { serverLog.set(it) }.onFailure { serverError.set(it.message ?: "unknown") }
        }.apply { start() }

        // 留一点时间让 bind 完成；用重试连接兜底，而不是固定 sleep 猜时序。
        var socket: LocalSocket? = null
        var lastErr: String? = null
        for (attempt in 1..50) {
            try {
                val s = LocalSocket(LocalSocket.SOCKET_STREAM)
                s.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
                socket = s
                sb.line("客户端 LocalSocket.connect 成功（第 $attempt 次尝试）")
                break
            } catch (t: Throwable) {
                lastErr = "${t.javaClass.simpleName}: ${t.message}"
                Thread.sleep(20)
            }
        }
        val s = socket
        if (s == null) {
            server.join(1500)
            return DemoResult.Failure("连接 Rust AF_UNIX 服务端失败：$lastErr")
        }

        s.use {
            // 对端身份（framework 也提供，对应 native 侧 SO_PEERCRED）
            runCatching {
                val cred = it.peerCredentials
                sb.line("本地读 peerCredentials: pid=${cred.pid} uid=${cred.uid} gid=${cred.gid}")
            }.onFailure { e -> sb.line("读 peerCredentials 失败：${e.message}") }

            it.outputStream.write("ping-from-java-localsocket\n".toByteArray())
            it.outputStream.flush()
            sb.line("已发送一行请求，等待 Rust 服务端应答…")
            val reply = BufferedReader(InputStreamReader(it.inputStream)).readLine()
            sb.line("收到 Rust 服务端应答：\"$reply\"")
            sb.verdict(
                reply?.startsWith("rust-pong") == true,
                "应答由 Rust 服务端产生 ⇒ **Kotlin↔Rust 跨语言字节流打通**",
                "应答不符合预期：$reply",
            )
        }

        server.join(3000)
        sb.line("")
        sb.line("Rust 服务端日志：")
        (serverLog.get() ?: "（无）").trim().lines().forEach { l -> sb.line("  $l") }
        serverError.get()?.let { sb.line("  服务端错误：$it") }
        sb.line("")
        sb.line("【要点】abstract 命名空间 socket 无文件、无需清理、不受路径长度限制；")
        sb.line("SO_PEERCRED / peerCredentials 给出**内核保证**的对端身份，比 TCP 更适合本机通信。")
        return DemoResult.Success(sb.toString())
    }

    private fun rustUnixPath(context: Context): String = workPath(context, "selftest.sock")

    // ══════════════════════════════════════════════════════════════════
    // 6. TCP loopback
    // ══════════════════════════════════════════════════════════════════

    private fun tcpLoopback(): DemoResult {
        val sb = StringBuilder()
        sb.line("【目标】用 127.0.0.1 上的 TCP 连接做本机进程内往返，并对照 AF_UNIX")
        sb.line("pid=${Process.myPid()}")
        sb.line("")
        try {
            // 服务端线程：绑定临时端口，接受一次连接，回一行。
            val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
            val port = server.localPort
            sb.line("ServerSocket 绑定 127.0.0.1:$port（端口 0 → 内核分配临时端口）")
            val serverThread = Thread {
                runCatching {
                    server.accept().use { c ->
                        val line = BufferedReader(InputStreamReader(c.inputStream)).readLine()
                        c.getOutputStream().write("tcp-pong(len=${line?.length ?: 0})\n".toByteArray())
                        c.getOutputStream().flush()
                    }
                }
            }.apply { start() }

            Socket("127.0.0.1", port).use { c ->
                sb.line("客户端 Socket 已连接：local=${c.localSocketAddress} remote=${c.remoteSocketAddress}")
                c.getOutputStream().write("ping-over-tcp\n".toByteArray())
                c.getOutputStream().flush()
                val reply = BufferedReader(InputStreamReader(c.inputStream)).readLine()
                sb.line("收到应答：\"$reply\"")
                sb.verdict(reply?.startsWith("tcp-pong") == true, "TCP 本机往返成功", "应答异常：$reply")
            }
            serverThread.join(2000)
            server.close()
        } catch (t: Throwable) {
            return DemoResult.Failure("${t.javaClass.simpleName}: ${t.message}")
        }
        sb.line("")
        sb.line("【与 AF_UNIX 的对照】")
        sb.line("· TCP 走完整协议栈（含端口、校验、拥塞控制），可跨机；本机通信有额外开销。")
        sb.line("· AF_UNIX 走内核内部的 socket，无 TCP/IP 头，且能用 abstract 命名空间免清理。")
        sb.line("· 本机可信通信优先 AF_UNIX；需要跨机或复用现成 TCP 生态时才用 loopback TCP。")
        return DemoResult.Success(sb.toString())
    }

    // ══════════════════════════════════════════════════════════════════
    // 7. Linux 原生（转发到 Rust）
    // ══════════════════════════════════════════════════════════════════

    private fun nativeDemo(context: Context, kind: String, arg: String): DemoResult {
        if (!IpcNativeBridge.available) {
            return DemoResult.Failure("原生演示不可用：${IpcNativeBridge.describe()}")
        }
        val sb = StringBuilder()
        sb.line("【原生演示】kind=$kind  运行进程 pid=${Process.myPid()}")
        sb.line("（日志为 Rust 侧原文，未做二次加工）")
        sb.line("")
        val r = IpcNativeBridge.run(kind, arg)
        return r.fold(
            onSuccess = { log -> sb.append(log); DemoResult.Success(sb.toString()) },
            onFailure = { e -> DemoResult.Failure("native 调用失败：${e.message}") },
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // 辅助
    // ══════════════════════════════════════════════════════════════════

    /**
     * 绑定的收口：把 ServiceConnection 与「等待 + 保存结果 + 记录失败原因」包在一起。
     *
     * [context] 由构造传入，是为了让 [unbind] 能真正解绑 —— 之前把 unbind 写成空操作
     * 会**泄漏 ServiceConnection**（每个演示重绑一次，攒够后 bindService 会失败）。
     */
    private class ComputeConnection(private val context: Context) : ServiceConnection {
        @Volatile var remote: IRemoteCompute? = null
        @Volatile var failure: String? = null
        private val latch = CountDownLatch(1)
        private var unbound = false

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = IRemoteCompute.Stub.asInterface(service)
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 远端进程崩溃时也会走到这里（配合死亡通知一起看）。
            failure = "onServiceDisconnected"
        }

        fun await(timeoutMs: Long): IRemoteCompute? {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            return remote
        }

        fun unbind() {
            if (unbound) return
            unbound = true
            runCatching { context.unbindService(this) }
        }
    }

    private fun bindCompute(context: Context): ComputeConnection {
        val conn = ComputeConnection(context)
        val ok = context.bindService(
            Intent(context, com.interview.ipc.binder.RemoteComputeService::class.java)
                .setAction(com.interview.ipc.binder.RemoteComputeService.ACTION_BIND),
            conn, Context.BIND_AUTO_CREATE,
        )
        if (!ok) conn.failure = "bindService 返回 false"
        return conn
    }

    private fun workPath(context: Context, name: String): String =
        IpcNativeBridge.workDir(context.filesDir, name)

    private fun Context.currentProcess(): String = applicationInfo.processName ?: packageName

    private fun StringBuilder.line(s: String) = append(s).append('\n')

    /** 给一条结论加上明确的「成立 / 不成立」判定，避免读者自己猜。 */
    private fun StringBuilder.verdict(ok: Boolean, pass: String, fail: String) {
        append(if (ok) "✅ " else "❌ ").append(if (ok) pass else fail).append('\n')
    }
}
