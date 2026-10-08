package com.interview.内存

import com.interview.内存.MemoryAllocTracker.AllocatorStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Time: 2026/10/8
 * Author: wgt
 * Description: 内存监控**纯逻辑**部分的单测（能在宿主 JVM 上跑，无需设备）
 *
 * ─── 为什么这几个点值得单测（而不是"跑一下看看"）───
 *
 * 1. **allocatorStats 的解析**是"监控自己可能静默给错值"的地方：
 *    它每次采样都跑，一旦解析漂移，页面上的数字就会一直错而没有任何报错。
 *    so 这里**既覆盖正确输入，也覆盖畸形输入**（畸形的必须退化成"不可用"，
 *    而不是给出一个像模像样的错数）。
 * 2. **trim 级别的语义分类**（`isPressureLevel`）是最容易写错的一处：
 *    `UI_HIDDEN(20)` 的数值**大于** `RUNNING_LOW(10)`，用阈值比较必然误判。
 *    单测把这条"数值与语义不同轴"的事实钉住 —— 以后有人改成 `level > 10`，
 *    这里会立刻红。
 * 3. **水位分级的低内存降档**：这是"只在低端机上出错"的那类逻辑，
 *    没有单测就只能靠换设备才能发现。
 * 4. **数组类名归一化**（`[B` → `byte[]`）：纯函数、易错、且影响直方图可读性。
 * 5. **直方图差分**：它是"谁涨了"的唯一答案，方向搞反（涨看成跌）会很误导。
 */
class MemoryMetricsLogicTest {

    // ─────────────────────────────────────────────
    // 一、allocatorStats 解析
    // ─────────────────────────────────────────────

    @Test
    fun `allocatorStats 正常输入应逐字段解析`() {
        val raw = "used=8846640 free=559040 mmap=11960320 arena=0 ordblks=42|sc:4096:2,65536:1,8192:5"
        val s = MemoryAllocTracker.parseAllocatorStats(raw)
        assertTrue("应当可用", s.available)
        assertEquals(8846640L, s.usedBytes)
        assertEquals(559040L, s.freeBytes)
        assertEquals(11960320L, s.mmapBytes)
        assertEquals(0L, s.arenaBytes)
        assertEquals(42L, s.freeChunks)
        assertEquals(listOf(4096 to 2, 65536 to 1, 8192 to 5), s.sizeClasses)
    }

    @Test
    fun `allocatorStats 无尺寸档时应给出空列表而不是失败`() {
        val s = MemoryAllocTracker.parseAllocatorStats("used=100 free=200 mmap=300 arena=0 ordblks=1|sc:")
        assertTrue(s.available)
        assertTrue("sizeClasses 应为空", s.sizeClasses.isEmpty())
    }

    @Test
    fun `allocatorStats 畸形输入必须退化为不可用而不是给出错值`() {
        // 这是最要紧的一条：宁可页面上写"不可用"，也不能显示一个看起来正常的错数。
        listOf(
            "",
            "garbage",
            "used=abc free=def",
            "|sc:not-a-size:3",
        ).forEach { bad ->
            val s = MemoryAllocTracker.parseAllocatorStats(bad)
            assertFalse("畸形输入 '$bad' 不应被判为可用", s.available)
            assertEquals(-1L, s.usedBytes)
        }
    }

    @Test
    fun `allocatorStats 尺寸档里的非法项应被丢弃而不是污染整条`() {
        // 一个坏的尺寸档不能让整份读数作废（其余字段仍然有效）。
        val s = MemoryAllocTracker.parseAllocatorStats(
            "used=1 free=2 mmap=3 arena=4 ordblks=5|sc:4096:2,broken,8192:0,-8:3,16384:1"
        )
        assertTrue(s.available)
        assertEquals(listOf(4096 to 2, 16384 to 1), s.sizeClasses)
    }

    // ─────────────────────────────────────────────
    // 二、trim 级别的"两个语义轴"（最容易写错的一处）
    // ─────────────────────────────────────────────

    @Test
    fun `UI_HIDDEN 的数值大于 RUNNING_LOW 但语义上不是内存压力`() {
        // 这条断言一旦变红，说明有人把判定改成了阈值比较 —— 那会在低端机上刷假告警。
        val uiHidden = android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        val runningLow = android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        assertTrue("前提：UI_HIDDEN(=$uiHidden) 的数值确实大于 RUNNING_LOW(=$runningLow)",
            uiHidden > runningLow)
        assertFalse("UI_HIDDEN 不是内存压力", MemoryMetrics.isPressureLevel(uiHidden))
        assertTrue("RUNNING_LOW 是内存压力", MemoryMetrics.isPressureLevel(runningLow))
    }

