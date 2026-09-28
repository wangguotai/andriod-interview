package com.interview.thread

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Time: 2026/9/27
 * Author: wgt
 * Description: 第 4 步「运行时防御」—— 栈压缩 与 线程限流
 *
 * 两个兜底手段，用于那些改不了源码、又插不进桩的三方 SDK：
 *
 * 1. 栈压缩：Thread 构造第 4 个参数 stackSize 会一路传到 pthread_attr_setstacksize。
 *    默认子线程栈 <1MB（虚拟地址空间 reserve），压到 256KB/64KB 可把虚拟空间占用砍一个数量级。
 *    ⚠️ 关键认知：省的是「虚拟地址空间」，不是物理内存。所以对 32 位设备意义最大。
 *
 * 2. 限流：全局统计活跃线程数，超阈值拒绝新建，避免 pthread_create failed OOM。
 */
object ThreadDefense {

    private const val TAG = "ThreadDefense"

    /** 归因用：限流器提交到后台泳道的 caller 标识 */
    private const val CALLER = "ThreadDefense.limiter"

    /** PTHREAD_STACK_MIN 在多数架构是 16KB，留足余量避免栈溢出 */
    const val STACK_TINY = 64 * 1024L
    const val STACK_SMALL = 256 * 1024L

    /**
     * 创建一个指定栈大小的线程。
     * stackSize 为 0 时使用系统默认（Android 子线程 <1MB）。
     */
    fun newThreadWithStack(
        name: String,
        stackSize: Long,
        block: () -> Unit,
    ): Thread = Thread(null, block, name, stackSize).apply {
        isDaemon = true
    }

    /**
     * 演示：用不同栈大小创建大量线程，观察虚拟地址空间的差异。
     * 这个 Demo 只在 32 位设备上能真正撞到 OOM，64 位下只统计成功数。
     */
    fun stressTestThreadCreation(stackSize: Long, count: Int): String {
        val created = AtomicInteger(0)
        var failure: Throwable? = null
        val threads = mutableListOf<Thread>()

        repeat(count) { index ->
            try {
                val t = newThreadWithStack("stack-test-$index", stackSize) {
                    // 立刻结束，只关心「能否创建成功」
                }
                threads += t
                t.start()
                created.incrementAndGet()
            } catch (e: OutOfMemoryError) {
                failure = e
                return@repeat
            } catch (e: Throwable) {
                failure = e
                return@repeat
            }
        }
        threads.forEach { runCatching { it.join(1000) } }

        val label = if (stackSize == 0L) "系统默认(<1MB)" else "${stackSize / 1024}KB"
        val msg = buildString {
            append("栈大小=$label 目标=$count 成功创建=${created.get()}")
            failure?.let { append("  失败原因=${it.javaClass.simpleName}: ${it.message}") }
        }
        Log.i(TAG, msg)
        return msg
    }

    /**
     * 线程限流器：全局活跃线程数守卫。
     * 超过阈值时拒绝新建任务，走降级逻辑（丢弃 / 排队 / 调用者线程执行）。
     */
    class ThreadLimiter(private val maxThreads: Int) {
        private val active = AtomicInteger(0)

        /** 执行任务；超过上限则拒绝执行并回调。 */
        fun execute(taskName: String, task: Runnable): Boolean {
            val current = active.get()
            if (current >= maxThreads) {
                Log.w(TAG, "限流命中：活跃=$current 上限=$maxThreads，拒绝任务 [$taskName]")
                return false
            }
            active.incrementAndGet()
            val accepted = ThreadPools.background.execute(CALLER) {
                try {
                    task.run()
                } finally {
                    active.decrementAndGet()
                }
            }
            if (!accepted) {
                // 泳道队列满：交由限流器自身语义处理（视为拒绝，让上层降级）
                active.decrementAndGet()
                Log.w(TAG, "限流命中：泳道队列已满，拒绝任务 [$taskName]")
                return false
            }
            return true
        }

        fun activeCount(): Int = active.get()
    }

    /** 演示限流：故意提交超过上限的任务，观察拒绝行为。 */
    fun demoThermalLimiting(limiter: ThreadLimiter, total: Int): String {
        var rejected = 0
        repeat(total) { index ->
            val accepted = limiter.execute("task-$index") {
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                }
            }
            if (!accepted) rejected++
        }
        val msg = "提交 $total 个任务，被限流拒绝 $rejected 个（并发上限=${limiter.activeCount()}）"
        Log.i(TAG, msg)
        return msg
    }

    // ─────────────────────────────────────────────
    // 补充：ASM/Hook 之前的「无侵入」替代 —— 运行时替换默认 ThreadFactory
    // ─────────────────────────────────────────────

    /**
     * 三方 SDK 常直接用 Executors.defaultThreadFactory()。
     * 虽然无法改成它们的调用点，但可以在自己的代码里统一用这个包装。
     *
     * 对已经编译好的三方 SDK，只能靠 ASM（改 class）或 Native Hook（改运行时）来收口。
     */
    fun unifiedFactory(prefix: String) = ThreadPools.NamedThreadFactory(prefix)
}
