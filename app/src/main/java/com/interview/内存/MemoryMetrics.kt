package com.interview.内存

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * Time: 2026/10/8
 * Author: wgt
 * Description: 线上内存监控的**指标层** —— 把「内存」这件事变成一组有口径、可比较、可运维的数字
 *
 * ══════════════════════════════════════════════════════════════════════
 * 〇、本文件与既有 `image/MemoryProbe.kt` 的分工（避免重复造轮子）
 * ══════════════════════════════════════════════════════════════════════
 *
 * `MemoryProbe` 是**离线教学探针**：三个数（Java 堆 / native 堆 / graphics PSS），
 * 给「大图加载 Lab」当场演示「降采样生效了没」。它没有口径、没有容器感、没有基线。
 *
 * 本类要做的是**线上口径**，差别在四处（每一处都是线上会咬人的地方）：
 *
 * | | MemoryProbe | 本类 |
 * |---|---|---|
 * | 堆上限 | `Runtime.maxMemory()` | **`min(maxMemory, memoryClass)`** —— 见 §一.1 |
 * | 分配器视角 | `Debug.getNativeHeapAllocatedSize` | 再拆 **arena/free/映射** 与 **scudo 尺寸档** —— 见 §一.3 |
 * | 容器感 | 无 | 收 `onTrimMemory` 的**平台原话**（UI_HIDDEN / RUNNING_*） —— 见 §一.4 |
 * | 时间维度 | 单点 | 采样 + **基线 + 斜率**（回答「一直在涨」还是「涨了就回落」） —— 见 §二 |
 *
 * ══════════════════════════════════════════════════════════════════════
 * 一、四个必须先把口径定对的地方（这是"内存监控"最容易做成废数据的地方）
 * ══════════════════════════════════════════════════════════════════════
 *
 * **1. Java 堆的"上限"到底以谁为准？**
 *
 * `Runtime.maxMemory()` 返回的是 ART 堆的**硬上限**，但厂商通常还会通过
 * `ActivityManager.getMemoryClass()` 下发一个**更小的软上限**（大型堆另有
 * `getLargeMemoryClass()`，需要 manifest 里开 `largeHeap`）。
 * 实测（API 36 模拟器 / Pixel 级配置）：`maxMemory()` = 268435456（256 MB）、
 * `memoryClass` = 256、`largeMemoryClass` = 512。
 *
 * ⚠️ 若只拿 `maxMemory()` 算水位，**UI 会显示"才用了一半"而实际已经贴到软线**，
 * 于是「快 OOM 了」这个信号永远不触发。所以本类同时给出两个比值，
 * 并把 [Snapshot.heapLimitBytes] 定义为**两者取小**，作为唯一的水位分母。
 *
 * **2. 「用了多少」不能用单个数，要看三兄弟。**
 *
 * ```
 *   used      = totalMemory() - freeMemory()    ← 存活对象（"真水位"）
 *   committed = totalMemory()                   ← ART 已向系统要到的内存（含空白）
 *   max       = 上限
 * ```
 *
 * 线上最常见的误判是**把 committed 当 used**：GC 之后 used 掉下来了、
 * committed 没还（ART 的 `HeapGrowthLimit` 策略下也不会立即还），
 * 于是曲线看起来"一直不降"——那是**容量**不是**占用**。
 * 同时 committed 又是 OOM 的**真实前兆**：`committed == max` 且 used 贴着它，
 * 下一次分配就只能抛 OOM 了。两者都要采，但**语义必须分开**。
 *
 * **3. Native 侧不是"一个数"，而是至少三层（这一层是面试的分水岭）。**
 *
 * ```
 *   ① Debug.getNativeHeapAllocatedSize()  ← malloc/free 的净账（分配器视角）
 *   ② MemoryInfo.nativePss                ← 内核视角：本进程 native 私有的分摊页
 *   ③ /proc/self/status VmRSS             ← 进程全部驻留（含 code / graphics / stack）
 *
 *   ⚠️ 三者互不相等，而且**谁大谁小不固定**：
 *      · 一个 8 MB 的 malloc，在 API 26+ 可能来自 mmap 而不是主 arena
 *        （阈值 __bionic_...，实测本机 scudo 下 4096B×8..65536B 都进 size-class 表，
 *          再大就走 mmap）→ ① 记它，但 ② 未必等量增加；
 *      · 位图（API 26+）在 native 堆，但**归属 graphics 段**，不计入 ② 的 native
 *        （系统按"谁分配的"归类，不是按"内存在哪"）；
 *      · 释放后 ① 立刻减，② 要等页被真正 unmap/归还（分配器的 free 缓存
 *        —— scudo 的 secondary cache —— 会留住页）→ 出现"native 堆已回落、
 *        PSS 不降"的**正常**现象。
 * ```
 *
 * ⇒ 所以线上判断「native 泄漏」不能只盯 ①，要看 **①持续涨 且 ②/PSS 同步涨**。
 *   只看 ① 会把分配器缓存当泄漏，只看 PSS 会把 mmap 抖动当泄漏。
 *   本类四个都采（见 [Snapshot.nativeAllocatedBytes] / [Snapshot.nativePssKb] /
 *   [Snapshot.vmRssKb] / [Snapshot.nativeHeapSizeBytes]），
 *   并在 [Trend] 里给出**"同步涨"**这个判据（§二.3）。
 *
 * **4. 线上最权威的"内存压力"信号其实来自系统，不是我们自己算的。**
 *
 * `ComponentCallbacks2.onTrimMemory(level)` 是**系统主动告诉你**它现在多缺内存：
 *
 * | level | 系统的原话 | 我们该做什么 |
 * |---|---|---|
 * | `TRIM_MEMORY_UI_HIDDEN`(20) | "你到后台了" | 清 UI 级缓存（**不是**内存压力） |
 * | `TRIM_MEMORY_RUNNING_MODERATE`(5) | "整机内存有点紧" | 清可重建缓存 |
 * | `TRIM_MEMORY_RUNNING_LOW`(10) | "很紧了" | 清掉图片/列表缓存 |
 * | `TRIM_MEMORY_RUNNING_CRITICAL`(15) | "快不行了，再不清就杀你" | 清一切，上报 |
 * | `TRIM_MEMORY_COMPLETE`(80) | "你排在被杀队列的最前面" | 上报 + 落盘 |
 *
 * ⚠️ 这里有个**实证级别的坑**：`TRIM_MEMORY_UI_HIDDEN` 与 `TRIM_MEMORY_RUNNING_*`
 * 是**两个不同的语义轴**（可见性 vs 内存压力），但它们的数值交叉分布
 * （UI_HIDDEN=20 恰好落在 RUNNING_LOW=10 与 RUNNING_CRITICAL=15 之上）。
 * 所以**必须用相等比较，绝不能写 `level > 10` 这种阈值判断** ——
 * 那样会把"退到后台"(20) 误判成"内存极度紧张"，在低端机上刷出一堆假告警。
 * 本类用 `when (level) { 精确常量 -> ... }`，并把这一条固化在代码里。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 二、为什么必须有「采样 + 趋势」两层（不是采一次就够了）
 * ══════════════════════════════════════════════════════════════════════
 *
 * 单点快照回答不了「有没有问题」，因为内存的场景天生是**时间序列**问题：
 *
 * ① **正常的爬升**：进页面 → 加载图片 → 占用涨 → 退出页面 → 回落。涨是应该的。
 * ② **缓慢泄漏**：进页面 → 涨 → 退出 → **不回落**（或回落不到基线）。
 * ③ **抖动**：GC 前涨到 80%，GC 后回到 30%，来回震荡（正常，但**如果没回落就是 OOM 前兆**）。
 *
 * 三者用单点无法区分。所以：
 *
 * · [Sampler] 固定间隔采点（**在主线程 Handler 上**，理由见该类注释）；
 * · [Trend] 在环形缓冲上做**基线对比**与**线性斜率**，把"涨"分成
 *   「涨了会回落」[Verdict.GROW_AND_FALL] 与「涨了不回落」[Verdict.LEAK_SUSPECT]。
 *
 * ⚠️ 诚实边界：**斜率不是泄漏的证明**。它只能给出"值得看一眼"。
 *    真正的泄漏证明必须靠 Java 堆直方图（[MemoryHeapAnalyzer]）与 native 归因
 *    （[MemoryAllocTracker]）去找**谁持有**。见 INTERVIEW-线上内存监控方案.md §四。
 */
