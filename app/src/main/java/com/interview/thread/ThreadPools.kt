package com.interview.thread

import android.os.Process
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Time: 2026/9/27
 * Author: wgt
 * Description: 第 1 步「规范层」——全 App 统一线程池收口
 *
 * 设计要点：
 * 1. 所有业务线程池集中在此，禁止业务代码裸用 new Thread / Executors.newXxx
 * 2. 每个池都显式提供 ThreadFactory，做到「语义化命名 + daemon + 优先级」
 * 3. 线程命名是后续监控的前提：默认工厂产出的 pool-1-thread-1 / Thread-7
 *    在线上线程快照里根本无法定位来源
 */
object ThreadPools {

    /**
     * 统一 ThreadFactory：
     * - 语义化命名：便于线程快照、Hook 回溯、线上问题定位
     * - daemon：不阻止 JVM 退出（Android 进程被回收时）
     * - 优先级：后台任务降优先级，减少对主线程调度的干扰
     */
    class NamedThreadFactory(
        private val prefix: String,
        private val daemon: Boolean = true,
        private val priority: Int = Thread.NORM_PRIORITY,
    ) : ThreadFactory {
        private val counter = AtomicInteger(0)

        override fun newThread(r: Runnable): Thread = Thread(r, "$prefix-${counter.incrementAndGet()}").apply {
            isDaemon = daemon
            this.priority = this@NamedThreadFactory.priority
        }
    }

    /** IO 密集型：网络、文件、数据库。核心线程可回收，避免常驻占用。 */
    val io: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            CORE_IO, MAX_IO, 60L, TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            NamedThreadFactory("app-io", priority = Thread.NORM_PRIORITY - 1),
        ).apply {
            allowCoreThreadTimeOut(true)
            // 线程创建失败时的兜底：降级为调用者线程执行，避免直接抛 OOM
            rejectedExecutionHandler = ThreadPoolExecutor.CallerRunsPolicy()
        }
    }

    /** CPU 密集型：计算。核心数 = 核数 ± 1，超出只会增加上下文切换损耗。 */
    val cpu: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            CORE_CPU, CORE_CPU, 30L, TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            NamedThreadFactory("app-cpu"),
        ).apply {
            allowCoreThreadTimeOut(true)
        }
    }

    /** 串行任务：需要保证顺序的场景。 */
    val single: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            LinkedBlockingQueue(),
            NamedThreadFactory("app-single"),
        )
    }

    /** 定时 / 延时任务。 */
    val scheduled: ScheduledExecutorService by lazy {
        Executors.newScheduledThreadPool(2, NamedThreadFactory("app-scheduled"))
    }

    /**
     * 演示用：一个「不受控」的池，故意用默认 ThreadFactory + SynchronousQueue，
     * 用来在线程快照里对照出「命名率」差异。
     */
    val unnamed: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            0, 32, 60L, TimeUnit.SECONDS,
            SynchronousQueue(),
            Executors.defaultThreadFactory(), // ← 产出 pool-1-thread-N
        )
    }

    /** 收敛池：ASM 把 new Thread 收敛后的最终落点 */
    val converged: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            2, 8, 60L, TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            NamedThreadFactory("app-converged", priority = Thread.NORM_PRIORITY - 1),
        ).apply {
            allowCoreThreadTimeOut(true)
            rejectedExecutionHandler = ThreadPoolExecutor.CallerRunsPolicy()
        }
    }

    private val CORE_IO = 4
    private val MAX_IO = 16
    private val CORE_CPU = maxOf(2, Runtime.getRuntime().availableProcessors() - 1)

    /** 供 Demo 展示：当前各统一池的配置概览。 */
    fun describe(): String = buildString {
        appendLine("当前设备 CPU 核数：${Runtime.getRuntime().availableProcessors()}")
        appendLine("io        core=${io.corePoolSize} max=${io.maximumPoolSize}  实际=${io.poolSize}")
        appendLine("cpu       core=${cpu.corePoolSize} max=${cpu.maximumPoolSize}  实际=${cpu.poolSize}")
        appendLine("single    core=1  实际=${single.poolSize}")
        appendLine("scheduled 实际=${(scheduled as ThreadPoolExecutor).poolSize}")
        appendLine("converged 实际=${converged.poolSize}")
        appendLine("unnamed   实际=${unnamed.poolSize}")
    }
}
