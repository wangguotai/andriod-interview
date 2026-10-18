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
import com.interview.nativecrash.NativeCrashHandler

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

        // ═══════ 通道 4：SIGQUIT ═══════

        bind(R.id.btn_sigquit_install) {
            emit(
                "【通道 4：拦截 SIGQUIT】\n" +
                        "⚠️ 本模块是本仓库**唯一一处主动去抢系统既有行为**的探针，所以默认不装。\n\n" +
                        "它做三件事（少一件就是「假成功」）：\n" +
                        "  ① 解除 SIGQUIT 屏蔽（pthread_sigmask）—— 否则 sigaction 收不到任何信号\n" +
                        "  ② handler 里只做 write(pipe) + tgkill 转发，重活推到独立线程\n" +
                        "  ③ ★ 把信号**再转发给 SignalCatcher** —— 漏了这步，AMS 会一直等不到\n" +
                        "     本进程写堆栈，直到 20s 超时才继续（ANR 弹窗慢 20s，trace 也可能不完整）\n\n" +
                        "为什么不能简单 sigaction 了事：SIGQUIT 在 zygote 里就被 Block 了，\n" +
                        "唯一的所有者是 ART 的 SignalCatcher 线程（它用 sigwait 同步等）。\n" +
                        "信号只会投递给「不阻塞它」的那一个线程 —— 所以我们先解除屏蔽抢过来，\n" +
                        "再还回去。\n\n" +
                        "⚠️ 收到 SIGQUIT ≠ 发生 ANR：别的进程 ANR 时我们会被顺带 dump，\n" +
                        "任何人也能 kill -3 我们。所以本通道做二次判定：\n" +
                        "   确认 ANR = 本进程被 AMS 标记为 NOT_RESPONDING（系统口径）\n" +
                        "   自己给自己发的信号 → 直接否掉\n" +
                        "判为误报的**不会**进上报管道（否则会污染平台的 ANR 率）。"
            )
            emit(AnrSigquitChannel.describeInstall())
        }

        bind(R.id.btn_sigquit_stats) {
            emit("【通道 4 读数】\n" + AnrSigquitChannel.describe())
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
                "【Native 崩溃：先说清楚「接不住」这个结论的适用范围】\n" +
                        "本页原来在这里写着「App 内不该接 native 崩溃，本项目刻意不做」。\n" +
                        "那个结论**本身没错，但它只否决了一种做法** —— 用自研 handler **替换**\n" +
                        "系统的取证链路。下面这一排按钮做的是**另一种**（Crashpad / xCrash 的做法）：\n\n" +
                        "    崩溃 → 内核投递信号 → [我们：只抓现场+落盘] → 链式交回原 handler\n" +
                        "                            └──► debuggerd → tombstone **照旧产生**\n\n" +
                        "也就是说取证是**增量**，平台的证据一个都不少。所以原来那三条理由\n" +
                        "（信号上下文不安全 / 破坏 debuggerd / 抓得不如系统）在这里不成立：\n" +
                        "  · 我们是**接着**系统 handler 跑，不是顶掉它（见 native 侧 handOffToSystem）；\n" +
                        "  · 它拿到的 tombstone 与不装时**完全一致**（这点必须在真机上核对）。\n\n" +
                        "⚠️ 换来了两条**新的**、必须写在明面上的边界：\n" +
                        "  1. `_Unwind_Backtrace` 在信号处理器里是「尽力而为」：崩溃点若在动态链接\n" +
                        "     器锁内（如 dlopen 里），我们可能死锁 → 报告落不下来，但 tombstone 仍在。\n" +
                        "     生产级做法是自带 libunwindstack 做内存解析 —— 而\n" +
                        "     /system/lib64/libunwindstack.so **不在 public.libraries.txt 里**，\n" +
                        "     App 不能 dlopen（实测确认），所以本仓库选择「用 NDK 自带的 unwinder，\n" +
                        "     并把边界写在明面上」。\n" +
                        "  2. 报告只带 `模块名+偏移`，不能在设备上符号化（Release 无 .symtab、\n" +
                        "     dladdr 会重入 linker 锁）。事后用 tools/native-mem-symbolize.sh 还原。\n\n" +
                        "它与 ExitInfo 回捞是**互补**，不是替代：\n" +
                        "  · 本通道 **API 24+** 可用（ExitInfo 要 API 30+，本仓库 minSdk 24）；\n" +
                        "  · 本通道有 fd 表 / 进程状态 / 全线程栈，且是**文本**、cat 就能看；\n" +
                        "  · 我们 handler 万一死锁时，还靠 ExitInfo 兜底。\n\n" +
                        "→ 现在去点下面那排 ⚠ 按钮，真的崩一次，再回来点「回捞Native报告」。"
            )
        }

        // ═══════ Native 崩溃取证（进程内）═══════

        bind(R.id.btn_native_grab) {
            emit(
                "【Native 取证安装状态】\n" +
                        "机制：接管 SIGSEGV/ABRT/BUS/ILL/FPE/TRAP/SYS 七个信号，\n" +
                        "抓「崩溃线程栈 → 给其他线程发 SIGRTMIN+3 让它们各自抓自己的栈 → maps/fd/status」，\n" +
                        "落盘后**把信号链式交回系统**（tombstone 照旧）。\n\n"
            )
            emit(NativeCrashCollector.describeStatus(this))
        }

        bind(R.id.btn_native_recover) {
            emit("回捞中（后台泳道）…")
            ThreadPools.background.execute("native.demo.recover") {
                val n = NativeCrashCollector.recoverAndReport(this)
                val text = NativeCrashCollector.latestReportText(this)
                runOnUiThread {
                    emit(
                        "【Native 报告回捞】\n本次回捞到 $n 份并已交给上报出口。\n\n" +
                                (if (text == null) "目录里没有任何报告。\n" +
                                        "→ 先点下面某个 ⚠ 按钮真的崩一次，重启 App 后再来点这里。\n"
                                else "── 最近一份报告（原文，可对照 NATIVE_CRASH_FORMAT.md）──\n" +
                                        text.take(6000) +
                                        if (text.length > 6000) "\n……（截断，完整内容见报告文件）" else "")
                    )
                }
            }
        }

        bind(R.id.btn_native_upload) {
            emit(
                "【上报出口状态】\n" +
                        "⚠️ 这里必须把「当前处于哪一档」说清楚，否则会误判成'能上报了'：\n\n" +
                        (if (CrashUploader.isConfigured())
                            "  ✅ **真实 HTTP**：endpoint 已配置。\n" +
                                    "     2xx 才算成功（才清账/清遗嘱）；4xx 不重试；5xx/网络异常最多重试 2 次。\n" +
                                    "     endpoint/token 来自 local.properties（不入库），经 BuildConfig 注入 ——\n" +
                                    "     所以改配置后**必须重新构建**才生效。\n\n"
                        else
                            "  ⚠️ **模拟提交**：local.properties 里没有 crash.upload.url。\n" +
                                    "     此时 ReportDriver 走本地链路，\"已提交 N 批次\"只代表**本地链路走通了**，\n" +
                                    "     **没有任何网络请求发出**。配法见 local.properties 的注释示例。\n" +
                                    "     为什么没配时不干脆返回 false：那会让事件永远留在缓冲里、\n" +
                                    "     实验页什么都看不到（见 ReportDriver.uploadBatch 的注释）。\n\n") +
                        CrashUploader.describe() + "\n\n" +
                        "崩溃上报的完整链路（含 native）：\n" +
                        "  native 信号 → 落盘报告 → 下次启动回捞 → 解析 → StabilityReporter 环形缓冲\n" +
                        "  → CrashJournal 遗嘱（同步落盘）→ ReportDriver.flush → 【本出口】\n" +
                        "  成功(true) 才 acknowledge 遗嘱 —— 崩溃 handler 不能自证'我报完了'。"
            )
        }

        bind(R.id.btn_native_abort) {
            emit(
                "⚠️【即将主动触发 SIGABRT —— 进程会立刻死掉】\n\n" +
                        "为什么用 abort() 而不是空指针写：\n" +
                        "  · abort() → SIGABRT，si_code = **SI_TKILL**，来源可辨（就是'我们主动的'）；\n" +
                        "  · 空指针写 → SIGSEGV/SEGV_MAPERR，与真实业务崩溃**无法区分**。\n" +
                        "    实验数据混进真实数据里，是比自己写错更难发现的问题。\n\n" +
                        "预期：\n" +
                        "  1. 进程立即死（logcat 可见 debuggerd 的 tombstone 输出 —— 证明**没抢掉**系统取证）；\n" +
                        "  2. files/native-crash/ 下出现 report-*.txt；\n" +
                        "  3. 重启 App → 本页「回捞Native报告」能看到完整现场。\n" +
                        "对照：此时 logcat 里**同时**有系统 tombstone 与我们的报告 —— 两者都要有。"
            )
            if (!NativeCrashHandler.crashByAbort("Lab: 主动 SIGABRT（si_code 归因对照）")) {
                emit("⚠️ native 库不可用（非 arm64 设备？），无法触发")
            }
        }

        bind(R.id.btn_native_null) {
            emit(
                "⚠️【即将空指针解引用 —— SIGSEGV / SEGV_MAPERR / fault_addr=0x0】\n" +
                        "用来与 ABRT 按钮做 si_code 对照：\n" +
                        "  · SEGV_MAPERR = 地址**未映射**（空指针/野指针/use-after-free）\n" +
                        "  · SEGV_ACCERR = 地址已映射但**无权限**（越界写只读段）\n" +
                        "两者的修复方向完全不同，所以报告里 si_code 与 fault_addr 是分开的两个字段。"
            )
            if (!NativeCrashHandler.crashByNullDeref()) {
                emit("⚠️ native 库不可用，无法触发")
            }
        }

        bind(R.id.btn_native_stack) {
            emit(
                "⚠️【即将栈溢出 —— SIGSEGV，fault_addr 贴着栈底而不是 0】\n" +
                        "这是「必须看 fault_addr，不能只看 si_code」的实例：\n" +
                        "  它和空指针一样都是 SEGV_MAPERR，但 fault_addr 完全不同 ——\n" +
                        "  一个在 0x0，一个在栈 VMA 边界外。\n" +
                        "另外它会检验备用信号栈（SA_ONSTACK + sigaltstack）：\n" +
                        "  栈已经不能用了，不切到 alt stack 的话 handler 一压栈就二次崩溃，\n" +
                        "  报告一个字都落不下来。"
            )
            if (!NativeCrashHandler.crashByStackOverflow()) {
                emit("⚠️ native 库不可用，无法触发")
            }
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