object MemoryMetrics {

    private const val TAG = "MemoryMetrics"

    // ─────────────────────────────────────────────
    // 一、快照
    // ─────────────────────────────────────────────

    /**
     * 一次内存快照。字段刻意**扁平**（便于直接映射到 APM 宽表），单位统一为字节/KB 并在名字里写明。
     *
     * 注意 [dalvikPssKb] 这个名字沿用系统的历史叫法 —— 它在 ART 上指的是
     * **Java 堆**（含 ART 自己的元数据），不是 Dalvik 虚拟机。
     */
    data class Snapshot(
        // ── Java 堆（JVM）──
        /** 存活对象占用 = totalMemory - freeMemory */
        val javaUsedBytes: Long,
        /** ART 已向系统提交的堆（含未使用的空白页） */
        val javaCommittedBytes: Long,
        /** ART 堆硬上限 = Runtime.maxMemory() */
        val javaMaxBytes: Long,
        /** 厂商软上限 = ActivityManager.getMemoryClass() * 1MB */
        val memoryClassBytes: Long,
        /** largeHeap 上限 = getLargeMemoryClass() * 1MB */
        val largeMemoryClassBytes: Long,
        /** 水位分母 = min(javaMaxBytes, memoryClassBytes)。⚠️ 见类注释 §一.1 */
        val heapLimitBytes: Long,

        // ── Native ──
        /** malloc/free 净账（分配器视角，API 全版本可用） */
        val nativeAllocatedBytes: Long,
        /** 分配器**已映射**的总量（含 free 缓存与碎片；远大于 allocated 是正常的） */
        val nativeHeapSizeBytes: Long,
        /** 内核视角：本进程 native 私有的分摊页 */
        val nativePssKb: Int,
        /** 进程全部驻留内存（VmRSS）—— 含 code / graphics / stack */
        val vmRssKb: Int,
        /** 进程生命周期内的驻留峰值（VmHWM）—— 只在真机上可信 */
        val vmHwmKb: Int,

        // ── PSS 分解（系统的归类，含"不在我名下但我在用"的图形内存）──
        val totalPssKb: Int,
        val dalvikPssKb: Int,
        val graphicsPssKb: Int,
        val eglPssKb: Int,
        val glPssKb: Int,
        val codePssKb: Int,
        val stackPssKb: Int,
        val otherPssKb: Int,
        /** 私有脏页合计：**真正的"这块内存只有我在用"**，最接近"必须还回去"的量 */
        val totalPrivateDirtyKb: Int,
        /** 交换出去的 PSS（API 30+ 才有意义；低内存设备上会先换出再杀） */
        val swapPssKb: Int,

        // ── 分配器内部（malloc_info / mallinfo2）──
        /** 分配器视角的"在用字节"（mallinfo2.uordblks） */
        val allocatorUsedBytes: Long,
        /** 分配器视角的"空闲字节"（mallinfo2.fordblks）——**碎片与缓存的量** */
        val allocatorFreeBytes: Long,
        /** mmap 出去的字节（mallinfo2.hblkhd）——大块/位图常在这里 */
        val allocatorMmapBytes: Long,
        /** scudo 尺寸档 Top-N（`sizeBytes to liveCount`）。非 scudo 设备为空 —— 见 §三 */
        val sizeClasses: List<Pair<Int, Int>>,

        // ── ART/GC 运行时统计（Debug.getRuntimeStats，API 23+）──
        /** art.gc.gc-count 等，键名原样保留（不同 ROM 可能缺项） */
        val runtimeStats: Map<String, String>,

        // ── 设备与容器状态 ──
        val memoryClassMb: Int,
        val isLowRamDevice: Boolean,
        /** 系统最近一次下发的 trim 级别（ComponentCallbacks2 常量），未收到过为 -1 */
        val lastTrimLevel: Int,
        /** 系统是否处于全局低内存（ActivityManager.MemoryInfo.lowMemory） */
        val deviceLowMemory: Boolean,
        /** 整机可用内存（含可回收的 cached），用于判断"是不是整机在挤" */
        val deviceAvailMemBytes: Long,
        /** 整机总内存 */
        val deviceTotalMemBytes: Long,
        /** 整机低内存阈值 */
        val deviceThresholdBytes: Long,

        val timestamp: Long,
    ) {
        val javaUsedRatio: Float
            get() = if (heapLimitBytes <= 0) 0f else (javaUsedBytes.toFloat() / heapLimitBytes)

        val javaCommittedRatio: Float
            get() = if (heapLimitBytes <= 0) 0f else (javaCommittedBytes.toFloat() / heapLimitBytes)

        /** 剩余到硬上限的绝对量。比"百分比"更能回答"还能不能扛住一次大图" */
        val javaHeadroomBytes: Long
            get() = (javaMaxBytes - javaUsedBytes).coerceAtLeast(0L)

        /** 分配器未归还的比例：高 = 碎片/缓存重（不一定泄漏，但解释"PSS 不降"） */
        val allocatorWasteRatio: Float
            get() = if (nativeHeapSizeBytes <= 0) 0f
            else ((nativeHeapSizeBytes - nativeAllocatedBytes).toFloat() / nativeHeapSizeBytes)

        fun toEventDetail(): String = buildString {
            append("java=${fmt(javaUsedBytes)}/${fmt(heapLimitBytes)}")
            append("(${(javaUsedRatio * 100).toInt()}%)")
            append(" committed=${fmt(javaCommittedBytes)}")
            append(" | nativeAlloc=${fmt(nativeAllocatedBytes)}")
            append(" nativeMapped=${fmt(nativeHeapSizeBytes)}")
            append(" nativePss=${fmtKb(nativePssKb)}")
            append(" rss=${fmtKb(vmRssKb)} hwm=${fmtKb(vmHwmKb)}")
            append(" | pss=${fmtKb(totalPssKb)}")
            append("(dalvik=${fmtKb(dalvikPssKb)} gfx=${fmtKb(graphicsPssKb)}")
            append(" code=${fmtKb(codePssKb)} stack=${fmtKb(stackPssKb)} other=${fmtKb(otherPssKb)})")
            append(" privDirty=${fmtKb(totalPrivateDirtyKb)}")
            append(" | allocatorUsed=${fmt(allocatorUsedBytes)}")
            append(" free=${fmt(allocatorFreeBytes)} mmap=${fmt(allocatorMmapBytes)}")
            append(" | trim=${trimName(lastTrimLevel)} lowRam=$isLowRamDevice")
        }.let { it.take(1024) } // 统一出口的 detail 有体积预算（见 StabilityReporter.Event）
    }

