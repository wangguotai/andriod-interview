package com.interview.thread

import android.util.Log
import java.util.concurrent.Executors

/**
 * Time: 2026/9/27
 * Author: wgt
 * Description: 第 1 步「规范层」对照 —— 故意复现线程失控的几种典型劣习
 *
 * 这些场景模拟三方 SDK 的常见行为，作为后续监控 / 插桩 / Hook 的「靶子」：
 * 1. 裸 new Thread 且不命名 —— 线上只会看到 Thread-12，无法溯源
 * 2. 每个请求都 new 一个池 —— 池泛滥，核心线程常驻
 * 3. 各 SDK 各自建池 —— 池与池之间不兼容，调度效率下降
 * 4. HandlerThread 滥用 —— 常驻等待，后台耗电
 */
object ThreadMisuseScenarios {

    private const val TAG = "ThreadMisuse"

    /**
     * 劣习 1：裸 new Thread，不命名。
     * 后果：线程快照里出现 Thread-N，无法判断是谁创建的。
     */
    fun bareUnnamedThreads(count: Int) {
        repeat(count) {
            Thread {
                sleepQuietly(3_000)
            }.start()  // ← 无名字，无 daemon 设置
        }
        Log.w(TAG, "劣习1：创建了 $count 个匿名 new Thread")
    }

    /**
     * 劣习 2：每个任务都新建一个线程池。
     * 后果：核心线程默认不回收 → 池越多，常驻线程越多。
     * 此处故意不关闭，模拟「创建后忘记 shutdown」的泄漏。
     */
    fun newPoolPerTask(count: Int) {
        repeat(count) { index ->
            val pool = Executors.newFixedThreadPool(2) { r ->
                Thread(r, "leaked-pool-$index-thread")  // 有命名，便于识别是这行造的
            }
            pool.execute { sleepQuietly(1_000) }
            // ⚠️ 故意不 shutdown()，让核心线程常驻
        }
        Log.w(TAG, "劣习2：创建了 $count 个未关闭的线程池")
    }

    /**
     * 劣习 3：模拟多个「SDK」各自建池（各自命名前缀）。
     * 后果：线程散落各处，无法统一监控 / 取消 / 设优先级。
     */
    fun multiSdkPools() {
        listOf("sdk-pay", "sdk-stat", "sdk-push", "sdk-map").forEach { sdkName ->
            val pool = Executors.newCachedThreadPool { r ->
                Thread(r, "$sdkName-worker")
            }
            repeat(2) {
                pool.execute { sleepQuietly(5_000) }
            }
        }
        Log.w(TAG, "劣习3：4 个 SDK 各自建了线程池")
    }

    /**
     * 劣习 4：HandlerThread 常驻。
     * 后果：线程进入 Looper 死循环等待，长期存活；若在后台则持续占用资源。
     */
    fun handlerThreadAbuse(count: Int) {
        repeat(count) { index ->
            val thread = android.os.HandlerThread("sdk-handler-$index").apply { start() }
            // 不做任何事，只让它常驻等待消息
            thread.looper
        }
        Log.w(TAG, "劣习4：启动了 $count 个常驻 HandlerThread")
    }

    /** 一次性投放全部劣习，用于生成对照基线。 */
    fun launchAll() {
        bareUnnamedThreads(10)
        newPoolPerTask(5)
        multiSdkPools()
        handlerThreadAbuse(5)
        Log.w(TAG, "已投放全部失控场景")
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
