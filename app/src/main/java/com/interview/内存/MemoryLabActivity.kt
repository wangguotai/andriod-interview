package com.interview.内存

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.interview.thread.ThreadPools

/**
 * Time: 2026/10/8
 * Author: wgt
 * Description: 线上内存监控（JVM + Native）—— 可运行演示宿主
 *
 * ─── 设计原则（沿用稳定性实验页那一条）：每个按钮对应**一条可证伪的判断** ───
 *
 * 三组对照实验是这些按钮的价值所在（**每一组都能自己把"监控有效"证出来**）：
 *
 * ① **JVM 堆**：加压 → 看水位越级与事件；抓 hprof → 看"哪一类对象"变多了。
 * ② **Native**：造负载 → 看 malloc 净账 / 分配器 / PSS 三条线**不一致**；
 *    装归因探针 → 看**哪个函数**要的内存（这是 API 拿不到的那一格）。
 * ③ **容器感**：模拟 trim 压力 → 证明"系统视角"与"进程视角"会背离。
 *
 * ⚠️ 有两个按钮是**有代价的演示**（抓 hprof 会 stop-the-world 0.5~2s、
 *    装归因探针会挂在全局 malloc 热路径上）。它们的存在是为了让"证据"
 *    可复现，不是给日常使用 —— 执行前都会先在输出区写明预期。
 */
class MemoryLabActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private val log = StringBuilder()

    /** JVM 加压负载（显式持有，才能显式释放 —— 避免"演示完自己泄漏"） */
    private var heapBlocks: MutableList<ByteArray> = ArrayList()

    /** Native 侧的显式负载（走 native 分配器，不占 Java 堆） */
    private var nativeBlocks: LongArray = LongArray(0)

    /** 位图负载 */
    private val bitmaps = ArrayList<Bitmap>()

    /** 上一份直方图（差分用） */
    private var lastHistogram: MemoryHeapAnalyzer.Histogram? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory_lab)
        output = findViewById(R.id.tv_output)

        // 接入监控（幂等；Application 里也装了 —— 这里是为了"单页可独立演示"）
        MemoryMonitor.install(application)

        bind(R.id.btn_install) {
            emit(
                "【接入清单】本页已启用：\n" +
                        "  Application/MemoryMonitor.install → 注册 trim 回调 + 2s 采样器（环形 256 点 ≈ 8.5min）\n" +
                        "  事件出口                          → StabilityReporter（与 ANR/Crash **同一条管道**）\n" +
                        "  本页                              → onResume 记基线 / onPause 出收口报告\n\n" +
                        "⚠️ 刻意**不自动装**两样东西（会改变被测系统的行为）：\n" +
                        "  · native 归因探针（memtrace / bytehook）—— 挂在全局 malloc 热路径上\n" +
                        "  · 堆直方图（hprof）—— stop-the-world 0.5~2s\n" +
                        "  两者都必须**显式点按钮**，且「开着」这件事在总览里能看到。"
            )
        }

        bind(R.id.btn_overview) { emit(MemoryMonitor.formatOverview()) }

        bind(R.id.btn_clear) {
            log.setLength(0)
            MemoryMonitor.resetAll()
            emit("已清空输出与全部内存态统计（位图/归因/水位/trim 计数）")
        }

        // ═══════ 第一组：JVM 堆 ═══════

        bind(R.id.btn_heap_snapshot) {
            val s = MemoryMetrics.capture(this)
            emit(MemoryMetrics.describe(s) + "\n\n波形：\n" + sparkline())
        }

        bind(R.id.btn_pressure_64) {
            val before = MemoryMetrics.capture(this)
            emit(
                "【JVM 加压 64MB】\n" +
                        "加压前：java=${MemoryMetrics.fmt(before.javaUsedBytes)}" +
                        "（${MemoryMetrics.fmtRatio(before.javaUsedRatio)} of 软上限）\n" +
                        "预期：\n" +
                        "  · 水位越级（NORMAL→HIGH，甚至 CRITICAL，取决于堆上限）\n" +
                        "  · 事件流水里出现 MEMORY_HIGH / MEMORY_CRITICAL（**只在越级时**，不是每秒一条）\n" +
                        "  · soft limit=${MemoryMetrics.fmt(before.heapLimitBytes)}" +
                        "（memoryClass=${before.memoryClassMb}MB）vs 硬上限=${MemoryMetrics.fmt(before.javaMaxBytes)}\n" +
                        "⚠️ 这里用 ByteArray（Java 堆），与 image Lab 的位图负载（native/graphics）互补。"
            )
            heapBlocks.addAll(MemoryMonitor.makeHeapPressure(64))
            val after = MemoryMetrics.capture(this)
            emit(
                "加压后：java=${MemoryMetrics.fmt(after.javaUsedBytes)}" +
                        "（${MemoryMetrics.fmtRatio(after.javaUsedRatio)}）" +
                        " committed=${MemoryMetrics.fmt(after.javaCommittedBytes)}\n" +
                        "⚠️ 注意 used 与 committed 的**差别**：committed 是 ART 已经要到手的容量，\n" +
                        "   GC 之后 used 会掉但 committed 不还 —— 把 committed 当 used 是最常见的误判。"
            )
        }

        bind(R.id.btn_pressure_release) {
            val n = heapBlocks.size
            heapBlocks.clear()
            val s = MemoryMetrics.capture(this)
            emit(
                "已释放 $n MB Java 堆负载。\n" +
                        "当前 java=${MemoryMetrics.fmt(s.javaUsedBytes)}（${MemoryMetrics.fmtRatio(s.javaUsedRatio)}）\n" +
                        "⚠️ 刻意**不在这里主动 System.gc()**：\n" +
                        "   要让数据说话 —— 释放后 used 可能不会立刻掉（等 ART 自己的 GC）。\n" +
                        "   想立刻看到回落，点「触发GC」按钮，两者对照更有说服力。"
            )
        }

        bind(R.id.btn_gc) {
            val before = MemoryMetrics.capture(this)
            val t0 = SystemClock.uptimeMillis()
            // ⚠️ System.gc() 是**建议**不是命令：ART 可能忽略（尤其是后台进程）。
            //    所以这里测出耗时并直接把"有效没效"打出来，而不是假装它一定生效。
            Runtime.getRuntime().gc()
            val cost = SystemClock.uptimeMillis() - t0
            val after = MemoryMetrics.capture(this)
            val freed = before.javaUsedBytes - after.javaUsedBytes
            val gcDelta = (after.runtimeStats["art.gc.gc-count"]?.toLongOrNull() ?: -1L) -
                    (before.runtimeStats["art.gc.gc-count"]?.toLongOrNull() ?: -1L)
            emit(
                "【触发 GC】耗时 ${cost}ms\n" +
                        "  java: ${MemoryMetrics.fmt(before.javaUsedBytes)} → ${MemoryMetrics.fmt(after.javaUsedBytes)}" +
                        "（释放 ${MemoryMetrics.fmt(freed)}）\n" +
                        "  art.gc.gc-count 变化：${if (gcDelta < 0) "（本机 runtimeStats 无此键）" else gcDelta}\n" +
                        "  committed: ${MemoryMetrics.fmt(before.javaCommittedBytes)} → ${MemoryMetrics.fmt(after.javaCommittedBytes)}\n" +
                        "⚠️ `Runtime.gc()` 只是**建议**：ART 有权忽略。所以上面对比的是\n" +
                        "   'gc-count 有没有真的增加' + 'used 有没有真的掉'，而不是假定它一定生效。\n" +
                        "   另一条更可靠的观察路径：看趋势报告里的「GC 谷值包络」。"
            )
        }

        bind(R.id.btn_hprof) {
            if (!HeapDumpGate.canDump()) {
                emit(
                    "【堆直方图】被闸门挡住：\n" + HeapDumpGate.describe() +
                            "\n闸门的三个条件：开关打开 / 不在运行 / 距上次 > ${HeapDumpGate.minIntervalMs / 60000} 分钟。\n" +
                            "（stop-the-world 的取证动作必须去重，否则会在内存紧张时把自己压死）"
                )
                return@bind
            }
            emit(
                "【堆直方图】即将抓 hprof —— ⚠️ 会 **stop-the-world 约 0.5~2 秒**，\n" +
                        "产出几 MB ~ 几十 MB 文件，然后在后台解析（单遍流式，内存占用与文件大小无关）。\n" +
                        "预期输出：类 → 实例数/字节数（按字节、按数量各 Top20）。"
            )
            val accepted = HeapDumpGate.request(this, "手动按钮") { h ->
                runOnUiThread {
                    if (h == null) {
                        emit("直方图失败：${HeapDumpGate.lastError ?: "未知"}（见 logcat TAG=MemoryAnalyze）")
                    } else {
                        lastHistogram = h
                        emit("【直方图结果】\n" + h.describe())
                    }
                }
            }
            if (!accepted) emit("提交失败：后台泳道饱和或闸门竞态（把闸门放回了，稍后重试）")
        }

        bind(R.id.btn_hprof_diff) {
            val before = lastHistogram
            if (before == null) {
                emit(
                    "【压力前后差分】还没有基线直方图。\n" +
                            "正确用法（这也是线上最有用的一次读数）：\n" +
                            "  1. 点「抓直方图」拿到基线（此时**不要**加压）\n" +
                            "  2. 点「加压64MB」（或造位图/造 native 负载）\n" +
                            "  3. 再点「抓直方图」→ 它会自动与基线差分\n" +
                            "⚠️ 单张直方图只能看「谁最大」（可能一直如此、属正常）；\n" +
                            "   差分才能回答「谁在这次操作里**涨了**」——那才是要动手改的地方。"
                )
                return@bind
            }
            // 立即抓一份新的来差分
            if (!HeapDumpGate.canDump()) {
                emit(
                    "【差分】当前已有基线（${MemoryMetrics.fmt(before.totalBytes)} / ${before.classCount} 个类），\n" +
                            "但闸门未放行（距上次不足 ${HeapDumpGate.minIntervalMs / 60000} 分钟），无法抓第二份做差分。\n" +
                            "演示时可以把 HeapDumpGate.minIntervalMs 调小。"
                )
                return@bind
            }
            emit("【差分】基线=${MemoryMetrics.fmt(before.totalBytes)}，正在抓第二份……（同样会冻结进程）")
            HeapDumpGate.request(this, "差分") { after ->
                runOnUiThread {
                    if (after == null) {
                        emit("第二份直方图失败：${HeapDumpGate.lastError ?: "未知"}")
                        return@runOnUiThread
                    }
                    val diff = HeapDiff.diff(before, after, minDeltaBytes = 256 * 1024)
                    emit(
                        buildString {
                            appendLine("【差分结果】阈值 256KB，共 ${diff.size} 个类的字节数变化")
                            appendLine("${"类".padEnd(44)} ${"Δ数量".padStart(10)} ${"Δ字节".padStart(12)}")
                            diff.take(25).forEach {
                                appendLine(
                                    "%-44s %10d %12s".format(
                                        it.className.take(44), it.instanceCount, MemoryMetrics.fmt(it.totalBytes)
                                    )
                                )
                            }
                            if (diff.isEmpty()) appendLine("（没有超过阈值的类 —— 说明这次操作没有显著改变 Java 堆）")
                            appendLine()
                            appendLine("⚠️ Δ为负 = 该类减少了（GC 释放/缓存被清）")
                        }
                    )
                    lastHistogram = after
                }
            }
        }

        bind(R.id.btn_heap_history) {
            val h = HeapDumpGate.lastResult
            emit(
                if (h == null) {
                    "【直方图历史】（无）—— 先点「抓直方图」"
                } else {
                    "【最近一次直方图】\n${h.describe(15)}"
                }
            )
        }

        // ═══════ 第二组：Native ═══════

        bind(R.id.btn_native_snapshot) {
            val s = MemoryMetrics.capture(this)
            emit(
                buildString {
                    appendLine("【Native 三条线（**互不相等，这是重点**）】")
                    appendLine("  ① malloc 净账        ${MemoryMetrics.fmt(s.nativeAllocatedBytes)}  ← 分配器视角（Debug API）")
                    appendLine("  ② 分配器已映射       ${MemoryMetrics.fmt(s.nativeHeapSizeBytes)}  （浪费率 ${MemoryMetrics.fmtRatio(s.allocatorWasteRatio)}）")
                    appendLine("  ③ nativePss          ${MemoryMetrics.fmtKb(s.nativePssKb)}  ← 内核视角（Debug.getMemoryInfo）")
                    appendLine("  ④ VmRSS              ${MemoryMetrics.fmtKb(s.vmRssKb)}  （含 code/graphics/stack）")
                    appendLine("  ⑤ VmHWM(峰值)        ${MemoryMetrics.fmtKb(s.vmHwmKb)}")
                    appendLine()
                    appendLine("  分配器内部：used=${MemoryMetrics.fmt(s.allocatorUsedBytes)}" +
                            " free=${MemoryMetrics.fmt(s.allocatorFreeBytes)}" +
                            " mmap=${MemoryMetrics.fmt(s.allocatorMmapBytes)}")
                    if (s.sizeClasses.isNotEmpty()) {
                        appendLine("  scudo 尺寸档（size × 存活数）：")
                        s.sizeClasses.take(8).forEach { (sz, n) ->
                            appendLine("    ${sz.toString().padStart(9)} B × ${n.toString().padStart(6)} = ${MemoryMetrics.fmt(sz.toLong() * n)}")
                        }
                    }
                    appendLine()
                    appendLine("⚠️ 为什么四者不相等（面试的分水岭）：")
                    appendLine("  · 大块分配可能走 mmap 而非主 arena → ①记它但②未必等量涨")
                    appendLine("  · 位图在 native 堆但系统归到 graphics 段 → 不计入 nativePss")
                    appendLine("  · 释放后①立刻减，②要等页真正归还（scudo 的 secondary cache 会留住页）")
                    appendLine("  ⇒ 判断 native 泄漏要看 **①持续涨 且 ③/PSS 同步涨**，单看某一个都会误判。")
                }
            )
        }

        bind(R.id.btn_memtrace_install) {
            val ok = MemoryAllocTracker.install()
            emit(
                buildString {
                    appendLine("【装 native 归因探针（memtrace / bytehook）】结果=${if (ok) "成功" else "失败"}")
                    appendLine()
                    appendLine(MemoryAllocTracker.status())
                    appendLine()
                    appendLine("⚠️ 它挂在**全局 malloc 热路径**上，这就是它不自动安装的原因：")
                    appendLine("  · 每次大分配多一次 unwind（栈回溯）")
                    appendLine("  · 有损采样：>=8KB 全采；[64B,8KB) 1/256；拿不到自旋锁就跳过")
                    appendLine("  · 它**会改变被测对象**（所以装/不装的数字不能直接对比）")
                    appendLine("  三条取舍的完整代价说明见 app/src/main/cpp/memtrace.cpp 文件头 §三。")
                }
            )
        }

        bind(R.id.btn_memtrace_report) {
            if (!MemoryAllocTracker.isInstalled()) {
                emit("【归因报告】探针未安装 —— 先点「装归因探针」。\n\n" + MemoryAllocTracker.status())
                return@bind
            }
            emit("【归因报告】生成中（会遍历站点表 + 解析模块映射，几十毫秒级，已丢到后台泳道）")
            ThreadPools.background.execute("memory.memtrace.report") {
                val text = MemoryAllocTracker.report(20)
                val sites = MemoryAllocTracker.parseReport(text)
                runOnUiThread { emit(text + "\n\n（结构化解析：${sites.size} 个站点，Top1=${sites.firstOrNull()?.let { MemoryMetrics.fmt(it.liveBytes) } ?: "无"}）") }
            }
        }

        bind(R.id.btn_native_pressure) {
            emit(
                "【造 Native 负载】分配 32 × 1MB（走 native 分配器，**不占 Java 堆**）。\n" +
                        "预期（这是最能说明问题的一组对照）：\n" +
                        "  · ① malloc 净账 **+32MB**（分配器视角立刻反映）\n" +
                        "  · Java 堆 **不动**（这是「位图/缓冲在 native」那个知识点的现场）\n" +
                        "  · 若归因探针已装 → 「归因报告」里能**看到是哪一行代码**要的这 32MB\n" +
                        "  · PSS/VmRSS 的涨幅可能略大于或小于 32MB（页对齐 + 分配器元数据）"
            )
            nativeBlocks = MemoryAllocTracker.allocateNativeDemo(32, 1 shl 20)
            val s = MemoryMetrics.capture(this)
            emit("已分配。nativeAlloc=${MemoryMetrics.fmt(s.nativeAllocatedBytes)}" +
                    " java=${MemoryMetrics.fmt(s.javaUsedBytes)}（对比：Java 堆应基本没动）")
        }

        bind(R.id.btn_native_free) {
            val n = nativeBlocks.size
            MemoryAllocTracker.freeNativeDemo(nativeBlocks)
            nativeBlocks = LongArray(0)
            val s = MemoryMetrics.capture(this)
            emit(
                "已释放 $n 块 native。" +
                        "nativeAlloc=${MemoryMetrics.fmt(s.nativeAllocatedBytes)}" +
                        " 分配器已映射=${MemoryMetrics.fmt(s.nativeHeapSizeBytes)}\n" +
                        "⚠️ 注意 ①②的**不同步**：净账掉得快，已映射/PSS 可能不降 —— " +
                        "那是分配器缓存（正常），不是泄漏。"
            )
        }

        bind(R.id.btn_memtrace_reset) {
            MemoryAllocTracker.reset()
            emit("归因探针已清零（**刻意不提供卸载**：卸载后已分配的块 free 就看不见了，\n" +
                    "存活字节会永远停在那里，比「不卸载」更容易被误读成泄漏）")
        }

        // ═══════ 第三组：图形内存 / 趋势 / 台账 ═══════

        bind(R.id.btn_bitmap_pressure) {
            val source = "MemoryLab:压力演示"
            emit(
                "【造位图 8 × 1MB】用 `Bitmap.createBitmap(512, 512, ARGB_8888)` = 1MB/张。\n" +
                        "预期：\n" +
                        "  · BitmapTracker 当前字节 +8MB（按**allocationByteCount** 记，不是 byteCount）\n" +
                        "  · 系统 summary.graphics 同步上涨（在快照里看）\n" +
                        "  · Java 堆**几乎不动** —— 这就是「位图内存在 Java 堆看不见」的现场证据\n" +
                        "⚠️ Glide 内部创建的位图不经过 BitmapTracker ⇒ 本计数 ≤ 系统 graphics，差额是信息。"
            )
            repeat(8) {
                val b = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
                bitmaps.add(b)
                BitmapTracker.onBitmapCreated(b, source)
            }
            val s = MemoryMetrics.capture(this)
            emit(BitmapTracker.describe(s.graphicsPssKb) + "\nJava 堆：${MemoryMetrics.fmt(s.javaUsedBytes)}")
        }

        bind(R.id.btn_bitmap_free) {
            val source = "MemoryLab:压力演示"
            bitmaps.forEach { b ->
                BitmapTracker.onBitmapRecycled(b, source)
                b.recycle()
            }
            val n = bitmaps.size
            bitmaps.clear()
            val s = MemoryMetrics.capture(this)
            emit(
                "已回收 $n 张位图。\n" + BitmapTracker.describe(s.graphicsPssKb) + "\n" +
                        "⚠️ 注意 graphics PSS 可能**不立刻下降**（系统侧的统计与页面归还都有延迟），\n" +
                        "   而我们自己的计数是同步的 —— 这个不一致本身值得讲清楚。"
            )
        }

        bind(R.id.btn_trend) {
            emit("【趋势报告】\n" + MemoryMonitor.flushTrend("MemoryLab"))
        }

        bind(R.id.btn_trim_sim) {
            emit(
                "【模拟 trim 压力】\n" +
                        "⚠️ 这不是伪造：**刻意调用系统那条路径**（通过 registerComponentCallbacks 注册的\n" +
                        "   回调对象由我们直接触发），用来验证「trim 上报链路」通了。\n" +
                        "   真实设备上的触发者也是同一个入口。\n" +
                        "预期：\n" +
                        "  · RUNNING_LOW / RUNNING_CRITICAL / COMPLETE → 产生 MEMORY_TRIM 事件\n" +
                        "  · UI_HIDDEN(20) → **不上报**（它只是「退到后台」，一天几十次；\n" +
                        "    且它的数值 20 大于 RUNNING_LOW 的 10，用阈值比较会误判 —— 这就是\n" +
                        "    为什么代码里必须用 `when` 精确匹配而不是 `level > 10`）"
            )
            // 直接走 MemoryMonitor 内部同一条判定路径（不做任何"假装上报"）
            val results = listOf(
                android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
                android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
                android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
                android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ).joinToString("\n") { lvl ->
                "  级别 ${lvl.toString().padStart(2)} → ${MemoryMetrics.trimName(lvl)}" +
                        "  压力轴=${MemoryMetrics.isPressureLevel(lvl)}"
            }
            emit("级别语义表：\n$results\n\n" + MemoryMonitor.formatOverview())
        }



        bind(R.id.btn_cross_session) {
            emit(MemoryMonitor.crossSessionReport(this))
        }

        // ── 上报驱动（模拟提交 + 在 App 内看数据）──
        bind(R.id.btn_upload_toggle) {
            val d = com.interview.稳定性监控.StabilityMonitor.ReportDriver
            // 库侧默认关、App 侧 install 时已开；这里做的是"临时切换"（观察用）
            if (d.isRunning()) d.stop() else d.start(intervalMs = 5_000L)
            emit(uploadStatus("切换"))
        }

        bind(R.id.btn_upload_now) {
            // 手动立刻上报一次（与周期驱动走**同一段代码**，见 ReportDriver.flushNow）
            val ok = com.interview.稳定性监控.StabilityMonitor.ReportDriver.flushNow("手动")
            emit(
                buildString {
                    val n = com.interview.稳定性监控.StabilityReporter.lastUploadSize
                    appendLine(
                        "【立即上报】" + when {
                            !ok -> "失败（事件保留，下轮再试）"
                            n == 0 -> "成功，但**本次没有待发事件**（水位没越级、trim 没到压力轴时就是这样）"
                            else -> "成功，本次提交 $n 条"
                        }
                    )
                    appendLine()
                    appendLine(uploadStatus("手动"))
                }
            )
        }

        bind(R.id.btn_upload_view) {
            emit(
                buildString {
                    appendLine("【模拟提交的数据（App 内查看）】")
                    appendLine("⚠️ 这里是**模拟出口**：不联网，只记录「发了什么」，")
                    appendLine("   用于证明「采集 → 判定 → 出口 → 可查看」这条链路是通的。")
                    appendLine()
                    appendLine(uploadStatus("查看"))
                    appendLine()
                    val log = com.interview.稳定性监控.StabilityReporter.uploadLog()
                    if (log.isEmpty()) {
                        appendLine("（还没提交过任何批次）")
                        appendLine("先点「立即上报」，或点「开上报驱动」让它每 5s 自动发一次。")
                    } else {
                        appendLine("── 最近 ${log.size} 个批次（新→旧）──")
                        log.reversed().forEachIndexed { i, batch ->
                            appendLine()
                            appendLine("批次[-$i]：${batch.size} 条")
                            batch.takeLast(12).forEach {
                                appendLine("  [${it.kind.name}] ${it.name}")
                                if (it.detail.isNotBlank()) {
                                    appendLine("      ${it.detail.take(160)}")
                                }
                            }
                        }
                    }
                }
            )
        }

        bind(R.id.btn_crash_header) {
            val events = com.interview.稳定性监控.StabilityReporter
                .snapshot(200)
                .filter { it.kind.name.startsWith("MEMORY") }
            emit(
                buildString {
                    appendLine("【统一出口里的内存事件（共 ${events.size} 条）】")
                    if (events.isEmpty()) {
                        appendLine("（无）—— 内存事件**只在状态变化时**产生（越级 / trim 压力 / 周期聚合），")
                        appendLine("不是每次采样一条。这正是「水位是连续量、越级才是事件」这条设计的体现。")
                    } else {
                        events.takeLast(20).forEach {
                            appendLine("  ${it.oneLine()}")
                            appendLine("     ${it.detail.take(220)}")
                        }
                    }
                    appendLine()
                    appendLine(com.interview.稳定性监控.StabilityReporter.describe())
                }
            )
        }
    }

    /**
     * 上报驱动的状态文案。
     *
     * ⚠️ 这里刻意把「驱动是否在跑」「待发队列有多少」「已经成功提交了多少」三件事
     *    分开显示 —— 只显示"开着"是不够的：开着但一条没发出去（例如 upload 恒 false）
     *    在只看开关的 UI 上和正常情况**长得一样**，而那正是最坏的数据丢失形态。
     */
    private fun uploadStatus(action: String): String {
        val d = com.interview.稳定性监控.StabilityMonitor.ReportDriver
        val pending = com.interview.稳定性监控.StabilityReporter.snapshot(1000)
            .count { it.kind.name.startsWith("MEMORY") }
        return buildString {
            appendLine("── 上报驱动（$action）──")
            appendLine(
                "  驱动：" + when {
                    d.isRunning() -> "运行中（安装时已自动开启；周期 + 每次进后台）"
                    else -> "已关闭（可在本页重新开启）"
                }
            )
            appendLine("  出口：模拟提交（不联网；接平台时替换 upload 一个 lambda）")
            appendLine("  待发（内存类）：$pending 条")
            appendLine("  累计已提交：${com.interview.稳定性监控.StabilityReporter.uploadedCount()} 条 / ${d.batchCount()} 批次（周期）")
            appendLine("  最近一批：${com.interview.稳定性监控.StabilityReporter.lastUploadSize} 条")
        }
    }

    // ─────────────────────────────────────────────
    // 页面级接线（与稳定性模块的纪律一致：**由页面自己挂**）
    // ─────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        MemoryMonitor.onActivityResumed(this, "MemoryLab")
    }

    override fun onPause() {
        emit(MemoryMonitor.onActivityPaused(this, "MemoryLab"))
        super.onPause()
    }

    // ─────────────────────────────────────────────
    // 小工具
    // ─────────────────────────────────────────────

    /** 用字符画一条 Java 堆水位的迷你波形（页面上能直接看出"涨了有没有回落"）。 */
    private fun sparkline(blocks: Int = 40): String {
        val pts = MemoryMonitor.samplerRef()?.points().orEmpty()
        if (pts.isEmpty()) return "（采样点为空 —— 先让页面停留一会儿）"
        val take = pts.takeLast(blocks)
        val min = take.minOf { it.javaUsedBytes }
        val max = take.maxOf { it.javaUsedBytes }
        if (max <= min) return "（水位无变化：${MemoryMetrics.fmt(min)}）"
        val chars = "▁▂▃▄▅▆▇█"
        val line = take.joinToString("") {
            val r = (it.javaUsedBytes - min).toDouble() / (max - min)
            chars[(r * (chars.length - 1)).toInt().coerceIn(0, chars.length - 1)].toString()
        }
        return "水位 ${MemoryMetrics.fmt(min)} .. ${MemoryMetrics.fmt(max)}（${take.size} 点）\n  $line"
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

    private companion object {
        const val TAG = "MemoryMonitor"
    }
}