    // ─────────────────────────────────────────────
    // 二、采集
    // ─────────────────────────────────────────────

    /**
     * 采集一次。**本方法可在主线程调用**（全部是轻量系统调用 + 一次 /proc 读），
     * 但 `malloc_info` 与 `sizeClasses` 排在 [MemoryAllocTracker] 里、
     * 由 native 侧做，走的是同一个 fast path（见其注释）。
     *
     * ⚠️ 采集失败必须降级而不是抛：线上任何"监控把主流程搞崩"都是不可接受的。
     *    所以每块都用 `runCatching`，缺项填 0/-1 并由 [Snapshot] 的派生属性判空。
     */
    fun capture(context: Context): Snapshot {
        val now = System.currentTimeMillis()
        val rt = Runtime.getRuntime()
        val javaUsed = rt.totalMemory() - rt.freeMemory()
        val javaCommitted = rt.totalMemory()
        val javaMax = rt.maxMemory()

        var memoryClassMb = 0
        var largeMemoryClassMb = 0
        var isLowRam = false
        var trimLevel = -1
        var deviceLowMemory = false
        var deviceAvail = 0L
        var deviceTotal = 0L
        var deviceThreshold = 0L

        runCatching {
            val am = context.applicationContext
                .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            memoryClassMb = am.memoryClass
            largeMemoryClassMb = am.largeMemoryClass
            isLowRam = am.isLowRamDevice
            trimLevel = lastTrimLevelOf(context)
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            deviceLowMemory = mi.lowMemory
            deviceAvail = mi.availMem
            deviceTotal = mi.totalMem
            deviceThreshold = mi.threshold
        }.onFailure { Log.w(TAG, "读 ActivityManager 失败（降级）", it) }

        var totalPss = 0
        var dalvikPss = 0
        var graphicsPss = 0
        var eglPss = 0
        var glPss = 0
        var codePss = 0
        var stackPss = 0
        var otherPss = 0
        var privateDirty = 0
        var swapPss = 0
        runCatching {
            val am = context.applicationContext
                .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()
            if (info != null) {
                totalPss = info.totalPss
                dalvikPss = info.dalvikPss
                otherPss = info.otherPss
                privateDirty = info.totalPrivateDirty
                swapPss = runCatching { info.getTotalSwappablePss() }.getOrDefault(0)
                // ⚠️ 下面这些**没有公开字段**，只能走 getMemoryStat(key)（API 23+）。
                //    键名来自 frameworks/base/core/java/android/os/Debug.java 的
                //    addDetailedMemoryStats()，其中 "summary.*" 系列是跨版本稳定的，
                //    而 "egl"/"gl" 这两个**是 ROM 相关项**（有 GPU 驱动才统计），
                //    缺失是正常的 —— 所以逐个 runCatching，缺项记 0。
                graphicsPss = memStat(info, "summary.graphics")
                codePss = memStat(info, "summary.code")
                stackPss = memStat(info, "summary.stack")
                // ⚠️ 这两个是**非 summary** 项，实测部分 ROM 拿不到；拿不到不影响主口径
                eglPss = memStat(info, "egl")
                glPss = memStat(info, "gl")
            }
        }.onFailure { Log.w(TAG, "getProcessMemoryInfo 失败（降级）", it) }

        val memoryClassBytes = memoryClassMb.toLong() * 1024 * 1024
        val largeMemoryClassBytes = largeMemoryClassMb.toLong() * 1024 * 1024
        // 水位分母取小：厂商软上限通常是先撞到的那个（见类注释 §一.1）
        val heapLimit = if (memoryClassBytes in 1..javaMax) memoryClassBytes else javaMax

        val nativeAlloc = runCatching { Debug.getNativeHeapAllocatedSize() }.getOrDefault(-1L)
        val nativeHeapSize = runCatching { Debug.getNativeHeapSize() }.getOrDefault(-1L)

        val vm = readSelfStatus()
        val allocator = MemoryAllocTracker.refreshAllocatorStats()

        return Snapshot(
            javaUsedBytes = javaUsed,
            javaCommittedBytes = javaCommitted,
            javaMaxBytes = javaMax,
            memoryClassBytes = memoryClassBytes,
            largeMemoryClassBytes = largeMemoryClassBytes,
            heapLimitBytes = heapLimit,
            nativeAllocatedBytes = nativeAlloc,
            nativeHeapSizeBytes = nativeHeapSize,
            nativePssKb = 0, // 由下方 PSS 分解统一提供（MemoryInfo 没有 nativePss 的公开字段）
            vmRssKb = vm.rssKb,
            vmHwmKb = vm.hwmKb,
            totalPssKb = totalPss,
            dalvikPssKb = dalvikPss,
            graphicsPssKb = graphicsPss,
            eglPssKb = eglPss,
            glPssKb = glPss,
            codePssKb = codePss,
            stackPssKb = stackPss,
            otherPssKb = otherPss,
            totalPrivateDirtyKb = privateDirty,
            swapPssKb = swapPss,
            allocatorUsedBytes = allocator.usedBytes,
            allocatorFreeBytes = allocator.freeBytes,
            allocatorMmapBytes = allocator.mmapBytes,
            sizeClasses = allocator.sizeClasses,
            runtimeStats = runCatching { Debug.getRuntimeStats() }.getOrDefault(emptyMap()),
            memoryClassMb = memoryClassMb,
            isLowRamDevice = isLowRam,
            lastTrimLevel = trimLevel,
            deviceLowMemory = deviceLowMemory,
            deviceAvailMemBytes = deviceAvail,
            deviceTotalMemBytes = deviceTotal,
            deviceThresholdBytes = deviceThreshold,
            timestamp = now,
        ).let { s ->
            // nativePss 的取值有两条路（MemoryInfo 无公开字段 / Debug.getMemoryInfo 有）
            // 用 Debug.getMemoryInfo（公开且便宜）补上它 —— 见 §一.3 第②层。
            val di = Debug.MemoryInfo()
            val nativePss = runCatching { Debug.getMemoryInfo(di); di.nativePss }.getOrDefault(0)
            s.copy(nativePssKb = nativePss)
        }
    }

