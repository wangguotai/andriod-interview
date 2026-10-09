package com.interview.内存

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Time: 2026/10/9
 * Author: wgt
 * Description: [SmapsReader] 的**纯 JVM 单测**（宿主 JVM 直接跑，不需要设备）
 *
 * ─── 为什么这些点非测不可（这些全是真机上会踩的坑）───
 *
 * smaps 解析属于"监控自己可能静默给错值"的那类代码：它每天在后台跑，
 * 一旦解析漂移，报告的 RSS 会**偏低且看不出来**（少了哪条没人知道）。
 * 所以这里**既覆盖正常样本、也覆盖畸形样本**，并且专门把下面几条"看着不起眼、
 * 错了却致命"的边界钉住：
 *
 * 1. `Rss:` 后面是**两个空格**（不同内核可能是不同个数）——不能按固定分隔符切。
 * 2. `VmFlags:` 行**长得像属性行**，最容易被误判成新映射（会把条数算多）。
 * 3. 未知键（`SwapPss:` / `Anonymous:` / `Locked:` ...）必须忽略且不中断。
 * 4. 畸形行必须进 `skippedLines` 而不是让解析静默吞掉。
 */
class SmapsReaderTest {

    /** 造一条映射块；attrs 里每项形如 "Rss:  123"（键与值，冒号后空格数由调用方决定）。 */
    private fun block(header: String, vararg attrs: String): String =
        buildString {
            appendLine(header)
            attrs.forEach { appendLine(it) }
        }

    // 一条标准的、带路径的 .so 映射（地址范围 0x100000 = 1024 KB）
    private val soHeader =
        "7f9d00000000-7f9d00100000 r-xp 00000000 08:01 100 /data/app/base.apk!/lib/arm64-v8a/libfoo.so"
    // 匿名映射：行尾就是 "00:00 0"，没有路径
    private val anonHeader = "7f9c1a400000-7f9c1a401000 rw-p 00000000 00:00 0"
    // 命名匿名段（dalvik 的 region space）——路径里带空格和括号
    private val dalvikHeader =
        "7f0000000000-7f0000100000 rw-p 00000000 00:00 0 [anon:dalvik-main space (region space)]"
    private val stackHeader = "7fff00000000-7fff00010000 rw-p 00000000 00:00 0 [stack]"
    private val heapHeader = "0e0000000000-0e0000100000 rw-p 00000000 00:00 0 [heap]"
    private val kgslHeader = "7f9e00000000-7f9e00100000 rw-p 00000000 00:00 0 /dev/kgsl-3d0"
    private val fontHeader = "7f9f00000000-7f9f00100000 r--p 00000000 08:01 500 /system/fonts/NotoSansCJK-Regular.ttc"

    // ─────────────────────────────────────────────
    // 一、正常多映射解析
    // ─────────────────────────────────────────────

    @Test
    fun `正常样本_应解析出全部映射且匿名段给占位名`() {
        val text = buildString {
            append(block(soHeader, "Rss:  512 kB", "Pss:  500 kB", "Private_Dirty:  12 kB", "Swap:  4 kB"))
            append('\n')
            append(block(anonHeader, "Rss:  8 kB", "Pss:  8 kB", "Private_Dirty:  8 kB", "Swap:  0 kB"))
            append('\n')
            append(block(dalvikHeader, "Rss:  2048 kB", "Pss:  1000 kB", "Private_Dirty:  900 kB", "Swap:  0 kB"))
        }

        val r = SmapsReader.parse(text)

        assertEquals("应解析 3 条映射", 3, r.parsedLines)
        assertEquals("不应有跳过行", 0, r.skippedLines)
        assertEquals(3, r.mappings.size)
        // 合计：512 + 8 + 2048 = 2568 KB
        assertEquals(2568L, r.totalRssKb)
        // PSS：500 + 8 + 1000 = 1508 KB
        assertEquals(1508L, r.totalPssKb)

        val byPath = r.mappings.associateBy { it.path }
        assertTrue("应含带路径的 .so", byPath.containsKey(soHeader.substringAfter(" 100 ")))
        // 匿名映射必须有占位名，不能是空串
        assertTrue("匿名映射应有占位名 [anon]", byPath.containsKey(SmapsReader.ANON))
        // 命名匿名段保留原文（含空格与括号）
        assertTrue(
            "命名匿名段应保留原文",
            byPath.containsKey("[anon:dalvik-main space (region space)]")
        )

        val dalvik = byPath.getValue("[anon:dalvik-main space (region space)]")
        assertEquals(2048L, dalvik.rssKb)
        assertEquals(1000L, dalvik.pssKb)
        assertEquals(900L, dalvik.privateDirtyKb)
        assertEquals("rw-p", dalvik.perms)
    }