    @Test
    fun `所有 RUNNING_ 与后台级别都应判为压力轴`() {
        val expect = listOf(
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        )
        expect.forEach { assertTrue("level=$it 应为压力轴", MemoryMetrics.isPressureLevel(it)) }
        assertFalse(MemoryMetrics.isPressureLevel(-1))
        assertFalse(MemoryMetrics.isPressureLevel(0))
    }

    @Test
    fun `trimName 对未知级别要如实报出数值而不是编个名字`() {
        assertEquals("未知(9999)", MemoryMetrics.trimName(9999))
        assertEquals("未收到", MemoryMetrics.trimName(-1))
    }

    // ─────────────────────────────────────────────
    // 三、水位分级：软上限、硬上限、低内存降档
    // ─────────────────────────────────────────────

    private fun snap(
        used: Long,
        limit: Long,
        max: Long = limit,
        lowRam: Boolean = false,
    ) = MemoryMetrics.Snapshot(
        javaUsedBytes = used,
        javaCommittedBytes = used,
        javaMaxBytes = max,
        memoryClassBytes = limit,
        largeMemoryClassBytes = limit * 2,
        heapLimitBytes = limit,
        nativeAllocatedBytes = 0,
        nativeHeapSizeBytes = 0,
        nativePssKb = 0,
        vmRssKb = 0,
        vmHwmKb = 0,
        totalPssKb = 0, dalvikPssKb = 0, graphicsPssKb = 0, eglPssKb = 0, glPssKb = 0,
        codePssKb = 0, stackPssKb = 0, otherPssKb = 0, totalPrivateDirtyKb = 0, swapPssKb = 0,
        allocatorUsedBytes = 0, allocatorFreeBytes = 0, allocatorMmapBytes = 0,
        sizeClasses = emptyList(), runtimeStats = emptyMap(),
        memoryClassMb = (limit / 1024 / 1024).toInt(), isLowRamDevice = lowRam,
        lastTrimLevel = -1, deviceLowMemory = false,
        deviceAvailMemBytes = 0, deviceTotalMemBytes = 0, deviceThresholdBytes = 0,
        timestamp = 0L,
    )

    @Test
    fun `水位分级在普通设备上的两条线`() {
        val limit = 256L * 1024 * 1024
        assertEquals(MemoryMetrics.Level.NORMAL, MemoryMetrics.classify(snap(limit * 50 / 100, limit)))
        assertEquals(MemoryMetrics.Level.HIGH, MemoryMetrics.classify(snap(limit * 75 / 100, limit)))
        assertEquals(MemoryMetrics.Level.CRITICAL, MemoryMetrics.classify(snap(limit * 90 / 100, limit)))
    }

    @Test
    fun `低内存设备必须降档`() {
        val limit = 256L * 1024 * 1024
        val used62 = limit * 62 / 100
        assertEquals("普通设备上 62% 还是 NORMAL",
            MemoryMetrics.Level.NORMAL, MemoryMetrics.classify(snap(used62, limit, lowRam = false)))
        assertEquals("低内存设备上 62% 已经是 HIGH（阈值降 10%）",
            MemoryMetrics.Level.HIGH, MemoryMetrics.classify(snap(used62, limit, lowRam = true)))
    }

    @Test
    fun `committed 贴到硬上限时即使 used 很低也要判 CRITICAL`() {
        // used 低但 committed 满 = "下一次大分配没有退路"，这是 OOM 的前兆。
        val limit = 256L * 1024 * 1024
        val s = snap(used = limit * 10 / 100, limit = limit, max = limit).copy(
            javaCommittedBytes = (limit * 97) / 100
        )
        assertEquals(MemoryMetrics.Level.CRITICAL, MemoryMetrics.classify(s))
    }

    @Test
    fun `水位分母取 min(硬上限, 厂商软上限)`() {
        // 若只拿 maxMemory 当分母，会显示"才用了一半"而实际已贴到软线。
        val used = 140L * 1024 * 1024
        val soft = 256L * 1024 * 1024          // memoryClass 256MB
        val hard = 512L * 1024 * 1024          // largeHeap 拿到 512MB
        val s = snap(used = used, limit = soft, max = hard)
        assertEquals("分母应是软上限", soft, s.heapLimitBytes)
        assertEquals(MemoryMetrics.Level.NORMAL, MemoryMetrics.classify(s)) // 140/256 = 54.7%
        // 若误用硬上限做分母：140/512 = 27% —— 更看不出问题，所以下面这条是"反面确认"：
        assertTrue(s.javaUsedRatio > 0.5f)
    }