    private fun memStat(info: Debug.MemoryInfo, key: String): Int =
        runCatching { info.getMemoryStat(key)?.toInt() ?: 0 }.getOrDefault(0)

    /** /proc/self/status 的两行（VmRSS / VmHWM）。**自己读文件，不依赖任何 API** —— 全版本可用。 */
    private data class Vm(val rssKb: Int, val hwmKb: Int)

    private fun readSelfStatus(): Vm = runCatching {
        var rss = 0
        var hwm = 0
        java.io.File("/proc/self/status").readLines().forEach { line ->
            when {
                line.startsWith("VmRSS:") -> rss = parseKb(line)
                line.startsWith("VmHWM:") -> hwm = parseKb(line)
            }
        }
        Vm(rss, hwm)
    }.getOrElse { Vm(0, 0) }

    private fun parseKb(line: String): Int =
        line.substringAfter(':').trim().split(' ').firstOrNull()?.toIntOrNull() ?: 0

    // ─────────────────────────────────────────────
    // 三、trim 级别（"容器感"）
    // ─────────────────────────────────────────────

    /** 进程内最近一次 trim 级别。由 [MemoryMonitor.onTrimMemory] 写入 */
    @Volatile
    private var lastTrimLevel: Int = -1

    internal fun recordTrimLevel(level: Int) {
        lastTrimLevel = level
    }

    private fun lastTrimLevelOf(@Suppress("UNUSED_PARAMETER") context: Context): Int = lastTrimLevel

    /**
     * trim 级别的可读名。
     *
     * ⚠️ 用 `when` 精确匹配而不是阈值比较 —— 见类注释 §一.4 的实证坑。
     *    `TRIM_MEMORY_UI_HIDDEN(20)` 在数值上大于 `TRIM_MEMORY_RUNNING_LOW(10)`，
     *    但它们**不在同一个语义轴上**。
     */
    fun trimName(level: Int): String = when (level) {
        -1 -> "未收到"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> "UI_HIDDEN(20) 退到后台"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> "RUNNING_MODERATE(5) 整机有点紧"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "RUNNING_LOW(10) 很紧"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "RUNNING_CRITICAL(15) ⚠快被杀了"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> "BACKGROUND(40) 已在后台队列"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE -> "MODERATE(60) 后台队列中段"
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> "COMPLETE(80) ⚠队列最前，即将被杀"
        else -> "未知($level)"
    }

