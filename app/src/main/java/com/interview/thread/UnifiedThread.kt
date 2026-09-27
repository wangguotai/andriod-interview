package com.interview.thread

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Time: 2026/9/27
 * Author: wgt
 * Description: 第 3 步「收敛层」的目标类 —— 线程统一收口
 *
 * 由 ASM 插件在编译期把 `new Thread(...)` 的 owner 替换成本类
 * （同时改 NEW 与 <init> 两个指令，否则校验器会抛 VerifyError）。
 *
 * ─── 核心洞察 ───
 * 我们不统一 Thread，而是统一 **Runnable 的执行环境**。
 *
 * 原因：Thread 创建依赖局部变量表（ASTORE/ALOAD 下标由编译上下文决定），
 * 无法做「整体替换」；但 Runnable 是干净的突破点——
 * 把它的执行位置从「新建线程」改成「统一线程池」，就完成了收敛。
 *
 * ⚠️ 必须兼容所有被替换的 Thread 构造函数签名，且构造里不能做阻塞操作。
 */
class UnifiedThread : Thread {

    /** 本次收敛的原始线程名，用于日志对照 */
    val originalName: String

    /** 被收敛的 Runnable */
    private val task: Runnable?

    constructor() : super() {
        this.task = null
        this.originalName = "unnamed"
    }

    constructor(r: Runnable?) : super(r) {
        this.task = r
        this.originalName = "unnamed"
    }

    constructor(name: String?) : super(name) {
        this.task = null
        this.originalName = name ?: "unnamed"
    }

    constructor(r: Runnable?, name: String?) : super(r, name) {
        this.task = r
        this.originalName = name ?: "unnamed"
    }

    constructor(group: ThreadGroup?, r: Runnable?) : super(group, r) {
        this.task = r
        this.originalName = "unnamed"
    }

    constructor(group: ThreadGroup?, name: String?) : super(group, name) {
        this.task = null
        this.originalName = name ?: "unnamed"
    }

    constructor(group: ThreadGroup?, r: Runnable?, name: String?) : super(group, r, name) {
        this.task = r
        this.originalName = name ?: "unnamed"
    }

    constructor(group: ThreadGroup?, r: Runnable?, name: String?, stackSize: Long) :
            super(group, r, name, stackSize) {
        this.task = r
        this.originalName = name ?: "unnamed"
    }

    /**
     * 关键覆写：不真正创建新线程，而是把 Runnable 提交到统一线程池。
     * 这就是「统一 Runnable 执行环境」的落点。
     */
    override fun start() {
        val r = task
        if (r == null) {
            Log.w(TAG, "UnifiedThread.start() 无 Runnable（无参构造），跳过收敛：$originalName")
            return
        }
        Log.d(TAG, "收敛成功：[$originalName] 由「新建线程」改为「提交统一池 app-converged」")
        convergedCount.incrementAndGet()
        try {
            ThreadPools.converged.execute(r)
        } catch (e: Throwable) {
            // 兜底：统一池不可用时降级为原地执行，保持业务不中断
            Log.e(TAG, "提交统一池失败，降级原地执行", e)
            r.run()
        }
    }

    companion object {
        private const val TAG = "UnifiedThread"

        /** 统计被收敛的线程总数，供 Demo 展示 */
        val convergedCount = AtomicInteger(0)
    }
}
