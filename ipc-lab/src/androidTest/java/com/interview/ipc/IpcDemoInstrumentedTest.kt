package com.interview.ipc

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 在**真实设备**上逐个跑完 [IpcLabCatalog] 的每个演示，并断言证据成立。
 *
 * ─── 这个测试存在的意义 ───
 *
 * UI 上点一遍是「看起来跑了」；这个测试是「跑完并断言关键结论」。它把每个演示日志里
 * 的 ✅ 判定当断言目标 —— 那些判定本身就是「跨进程成立」的可复核条件（如两端 pid 不同）。
 * 一旦某个演示退化成同进程调用、或 native 库没打进 APK，这里会先红。
 *
 * 运行：./gradlew :ipc-lab:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class IpcDemoInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyDemoRunsAndProducesEvidence() {
        val failures = mutableListOf<String>()
        for (demo in IpcLabCatalog.DEMOS) {
            val result = IpcDemoRunner.run(context, demo.id)
            when (result) {
                is DemoResult.Failure -> failures += "${demo.id}: ${result.reason}"
                is DemoResult.Success -> {
                    println("========== ${demo.id} ==========")
                    println(result.log)
                    // 每个演示至少要有内容；并且不能出现「❌」的失败判定。
                    if (result.log.isBlank()) failures += "${demo.id}: 日志为空"
                    if (result.log.contains("❌")) {
                        failures += "${demo.id}: 出现失败判定 ❌\n${result.log.lines().filter { it.contains("❌") }}"
                    }
                }
            }
        }
        assertTrue("以下演示未通过：\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    /**
     * 单独、显式地断言「确实跨进程」这一条 —— 这是本 Lab 的立身之本。
     * 用 AIDL 同步调用返回的 pid 与客户端 pid 对比。
     */
    @Test
    fun binderCallCrossesProcessBoundary() {
        val result = IpcDemoRunner.run(context, "binder_sync")
        assertTrue("binder_sync 应成功", result is DemoResult.Success)
        val log = (result as DemoResult.Success).log
        assertTrue("应出现「确实跨进程」判定：\n$log", log.contains("确实跨进程"))
    }

    @Test
    fun nativeLibraryProvidesAllDemos() {
        val supported = com.interview.ipc.nativebridge.IpcNativeBridge.supported()
        assertTrue("native 库应可用：${com.interview.ipc.nativebridge.IpcNativeBridge.describe()}", supported.isNotEmpty())
        for (kind in listOf("pipe", "fifo", "shm", "signal", "flock", "unix")) {
            assertTrue("native 应支持 $kind，实际=$supported", supported.contains(kind))
        }
    }
}