    // ─────────────────────────────────────────────
    // 二、Rss 后多个空格（关键边界）
    // ─────────────────────────────────────────────

    @Test
    fun `Rss冒号后多个空格_必须正确取值`() {
        // 一句里故意放两种：3 个空格和 1 个空格，以及制表符
        val text = buildString {
            append(block(soHeader, "Rss:   1234 kB", "Pss:\t567 kB", "Private_Dirty:  890 kB"))
            append('\n')
        }
        val m = SmapsReader.parse(text).mappings.single()
        assertEquals("3 个空格应正确解析", 1234L, m.rssKb)
        assertEquals("制表符应正确解析", 567L, m.pssKb)
        assertEquals(890L, m.privateDirtyKb)
    }

    // ─────────────────────────────────────────────
    // 三、VmFlags 行不被当作新映射 + 空行处理
    // ─────────────────────────────────────────────

    @Test
    fun `VmFlags与空行_不应产生额外映射也不应计入skipped`() {
        val text = buildString {
            append(block(soHeader, "Rss:  100 kB", "Pss:  100 kB", "VmFlags: rd ex mr mw me"))
            append('\n')       // 映射之间有空行
            append('\n')       // 连着两个空行也要能扛
            append(block(anonHeader, "Rss:  50 kB", "Pss:  50 kB", "VmFlags: rw mw"))
        }
        val r = SmapsReader.parse(text)
        assertEquals("VmFlags 不能算新映射", 2, r.mappings.size)
        assertEquals(2, r.parsedLines)
        assertEquals("空行不是畸形行，不应计入 skipped", 0, r.skippedLines)
        assertEquals(150L, r.totalRssKb)
    }

    // ─────────────────────────────────────────────
    // 四、未知属性键被忽略
    // ─────────────────────────────────────────────

    @Test
    fun `未知属性键_应被忽略且不影响已知字段`() {
        val text = buildString {
            append(
                block(
                    soHeader,
                    "Size:              1024 kB",
                    "KernelPageSize:       4 kB",
                    "Rss:                512 kB",
                    "Pss:                500 kB",
                    "Pss_Dirty:          400 kB",
                    "Shared_Clean:       100 kB",
                    "Private_Dirty:       12 kB",
                    "Referenced:         300 kB",
                    "Anonymous:          256 kB",  // 老内核没有的新键
                    "Swap:                 4 kB",
                    "SwapPss:              0 kB",  // 关键：新键，必须忽略而不是报错
                    "Locked:               0 kB",
                )
            )
            append('\n')
        }
        val r = SmapsReader.parse(text)
        assertEquals(1, r.parsedLines)
        assertEquals("未知键不应计入 skipped", 0, r.skippedLines)
        val m = r.mappings.single()
        assertEquals(512L, m.rssKb)
        assertEquals(500L, m.pssKb)
        assertEquals(12L, m.privateDirtyKb)
        assertEquals(4L, m.swapKb)
    }

    // ─────────────────────────────────────────────
    // 五、畸形行 → skippedLines，且不中断
    // ─────────────────────────────────────────────