    // ─────────────────────────────────────────────
    // 四、趋势判决
    // ─────────────────────────────────────────────

    /**
     * 造一条序列。
     *
     * ⚠️ `nativeMb` 是**末点的值**，native 从 0 线性爬到它 —— 不是"每点都等于它"。
     *    写成恒定值会让 `nativeRise` 恒为 0，于是所有"native 是否同步上涨"的用例
     *    都变成假绿（我第一版就是这么写的，靠断言失败才发现）。
     */
    private fun series(vararg usedMb: Int, nativeMb: Int = 0): List<MemoryMetrics.Snapshot> {
        val limit = 256L * 1024 * 1024
        val n = usedMb.size
        return usedMb.mapIndexed { i, mb ->
            val nativeRamped =
                if (n <= 1) nativeMb.toLong() else nativeMb.toLong() * i / (n - 1)
            snap(mb.toLong() * 1024 * 1024, limit).copy(
                timestamp = i * 2000L,
                nativeAllocatedBytes = nativeRamped * 1024 * 1024,
            )
        }
    }

    @Test
    fun `样本不足三点时不下结论`() {
        assertEquals(null, MemoryMetrics.Trend.analyze(series(10, 20)))
    }

    @Test
    fun `平缓序列应判 STABLE`() {
        val r = MemoryMetrics.Trend.analyze(series(50, 51, 50, 51, 50))!!
        assertEquals(MemoryMetrics.Trend.Verdict.STABLE, r.verdict)
    }

    @Test
    fun `涨了会回落应判 GROW_AND_FALL（正常缓存行为）`() {
        val r = MemoryMetrics.Trend.analyze(series(40, 80, 120, 90, 45))!!
        assertEquals(MemoryMetrics.Trend.Verdict.GROW_AND_FALL, r.verdict)
        assertEquals(45L * 1024 * 1024, r.lastBytes)
        assertEquals(40L * 1024 * 1024, r.baseBytes)
        assertEquals(80L * 1024 * 1024, r.riseBytes)
    }

    @Test
    fun `涨了不回落应判 LEAK_SUSPECT`() {
        val r = MemoryMetrics.Trend.analyze(series(40, 60, 80, 100, 120))!!
        assertEquals(MemoryMetrics.Trend.Verdict.LEAK_SUSPECT, r.verdict)
    }

    @Test
    fun `涨不到阈值时不应触发 LEAK_SUSPECT（避免正常波动被报成泄漏）`() {
        // 涨 8MB < 16MB 阈值
        val r = MemoryMetrics.Trend.analyze(series(40, 44, 48, 44, 46))!!
        assertEquals(MemoryMetrics.Trend.Verdict.STABLE, r.verdict)
    }

    @Test
    fun `基线取窗口内最小值而不是第一个点`() {
        // 首点偏高（采样起点落在"刚做完事"的时刻），若用首点做基线会漏判
        val r = MemoryMetrics.Trend.analyze(series(90, 40, 60, 100))!!
        assertEquals("基线应是 40MB（窗口最小值）", 40L * 1024 * 1024, r.baseBytes)
    }

    @Test
    fun `native 同步上涨时要在判决里标记出来（按比例，不是仅仅大于 0）`() {
        // Java 涨 80MB；native 也涨 80MB ⇒ 明显同步
        val synced = MemoryMetrics.Trend.analyze(series(40, 60, 80, 100, 120, nativeMb = 80))!!
        assertTrue("native 涨幅达到 Java 涨幅一半以上 ⇒ 应标记", synced.nativeSyncedRise)

        // native 不动 ⇒ 不该标记
        val javaOnly = MemoryMetrics.Trend.analyze(series(40, 60, 80, 100, 120, nativeMb = 0))!!
        assertFalse("native 不动时不应标记", javaOnly.nativeSyncedRise)
    }

    @Test
    fun `native 只涨一点点时不应被误判为同步上涨`() {
        // ⚠️ 这是这条判据的关键边界：Java 涨 80MB、native 只涨 4MB（正常抖动）时，
        //    若按 ">0" 判定就会说"native 同步涨"，把排查方向指向错误的半边。
        val r = MemoryMetrics.Trend.analyze(series(40, 60, 80, 100, 120, nativeMb = 4))!!
        assertEquals(MemoryMetrics.Trend.Verdict.LEAK_SUSPECT, r.verdict)
        assertFalse("native 涨幅远小于 Java ⇒ 不应标记为同步", r.nativeSyncedRise)
    }

