package com.interview.ipc.binder

import android.os.Parcel
import android.os.Parcelable
import android.os.Process

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 跨进程传递的「元信息」，用于让每条 IPC 证据自证来源。
 *
 * 每个字段都不是装饰：
 *  - [pid]：证明结果来自**另一个进程**（不同 pid 才算真跨进程）；
 *  - [tid]：证明处理调用的是 Binder 线程池里的哪个线程（不同并发调用应给出不同 tid）；
 *  - [uid]：证明服务端确实以独立身份运行；
 *  - [binderThread]：Binder 驱动当前执行在本进程的第几个线程；
 *  - [processName]：人类可读的进程名，上屏时一眼能认出 `:ipc_remote`。
 *
 * ─── 为什么手写 Parcelable 而不是 @Parcelize ───
 *
 * 本 module 不引 kotlin-parcelize 插件，手写 writeToParcel 只有几行，
 * 而且能让「字段顺序必须严格一致」这条跨进程契约**肉眼可见**。
 * Parcelable 的字段读写顺序一旦两端不一致，就是「读到错位数据但不报错」的
 * 静默 bug —— 手写反而更容易在 review 时发现。
 */
data class RemoteInfo(
    val pid: Int,
    val tid: Int,
    val uid: Int,
    val binderThread: Int,
    val processName: String,
) : Parcelable {

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeInt(pid)
        dest.writeInt(tid)
        dest.writeInt(uid)
        dest.writeInt(binderThread)
        dest.writeString(processName)
    }

    override fun describeContents(): Int = 0

    override fun toString(): String =
        "pid=$pid tid=$tid uid=$uid binderThread=$binderThread proc=$processName"

    companion object {
        /** 采集**当前进程/线程**的信息。在服务端调用它，得到的就是远端的身份。 */
        @JvmStatic
        fun here(processName: String): RemoteInfo = RemoteInfo(
            pid = Process.myPid(),
            tid = Process.myTid(),
            uid = Process.myUid(),
            binderThread = currentBinderThreadId(),
            processName = processName,
        )

        /**
         * 取当前线程在 Binder 线程池里的编号。
         *
         * `Binder.getCallingPid()` 拿到的是**调用方**的 pid，不是本线程编号；
         * 能标识「本线程是池里第几号」的是线程名，形如 `binder:1234_2`。
         * 这里解析下划线后的数字作为编号；不是 binder 线程（如主线程）时返回 -1，
         * 让证据里一眼能看出「这次没走线程池」。
         */
        private fun currentBinderThreadId(): Int {
            val name = Thread.currentThread().name ?: return -1
            val idx = name.lastIndexOf('_')
            if (idx < 0 || !name.startsWith("binder:")) return -1
            return name.substring(idx + 1).toIntOrNull() ?: -1
        }

        @JvmField
        val CREATOR: Parcelable.Creator<RemoteInfo> = object : Parcelable.Creator<RemoteInfo> {
            override fun createFromParcel(source: Parcel): RemoteInfo = RemoteInfo(
                pid = source.readInt(),
                tid = source.readInt(),
                uid = source.readInt(),
                binderThread = source.readInt(),
                processName = source.readString() ?: "",
            )

            override fun newArray(size: Int): Array<RemoteInfo?> = arrayOfNulls(size)
        }
    }
}
