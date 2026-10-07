package com.interview.稳定性监控

import android.os.Bundle
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.interview.thread.ThreadPools
import java.util.concurrent.TimeUnit

/**
 * Time: 2026/10/7
 * Author: wgt
 * Description: 线上 ANR / Crash / 卡顿监控 —— 可运行演示宿主
 *
 * ─── 设计原则：每个按钮都对应**一条可证伪的判断**，不是"点一下看看日志" ───
 *
 * 三组对照实验是这些按钮的价值所在：
 *
 * ① **主线程 sleep vs 忙等** → 证明「ANR 哨兵判定的是'没响应'，不是'CPU 高'」
 * ② **后台线程崩 vs 主线程崩** → 证明「后台异常默认也会杀进程」；装了兜底后
 *    后台不死、主线程必死（判断依据是 **PID**，不是 logcat 里的 FATAL 行）
 * ③ **自报 ANR vs 真 ANR** → 证明「self ANR 与系统 ANR 的 trace 差异」，
 *    以及为什么必须有主动探测通道
 *
 * ⚠️ 有两个按钮是**危险演示**（真 ANR 15s / 主线程崩），会真的把进程搞死或让系统
 *    弹"无响应"框。它们的存在是为了让"证据"可复现，不是给日常使用。
 *    危险按钮在执行前都会先在输出区写明「预期结果」，便于事后核对。
 */
class StabilityLabActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private val log = StringBuilder()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stability_lab)
        output = findViewById(R.id.tv_output)

        // 接入监控（幂等；Application 里其实已经装过了，这里是为了让"单页可独立演示"）
        StabilityMonitor.installEarly(application)
        StabilityMonitor.installOnCreate(application)
        // ⭐ 挂帧监控：FrameMetrics 是 per-Window 的，必须在窗口存在后才挂
        StabilityMonitor.watchJank(this)

        bind(R.id.btn_install) {
            emit(
                "【接入清单】本页已启用：\n" +
                        "  Application.attachBaseContext → CrashMonitor.install（最早，兜住初始化崩溃）\n" +
                        "  Application.onCreate         → beginSession / recoverPending / ANR探针 / 采样器\n" +
                        "  后台泳道                     → ExitInfo 回捞（不阻塞启动）\n" +
                        "  Activity                     → JankMonitor.watch(this)（本步）\n\n" +
                        "⚠️ 刻意**不**在 Application 里自动 watch 每个 Activity：" +
                        "FrameMetrics 回调每帧一次，\n   在生命周期里做重活本身就是最常见的卡顿源。\n" +
                        "   生产环境要全量挂，请按会话采样（如 1/50），而不是全开。"
            )
        }

        bind(R.id.btn_overview) {
            emit(StabilityMonitor.formatOverview())
        }

        bind(R.id.btn_clear) {
            log.setLength(0)
            StabilityMonitor.resetAll()
            emit("已清空输出与内存统计")
        }

        // ═══════ 卡顿 ═══════

        bind(R.id.btn_jank_slow3s) {
            emit(
                "【卡顿对照 A：主线程 sleep 3s】\n" +
                        "预期：\n" +
                        "  · 帧监控：3 秒里一帧都没画出来（不是「某帧慢」，是「帧停了」）\n" +
                        "  · ANR 哨兵：连续 2 轮无响应 → 报 ANR（估算阻塞 ≈ 2000ms+）\n" +
                        "  · Looper 旁听：一条消息 3000ms\n" +
                        "  · 采样器：采到 Thread.sleep（归类 WAIT，**不是 CPU 热点**）"
            )
            mainHandlerPost {
                Thread.sleep(3000)
                emit("……主线程 sleep 3s 结束")
            }
        }

        bind(R.id.btn_jank_busy) {
            emit(
                "【卡顿对照 B：主线程忙等 4s】\n" +
                        "与对照 A 的**唯一差别**：这里是 CPU 一直在算，不是睡眠。\n" +
                        "预期：\n" +
                        "  · ANR 哨兵：**同样**判定无响应 —— 这证明哨兵测的是「响应性」\n" +
                        "    而不是「CPU 占用」（很多自研方案在这里判错，去比 CPU 使用率）\n" +                        "  · 采样器：采到 computeBusy() 的栈，归类 **CPU** 热点（与 A 相反）\n" +
                        "  · 帧监控：同样丢帧"
            )
            mainHandlerPost {
                computeBusy(TimeUnit.SECONDS.toMillis(4))
                emit("……主线程忙等 4s 结束")
            }
        }

        bind(R.id.btn_jank_report) {
            StabilityMonitor.flushJank("StabilityLab")
            emit(
                "【帧报告已生成】\n" +
                        "判定用 FrameMetrics.DEADLINE（API 31+，自适应刷新率），\n" +
                        "低版本降级为与 16.6ms 比较 —— 两者**不可直接比较**。\n\n" +
                        "⚠️ 能力边界：只覆盖 app 侧。系统合成侧（SurfaceFlinger/Buffer Stuffing）\n" +
                        "的 jank 不在 FrameMetrics 里，所以这里的 jank 数 ≤ Perfetto 的 jank 数。\n" +
                        "详见 tools/perfetto/INTERVIEW-perfetto.md 的 jank_type 分布。\n\n" +
                        "报告正文已输出到输出区与 logcat（TAG: JankMonitor）。"
            )
            emit(JankMonitor.describe())
        }

        bind(R.id.btn_looper_slow) {
            emit("【Looper 慢消息】即将在主线程执行一条 1.5s 的消息（阈值 500ms）")
            mainHandlerPost {
                computeBusy(1500)
                emit("……慢消息结束，应有 SLOW_MESSAGE 事件（TAG: Stability）")
            }
        }

        bind(R.id.btn_sampler_report) {
            emit(MainThreadSampler.formatReport())
        }

        bind(R.id.btn_sampler_stop) {
            MainThreadSampler.samplingEnabled = !MainThreadSampler.samplingEnabled
            emit(
                "【采样开关】samplingEnabled = ${MainThreadSampler.samplingEnabled}\n" +
                        "说明：任何持续开销的功能都必须有开关与预算上限。\n" +
                        "  采样上限 20000 个（内存有界）；间隔自适应 200ms/50ms。\n" +
                        "  采样率与统计误差：±1/√N，1000 样本 ≈ ±3%，所以不要用一次 30s 的采样下结论。"
            )
        }

        // ═══════ ANR ═══════

        bind(R.id.btn_anr_probe_log) {
            emit(
                "【ANR 三条通道，各自的定位】\n\n" +
                        "通道1 ApplicationExitInfo（唯一真·ANR 证据，API 30+）\n" +
                        "  · 系统自己的判定，Play Console 的 ANR 数就走这条\n" +
                        "  · 环形缓冲会覆盖 → **每次启动都要回捞**\n" +
                        "  · trace 可能挂在非 ANR 的 reason 上 → **按「有没有 trace」判断，别按 reason**\n" +
                        "  · self ANR（自己调 appNotResponding）**不带全量 trace**\n\n" +
                        "通道2 哨兵主动探测（本项目默认，全版本可用）\n" +
                        "  · postAtFrontOfQueue + 自身线程等待回环\n" +
                        "  · 连续 2 轮无响应才判（过滤 GC 长暂停的假阳性）+ 边沿触发\n" +
                        "  · 探测线程 park 常驻 —— 必须走收口层的 dedicatedThread，\n" +
                        "    塞进共享泳道会永久占住一个 worker\n\n" +
                        "通道3 Looper 旁听（零额外线程，只统计执行耗时）\n" +
                        "  · 阈值 500ms；Choreographer/ActivityThread.H 不计入（刷屏且本就频繁）\n" +
                        "  · ⚠️ 已知失真：慢消息之后的「本该执行的消息」不打印 >>>>>，\n" +
                        "    所以单条消息耗时准确，但整体卡顿时长是**低估**的\n\n" +
                        "── 为什么不用 FileObserver 监听 /data/anr ──\n" +
                        "本机实测（Redmi K40 / Android 12）：\n" +
                        "  $ adb shell run-as com.example.myapplication ls /data/anr\n" +
                        "  ls: /data/anr: Permission denied\n" +
                        "  $ adb shell ls -ld /data/anr\n" +
                        "  drwxrwxr-x 2 system system\n" +
                        "/data/anr 属主是 system，SELinux 域的强制限制（appdomain 无 anr_data_file 读权限），\n" +
                        "**与运行时权限无关，申请权限也解决不了**。这条路在 Android 8+ 上已死。"
            )
        }

        bind(R.id.btn_anr_fake) {
            emit(
                "【自报 ANR（self ANR）】\n" +
                        "调用 ActivityManager.appNotResponding() 主动告诉系统「我卡了」。\n" +
                        "⚠️ 关键差异：Android 12+ 的 self ANR 在 ExitInfo 里**不带全量线程 trace**\n" +
                        "   （系统认为你知道原因了）。\n" +
                        "预期：logcat 出现 ANR 相关输出；下次 ExitInfo 回捞能看到该条，\n" +
                        "     但 trace 摘要会说明「未解析出线程堆栈」—— 这正是它诚实的地方。"
            )
            val ok = runCatching {
                (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager)
                    .appNotResponding("StabilityLab 主动上报：模拟业务检测到不可恢复的卡顿")
                true
            }.getOrElse { false }
            emit(
                if (ok) "已调用 appNotResponding()（部分 ROM 对非系统应用做了限制，可能静默失效）"
                else "调用失败（ROM 限制或权限）：see logcat TAG: StabilityLab"
            )
        }

        bind(R.id.btn_anr_real) {
            emit(
                "⚠️【真 ANR 演示｜危险】\n" +
                        "即将做两件事：①让主线程阻塞 15 秒；②在阻塞**期间**持续向本页注入触摸事件。\n\n" +
                        "为什么必须注入触摸（本页最值钱的一条实测）：\n" +
                        "  只阻塞主线程 15s、不去碰屏幕 → 系统**不会**报 ANR。\n" +
                        "  实测证据（Redmi K40 / Android 12，已在本机验证）：\n" +
                        "    阻塞 15s 且无输入 → `adb shell dumpsys window | grep lastanr`\n" +
                        "                          → <no ANR has occurred since boot>\n" +
                        "    而我们的哨兵会如实报 13 轮无响应（估算阻塞 13000ms）。\n\n" +
                        "  ⇒ 这恰好证明：**ANR 的判据是「系统事务在等你」，不是「你卡了」**。\n" +
                        "     没有输入在排队时，主线程卡多久都不构成 ANR\n" +
                        "     （后台 Service 的阈值甚至高达 200s）。\n" +
                        "     所以「ANR 率」与「用户感知卡顿率」是两个指标，不能互相替代。\n\n" +
                        "本次预期：\n" +
                        "  · 输入事件分发超时（默认 5s）→ 系统弹「应用无响应」\n" +
                        "  · 前台 ANR 通常被杀进程；本 ROM 是否杀要看实测\n" +
                        "  · 若被杀 → 下次启动 ExitInfo 回捞能看到 REASON_ANR，\n" +
                        "    且这一条通常**真的带 trace**（与 self ANR 不同）\n" +
                        "  · 判断「到底死没死」看 PID，不要看 logcat 里的 FATAL 行\n" +
                        "    adb shell pidof com.example.myapplication"
            )
            // 阻塞期间持续注入触摸 → 让 InputDispatcher 真的在等我们，
            // 才会走到 AMS.appNotResponding()（见上面的实测说明）。
            // ⚠️ 注入的坐标落在本页的**触控区**（按钮带），不要落在输出区（那里不消费触摸）。
            ThreadPools.dedicatedThread("demo-anr-injector") {
                repeat(24) {
                    runCatching {
                        Runtime.getRuntime()
                            .exec(arrayOf("input", "tap", "540", "1221"))
                            .waitFor()
                    }
                    Thread.sleep(500)
                }
            }.start()
            mainHandlerPost {
                Thread.sleep(15_000)
                emit("……15s 结束（如果进程还活着，说明这个 ROM 没有杀；用 lastanr 核对系统是否登记）")
            }
        }

        // ═══════ 回捞 / 解析 ═══════

        bind(R.id.btn_exitinfo) {
            emit("ExitInfo 回捞中（后台泳道，读 trace 可能耗时）…")
            ThreadPools.background.execute("stability.demo") {
                val items = AnrMonitor.ExitInfoCollector.collect(this, readTrace = true)
                runOnUiThread {
                    emit("【ExitInfo 回捞结果】\n" + AnrMonitor.ExitInfoCollector.describeAll(items))
                }
            }
        }

        bind(R.id.btn_trace_sample) {
            emit(
                "【trace 解析器自测（内置样本）】\n" +
                        "先证明解析器能正确处理「文本 ANR trace」，再看二进制分支与空 trace 分支。\n"
            )
            // ① 文本 trace（含锁等待链）
            val text = SAMPLES
            val r = AnrTraceParser.parse(text.toByteArray())
            emit("① 文本样本 → isBinary=${r.isBinary} 有堆栈=${r.hasThreadStacks}\n${r.summary}")
            // ② 二进制（伪造一段 protobuf 风格字节）
            val fakeProto = byteArrayOf(0x0A, 0x05, 0x01, 0x02, 0x03, 0x04, 0x05, 0x10, 0x2A)
            val rb = AnrTraceParser.parse(fakeProto)
            emit("② 二进制样本 → isBinary=${rb.isBinary}\n${rb.summary}")
            // ③ 空 / 只有进程信息的 trace（模拟 self ANR 拿不到堆栈）
            val stripped = "----- pid 12345 at 2026-10-07 12:00:00 -----\nCmd line: com.example.myapplication\n"
            val rc = AnrTraceParser.parse(stripped.toByteArray())
            emit("③ 被裁剪的样本（模拟 self ANR）→ 有堆栈=${rc.hasThreadStacks}\n${rc.summary}")
        }

        bind(R.id.btn_journal) {
            val recs = CrashJournal.recoverPending(this)
            emit(
                "【崩溃遗嘱（CrashJournal）】\n" +
                        "磁盘残留未确认记录：${recs.size} 条\n" +
                        (if (recs.isEmpty()) "（无 —— 说明上次没有'上报未完成就死了'的崩溃）\n"
                        else recs.joinToString("\n") { it.describe() } + "\n") +
                        "\n机制：崩溃 handler 第一动作 = 同步落盘 + fsync（此时进程必死，\n" +
                        "后台上报来不及）；上报成功后 acknowledge 清账；下次启动 recoverPending 补报。\n" +
                        "⚠️ 文件有界（最多 20 条），否则「启动即崩」会让日志无限增长。\n\n" +
                        "会话状态：${CrashJournal.beginSession(this).describe()}"
            )
        }

        // ═══════ Crash ═══════

        bind(R.id.btn_crash_bg) {
            val pidBefore = Process.myPid()
            emit(
                "【对照：后台线程未捕获异常】\n" +
                        "pid(before)=$pidBefore\n" +
                        "预期：logcat 会有 FATAL EXCEPTION，但进程**活着**（装了兜底）。\n" +
                        "  ⚠️ 默认 handler 是 KillApplicationHandler —— 没有兜底时\n" +
                        "     这个后台异常会直接杀进程，且很容易被误判成「随机崩溃」。\n" +
                        "  验证：adb shell pidof com.example.myapplication 应仍是 $pidBefore"
            )
            ThreadPools.dedicatedThread("demo-crash-bg") {
                throw IllegalStateException("后台线程故意崩溃（应被兜住）")
            }.start()
        }

        bind(R.id.btn_crash_task) {
            emit(
                "【线程池 submit() 路径的异常】\n" +
                        "⚠️ 这条路径的异常**默认被吞进 Future**，不调 get() 永远不知道任务炸了。\n" +
                        "本仓库的做法：ThreadErrorReporter 通过可插拔 sink 汇入统一出口，\n" +
                        "并且不依赖调用方记得 get()。\n" +
                        "预期：输出区能看到「累计任务异常」，且进程不受影响。"
            )
            // 用 submit 而非 execute：专门演示"异常被吞进 Future"这条路径
            val f = ThreadPools.single.submit {
                throw IllegalArgumentException("submit 路径的异常（会被 Future 吞掉）")
            }
            runCatching { f.get(300, TimeUnit.MILLISECONDS) }
                .onFailure { Log.w(TAG, "get() 才看到异常：${it.cause?.javaClass?.simpleName}") }
            emit("已提交。注意：不调 get() 的话，这个异常在业务代码里**完全不可见**。")
        }

        bind(R.id.btn_crash_main) {
            emit(
                "⚠️【危险：主线程未捕获异常】\n" +
                        "预期：进程**必须死**（pid 变化）。\n" +
                        "为什么不让它活：吞掉主线程异常 = 留下状态不一致的 App\n" +
                        "（Activity 栈、生命周期、单例都停在半途），用户看到的是「点了没反应」，\n" +
                        "比崩溃更难查、更伤口碑。\n" +
                        "只有非主线程异常才「上报后吞掉」。\n\n" +
                        "下一次启动时，本次崩溃会由遗嘱补报（因为主线程崩时来不及后台上报）。"
            )
            mainHandlerPost { throw RuntimeException("主线程故意崩溃（必须死）") }
        }

        bind(R.id.btn_native_crash) {
            emit(
                "【Native 崩溃：App 内接不住，也不该硬接】\n" +
                        "可以在 native 注册 SIGSEGV handler 自己抓，但本项目**刻意不做**：\n" +
                        "  1. 信号处理器运行在崩溃现场，栈可能已损坏，能安全调用的函数极少；\n" +
                        "  2. 抢装 handler 容易破坏系统自己的崩溃收集（debuggerd/tombstone），\n" +
                        "     反而**丢掉平台侧证据**；\n" +
                        "  3. Android 10+ 的 crash_dump/debuggerd 质量更高且带符号化上下文。\n\n" +
                        "正确做法 = 回捞：ExitInfo 的 REASON_CRASH_NATIVE 会给一份 **protobuf** trace。\n" +                        "  原始字节原样上传，绝不当文本保存/传输（会被编码层改写字节）；\n" +
                        "  在平台侧用 ndk-stack / Crashpad 符号化。\n" +
                        "  App 内自己写 proto 解析是负收益：没有 .proto 契约、没有符号表。\n\n" +
                        "（本按钮只输出说明，不真的崩 native —— 崩了本页就演示不下去了。）"
            )
        }

        bind(R.id.btn_events) {
            emit("【事件流水（统一上报出口的环形缓冲）】\n" + StabilityReporter.describe() + "\n" +
                    StabilityReporter.snapshot(30).joinToString("\n") { "  ${it.oneLine()}\n     ${it.detail.take(200)}" })
        }

        bind(R.id.btn_drain) {
            emit(
                "【模拟上报：flush 语义】\n" +
                        "flush = drainTo + 清账崩溃遗嘱，由**后台泳道**驱动，这是刻意的：\n" +
                        "  · 崩溃 handler 在出错线程上 → 那里绝不能做序列化/IO\n" +
                        "  · 否则「崩溃上报」本身就变成「崩溃 + 卡顿」\n" +
                        "  · 崩溃 handler 也**不能**自己去 acknowledge 遗嘱 —— 那是自证，不可信\n" +
                        "→ 所以「发送成功」与「清账」必须发生在同一个地方：这里。\n" +
                        "drainTo 返回 true 才销账；false（发送失败）事件保留，下次再报。"
            )
            ThreadPools.background.execute("stability.drain") {
                val n = StabilityReporter.snapshot().size
                val ok = StabilityMonitor.flush { batch ->
                    // 这里代替真实的平台发送。生产环境注入 upload 实现。
                    Log.i(TAG, "模拟上报 ${batch.size} 条事件")
                    true
                }
                runOnUiThread { emit("已模拟上报 $n 条事件（结果=$ok，true 即已发送并清账）") }
            }
        }
    }

    // ─────────────────────────────────────────────
    // 演示负载
    // ─────────────────────────────────────────────

    /**
     * 纯计算负载：让**采样器能采到 CPU 样本**（与 Thread.sleep 的 WAIT 样本形成对照）。
     * 用 volatile 写避免被 JIT 优化掉整个循环 —— 这是"忙等"演示的经典坑：
     * 不写回的话编译器会把循环删掉，测出来是 0ms。
     */
    @Volatile
    private var sink = 0L

    private fun computeBusy(millis: Long) {
        val end = SystemClock.uptimeMillis() + millis
        var acc = 0L
        while (SystemClock.uptimeMillis() < end) {
            for (i in 1..50_000) acc += (i * 31L) xor acc
        }
        sink = acc
    }

    private fun mainHandlerPost(block: () -> Unit) {
        android.os.Handler(mainLooper).post(block)
    }

    private fun bind(id: Int, action: () -> Unit) {
        findViewById<Button>(id).setOnClickListener {
            runCatching { action() }.onFailure { emit("执行失败：${it.message}") }
        }
    }

    private fun emit(text: String) {
        Log.i(TAG, text)
        runOnUiThread {
            log.append(text).append("\n\n")
            output.text = log.toString()
        }
    }

    override fun onDestroy() {
        // ⚠️ 必须移除 FrameMetrics 监听：窗口销毁后监听器仍在会继续收到回调并持有
        //    Activity 引用 → 内存泄漏 + 无意义的每帧开销。
        JankMonitor.stop()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "StabilityLab"

        /**
         * 内置的文本 ANR trace 样本。
         *
         * ⚠️ 这是**构造的样本**，不是真机 trace（真机 trace 见 ExitInfo 回捞）。
         *    它的作用是**证明解析器的行为**：能否识别线程块、能否
         *    跟着 `held by thread N` 建出等待链、能否正确区分
         *    Native / Blocked / Waiting 状态。用真 trace 测不出"解析器在
         *    锁等待链上的正确性"（真 trace 里未必有锁竞争）。
         */
        private val SAMPLES = """
            ----- pid 12345 at 2026-10-07 12:00:00 -----
            Cmd line: com.example.myapplication
            Build fingerprint: 'Redmi/alioth/alioth:12/...'
            Reason: Input dispatching timed out (server is not responding)
            -----
            "main" prio=5 tid=1 Blocked
              | group="main" sCount=1 dsCount=0 flags=1 obj=0x12c00000 self=0x7f8a
              | sysTid=12345 nice=-10 cgrp=default sched=0/0 handle=0x7f9b
              | state=S schedstat=( 1234567 890123 456 ) utm=12 stm=34 core=2 HZ=100
              | stack=0x7fc0000000-0x7fc0002000 stackSize=8192KB
              | held mutexes=
              at com.example.myapplication.MainActivity.doBlockingWork(MainActivity.kt:88)
              at com.example.myapplication.MainActivity.dispatchTouchEvent(MainActivity.kt:52)
              - waiting to lock <0x0a1b2c3d> (a java.lang.Object) held by thread 12
              at android.view.ViewGroup.dispatchTouchEvent(ViewGroup.java:2456)
              at android.view.ViewRootImpl.processInputEvent(ViewRootImpl.java:10234)
            "app-disk-1" prio=10 tid=12 Waiting
              | group="main" sCount=1 dsCount=0 flags=1 obj=0x12c00010 self=0x7f8b
              | sysTid=12400 nice=10 cgrp=default sched=0/0 handle=0x7f9c
              | state=S schedstat=( 2345678 901234 567 ) utm=23 stm=45 core=3 HZ=100
              - waiting to lock <0x0a1b2c40> (a java.lang.Object) held by thread 7
              at com.example.myapplication.DataStore.readConfig(DataStore.kt:140)
              at com.interview.thread.ThreadPools.Lane.execute(ThreadPools.kt:270)
            "app-db-1" prio=10 tid=7 Native
              | group="main" sCount=1 dsCount=0 flags=1 obj=0x12c00020 self=0x7f8c
              | sysTid=12401 nice=10 cgrp=default sched=0/0 handle=0x7f9d
              | state=S schedstat=( 3456789 012345 678 ) utm=34 stm=56 core=5 HZ=100
              - locked <0x0a1b2c40> (a java.lang.Object)
              at android.database.sqlite.SQLiteConnection.nativeExecute(Native method)
              at android.database.sqlite.SQLiteConnection.execute(SQLiteConnection.java:580)
            "RenderThread" prio=7 tid=15 Runnable
              | group="main" sCount=0 dsCount=0 flags=0 obj=0x12c00030 self=0x7f8d
              | sysTid=12403 nice=-4 cgrp=default sched=0/0 handle=0x7f9e
              | state=R schedstat=( 4567890 123456 789 ) utm=45 stm=67 core=1 HZ=100
              at android.view.ThreadedRenderer.nSyncAndDrawFrame(Native method)
            """.trimIndent()
    }
}
