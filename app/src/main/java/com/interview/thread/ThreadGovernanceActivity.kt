package com.interview.thread

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Time: 2026/9/27
 * Author: wgt
 * Description: 线程治理方案 —— 分步演示宿主
 *
 * 用法：点击按钮观察 logcat（TAG: ThreadDemo / ThreadMonitor / ThreadMisuse / ThreadDefense）
 * 每步都对应治理方案的四层防线。
 */
class ThreadGovernanceActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_thread_governance)
        output = findViewById(R.id.tv_output)

        bind(R.id.btn_step1_pools) {
            emit("【第1步 规范层】统一线程池已就绪\n${ThreadPools.describe()}")
            Log.i(TAG, "统一线程池:\n${ThreadPools.describe()}")
        }

        bind(R.id.btn_step1_misuse) {
            ThreadMisuseScenarios.launchAll()
            emit("【第1步】已投放失控线程场景（裸Thread/池泛滥/多SDK池/HandlerThread）\n" +
                    "点「线程快照」查看结果")
        }

        bind(R.id.btn_step2_snapshot) {
            val snap = ThreadMonitor.snapshot()
            emit(snap.formatTop(25))
            Log.i(TAG, "线程总数=${snap.total} 业务=${snap.business} 不可溯源=${snap.defaultNamed}")
        }

        bind(R.id.btn_step2_compare) {
            // 对照实验：分别用统一池和默认工厂池执行任务，比对命名率
            val latch = CountDownLatch(6)
            repeat(6) {
                ThreadPools.background.execute("demo.naming") { sleepQuietly(1500); latch.countDown() }
                ThreadPools.unnamed.execute { sleepQuietly(1500); latch.countDown() }
            }
            latch.await(3, TimeUnit.SECONDS)
            val snap = ThreadMonitor.snapshot()
            emit("【第2步】命名率对照\n" +
                    "统一池线程名形如 app-bg-N（可溯源）\n" +
                    "默认工厂线程名形如 pool-N-thread-M（不可溯源）\n\n" +
                    "当前不可溯源占比：${"%.1f".format(snap.defaultNamedRatio * 100)}%\n" +
                    snap.formatTop(15))
        }

        bind(R.id.btn_lane_isolation) {
            emit("【第1步 泳道隔离对照】\n${ThreadPools.demoLaneIsolation()}")
        }

        bind(R.id.btn_step4_stack) {
            // 栈压缩对照：同一个环境下，不同栈大小的创建结果
            val defaultResult = ThreadDefense.stressTestThreadCreation(0L, 50)
            val smallResult = ThreadDefense.stressTestThreadCreation(ThreadDefense.STACK_SMALL, 50)
            emit("【第4步 栈压缩】\n$defaultResult\n$smallResult\n\n" +
                    "说明：栈占用的是『虚拟地址空间』reserve，非物理内存。\n" +
                    "32 位设备用户态地址空间 <3GB，几百个默认栈线程即可耗尽 → pthread_create OOM。")
        }

        bind(R.id.btn_step4_limit) {
            val limiter = ThreadDefense.ThreadLimiter(maxThreads = 8)
            val result = ThreadDefense.demoThermalLimiting(limiter, 30)
            emit("【第4步 限流】\n$result")
        }

        bind(R.id.btn_step4_hook) {
            val installed = NativeThreadHook.install()
            emit("【第4步 Native Hook】\n" +
                    "安装结果：${if (installed) "成功" else "失败（当前 ROM/ABI 不支持，已优雅降级）"}\n\n" +
                    "原理：Thread.start() → nativeCreate → pthread_create 是唯一收口点，\n" +
                    "Hook 后可捕获**所有**线程创建，包括改不了源码的三方 SDK。\n\n" +
                    "方案：本项目用 GOT Hook（兼容性优先，可上线）；\n" +
                    "若作为线下排查工具，可换 Inline Hook 换取更广覆盖。\n\n" +
                    "点「制造失控」后观察 TAG: ThreadHook 的计数")
        }

        bind(R.id.btn_priority_diag) {
            emit("【优先级设置对照】\n${ThreadPriorityDiagnostic.run()}")
        }

        bind(R.id.btn_native_enforce) {
            NativeThreadHook.applyStackShrinkPolicy()
            emit(
                "【Hook 降级已开启】策略：ThreadMisuseScenarios.* → DEMOTE（压栈）\n\n" +
                        "实测得到的**关键边界**（本 Demo 验证过）：\n" +
                        "  ❌ 在 pthread 入口 setpriority 设 nice 无效 ——\n" +
                        "     ART 在 Java 线程 run() 时重新应用 Java priority，\n" +
                        "     把它覆盖回 0。探针证据：设置后回读 10，\n" +
                        "     但业务真跑起来时 /proc 里是 0。\n" +
                        "  ✅ 改 pthread_attr 的栈大小有效 ——\n" +
                        "     栈在 pthread_create 时就 mmap 定死，ART 改不了。\n\n" +
                        "结论：治内存（栈）交给 native；\n" +
                        "      治调度（优先级）必须留给 Java 层 ThreadFactory。\n\n" +
                        "点「制造失控」，看下方日志的「栈 1040KB → 256KB」。"
            )
        }

        bind(R.id.btn_native_reject) {
            // 危险演示：故意拒绝，观察调用方拿到什么
            NativeThreadHook.rules = listOf(
                NativeThreadHook.Rule(
                    "com.interview.thread.ThreadMisuseScenarios.bareUnnamedThreads",
                    NativeThreadHook.Action.REJECT
                ),
                NativeThreadHook.Rule(
                    "com.interview.thread.ThreadMisuseScenarios",
                    NativeThreadHook.Action.DEMOTE
                ),
                NativeThreadHook.Rule("", NativeThreadHook.Action.ALLOW),
            )
            NativeThreadHook.enforceEnabled = true
            NativeThreadHook.reset()
            emit(
                "【危险演示：拒绝创建】bareUnnamedThreads → REJECT\n" +
                        "点「制造失控」后注意：\n" +
                        "  · 第 1 个线程被拒 → ART 抛 OutOfMemoryError\n" +
                        "  · 该异常会中断 launchAll()，后续场景不再执行\n" +
                        "  · logcat 可见 “pthread_create (1040KB stack) failed: Try again”\n\n" +
                        "结论：拒绝是「核选项」，仅在极端场景（如已 OOM 边缘）慎用。"
            )
        }

        bind(R.id.btn_native_raw) {
            NativeThreadHook.enforceEnabled = false
            NativeThreadHook.rules = listOf(NativeThreadHook.Rule("", NativeThreadHook.Action.ALLOW))
            NativeThreadHook.reset()
            emit("【Hook 治理已关闭】恢复纯观测（全部放行）。\n再点「制造失控」即对照组：线程正常大量创建。")
        }

        bind(R.id.btn_native_count) {
            val sites = NativeThreadHook.creationSitesSnapshot(12)
            emit(buildString {
                appendLine("【Native Hook 统计】")
                appendLine("捕获到线程创建：${NativeThreadHook.nativeCreatedCount.get()} 次")
                appendLine("  ├ 来自 Java 线程：${NativeThreadHook.fromJavaCount.get()} 次（可溯源）")
                appendLine("  └ 来自 Native 线程：${NativeThreadHook.fromNativeCount.get()} 次（无 Java 栈）")
                appendLine("被 ASM 收敛的线程：${UnifiedThread.convergedCount.get()} 次")
                appendLine()
                appendLine("── 治理决策 ──")
                appendLine("  放行 ${NativeThreadHook.allowCount.get()} / " +
                        "降级 ${NativeThreadHook.demoteCount.get()} / " +
                        "拒绝 ${NativeThreadHook.rejectCount.get()}")
                appendLine("  策略状态：${if (NativeThreadHook.enforceEnabled) "已开启" else "仅观测"}")
                if (sites.isNotEmpty()) {
                    appendLine()
                    appendLine("── 线程创建者分布（谁在造线程）──")
                    sites.forEach { (site, n) -> appendLine("  ×$n  $site") }
                }
            })
        }

        bind(R.id.btn_calibration) {
            emit("【泳道并发标定】开始扫描（约 1 分钟，请勿切后台）…\n" +
                    "判据：吞吐平台起点 vs P99 劣化点，取较小者为 core 上界")
            LaneCalibration.runAllAsync(this) { report -> emit(report) }
        }

        bind(R.id.btn_clear) {
            log.setLength(0)
            emit("已清空")
        }
    }

    private fun bind(id: Int, action: () -> Unit) {
        findViewById<Button>(id).setOnClickListener {
            runCatching { action() }.onFailure { emit("执行失败：${it.message}") }
        }
    }

    private fun emit(text: String) {
        runOnUiThread {
            log.append(text).append("\n\n")
            output.text = log.toString()
        }
    }

    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
    }

    companion object {
        private const val TAG = "ThreadDemo"
    }
}
