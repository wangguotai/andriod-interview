package com.interview.ipc.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.interview.ipc.ProcName
import java.io.File
import java.io.FileNotFoundException

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: ContentProvider 服务端（跑在 `:ipc_remote` 进程）。
 *
 * ─── ContentProvider 作为 IPC 的定位 ───
 *
 * Provider 由 **AMS 拉起/托管**，对外是 CRUD + call + openFile 的统一门面。它的强项是
 * **结构化数据 / 文件描述符的跨进程访问**，弱项是「请求-应答」语义（`call` 能做，
 * 但语义不如 AIDL 清晰）。它天然支持权限控制（readPermission/writePermission）。
 *
 * ─── 本 Provider 演示三条链路 ───
 *
 * 1. **结构化数据（Cursor）**：`query` 返回一张由 MatrixCursor 构造的小表，
 *    第一行固定为「元信息行」（pid / process）—— 客户端读到它即可证明数据来自另一进程。
 * 2. **方法调用（call）**：`call("sum", ...)` 走 Bundle 传参、Bundle 返回，
 *    等价于一次轻量 RPC。这是 Provider 上最接近「请求-应答」的用法。
 * 3. **文件描述符（openFile）**：`openFile` 打开一个落在本应用 filesDir 下的文件并返回
 *    `ParcelFileDescriptor`。**这是 framework 层的「传 fd」**，与 Rust 侧 `SCM_RIGHTS`
 *    是同一件事的不同实现 —— 客户端可借此读到大块数据（零拷贝的外壳）。
 *
 * ─── 为什么 openFile 返回的是「本 Provider 私有目录下的文件」───
 *
 * `ParcelFileDescriptor` 只能在**服务端自己有权访问**的路径上打开文件，然后
 * 把已打开的 fd 传给客户端；客户端拿到 fd 后按 fd 读写，**不需要**客户端的文件权限。
 * 这正是「传 fd 而不是传路径」的价值：绕过了对端文件系统权限的限制。
 */
class IpcDemoProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        Log.i(TAG, "onCreate ${ProcName.describe()}")
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        // 返回固定 schema 的小表，列名与客户端约定一致。
        val cursor = MatrixCursor(COLUMNS)
        // 第一行是「元信息行」：它的存在本身就是跨进程的证据。
        cursor.addRow(arrayOf("_meta", Process.myPid(), processName(), processName()))
        // 触发一次按 [uri] 路径分发的内容：/sum/a/b 直接算加法，用于演示「用 URI 传参」
        val seg = uri.pathSegments
        if (seg.size >= 3 && seg[0] == "sum") {
            val a = seg[1].toIntOrNull() ?: 0
            val b = seg[2].toIntOrNull() ?: 0
            cursor.addRow(arrayOf("sum", a, b, a + b))
        } else {
            // 常规数据行
            for (i in 1..3) {
                cursor.addRow(arrayOf("row$i", i, "value-$i", Process.myPid()))
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.ipclab.demo"

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        // 演示用：不做持久化，只回显「收到了什么」。真业务应落库并 notifyChange。
        Log.i(TAG, "insert values=$values at $uri")
        return Uri.parse("content://$AUTHORITY/echo/${values?.toString()?.hashCode() ?: 0}")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        Log.i(TAG, "delete at $uri")
        return 0
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        Log.i(TAG, "update at $uri")
        return 0
    }

    /**
     * 轻量 RPC：`call(method, arg, extras)` → `Bundle`。
     *
     * 支持的 method：
     *   - `"sum"`：extras 里取 a/b，返回 KEY_SUM；
     *   - `"pid"`：返回本进程 pid / 进程名；
     *   - `"echo"`：原样回显 extras 里的 KEY_TEXT。
     */
    override fun call(method: String, arg: String?, extras: android.os.Bundle?): android.os.Bundle {
        Log.i(TAG, "call($method, $arg) pid=${Process.myPid()}")
        val out = android.os.Bundle()
        out.putInt(KEY_REMOTE_PID, Process.myPid())
        out.putString(KEY_REMOTE_PROCESS, processName())
        when (method) {
            "sum" -> {
                val a = extras?.getInt(KEY_A) ?: 0
                val b = extras?.getInt(KEY_B) ?: 0
                out.putInt(KEY_SUM, a + b)
            }
            "pid" -> { /* 元信息已在上面填好 */ }
            "echo" -> out.putString(KEY_TEXT, "echo: ${extras?.getString(KEY_TEXT) ?: arg}")
            else -> out.putString(KEY_TEXT, "unknown method: $method")
        }
        return out
    }

    /**
     * 打开一个文件并返回 fd。客户端用返回的 `ParcelFileDescriptor` 读取，
     * 无需自己对该路径有权限。
     *
     * `mode` 支持 "r" / "w" / "rw"。演示只用到 "r"。
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("no context")
        val dir = File(ctx.filesDir, "ipc-provider").apply { mkdirs() }
        val file = File(dir, "shared-by-fd.txt")
        // 写入内容（服务端负责准备数据）。每次打开都刷新，保证内容可预期。
        file.writeText(
            "这段内容由 ContentProvider 进程(pid=${Process.myPid()})写入文件，\n" +
                "并通过 openFile() 返回的 ParcelFileDescriptor 把 **fd** 传给客户端；\n" +
                "客户端按 fd 读取，无需对该路径有任何权限 —— 这就是「传 fd 而非传路径」。\n"
        )
        Log.i(TAG, "openFile($uri, $mode) → ${file.absolutePath}")
        val flags = ParcelFileDescriptor.parseMode(mode)
        return ParcelFileDescriptor.open(file, flags)
    }

    private fun processName(): String = ProcName.current()

    companion object {
        private const val TAG = "IpcLab/Provider"
        const val AUTHORITY = "com.interview.ipclab.ipcprovider"

        val COLUMNS = arrayOf("key", "a", "b", "remote_pid")

        const val KEY_TEXT = "text"
        const val KEY_A = "a"
        const val KEY_B = "b"
        const val KEY_SUM = "sum"
        const val KEY_REMOTE_PID = "remote_pid"
        const val KEY_REMOTE_PROCESS = "remote_process"
    }
}