    @Test
    fun `畸形行应计入skipped且解析不中断`() {
        val text = buildString {
            append(block(soHeader, "Rss:  100 kB", "Pss:  100 kB"))
            append('\n')
            appendLine("<<< 这是一行被截断的垃圾 >>>")            // 既非头行也非属性行 → skip
            appendLine("7f0000000000-7f0000001000 rw-p")           // 截断的头行 → skip
            append(block(anonHeader, "Rss:  200 kB", "Pss:  200 kB"))
            append('\n')
        }
        val r = SmapsReader.parse(text)
        assertEquals("畸形行后仍应继续解析出 2 条映射", 2, r.parsedLines)
        assertEquals("两条畸形行都应如实记账", 2, r.skippedLines)
        assertEquals(300L, r.totalRssKb)
    }

    @Test
    fun `属性值非法_应计入skipped而不是悄悄当0`() {
        val text = buildString {
            append(block(soHeader, "Rss:  abc kB", "Pss:  100 kB"))
            append('\n')
        }
        val r = SmapsReader.parse(text)
        assertEquals(1, r.parsedLines)
        assertEquals("非法的 Rss 值必须暴露为 skipped", 1, r.skippedLines)
        // 关键：Rss 保持 0 但 skipped>0，读报告的人能看到"这条不可信"
        assertEquals(0L, r.mappings.single().rssKb)
    }

    @Test
    fun `空样本_应给出空结果而不是崩溃`() {
        val r = SmapsReader.parse("")
        assertEquals(0, r.mappings.size)
        assertEquals(0L, r.totalRssKb)
        assertEquals(0, r.skippedLines)
    }

    // ─────────────────────────────────────────────
    // 六、地址范围 → sizeKb
    // ─────────────────────────────────────────────

    @Test
    fun `地址范围应换算出正确的sizeKb`() {
        // 0x100000 = 1048576 B / 1024 = 1024 KB
        val text = buildString {
            append(block(soHeader, "Rss:  1 kB", "Pss:  1 kB"))
            append('\n')
            // 含超过 Int 范围的高地址：0x7f9c1a021000 - 0x7f9c1a000000 = 0x21000 = 135168 B / 1024 = 132 KB
            append(block("7f9c1a000000-7f9c1a021000 rw-p 00000000 00:00 0", "Rss:  1 kB"))
        }
        val r = SmapsReader.parse(text)
        val byPath = r.mappings.associateBy { it.path }
        assertEquals(1024L, byPath.getValue(soHeader.substringAfter(" 100 ")).sizeKb)
        assertEquals("高地址也要能算（不能溢出 Int）", 132L, byPath.getValue(SmapsReader.ANON).sizeKb)
    }

    // ─────────────────────────────────────────────
    // 七、top(n) 按 rssKb 降序
    // ─────────────────────────────────────────────

    @Test
    fun `top_n_应按rss降序`() {
        val text = buildString {
            append(block(soHeader, "Rss:  100 kB", "Pss:  100 kB")); append('\n')
            append(block(heapHeader, "Rss:  900 kB", "Pss:  900 kB")); append('\n')
            append(block(stackHeader, "Rss:  400 kB", "Pss:  400 kB")); append('\n')
        }
        val r = SmapsReader.parse(text)
        assertEquals("mappings 本身应整体降序", listOf(900L, 400L, 100L), r.mappings.map { it.rssKb })
        assertEquals(listOf(900L, 400L), r.top(2).map { it.rssKb })
        // n 超界不应崩
        assertEquals(3, r.top(99).size)
        assertEquals(0, r.top(0).size)
    }

    // ─────────────────────────────────────────────
    // 八、byCategory 归类
    // ─────────────────────────────────────────────