    /** 该级别是否属于「内存压力」轴（而不是「可见性」轴）。 */
    fun isPressureLevel(level: Int): Boolean = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> false
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
        ComponentCallbacks2.TRIM_MEMORY_MODERATE,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> true
        else -> false
    }

    // ─────────────────────────────────────────────
    // 四、水位分级
    // ─────────────────────────────────────────────

    enum class Level { NORMAL, HIGH, CRITICAL }

    /**
     * 水位分级。**阈值必须是可远程下发的参数**（生产口径，见 INTERVIEW 文档 §五），
     * 这里给的是能跑的默认值，并说明每个数的来源：
     *
     * | 级别 | 判据 | 为什么是这个数 |
     * |---|---|---|
     * | HIGH | used ≥ 70% 软上限 | ART 的 `HeapGrowthLimit` 策略在接近上限时 GC 频率指数上升，**卡顿先于 OOM 到来** |
     * | CRITICAL | used ≥ 85% 软上限**或** committed ≥ 95% 硬上限 | committed 贴近硬上限 = 下一次大分配没有退路 |
     *
     * ⚠️ **低内存设备要单独降档**：`isLowRamDevice` 的机器上（Go 版、1GB 机型），
     *    70% 就已经非常危险（系统给的后台余量极小）。
     *    实测经验：这类设备上把 HIGH 降到 60%、CRITICAL 降到 75%。
     */
    fun classify(
        s: Snapshot,
        highRatio: Float = 0.70f,
        criticalRatio: Float = 0.85f,
    ): Level {
        val bump = if (s.isLowRamDevice) 0.10f else 0f
        val high = (highRatio - bump).coerceAtLeast(0.30f)
        val critical = (criticalRatio - bump).coerceAtLeast(0.50f)
        val usedI = s.javaUsedRatio
        val committedI = if (s.javaMaxBytes > 0) s.javaCommittedBytes.toFloat() / s.javaMaxBytes else 0f
        return when {
            usedI >= critical || committedI >= 0.95f -> Level.CRITICAL
            usedI >= high -> Level.HIGH
            else -> Level.NORMAL
        }
    }

    // ─────────────────────────────────────────────
    // 五、采样与趋势
    // ─────────────────────────────────────────────

    /**
     * 周期性采样器。
     *
     * **放在主线程 Handler 上跑**（与 `image/MemoryProbe.Sampler` 同一取舍）：
     *   · 采样很轻（几次 syscall + 一次 /proc 读，实测 < 1ms）；
     *   · 结果要立刻刷 UI；
     *   · 更重要的：**如果采样本身掉帧，我们就没法判断"卡顿是不是采样造成的"**，
     *     而内存监控恰恰经常与卡顿问题一起排查。
     *
     * ⚠️ 间隔默认 **2000ms**，不是 1000ms。理由（实测取舍）：
     *    · 内存的问题是**分钟级**的（对比卡顿是毫秒级），1s 采样信息增益很低；
     *    · 环形缓冲是内存有界的，采得越密，同样容量覆盖的时间窗越短。
     *      2s × 256 点 ≈ **8.5 分钟**的窗口，足够看清"涨了有没有回落"。
     *    · 生产全量时还要再降（见 INTERVIEW 文档 §五 的采样率表）。
     */
    class Sampler(
        private val context: Context,
        private val intervalMs: Long = 2000L,
        private val capacity: Int = 256,
        private val onSample: (Snapshot) -> Unit = {},
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private val buffer = ConcurrentLinkedDeque<Snapshot>()

        @Volatile
        private var running = false

        private val sampleCount = AtomicLong(0)

        /** 采样总耗时（纳秒），用于自证「监控没有拖慢主线程」 */
        private val totalCostNanos = AtomicLong(0)

        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val t0 = System.nanoTime()
                runCatching {
                    val s = capture(context)
                    buffer.addLast(s)
                    while (buffer.size > capacity) buffer.pollFirst()
                    sampleCount.incrementAndGet()
                    onSample(s)
                }
                totalCostNanos.addAndGet(System.nanoTime() - t0)
                handler.postDelayed(this, intervalMs)
            }
        }

        fun start() {
            if (running) return
            running = true
            // 立刻给一个基线点，否则趋势窗口前 intervalMs 是空的（看不到起点）
            runCatching { buffer.addLast(capture(context)) }
            handler.postDelayed(tick, intervalMs)
        }

        fun stop() {
            running = false
            handler.removeCallbacks(tick)
        }

        fun isRunning(): Boolean = running

        fun points(): List<Snapshot> = buffer.toList()

        fun latest(): Snapshot? = buffer.peekLast()

        /** 单次采样平均耗时（毫秒）。**这个数字要能在页面上看到** —— 监控的自证成本。 */
        fun avgCostMs(): Double {
            val n = sampleCount.get()
            return if (n <= 0) 0.0 else totalCostNanos.get().toDouble() / n / 1_000_000.0
        }

        fun clear() {
            buffer.clear()
            sampleCount.set(0)
            totalCostNanos.set(0)
        }
    }

    /**
     * 趋势分析：把「涨没涨」拆成可运维的四种判决。
     *
     * ─── 算法（刻意保持"能口算"，不做机器学习那套）───
     *
     * ```
     *   基线 base = 窗口内**最小值**           ← 不是第一个点！
     *   峰值 peak = 窗口内**最大值**
     *   当前 last = 最后一个点
     *
     *   ① last ≤ base + fallbackTol   → STABLE      回来了，没事
     *   ② peak - base ≥ riseBytes     → 涨过（再分两种）
     *        └ last ≤ base + fallbackTol → GROW_AND_FALL 涨了会回落 = 缓存，正常
     *        └ 否则                      → LEAK_SUSPECT  涨了不回落 ⚠️
     *   ③ 否则                           → STABLE
     * ```
     *
     * 为什么基线取**最小值**而不是第一个点：采样起点随机落在"刚进页面"还是
     * "页面稳定后"，取第一个点会让判决依赖运气。最小值是**该窗口内的实证下界**，
     * 更接近"这个 App 的最小工作集（min working set）"这个物理含义。
     *
     * ⚠️ 为什么不用线性回归的斜率做判据：内存曲线**天生不单调**（GC sawtooth）。
     *    对锯齿曲线做回归，得到的斜率几乎只反映采样窗口长度，而不是泄漏速率。
     *    要估泄漏速率，正确做法是**只取每次 GC 之后的谷值**再回归
     *    —— 本类提供了 [gcValleys] 的判据（依据 `art.gc.gc-count` 变化），
     *    但**默认不开启**：它需要足够长的窗口，短窗口上会比最小值法更不稳。
     */
    object Trend {

        const val FALLBACK_TOLERANCE_BYTES = 4L * 1024 * 1024   // 4 MB：低于此视为"回落到位"
        const val RISE_THRESHOLD_BYTES = 16L * 1024 * 1024      // 16 MB：低于此视为"正常波动"

        enum class Verdict {
            /** 窗口内没有显著变化 */
            STABLE,

            /** 涨过又回落 → 典型的缓存/图片行为，**正常** */
            GROW_AND_FALL,

            /** 涨上去回不来 → 「值得看一眼」，**不是**泄漏的证明 */
            LEAK_SUSPECT,
        }

        data class Result(
            val verdict: Verdict,
            val baseBytes: Long,
            val peakBytes: Long,
            val lastBytes: Long,
            val riseBytes: Long,
            val fallbackBytes: Long,
            val sampleCount: Int,
            val windowMs: Long,
            /**
             * 「涨了不回落」是否在 native 侧也同步发生。
             * ⚠️ 这是**双条件判据**（见 MemoryMetrics 类注释 §一.3）：
             *    Java 涨 + native 不涨 → 大概率是位图/资源（走 graphics 段）
             *    Java 涨 + native 也涨 → 优先怀疑 native 泄漏（自研 .so / JNI 局部引用）
             */
            val nativeSyncedRise: Boolean,
        ) {
            fun describe(): String = when (verdict) {
                Verdict.STABLE ->
                    "STABLE：窗口 ${windowMs / 1000}s / $sampleCount 点，波动 ${fmt(riseBytes)}，无异常"
                Verdict.GROW_AND_FALL ->
                    "GROW_AND_FALL：涨 ${fmt(riseBytes)} → 回落 ${fmt(fallbackBytes)}（缓存行为，正常）"
                Verdict.LEAK_SUSPECT ->
                    "⚠️ LEAK_SUSPECT：基线 ${fmt(baseBytes)} → 峰值 ${fmt(peakBytes)} " +
                            "→ 现价 ${fmt(lastBytes)}，涨了 ${fmt(riseBytes)} 且**未回落**" +
                            if (nativeSyncedRise) "（native 同步上涨 → 优先怀疑 native 泄漏）"
                            else "（native 未同步 → 更像 Java 堆/位图，去看直方图）"
            }
        }

        fun analyze(
            points: List<Snapshot>,
            riseThreshold: Long = RISE_THRESHOLD_BYTES,
            fallbackTolerance: Long = FALLBACK_TOLERANCE_BYTES,
        ): Result? {
            if (points.size < 3) return null
            val used = points.map { it.javaUsedBytes }
            val base = used.min()
            val peak = used.max()
            val last = used.last()
            val rise = peak - base
            val fallback = peak - last
            val nativeBase = points.minOf { it.nativeAllocatedBytes }
            val nativeLast = points.last().nativeAllocatedBytes
            val nativeRise = nativeLast - nativeBase
            // ⚠️ "同步上涨"必须按**比例**判，不能只判 ">0"：
            //    Java 涨 80MB 而 native 只涨了几百 KB（正常的对象头/缓冲抖动）时，
            //    说它"native 同步上涨"会把排查方向**指向错误的半边**。
            //    取 50% 作为门槛：native 的涨幅达到 Java 涨幅一半以上，才值得去看 native。
            val nativeSynced = nativeRise > 0 && nativeRise >= rise / 2

            val verdict = when {
                last - base <= fallbackTolerance -> Verdict.STABLE
                rise < riseThreshold -> Verdict.STABLE
                fallback <= fallbackTolerance -> Verdict.LEAK_SUSPECT
                else -> Verdict.GROW_AND_FALL
            }
            return Result(
                verdict = verdict,
                baseBytes = base,
                peakBytes = peak,
                lastBytes = last,
                riseBytes = rise,
                fallbackBytes = fallback,
                sampleCount = points.size,
                windowMs = points.last().timestamp - points.first().timestamp,
                nativeSyncedRise = nativeSynced,
            )
        }

        /**
         * GC 谷值提取：把「GC 之后的最低点」串成一条包络线，再估泄漏速率。
         *
         * 依据不是猜的：`art.gc.gc-count` 是 ART 自己上报的 GC 次数
         * （`Debug.getRuntimeStats()` 的稳定键之一）。计数增加 = 该点之后发生过 GC。
         *
         * ⚠️ 诚实边界：**计数增加不代表这个点就是谷值**（GC 可能发生在采样间隔中间），
         *    所以这只是**近似包络**，用于看趋势方向，不能用于精确泄漏速率。
         */
        fun gcValleys(points: List<Snapshot>): List<Snapshot> {
            if (points.size < 2) return points
            val out = ArrayList<Snapshot>()
            var prevCount = gcCount(points.first())
            for (i in 1 until points.size) {
                val c = gcCount(points[i])
                if (c > prevCount) out.add(points[i])
                prevCount = c
            }
            return out
        }

        private fun gcCount(s: Snapshot): Long =
            s.runtimeStats["art.gc.gc-count"]?.toLongOrNull() ?: -1L
    }

    // ─────────────────────────────────────────────
    // 五之二、Native 独立判决
    // ─────────────────────────────────────────────

    /**
     * **Native 侧的趋势判决**（与 [Trend] 平级的第二条判决线）。
     *
     * ─── 为什么 native 必须有**自己的**判决，而不是搭 Java 的便车 ───
     *
     * 之前的实现里，`Trend.analyze` 的 `base/peak/last` **全部取自 `javaUsedBytes`**，
     * native 只在旁边报一个布尔标志 [Trend.Result.nativeSyncedRise]。
     * 后果是一个真实的漏判：
     *
     * > **Java 堆平稳、native 单边猛涨**的那种泄漏，会显示成 `STABLE`。
     *
     * 而这类泄漏在线上并不罕见（自研 .so、JNI 局部引用、位图、三方 SDK 的缓存池），
     * 恰恰是 Java 直方图**看不见**的那一半（hprof 只遍历托管堆）。
     * 所以 native 需要一个不依赖 Java 堆的判据。
     *
     * ─── 与 Java 判决的**三个刻意差异** ───
     *
     * 1. **四个信号各自判，再组合诊断**（Java 只有一个 `javaUsedBytes`）。
     *    四个信号回答的是不同问题，必须分开看：
     *      · [Signal.ALLOCATED] 分配器净账  → **我们自己的 malloc 有没有泄漏**
     *      · [Signal.HEAP_SIZE] 已映射总量  → 净账不涨而它在涨 = **碎片**（不是泄漏）
     *      · [Signal.VM_RSS]    进程驻留    → 含 mmap 大块、code、线程栈、图形
     *      · [Signal.GRAPHICS]  图形 PSS    → 位图/纹理这条线
     *    实测依据：一个 8 MB 的 malloc 在 API 26+ 可能走 **mmap**，
     *    于是 ALLOCATED 把它排除、VM_RSS 算进去（见类注释 §一.3）——
     *    **只看一个数一定会指错方向**。
     *
     * 2. **阈值更小**：native 的大块常走 mmap 而不进 `allocated`，
     *    单个信号的绝对幅度天然比 Java 堆小；沿用 Java 的 16 MB 会漏判。
     *    这里取 8 MB，**它是启发式的经验值，生产口径要求可远程下发**（同水位阈值）。
     *
     * 3. **`GROW_AND_FALL` 在 native 上要更可疑**（判据本身同构，但解读不同）：
     *    Java 有 GC 这个**强制的**回收机制，涨了回落是常态；
     *    native **没有等价的强制回收**（只能靠显式 free 或进程退出），
     *    所以 native 一旦"涨上去"，回落通常意味着确实有人放了——
     *    这反而是**好**信号。真正危险的是**不回落**的那种。
     *
     * ⚠️ **诚实边界**（与 [Trend] 完全一致，别把它当泄漏证明）：
     *    · `SUSPECT` 只给"值得看一眼"，**不是**泄漏的结论；
     *    · native 没有 GC，所以本类**提供不了** [Trend.gcValleys] 那样的谷值包络
     *      —— 要估 native 泄漏速率只能靠"多窗口确认 + 归因"（装探针看 per-site）。
     *    · 采样间隔（默认 2s）会漏掉两次采样之间的分配-释放对，看的是**净效果**。
     */
    object NativeTrend {

        /** native 单信号上涨阈值：8 MB。低于此视为正常抖动（可调，生产应远程下发）。 */
        const val RISE_THRESHOLD_BYTES = 8L * 1024 * 1024

        /** 回落容差：2 MB。低于此视为"回落到低点了"。 */
        const val FALLBACK_TOLERANCE_BYTES = 2L * 1024 * 1024

        enum class Verdict {
            /** 窗口内没有显著变化 */
            STABLE,

            /** 涨过又回落 → 在 native 上通常是"有人显式放了"，偏正常 */
            GROW_AND_FALL,

            /** 涨上去回不来 → 「值得看一眼」，**不是**泄漏的证明 */
            SUSPECT,

            /** 该信号在本次窗口内**根本读不到**（全 0 / 全 -1）—— 必须与 STABLE 区分 */
            UNKNOWN,
        }

        /** 四个独立信号。`label` 是给人看的说明，直接进报告。 */
        enum class Signal(val label: String) {
            ALLOCATED("分配器净账 malloc/free"),
            HEAP_SIZE("分配器已映射（含 free 缓存/碎片）"),
            VM_RSS("进程驻留 VmRSS（含 mmap/code/图形）"),
            GRAPHICS("图形 summary.graphics"),
        }

        data class SignalResult(
            val signal: Signal,
            val verdict: Verdict,
            val baseBytes: Long,
            val peakBytes: Long,
            val lastBytes: Long,
            val riseBytes: Long,
            val fallbackBytes: Long,
            val sampleCount: Int,
        ) {
            fun describe(): String = when (verdict) {
                Verdict.UNKNOWN -> "${signal.label}：本窗口读不到（UNKNOWN）"
                Verdict.STABLE -> "${signal.label}：STABLE（波动 ${fmt(riseBytes)}）"
                Verdict.GROW_AND_FALL -> "${signal.label}：涨 ${fmt(riseBytes)} → 回落 ${fmt(fallbackBytes)}"
                Verdict.SUSPECT -> "${signal.label}：⚠️ 涨 ${fmt(riseBytes)} 且未回落" +
                        "（${fmt(baseBytes)} → ${fmt(lastBytes)}）"
            }
        }

        data class Result(
            /** 四个信号里**取最严重**的那个作为总判决 —— native 无上限，所以"最严重"是并集不是加权 */
            val verdict: Verdict,
            val signals: List<SignalResult>,
            /** 组合诊断：由"哪几个信号在涨"推出最可能的归因方向 */
            val cause: String,
            val windowMs: Long,
            val sampleCount: Int,
        ) {
            fun signal(s: Signal): SignalResult? = signals.firstOrNull { it.signal == s }

            fun describe(): String = buildString {
                appendLine("═══ Native 趋势判决 ═══")
                appendLine("总判决：$verdict" +
                        "（窗口 ${windowMs / 1000}s / $sampleCount 点）")
                signals.forEach { appendLine("  · " + it.describe()) }
                appendLine("  ⇒ 归因方向：$cause")
            }
        }

        /**
         * 对窗口内的点做四个信号的独立判决，再组合诊断。
         *
         * @return 不足 3 点时返回 null（与 [Trend.analyze] 同一纪律：样本不够不下结论）
         */
        fun analyze(
            points: List<Snapshot>,
            riseThreshold: Long = RISE_THRESHOLD_BYTES,
            fallbackTolerance: Long = FALLBACK_TOLERANCE_BYTES,
        ): Result? {
            if (points.size < 3) return null

            // 每个信号：取值列 → 判判决。取值列里若有非法值（-1）必须整体视为不可用，
            // 而不是把它当 0 混进去（那会造出一条假的"暴涨/暴降"）。
            val results = listOf(
                judge(Signal.ALLOCATED, points.map { it.nativeAllocatedBytes }, riseThreshold, fallbackTolerance),
                judge(Signal.HEAP_SIZE, points.map { it.nativeHeapSizeBytes }, riseThreshold, fallbackTolerance),
                judge(Signal.VM_RSS, points.map { it.vmRssKb.toLong() * 1024 }, riseThreshold, fallbackTolerance),
                judge(Signal.GRAPHICS, points.map { it.graphicsPssKb.toLong() * 1024 }, riseThreshold, fallbackTolerance),
            )

            // 总判决取最严重：UNKNOWN < STABLE < GROW_AND_FALL < SUSPECT
            val verdict = results.maxByOrNull { severity(it.verdict) }?.verdict ?: Verdict.UNKNOWN

            return Result(
                verdict = verdict,
                signals = results,
                cause = diagnose(results),
                windowMs = points.last().timestamp - points.first().timestamp,
                sampleCount = points.size,
            )
        }

        private fun severity(v: Verdict): Int = when (v) {
            Verdict.UNKNOWN -> 0
            Verdict.STABLE -> 1
            Verdict.GROW_AND_FALL -> 2
            Verdict.SUSPECT -> 3
        }

        /**
         * 单信号判决。判据与 [Trend] 同构（最小值基线 + 回落容差），
         * 这样两条线的结论**可以互相引用而不会互相矛盾**。
         */
        private fun judge(
            signal: Signal,
            series: List<Long>,
            riseThreshold: Long,
            fallbackTolerance: Long,
        ): SignalResult {
            // 数据有效性：全非法 → UNKNOWN（必须是 UNKNOWN 而不是 STABLE，
            // 两者在报告里的含义天差地别 —— "没涨" vs "没读到"）
            val valid = series.all { it > 0 }
            if (!valid) {
                return SignalResult(signal, Verdict.UNKNOWN, 0, 0, 0, 0, 0, series.size)
            }
            val base = series.min()
            val peak = series.max()
            val last = series.last()
            val rise = peak - base
            val fallback = peak - last

            val verdict = when {
                last - base <= fallbackTolerance -> Verdict.STABLE
                rise < riseThreshold -> Verdict.STABLE
                fallback <= fallbackTolerance -> Verdict.SUSPECT
                else -> Verdict.GROW_AND_FALL
            }
            return SignalResult(signal, verdict, base, peak, last, rise, fallback, series.size)
        }

        /**
         * 组合诊断 —— **这才是"四个信号分开判"的收益所在**。
         *
         * 单个信号只能说"涨了"，哪几个一起涨才指向具体原因。
         */
        private fun diagnose(rs: List<SignalResult>): String {
            val alloc = rs.first { it.signal == Signal.ALLOCATED }
            val heap = rs.first { it.signal == Signal.HEAP_SIZE }
            val rss = rs.first { it.signal == Signal.VM_RSS }
            val gfx = rs.first { it.signal == Signal.GRAPHICS }

            fun rising(v: Verdict) = v == Verdict.SUSPECT

            return when {
                // 四个都不可用：配置/ROM 问题，不是内存问题
                rs.all { it.verdict == Verdict.UNKNOWN } ->
                    "四个信号都读不到 —— 先查监控本身（ROM 差异 / 读权限），别急着查内存"

                // 净账与 RSS 一起涨：最典型的 malloc 侧泄漏
                rising(alloc.verdict) && rising(rss.verdict) ->
                    "分配器净账与 RSS 同步上涨 ⇒ **优先怀疑我们自己的 native 分配**。" +
                            "下一步：装 memtrace 探针 → 归因报告 → native-mem-symbolize.sh 定到行" +
                            "（探针会改变被测对象，装/不装不能直接比）"

                // 净账不涨、RSS 涨、图形也涨：图形线
                !rising(alloc.verdict) && rising(rss.verdict) && rising(gfx.verdict) ->
                    "净账不动而图形与 RSS 同步涨 ⇒ **图形内存**（位图/纹理）。" +
                            "下一步：BitmapTracker 按来源/Config 看，再对比 summary.graphics 的差额；" +
                            "差额大就下钻 smaps 的 /dev/kgsl* 与 gralloc"

                // 净账不涨、RSS 涨、图形不涨：mmap 大块 / 线程栈 / code
                !rising(alloc.verdict) && rising(rss.verdict) && !rising(gfx.verdict) ->
                    "净账不动但 RSS 在涨 ⇒ 分配器**看不见**的那部分：" +
                            "mmap 大块 / 线程栈 / code / 文件映射。" +
                            "下一步：SmapsReader 看逐条映射（重点 /dev/kgsl*、[stack]、大块 anon）"

                // 净账不涨但"已映射"在涨：碎片，不是泄漏
                !rising(alloc.verdict) && rising(heap.verdict) ->
                    "净账不涨但**已映射总量**在涨 ⇒ 更像**碎片/free 缓存**，不是存活泄漏。" +
                            "查 scudo 尺寸档与浪费率（allocatorWasteRatio），必要时触发一次显式回收观察是否回落"

                // 只有净账涨
                rising(alloc.verdict) ->
                    "只有分配器净账在涨 ⇒ native 侧确有存活分配增长；" +
                            "但 RSS 未同步，可能是小对象为主。同样走 memtrace 归因"

                else -> "四个信号都未见显著上涨（或只是涨了会回落）—— 当前窗口 native 侧无明确异常"
            }
        }
    }

    // ─────────────────────────────────────────────
    // 六、格式化
    // ─────────────────────────────────────────────

    /** 统一用 MB、两位小数 —— 与 `image/MemoryProbe.format` 的约定保持一致。 */
    fun fmt(bytes: Long): String = "%.2f MB".format(bytes.toDouble() / 1024 / 1024)

    fun fmtKb(kb: Int): String = fmt(kb.toLong() * 1024)

    fun fmtRatio(r: Float): String = "%.1f%%".format(r * 100)

    /** 进程内存总览（实验页与 logcat 用）。 */
    fun describe(s: Snapshot): String = buildString {
        appendLine("═══ 内存快照 ${s.timestamp} ═══")
        appendLine("【JVM 堆】")
        appendLine("  used      ${fmt(s.javaUsedBytes)}  (${fmtRatio(s.javaUsedRatio)} of 软上限)")
        appendLine("  committed ${fmt(s.javaCommittedBytes)}  (${fmtRatio(s.javaCommittedRatio)} of 硬上限)")
        appendLine("  软上限/硬上限 ${fmt(s.heapLimitBytes)} / ${fmt(s.javaMaxBytes)}" +
                "   memoryClass=${s.memoryClassMb}MB large=${s.largeMemoryClassBytes / 1024 / 1024}MB")
        appendLine("  headroom  ${fmt(s.javaHeadroomBytes)}（离硬上限的绝对余量）")
        if (s.runtimeStats.isNotEmpty()) {
            appendLine("  GC/ART：")
            s.runtimeStats.entries
                .filter { it.key.contains("gc") || it.key.contains("alloc") }
                .sortedBy { it.key }
                .take(10)
                .forEach { appendLine("    ${it.key} = ${it.value}") }
        }
        appendLine()
        appendLine("【Native】")
        appendLine("  malloc 净账     ${fmt(s.nativeAllocatedBytes)}")
        appendLine("  分配器已映射    ${fmt(s.nativeHeapSizeBytes)}  (浪费率 ${fmtRatio(s.allocatorWasteRatio)})")
        appendLine("  nativePss       ${fmtKb(s.nativePssKb)}")
        appendLine("  VmRSS           ${fmtKb(s.vmRssKb)}   VmHWM(峰值) ${fmtKb(s.vmHwmKb)}")
        appendLine("  分配器内部      used=${fmt(s.allocatorUsedBytes)} free=${fmt(s.allocatorFreeBytes)}" +
                " mmap=${fmt(s.allocatorMmapBytes)}")
        if (s.sizeClasses.isNotEmpty()) {
            appendLine("  scudo 尺寸档 Top${s.sizeClasses.size}（sizeB × 存活数）：")
            s.sizeClasses.forEach { (sz, n) ->
                appendLine("    ${sz.toString().padStart(9)} B × $n = ${fmt(sz.toLong() * n)}")
            }
        }
        appendLine()
        appendLine("【PSS 分解（系统的归类）】")
        appendLine("  total=${fmtKb(s.totalPssKb)} privateDirty=${fmtKb(s.totalPrivateDirtyKb)}" +
                " swapPss=${fmtKb(s.swapPssKb)}")
        appendLine("  dalvik=${fmtKb(s.dalvikPssKb)} native=${fmtKb(s.nativePssKb)}" +
                " graphics=${fmtKb(s.graphicsPssKb)} egl=${fmtKb(s.eglPssKb)} gl=${fmtKb(s.glPssKb)}")
        appendLine("  code=${fmtKb(s.codePssKb)} stack=${fmtKb(s.stackPssKb)} other=${fmtKb(s.otherPssKb)}")
        appendLine("  ⚠️ 读到 graphics=${if (s.graphicsPssKb == 0) "0（本机无 GPU 驱动统计或 API 26 以下）" else fmtKb(s.graphicsPssKb)}")
        appendLine()
        appendLine("【设备与容器】")
        appendLine("  低内存设备=${s.isLowRamDevice}  系统低内存=${s.deviceLowMemory}")
        appendLine("  整机可用 ${fmt(s.deviceAvailMemBytes)} / 总 ${fmt(s.deviceTotalMemBytes)}" +
                "  阈值 ${fmt(s.deviceThresholdBytes)}")
        appendLine("  最近 trim：${trimName(s.lastTrimLevel)}")
        appendLine("  ⚠️ 类型：egl/gl 是 ROM 相关项（无 GPU 驱动统计时缺项）；" +
                "swapPss 在 API 30 以下恒为 0")
    }

    /** logcat 单行（`adb logcat -s MemoryMonitor` 可读）。 */
    fun oneLine(s: Snapshot): String =
        "java=${fmt(s.javaUsedBytes)}/${fmtRatio(s.javaUsedRatio)}" +
                " nativeAlloc=${fmt(s.nativeAllocatedBytes)}" +
                " rss=${fmtKb(s.vmRssKb)} pss=${fmtKb(s.totalPssKb)}" +
                " trim=${s.lastTrimLevel}"

    /** 反序列化辅助：趋势分析要读落盘的历史快照时用（见 MemoryJournal）。 */
    fun runtimeStatOf(s: Snapshot, key: String): Long =
        s.runtimeStats[key]?.toLongOrNull() ?: 0L

    /** 供实验页展示"哪些 runtime stat 键在本机真的可读"（跨 ROM 差异的一手证据）。 */
    fun runtimeStatKeys(s: Snapshot): List<String> = s.runtimeStats.keys.sorted()

    /** 提醒：本类不注册任何回调 —— 注册由 [MemoryMonitor] 统一做（生命周期集中）。 */
    internal fun apiNote(): String =
        if (Build.VERSION.SDK_INT >= 30) "API 30+：swapPss 与 ExitInfo 的 low-memory 原因可用"
        else "API < 30：swapPss 恒 0，低内存回收只能靠 trim 级别推断"
}