    // ─────────────────────────────────────────────
    // 五、数组类名归一化（直方图可读性）
    // ─────────────────────────────────────────────

    @Test
    fun `hprof 数组类名应归一化成可读形式`() {
        assertEquals("byte[]", MemoryHeapAnalyzer.arrayName("[B"))
        assertEquals("int[]", MemoryHeapAnalyzer.arrayName("[I"))
        assertEquals("long[]", MemoryHeapAnalyzer.arrayName("[J"))
        assertEquals("java.lang.String[]", MemoryHeapAnalyzer.arrayName("[Ljava/lang/String;"))
        assertEquals("<array>", MemoryHeapAnalyzer.arrayName(null))
        assertEquals("<array>", MemoryHeapAnalyzer.arrayName(""))
        assertEquals("[[I", MemoryHeapAnalyzer.arrayName("[[I")) // 多维不强行展开（如实保留）
    }

    // ─────────────────────────────────────────────
    // 六、直方图差分（"谁涨了"的方向不能反）
    // ─────────────────────────────────────────────

    private fun hist(vararg pairs: Pair<String, Pair<Long, Long>>): MemoryHeapAnalyzer.Histogram {
        val stats = pairs.map { (name, cb) ->
            MemoryHeapAnalyzer.ClassStat(name, cb.first, cb.second)
        }
        return MemoryHeapAnalyzer.Histogram(
            stats = stats,
            totalInstances = stats.sumOf { it.instanceCount },
            totalBytes = stats.sumOf { it.totalBytes },
            classCount = stats.size.toLong(),
            skippedSegments = 0,
            idSize = 8,
            fileBytes = 0,
            durationMs = 0,
            nameMappingReady = true,
            unresolvedInstances = 0,
        )
    }

    @Test
    fun `差分应给出涨的类为正、减少的类为负`() {
        val before = hist(
            "byte[]" to (10L to 10L * 1024 * 1024),
            "java.lang.String" to (100L to 1L * 1024 * 1024),
        )
        val after = hist(
            "byte[]" to (20L to 30L * 1024 * 1024),
            "java.lang.String" to (50L to 512L * 1024),
        )
        val diff = HeapDiff.diff(before, after, minDeltaBytes = 256 * 1024)
        val byName = diff.associateBy { it.className }
        assertEquals("byte[] 应 +20MB", 20L * 1024 * 1024, byName["byte[]"]!!.totalBytes)
        assertEquals("byte[] 应 +10 个", 10L, byName["byte[]"]!!.instanceCount)
        assertEquals("String 应减少", -512L * 1024, byName["java.lang.String"]!!.totalBytes)
        assertEquals("按字节降序：涨最多的排最前", "byte[]", diff.first().className)
    }

    @Test
    fun `差分应忽略低于阈值的噪声`() {
        val before = hist("A" to (1L to 100L))
        val after = hist("A" to (2L to 100L + 1024)) // 只涨 1KB
        assertTrue(HeapDiff.diff(before, after, minDeltaBytes = 256 * 1024).isEmpty())
    }

    @Test
    fun `差分应能识别新增与消失的类`() {
        val before = hist("Gone" to (5L to 4L * 1024 * 1024))
        val after = hist("New" to (5L to 4L * 1024 * 1024))
        val diff = HeapDiff.diff(before, after, minDeltaBytes = 1024 * 1024)
        val byName = diff.associateBy { it.className }
        assertEquals("消失的类应为负", -4L * 1024 * 1024, byName["Gone"]!!.totalBytes)
        assertEquals("新增的类应为正", 4L * 1024 * 1024, byName["New"]!!.totalBytes)
    }

    // ─────────────────────────────────────────────
    // 七、Snapshot 的派生属性（页面与事件都依赖它们）
    // ─────────────────────────────────────────────

    @Test
    fun `headroom 不能为负`() {
        val limit = 100L
        val s = snap(used = 200, limit = limit, max = limit)
        assertEquals("已超过硬上限时 headroom 取 0", 0L, s.javaHeadroomBytes)
    }

    @Test
    fun `allocatorWasteRatio 在映射为 0 时不能除零`() {
        val s = snap(1, 100).copy(nativeHeapSizeBytes = 0, nativeAllocatedBytes = 0)
        assertEquals(0f, s.allocatorWasteRatio, 0.0001f)
    }
}