    @Test
    fun `byCategory_应覆盖匿名_栈_so_kgsl_字体以及分配器堆`() {
        val text = buildString {
            append(block(soHeader, "Rss:  100 kB", "Pss:  100 kB")); append('\n')
            append(block(anonHeader, "Rss:  200 kB", "Pss:  200 kB")); append('\n')
            append(block(dalvikHeader, "Rss:  300 kB", "Pss:  300 kB")); append('\n')
            append(block(stackHeader, "Rss:  400 kB", "Pss:  400 kB")); append('\n')
            append(block(heapHeader, "Rss:  500 kB", "Pss:  500 kB")); append('\n')
            append(block("7faa00000000-7faa00100000 rw-p 00000000 00:00 0 [anon:libc_malloc]", "Rss:  600 kB")); append('\n')
            append(block("7fab00000000-7fab00100000 rw-p 00000000 00:00 0 [anon:scudo:primary]", "Rss:  700 kB")); append('\n')
            append(block(kgslHeader, "Rss:  800 kB", "Pss:  800 kB")); append('\n')
            append(block(fontHeader, "Rss:  900 kB", "Pss:  900 kB")); append('\n')
        }
        val r = SmapsReader.parse(text)
        val cats = r.byCategory().associateBy { it.name }

        fun rssOf(name: String) = cats.entries.first { it.key.contains(name) }.value.rssKb

        // 分配器堆：heap(500) + libc_malloc(600) + scudo(700) = 1800，且必须并成一类
        assertEquals("heap/malloc/scudo 必须并成一类", 1800L, rssOf("分配器堆"))
        assertEquals("匿名（无路径）", 200L, rssOf("匿名映射"))
        assertEquals("dalvik 命名匿名段", 300L, rssOf("Dalvik/ART"))
        assertEquals("[stack]", 400L, rssOf("线程栈"))
        assertEquals("so/apk/dex", 100L, rssOf("代码/资源"))
        assertEquals("/dev/kgsl", 800L, rssOf("GPU/图形"))
        assertEquals("字体", 900L, rssOf("字体"))

        // 类别汇总必须整体按 RSS 降序
        assertEquals(cats.values.map { it.rssKb }.sortedDescending(), cats.values.map { it.rssKb })
    }

    @Test
    fun `byCategory_分配器堆的条数应合并计数`() {
        val text = buildString {
            append(block(heapHeader, "Rss:  500 kB")); append('\n')
            append(block("7faa00000000-7faa00100000 rw-p 00000000 00:00 0 [anon:libc_malloc]", "Rss:  600 kB")); append('\n')
            append(block("7fab00000000-7fab00100000 rw-p 00000000 00:00 0 [anon:scudo:primary]", "Rss:  700 kB")); append('\n')
        }
        val r = SmapsReader.parse(text)
        val alloc = r.byCategory().first { it.name.contains("分配器堆") }
        assertEquals("三类同属分配器堆，应合并为 1 类 3 条", 3, alloc.count)
        assertEquals(1800L, alloc.rssKb)
    }

    // ─────────────────────────────────────────────
    // 九、describe 报告
    // ─────────────────────────────────────────────

    @Test
    fun `describe_应包含表头_明细_分类与结论且标出skipped`() {
        val text = buildString {
            append(block(kgslHeader, "Rss:  8000 kB", "Pss:  8000 kB", "Private_Dirty:  8000 kB")); append('\n')
            append(block(soHeader, "Rss:  100 kB", "Pss:  100 kB")); append('\n')
            appendLine("垃圾行")
        }
        val report = SmapsReader.parse(text).describe(topN = 5)

        assertTrue("应有 RSS 合计", report.contains("RSS 合计"))
        assertTrue("应有 PSS 合计", report.contains("PSS 合计"))
        assertTrue("应有映射条数", report.contains("映射 2 条"))
        assertTrue("应标出 skipped", report.contains("skipped=1"))
        assertTrue("应有 Top 明细", report.contains("Top5 明细"))
        assertTrue("应有分类汇总", report.contains("分类汇总"))
        assertTrue("明细里应出现 kgsl 路径", report.contains("/dev/kgsl-3d0"))
        assertTrue("结论应指向占比最大的类别", report.contains("GPU/图形"))
        assertTrue("结论应是给人读的一句话", report.contains("占比最大的是"))
    }

    @Test
    fun `describe_空样本不应崩溃`() {
        val report = SmapsReader.parse("").describe()
        assertTrue(report.contains("没有解析到任何映射"))
    }
}
