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

        bind(R.id.btn_native_trend) {
            emit("【Native 趋势报告（第二条判决线）】\n" + MemoryMonitor.flushNativeTrend("MemoryLab"))
        }

        bind(R.id.btn_smaps) {
            // ⚠️ 必须在后台线程：一次 smaps 读是"重"的（百 KB~MB 级 + 毫秒~几十毫秒解析）。
            //    emit 内部已 runOnUiThread，所以这里直接在后台算完调 emit。
            emit("【smaps 映射级归因】读取 /proc/self/smaps …（后台线程）")
            Thread {
                val text = runCatching { SmapsReader.readSelf().describe(topN = 20) }
                    .getOrElse { "读取失败：${it.message}\n⚠️ 部分 ROM/SELinux 会限制读 /proc/self/smaps" }
                emit(text)
            }.apply { name = "smaps-read" }.start()
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

        // ═══════ 第一组之二：OOM 现场取证（fork 快照）═══════
        // 这一组按钮的共同点：**每个都在演示/验证本方案的一条边界**，
        // 而不是"点一下看能不能跑"。尤其「对照实验flags」——它能复现三种卡死。

        bind(R.id.btn_oom_desc) {
            emit(OomSnapshotMonitor.describe(this))
        }

        bind(R.id.btn_oom_snapshot) {
            emit(
                oomProbeHeader() +
                        "【手动快照】用**当前水位**拍一张（不等 OOM）。\n" +
                        "预期：父进程只被挂起**毫秒级**（就是 fork 那个窗口），几秒后出文件。\n" +
                        "若堆很大（几百 MB），文件也会有几百 MB —— 这是本方案的真实成本，不是 bug。"
            )
            // ratio 传 1.0：按钮本身是显式动作，绕过"水位阈值"这一层判定
            val accepted = OomSnapshotMonitor.snapshotOnWatermark(this, OomSnapshotMonitor.MANUAL_SCENE, 1.0)
            if (!accepted) {
                emit(
                    "未受理。可能原因（逐个对照）：\n" +
                            "  · 最小间隔未到（当前 ${OomSnapshotMonitor.minIntervalMs / 1000}s）\n" +
                            "  · 已有一次快照进行中\n" +
                            "  · native 不可用（符号未命中 / 非 arm64）\n" +
                            "  · 后台泳道饱和\n" +
                            "  · enabled=false\n" +
                            "点「OOM能力/符号」能看到当前每一项的实际状态。"
                )
            }
        }

        bind(R.id.btn_oom_real) {
            emit(
                "【真 OOM + fork 快照】\n" +
                        "即将**真的**把 Java 堆分配满，直到 ART 抛 OutOfMemoryError，\n" +
                        "在 catch 里立刻 fork 子进程拍快照。\n\n" +
                        "预期：\n" +
                        "  · 水位冲到 max（本机 256MB）→ 抛 OutOfMemoryError\n" +
                        "  · fork 快照产出几百 MB 文件（实测 285MB / 1160ms）\n" +
                        "  · 本按钮下方能看到「OOM 已触发」+ 快照结果\n\n" +
                        "⚠️ 为什么必须真 OOM，而不是\"加压到 85% 再拍\"：\n" +
                        "   真 OOM 那一刻是本方案唯一真正要面对的场景 —— 堆已经满了，\n" +
                        "   而 fork 还要复制页表、子进程还要写缓冲。只测 85% 等于把最难的场景跳过。\n" +
                        "⚠️ 期间 App 会真的内存紧张，别的操作可能失败 —— 别掩盖这个代价。"
            )
            triggerRealOom()
        }

        bind(R.id.btn_oom_list) {
            // ⚠️ 先把 activity 语境取出来：buildString 的 lambda 里 `this` 是 StringBuilder，
            //    直接写 this 会把 StringBuilder 当 Context 传出去（编译期报 Type mismatch）。
            val ctx = this
            val files = OomSnapshotMonitor.listSnapshots(ctx)
            emit(
                buildString {
                    appendLine("【OOM 快照文件】共 ${files.size} 份 / ${MemoryMetrics.fmt(OomSnapshotMonitor.totalDiskUsage(ctx))}")
                    if (files.isEmpty()) {
                        appendLine("（无）—— 先点「快照(fork)」或「⚠真OOM+快照」")
                    } else {
                        files.forEach { f ->
                            appendLine("  ${f.name}")
                            appendLine("     ${MemoryMetrics.fmt(f.length())}   ${java.util.Date(f.lastModified())}")
                        }
                    }
                    appendLine()
                    appendLine("⚠️ 单份可达数百 MB（OOM 时堆是满的）—— 这就是保留上限只有 2 份的原因。")
                    appendLine("   生产上应当是「上报成功后立即删」；这里留 2 份是为了能对比两次 OOM。")
                }
            )
        }

        bind(R.id.btn_oom_analyze) {
            // ⚠️ 这个按钮的存在本身就是一条设计结论：**端内解析被有意推迟了**。
            //    在 OOM 时刻解析实测会自己 OutOfMemoryError（见 doSnapshot 的注释），
            //    所以解析必须等到"内存已经恢复"的时刻，也就是用户点它的现在。
            // ⚠️ 不能只看 lastSnapshot：它是**进程内**的字段，重启后就没了。
            //    而快照文件是落盘的 —— "进程重启后想分析上次崩溃的现场"恰恰是最常见的用法。
            //    所以这里以**磁盘上的最新文件**为准（lastSnapshot 只是优先提示用）。
            val f = OomSnapshotMonitor.lastSnapshot?.file?.takeIf { it.exists() }
                ?: OomSnapshotMonitor.listSnapshots(this).firstOrNull()
            if (f == null) {
                emit("还没有快照可解析 —— 先点「快照(fork)」或「真OOM+快照」。")
                return@bind
            }
            val fromDisk = OomSnapshotMonitor.lastSnapshot?.file?.absolutePath != f.absolutePath
            if (fromDisk) {
                emit("（进程内没有上次记录，改用磁盘上最新的一份：${f.name}）")
            }
            emit("开始解析 ${f.name}（${MemoryMetrics.fmt(f.length())}）…\n" +
                    "⚠️ 这是**流式**解析，内存占用不随文件大小线性增长，但 285MB 大概要几十秒；\n" +
                    "   期间页面会被这段输出挡住（后台线程在做，UI 没卡）。")
            ThreadPools.background.execute("oom.analyze") {
                val t0 = android.os.SystemClock.uptimeMillis()
                val h = OomSnapshotMonitor.analyze(f)
                val cost = android.os.SystemClock.uptimeMillis() - t0
                emit(
                    if (h == null) {
                        "解析失败（文件仍在，可用 shark/MAT 离线打开）：${f.absolutePath}"
                    } else {
                        buildString {
                            appendLine("═══ 事后解析结果（${cost}ms）═══")
                            appendLine("类 ${h.classCount} 个 / 合计 ${MemoryMetrics.fmt(h.totalBytes)} / 实例 ${h.totalInstances} 个")
                            appendLine("idSize=${h.idSize} 文件=${MemoryMetrics.fmt(h.fileBytes)} 类名映射=${if (h.nameMappingReady) "完整" else "缺失"}")
                            if (h.skippedSegments > 0 || h.unresolvedInstances > 0) {
                                appendLine("⚠️ 跳过 segment ${h.skippedSegments} 个 / 未解析实例 ${h.unresolvedInstances} 个 —— 数字**偏低**，如实暴露")
                            }
                            appendLine()
                            appendLine("按字节 Top10：")
                            h.top(10).forEach { appendLine("  ${it.describe()}") }
                            appendLine()
                            appendLine("⚠️ 只有「类 → 数量/字节」，没有引用链（谁持有它）。")
                            appendLine("   要看「谁**涨了**」需要两份快照差分，或者用 shark 打开这个文件看支配树。")
                        }
                    }
                )
            }
        }

        bind(R.id.btn_oom_cleanup) {
            val dir = OomSnapshotMonitor.snapshotDir(this)
            val before = OomSnapshotMonitor.totalDiskUsage(this)
            val removed = OomSnapshotMonitor.cleanupOldDumps(dir, keep = 0)
            val after = OomSnapshotMonitor.totalDiskUsage(this)
            emit("清理完成：删除 $removed 份，释放 ${MemoryMetrics.fmt(before - after)}（剩余 ${MemoryMetrics.fmt(after)}）")
        }
    }

    /**
     * 真 OOM 触发：把堆分配满，在 catch 里立刻取证。
     *
     * ⚠️ 必须跑在**后台线程**：这一操作会吃掉几百 MB，在主线程做会把 UI 一起拖死。
     *   但 fork 之后父进程会继续活着（这是本方案的前提），所以这里的"占用内存"
     *   在同一时刻也证明了**父进程没有被 dump 流程影响**。
     */
    private fun triggerRealOom() {
        ThreadPools.background.execute("oom.real.trigger") {
            val holder = ArrayList<ByteArray>()
            var oom: OutOfMemoryError? = null
            val t0 = SystemClock.uptimeMillis()
            try {
                // 一次 1MB：让"最后一次分配失败"的现场就在眼前，也便于观察已持有量
                while (true) {
                    holder.add(ByteArray(1024 * 1024))
                }
            } catch (e: OutOfMemoryError) {
                oom = e
            }
            val cost = SystemClock.uptimeMillis() - t0
            val used = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
            val max = Runtime.getRuntime().maxMemory()

            emit(
                "【真 OOM 已触发】耗时 ${cost}ms\n" +
                        "  异常：${oom?.message}\n" +
                        "  水位：${MemoryMetrics.fmt(used)} / ${MemoryMetrics.fmt(max)}\n" +
                        "  已持有：${holder.size} 个 1MB byte[]\n" +
                        "  现在调用 OomSnapshotMonitor.onOutOfMemoryError() —— 它会 fork 子进程取证。"
            )

            // 这就是"线上该怎么接"的写法：在**每个** catch(OutOfMemoryError) 里都调一次
            val held = holder.size
            val before = OomSnapshotMonitor.lastSnapshot
            val accepted = OomSnapshotMonitor.onOutOfMemoryError(this, oom, REAL_OOM_SCENE)
            emit("onOutOfMemoryError 受理=$accepted（false 表示被闸门挡住或 native 不可用）\n" +
                    "⚠️ 父进程此刻仍然活着、仍持有 ${held}MB —— 这正是 fork 方案与\n" +
                    "   「在本进程里 dump」的区别：取证不需要父进程先腾出内存。")

            // ⚠️ 这里**不能** sleep 固定秒数再读 lastSnapshot，两个坑：
            //    ① 真 OOM 时堆是满的（实测 285MB 文件），dump+解析远超 3s，会读到"还没好"；
            //    ② 更隐蔽的：lastSnapshot 是**全局**的，睡眠期间可能读到的是**上一张**快照，
            //       于是页面把旧记录当成这次的现场展示 —— 一个不会报错、但结论完全错的 bug。
            //    所以改成"轮询直到对象身份变化"，即**确认拿到的是这一次的结果**。
            val rec = waitForNewRecord(before, timeoutMs = 90_000)
            emit(
                if (rec == null) {
                    "（90s 内没等到新记录 —— 可能仍在写盘，或父进程已被系统回收。\n" +
                            " 点「快照文件列表」看文件是否已出现；文件在，就说明快照其实成功了。）"
                } else {
                    rec.describe() + "\n\n（父进程仍持有 ${held}MB，未被 dump 影响）"
                }
            )
            holder.clear()
            emit("已释放 ${held}MB 负载 —— 注意：**快照文件与这份释放无关**，\n" +
                    "它是 fork 那一刻的冻结副本（子进程早已独立）。")
        }
    }

    /**
     * 等一次**新的**快照记录出现（按对象身份判定，不按时间戳）。
     *
     * 为什么必须按身份：`lastSnapshot` 是全局单例字段。若只用 sleep + 读字段，
     * 很容易把**上一次**的结果当成这一次的现场展示出来 —— 不报错，但结论是错的。
     * 这是"取证工具本身给出错误证据"的最坏形态，所以宁可写几行轮询。
     */
    private fun waitForNewRecord(prev: OomSnapshotMonitor.SnapshotRecord?, timeoutMs: Long): OomSnapshotMonitor.SnapshotRecord? {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            val now = OomSnapshotMonitor.lastSnapshot
            if (now != null && now !== prev) return now
            Thread.sleep(200)
        }
        return null
    }

    /** OOM 组的输出前缀：把"当前能不能用"摆在每条输出最前面，避免"点了没反应"要靠猜。 */
    private fun oomProbeHeader(): String = buildString {
        appendLine("── OOM 快照能力 ──")
        val probe = com.interview.oomdump.OomSnapshotNative.probe()
        when {
            probe == null -> appendLine("  ⚠️ native 不可用：${com.interview.oomdump.OomSnapshotNative.loadError}")
            probe.usable -> appendLine("  ✅ 可用（hprof 入口符号已命中）")
            else -> appendLine("  ❌ 符号未命中 —— 点「OOM能力/符号」看缺了哪个")
        }
        appendLine()
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

        const val REAL_OOM_SCENE = "真OOM按钮"
    }
}
