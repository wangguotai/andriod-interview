package com.interview.ipc

import android.content.Context
import android.os.Process
import android.util.Log
import com.interview.ipc.nativebridge.IpcNativeBridge
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

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
 * 本文件随里程碑逐步生长：M1 引入 AF_UNIX（abstract/filesystem）+ TCP loopback。
 */
object IpcDemoRunner {

    private const val TAG = "IpcLab/Runner"

    /** 一次演示的统一入口。 */
    fun run(context: Context, id: String): DemoResult = try {
        when (id) {
            "local_socket_abstract" -> localSocketAbstract(context)
            "local_socket_rust_selftest" -> nativeDemo(context, "unix", rustUnixPath(context))
            "tcp_loopback" -> tcpLoopback()
            else -> DemoResult.Failure("未知演示 id: $id")
        }
    } catch (t: Throwable) {
        // 任何异常都要变成「可读的失败」，不能让 UI 崩。
        Log.e(TAG, "演示 $id 抛异常", t)
        DemoResult.Failure("${t.javaClass.simpleName}: ${t.message}")
    }

    // ══════════════════════════════════════════════════════════════════
    // 1. LocalSocket（抽象命名空间）↔ Rust AF_UNIX 服务端
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
        var socket: android.net.LocalSocket? = null
        var lastErr: String? = null
        for (attempt in 1..50) {
            try {
                val s = android.net.LocalSocket(android.net.LocalSocket.SOCKET_STREAM)
                s.connect(
                    android.net.LocalSocketAddress(
                        name,
                        android.net.LocalSocketAddress.Namespace.ABSTRACT,
                    ),
                )
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
    // 2. TCP loopback
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
    // 3. Linux 原生（转发到 Rust）
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

    private fun workPath(context: Context, name: String): String =
        IpcNativeBridge.workDir(context.filesDir, name)

    private fun StringBuilder.line(s: String) = append(s).append('\n')

    /** 给一条结论加上明确的「成立 / 不成立」判定，避免读者自己猜。 */
    private fun StringBuilder.verdict(ok: Boolean, pass: String, fail: String) {
        append(if (ok) "✅ " else "❌ ").append(if (ok) pass else fail).append('\n')
    }
}
