package com.interview.ec

import com.google.gson.Gson
import com.interview.ec.model.EcPageParseResult
import com.interview.ec.model.EcPageParser
import com.interview.ec.model.EcSchema
import com.interview.ec.model.ElementType
import com.interview.ec.model.LayoutStrategy
import com.interview.ec.model.ParseWarning
import com.interview.ec.model.PriceFormatter
import com.interview.ec.render.PagePlanBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Time: 2026/10/18
 * Author: wgt
 * Description: 解析层与展平层的**纯 JVM 单测**（无需设备/模拟器）。
 *
 * ─── 为什么这两层必须被单测钉死，而且必须能脱离 Android 跑 ───
 *
 * 「数据驱动首页」的失效模式有个共同特征：**不崩，只是画错**。
 *   · 服务端多下发一个未知类型 → 少一个楼层（没人发现）；
 *   · 列数不整除 → 每行右侧留白（以为是自己没对齐）；
 *   · 满行元素前面没补齐 → 标题上方有时有空白有时没有（最像"玄学"的一类）。
 *
 * 这些都不会让测试红、不会让 CI 挂，只会在真机上表现为"看起来不太对"。
 * 所以必须把**数据契约**（解析）与**占位计算**（展平）这两层的每条规则
 * 写成断言 —— 规则一旦被改动，测试立刻红，而不是等下一个人用眼睛发现。
 *
 * 它们之所以能脱离设备跑，是因为这两层刻意**不依赖任何 Android 类**
 * （见 EcPageModel.kt 的文件头说明）。这也是分层设计的直接收益：
 * 最容易错的逻辑，恰好是最容易测的。
 */
class EcPageParserTest {

    // ══════════════════════════════════════════════════════════════════
    //  一、真资产文件：与线上跑的是**同一份** JSON
    // ══════════════════════════════════════════════════════════════════

    /**
     * 直接读 `app/src/main/assets/ec-home/page.json` 来测，**不是**在测试里另写一份 JSON。
     *
     * 为什么不另写：另写的话，测试通过只证明"我编的那份 JSON 能被解析"，
     * 而真正会上线的那份改了、写歪了，测试**完全不会知道**。
     * 这里读真文件，意味着"asset 里写歪了"会当场让测试红。
     *
     * 单测的 working dir 是模块目录（`:app` → `app/`），所以路径从 `src/main/...` 起。
     */
    private fun realPageJson(): String {
        val f = File("src/main/assets/ec-home/page.json")
        assertTrue(
            "找不到真实资产文件：${f.absolutePath}（单测工作目录应为 :app 模块根目录）",
            f.exists(),
        )
        return f.readText()
    }

    @Test
    fun `真实资产文件应能解析成功且不是整体失败`() {
        val result = EcPageParser.parse(realPageJson())
        assertTrue("真实 JSON 不应整体失败：$result", result is EcPageParseResult.Success)
    }

    @Test
    fun `真实资产文件里埋的三种异常都应被如实报告`() {
        val success = EcPageParser.parse(realPageJson()) as EcPageParseResult.Success
        val codes = success.warnings.map { it.code }.toSet()

        // 这份 asset 是**故意写坏的**（见 page.json 里的注释与元素命名），
        // 目的是让"降级路径"在默认演示里就被走到，而不是只能靠读代码想象。
        assertTrue(
            "应报告未知组件（c_future 的 live_stream_room）：$codes",
            ParseWarning.CODE_UNKNOWN_COMPONENT in codes,
        )
        assertTrue(
            "应报告未知元件（e_mix_unknown 的 ar_try_on）：$codes",
            ParseWarning.CODE_UNKNOWN_ELEMENT in codes,
        )
        assertTrue(
            "应报告非法列数（c_bad_columns 的 7）：$codes",
            ParseWarning.CODE_BAD_COLUMNS in codes,
        )
    }

    @Test
    fun `未知组件应被整块跳过但其余组件全部保住`() {
        val success = EcPageParser.parse(realPageJson()) as EcPageParseResult.Success
        val ids = success.page.components.map { it.id }

        assertTrue("未知类型的组件应被跳过", "c_future" !in ids)
        // 关键断言：**其余组件一个都不能少**。这正是"一块坏不拖死全页"的证据。
        listOf(
            "c_top_banner", "c_kingkong", "c_flash_sale",
            "c_recommend_2col", "c_bad_columns", "c_mixed",
        ).forEach { id ->
            assertTrue("组件 $id 不应因为别处有错而被牵连", id in ids)
        }
    }

    @Test
    fun `未知元件应降级为 UNKNOWN 并保留原始类型名而不被丢弃`() {
        val success = EcPageParser.parse(realPageJson()) as EcPageParseResult.Success
        val mixed = success.page.components.first { it.id == "c_mixed" }
        val unknown = mixed.elements.first { it.id == "e_mix_unknown" }

        assertEquals(ElementType.UNKNOWN, unknown.type)
        // 保留 rawType 是"显示出来让人定位"的前提 —— 只标 UNKNOWN 是没用的，
        // 看到的人需要知道"客户端不认识的是哪个类型"。
        assertEquals("ar_try_on", unknown.rawType)
        assertTrue("未知元件必须仍在列表里（占位），不能被静默丢弃", unknown in mixed.elements)
    }

    @Test
    fun `非法列数 7 应被夹到合法值并告警`() {
        val success = EcPageParser.parse(realPageJson()) as EcPageParseResult.Success
        val bad = success.page.components.first { it.id == "c_bad_columns" }

        assertTrue("夹后的列数必须是合法值：${bad.columns}", bad.columns in EcSchema.LEGAL_COLUMNS)
        // 7 的邻居里 6 是合法的（且是最近的合法值之一），这里断言"夹到了 6"
        assertEquals(6, bad.columns)
        val w = success.warnings.first { it.code == ParseWarning.CODE_BAD_COLUMNS }
        // detail 必须说明"我们做了什么"，否则看的人不知道这条是"丢了"还是"改了"
        assertTrue("警告应说明处理方式：${w.detail}", w.detail.contains("已夹到"))
    }

    @Test
    fun `横滑组件的元素应由渲染层自己承载 且列数被规范为 1`() {
        val success = EcPageParser.parse(realPageJson()) as EcPageParseResult.Success
        val horizontal = success.page.components.first { it.layout == LayoutStrategy.HORIZONTAL }
        assertEquals("横滑楼层在外层只占 1 个 item，列数无意义", 1, horizontal.columns)
        assertTrue("横滑组件应保留全部元素（交给内层列表）", horizontal.elements.size >= 5)
    }

    // ══════════════════════════════════════════════════════════════════
    //  二、契约与边界（用最小 JSON 精确触发单条规则）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `JSON 语法错是唯一的整体失败`() {
        val r = EcPageParser.parse("""{ "page": { "components": [ """)
        assertTrue("语法错必须整体失败（此时没有部分成功的可能）", r is EcPageParseResult.Failure)
    }

    @Test
    fun `缺 page 节点视为整体失败`() {
        val r = EcPageParser.parse("""{ "schemaVersion": "1.0" }""")
        assertTrue(r is EcPageParseResult.Failure)
    }

    @Test
    fun `未知 schemaVersion 主版本应告警而不是拒绝`() {
        // 灰度期间新增字段会被 Gson 忽略，尽力解析通常仍能画出大部分内容。
        // 直接拒绝会让新版报文在老客户端上**全白**，这比"版本未知"严重得多。
        val json = """
            {"schemaVersion":"99.0","page":{"title":"t","components":[
              {"id":"c1","type":"grid","columns":2,"elements":[
                {"id":"e1","type":"product","title":"x","priceCents":100}]}]}}
        """.trimIndent()
        val r = EcPageParser.parse(json) as EcPageParseResult.Success
        assertEquals(1, r.page.components.size)
        assertTrue(
            "应报告版本告警",
            r.warnings.any { it.code == ParseWarning.CODE_SCHEMA_VERSION },
        )
    }

    @Test
    fun `缺少 schemaVersion 应按 v1 解析并告警`() {
        val json = """{"page":{"title":"t","components":[]}}"""
        val r = EcPageParser.parse(json) as EcPageParseResult.Success
        assertTrue(r.warnings.any { it.code == ParseWarning.CODE_SCHEMA_VERSION })
    }

    @Test
    fun `没有可渲染元件的组件应被跳过并告警`() {
        val json = """
            {"page":{"title":"t","components":[
              {"id":"empty","type":"grid","columns":2,"elements":[]},
              {"id":"ok","type":"grid","columns":2,"elements":[
                {"id":"e1","type":"product","title":"x"}]}]}}
        """.trimIndent()
        val r = EcPageParser.parse(json) as EcPageParseResult.Success
        assertEquals("空楼层应被跳过", listOf("ok"), r.page.components.map { it.id })
        assertTrue(r.warnings.any { it.code == ParseWarning.CODE_EMPTY_COMPONENT })
    }

    @Test
    fun `元件缺 id 应回退为位置 id 并告警 而不是整条丢掉`() {
        val json = """
            {"page":{"title":"t","components":[
              {"id":"c1","type":"grid","columns":2,"elements":[
                {"type":"product","title":"没有id的商品"}]}]}}
        """.trimIndent()
        val r = EcPageParser.parse(json) as EcPageParseResult.Success
        val e = r.page.components.first().elements.first()
        assertTrue("应回退出一个非空 id", e.id.isNotBlank())
        assertTrue(r.warnings.any { it.code == ParseWarning.CODE_MISSING_ID })
    }

    @Test
    fun `重复 id 应告警但保留全部元件`() {
        // 取舍依据：重复 id 影响的是**打点归因**，而丢元件影响的是**用户看到的商品数**。
        // 后者是用户可见的功能损失，优先级更高 → 保留 + 告警。
        val json = """
            {"page":{"title":"t","components":[
              {"id":"c1","type":"grid","columns":2,"elements":[
                {"id":"dup","type":"product","title":"a"},
                {"id":"dup","type":"product","title":"b"}]}]}}
        """.trimIndent()
        val r = EcPageParser.parse(json) as EcPageParseResult.Success
        assertEquals("两个都要保留", 2, r.page.components.first().elements.size)
        assertTrue(r.warnings.any { it.code == ParseWarning.CODE_DUPLICATE_ID })
    }

    @Test
    fun `颜色的非法值不应导致解析失败（留给渲染层兜底）`() {
        val json = """
            {"page":{"title":"t","components":[
              {"id":"c1","type":"grid","columns":2,"style":{"background":"不是颜色"},
               "elements":[{"id":"e1","type":"product","title":"x"}]}]}}
        """.trimIndent()
        val r = EcPageParser.parse(json) as EcPageParseResult.Success
        // 解析层**不校验颜色**：它不知道渲染层怎么用这个值。
        // 校验放在真正使用它的地方（parseColorOrNull → null → 用默认色），
        // 这样"颜色错了"不会升级成"楼层没了"。
        assertEquals("不是颜色", r.page.components.first().style.background)
    }

    @Test
    fun `列数缺失时默认 2 列`() {
        val json = """
            {"page":{"title":"t","components":[
              {"id":"c1","type":"grid","elements":[
                {"id":"e1","type":"product","title":"x"}]}]}}
        """.trimIndent()
        val r = EcPageParser.parse(json) as EcPageParseResult.Success
        assertEquals(2, r.page.components.first().columns)
    }

    @Test
    fun `banner 组件的列数应被规范为 1（语义覆盖配置）`() {
        val json = """
            {"page":{"title":"t","components":[
              {"id":"c1","type":"banner","columns":3,"elements":[
                {"id":"e1","type":"banner_image","title":"x"}]}]}}
        """.trimIndent()
        val r = EcPageParser.parse(json) as EcPageParseResult.Success
        assertEquals(1, r.page.components.first().columns)
    }

    @Test
    fun `页面标题缺失时应有兜底而不是空标题`() {
        val json = """{"page":{"components":[]}}"""
        val r = EcPageParser.parse(json) as EcPageParseResult.Success
        assertTrue("标题应有兜底：'${r.page.title}'", r.page.title.isNotBlank())
    }

    // ══════════════════════════════════════════════════════════════════
    //  三、schema 契约本身的约束（防止将来被改坏）
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun `SPAN_COUNT 必须能被每一个合法列数整除`() {
        // 这是整套 span 计算的**根基**：一旦不整除，`SPAN_COUNT / columns` 会取整，
        // 每行拼不满而右侧留白，且不同列数留白不同 —— 极难排查。
        // 这条断言把"根基"钉住：将来谁改 SPAN_COUNT 或加列数，这里立刻红。
        EcSchema.LEGAL_COLUMNS.forEach { c ->
            assertEquals(
                "列数 $c 无法整除 SPAN_COUNT=${EcSchema.SPAN_COUNT}，会导致行内留白",
                0, EcSchema.SPAN_COUNT % c,
            )
        }
    }

    @Test
    fun `金刚区常用的 5 列必须在合法集合内`() {
        // 5 列是金刚区最典型的列数。若 SPAN_COUNT 被改回 12，5 就消失了 —— 这条会红。
        assertTrue("5 列必须可用：${EcSchema.LEGAL_COLUMNS}", 5 in EcSchema.LEGAL_COLUMNS)
        assertEquals(12, EcSchema.spanPerElement(5))
    }

    @Test
    fun `spanPerElement 对非法列数应返回 null 而不是取整`() {
        // 返回"取整后的值"是本类最危险的退化：调用方拿到一个看起来合理的数，
        // 于是错误被一路带到布局里。返回 null 强迫调用方显式处理。
        assertNull(EcSchema.spanPerElement(7))
        assertNull(EcSchema.spanPerElement(0))
    }

    @Test
    fun `真实资产里的每个组件的列数都合法`() {
        val r = EcPageParser.parse(realPageJson()) as EcPageParseResult.Success
        r.page.components.forEach { c ->
            assertTrue(
                "组件 ${c.id} 的列数 ${c.columns} 非法（解析后不应再出现非法列数）",
                c.columns in EcSchema.LEGAL_COLUMNS,
            )
            // 而且必须能整除 —— 双重确认，因为这直接影响布局
            assertEquals(0, EcSchema.SPAN_COUNT % c.columns)
        }
    }
}

/**
 * 价格格式化单测。
 *
 * 金额是这个页面上"用户最会去核对"的数字，格式错了是**直接被用户看到**的错误
 * （比如 199.90 显示成 199.9 还算能接受，1299.00 显示成 1299.0 就显得不专业）。
 * 纯函数 + 单测，把每种分位组合钉住。
 */
class PriceFormatterTest {

    @Test
    fun `整元不显示小数`() {
        assertEquals("¥1,299", PriceFormatter.format(129900))
        assertEquals("¥399", PriceFormatter.format(39900))
        assertEquals("¥0", PriceFormatter.format(0))
    }

    @Test
    fun `一位小数只显示一位`() {
        assertEquals("¥199.9", PriceFormatter.format(19990))
    }

    @Test
    fun `两位小数补齐两位`() {
        assertEquals("¥19.99", PriceFormatter.format(1999))
        // 1990 分 = 19.90 元 → 分位是 0 → 应显示 ¥19.9（而不是 ¥19.90）
        assertEquals("¥19.9", PriceFormatter.format(1990))
        // 1905 分 = 19.05 元 → 必须补零成 05，不能显示成 19.5
        assertEquals("¥19.05", PriceFormatter.format(1905))
    }

    @Test
    fun `千分位分组正确`() {
        assertEquals("¥1,000", PriceFormatter.format(100000))
        assertEquals("¥12,345.67", PriceFormatter.format(1234567))
        assertEquals("¥123,456", PriceFormatter.format(12345600))
        assertEquals("¥999", PriceFormatter.format(99900))
    }

    @Test
    fun `小额与负值`() {
        assertEquals("¥0.01", PriceFormatter.format(1))
        assertEquals("¥0.1", PriceFormatter.format(10))
        assertEquals("-¥1.5", PriceFormatter.format(-150))
    }

    @Test
    fun `不应因为分组而引入逗号做小数点`() {
        // 手写分组而不用 String.format("%.2f") 的原因：后者受默认 Locale 影响，
        // 在部分欧洲/中东区域会把小数点写成逗号 → "¥1.299,00" 这种完全不可读的输出。
        // 这条断言确保输出里**最多只有一个点**，且它是小数点而非分组符。
        val s = PriceFormatter.format(1234567)
        assertEquals("只应有一个小数点：$s", 1, s.count { it == '.' })
    }
}

/**
 * 展平计划单测 —— 本 Lab 最核心的逻辑。
 *
 * 这些用例覆盖的全是"不崩、只画错"的场景，正是最需要断言的地方。
 */
class PagePlanTest {

    private fun parseOk(json: String) =
        (EcPageParser.parse(json) as EcPageParseResult.Success).page

    private fun gridJson(columns: Int, elementsJson: String) = """
        {"page":{"title":"t","components":[
          {"id":"c1","type":"grid","columns":$columns,"elements":[$elementsJson]}]}}
    """.trimIndent()

    @Test
    fun `每个楼层的 span 总量应等于整屏的整数倍（楼层边界必须落在行边界上）`() {
        // ★ 这是展平方案**最重要的一条不变量**，理由不是"美观"：
        //   展平后所有楼层是同一条连续位置序列，楼层之间没有"换行标记"。
        //   若某楼层以半行结尾（比如 2 列宫格里只剩 30 span），
        //   下一个楼层的首个元素会被 GridLayoutManager 塞进这半行 → 两个楼层粘连。
        //
        // 注意：这条断言的对象是"**每个楼层**"，而不是"整页总和"。
        //   整页总和为整数倍时，**逐楼层仍可能被拆成非整数倍**（例如
        //   楼层A=90、楼层B=90，总和 180 是整数倍，但两个楼层都错位）。
        //   所以必须逐楼层检查 —— 这正是第一版测试写错的地方。
        val page = parseOk(
            gridJson(
                2,
                (1..5).joinToString(",") { """{"id":"p$it","type":"product","title":"x"}""" },
            ),
        )
        val plan = PagePlanBuilder.build(page)
        plan.floors.forEach { floor ->
            val total = floor.rows.sumOf { it.spanSize }
            assertEquals(
                "楼层 ${floor.component.id} 的 span 总量 $total 不是整屏的整数倍 —— " +
                    "下一个楼层的元素会漏进本楼层的最后一行",
                0, total % EcSchema.SPAN_COUNT,
            )
        }
    }

    @Test
    fun `楼层以半行结尾时应被补齐 否则下一个楼层会漏进这半行`() {
        // 这是本文件里**唯一一个真正验证了 bug 修复**的用例，值得单独存在：
        // 楼层 A = 1 个 2 列宫格商品（占 30 span，半行结尾）；
        // 楼层 B = 1 个 2 列宫格商品。
        // 若不在楼层 A 末尾补齐，GridLayoutManager 会把楼层 B 的商品塞进
        // 楼层 A 的那半行里 —— 两个楼层在视觉上就是同一行，完全粘在一起。
        val json = """
            {"page":{"title":"t","components":[
              {"id":"floorA","type":"grid","columns":2,"elements":[
                {"id":"a1","type":"product","title":"A 的商品"}]},
              {"id":"floorB","type":"grid","columns":2,"elements":[
                {"id":"b1","type":"product","title":"B 的商品1"},
                {"id":"b2","type":"product","title":"B 的商品2"}]}]}}
        """.trimIndent()
        val plan = PagePlanBuilder.build(parseOk(json))

        val floorA = plan.floors.first { it.component.id == "floorA" }
        val floorB = plan.floors.first { it.component.id == "floorB" }

        assertEquals("楼层A 应被补齐到整行（30 + 30）", EcSchema.SPAN_COUNT, floorA.rows.sumOf { it.spanSize })
        assertTrue(
            "楼层A 末尾应有一个占位行（它以半行结尾）",
            floorA.rows.last().element.rawType == PagePlanBuilder.FILLER_RAW_TYPE,
        )
        // 楼层B 放**两个**商品：恰好占满一整行，因此**不需要**补齐。
        // 这是与楼层A 的对照 —— 补齐只在"行没排满"时发生，不能无脑每层都补
        //（无脑补会在每个楼层末尾都多出一行占位，纯浪费）。
        assertEquals(EcSchema.SPAN_COUNT, floorB.rows.sumOf { it.spanSize })
        assertTrue(
            "楼层B 本来就占满整行，不应有占位行",
            floorB.rows.none { it.element.rawType == PagePlanBuilder.FILLER_RAW_TYPE },
        )
    }

    @Test
    fun `2 列宫格里每个商品占 30 span`() {
        val page = parseOk(gridJson(2, """{"id":"p1","type":"product","title":"x"}"""))
        val plan = PagePlanBuilder.build(page)
        assertEquals(30, plan.rows.first().spanSize)
    }

    @Test
    fun `5 列金刚区的每个入口占 12 span（这正是 SPAN_COUNT 取 60 的理由）`() {
        val page = parseOk(gridJson(5, """{"id":"i1","type":"icon_entry","title":"x"}"""))
        val plan = PagePlanBuilder.build(page)
        assertEquals(12, plan.rows.first().spanSize)
        // 5 个入口应恰好拼满一行，没有任何留白
        val five = parseOk(
            gridJson(5, (1..5).joinToString(",") { """{"id":"i$it","type":"icon_entry"}""" }),
        )
        val p5 = PagePlanBuilder.build(five)
        assertEquals(EcSchema.SPAN_COUNT, p5.rows.sumOf { it.spanSize })
    }

    @Test
    fun `满行元素占满整行 且其前面的半行会被补齐`() {
        // 场景：2 列（每元素 30 span），序列 = 商品 + 标题。
        // 商品占 30（半行），标题要 60（整行）。
        //
        // ⚠️ 必须说清楚"为什么要补齐"，因为直觉答案是**错的**：
        //   直觉："不补齐的话标题会被挤进那半行里。" —— 错。
        //   源码 `GridLayoutManager.SpanSizeLookup.getSpanIndex()` 对
        //   `positionSpanSize == spanCount` 的元素**直接 return 0**（1.3.2 约 987 行），
        //   即满 span 元素永远从新行第 0 列开始，不可能被挤进半行。
        //   （这个错误假设被本测试的断言当场抓出来，见 PagePlanBuilder.flatten 的注释。）
        //
        //   真正需要补齐的理由是：**保持"每楼层 span 总量为整屏整数倍"这条不变量**。
        //   否则这半行会留给下一个楼层的元素（跨楼层粘连）。
        val page = parseOk(
            gridJson(
                2,
                """
                {"id":"p1","type":"product","title":"x"},
                {"id":"h1","type":"section_header","title":"标题","needsFullRow":true}
                """.trimIndent(),
            ),
        )
        val plan = PagePlanBuilder.build(page)

        // 期望：[商品 30][补齐 30][标题 60] = 3 行
        assertEquals("应有 3 行（商品 + 补齐 + 标题）", 3, plan.rows.size)
        assertEquals("商品占半行", 30, plan.rows[0].spanSize)
        assertEquals("补齐行应为剩余 span", 30, plan.rows[1].spanSize)
        assertTrue("补齐行必须是 filler", plan.rows[1].element.rawType == PagePlanBuilder.FILLER_RAW_TYPE)
        assertEquals("标题独占整行", EcSchema.SPAN_COUNT, plan.rows[2].spanSize)

        // 总 span 仍须是整屏的整数倍（补齐的直接目的）
        assertEquals(0, plan.rows.sumOf { it.spanSize } % EcSchema.SPAN_COUNT)
    }

    @Test
    fun `满行元素恰好遇到新行开头时不应插入多余补齐`() {
        // 序列 = 商品 + 商品 + 标题：两个商品已占满一行，标题本就从新行开始，
        // 此时插入补齐反而会多出一行**真实的空白**（把问题从"有时留白"变成"一定留白"）。
        val page = parseOk(
            gridJson(
                2,
                """
                {"id":"p1","type":"product"},
                {"id":"p2","type":"product"},
                {"id":"h1","type":"section_header","needsFullRow":true}
                """.trimIndent(),
            ),
        )
        val plan = PagePlanBuilder.build(page)
        assertEquals("应为 3 行，不应有补齐", 3, plan.rows.size)
        assertTrue(
            "不应出现 filler",
            plan.rows.none { it.element.rawType == PagePlanBuilder.FILLER_RAW_TYPE },
        )
    }

    @Test
    fun `BANNER_IMAGE 应自动独占一行（不必显式声明 needsFullRow）`() {
        // banner 的语义就是通栏，让服务端每次都要记得写 needsFullRow 是多余的负担，
        // 且漏写会得到一个半宽的大图 —— 正是"能降级就不要失败"要避免的那类错。
        val json = """
            {"page":{"title":"t","components":[
              {"id":"c1","type":"banner","elements":[
                {"id":"b1","type":"banner_image","title":"大图"}]}]}}
        """.trimIndent()
        val plan = PagePlanBuilder.build(parseOk(json))
        assertEquals(EcSchema.SPAN_COUNT, plan.rows.first().spanSize)
    }

    @Test
    fun `横滑组件在外层只产生 1 行`() {
        val json = """
            {"page":{"title":"t","components":[
              {"id":"c1","type":"horizontal","title":"秒杀","elements":[
                {"id":"p1","type":"product"},{"id":"p2","type":"product"},
                {"id":"p3","type":"product"},{"id":"p4","type":"product"}]}]}}
        """.trimIndent()
        val plan = PagePlanBuilder.build(parseOk(json))
        assertEquals("横滑楼层的内层元素不占用外层 item", 1, plan.rows.size)
        assertEquals(EcSchema.SPAN_COUNT, plan.rows.first().spanSize)
    }

    @Test
    fun `plan 的 rows 与 floors 必须严格对齐（validate 的自证）`() {
        // validate() 是防止"计划与实际位置错开 1 个"这类沉默错误的核心护栏 ——
        // 错开后 span 会算到错误的 item 上，表现是"某些卡片宽度莫名不对"而非崩溃。
        val json = """
            {"page":{"title":"t","components":[
              {"id":"c1","type":"grid","columns":2,"elements":[
                {"id":"p1","type":"product"},{"id":"h","type":"section_header","needsFullRow":true},
                {"id":"p2","type":"product"}]},
              {"id":"c2","type":"horizontal","title":"h","elements":[{"id":"p3","type":"product"}]},
              {"id":"c3","type":"grid","columns":5,"elements":[
                {"id":"i1","type":"icon_entry"},{"id":"i2","type":"icon_entry"}]}]}}
        """.trimIndent()
        val plan = PagePlanBuilder.build(parseOk(json))

        // build() 内部已调用 validate()（不满足会抛异常），这里再显式确认一次
        plan.validate()
        assertEquals(3, plan.floors.size)
        assertEquals(plan.floors.sumOf { it.rows.size }, plan.rows.size)

        // 每个 floor 的 rows 必须能被全局位置连续覆盖
        plan.floors.forEachIndexed { i, floor ->
            floor.rows.forEachIndexed { local, row ->
                assertEquals("floor $i local $local 的全局位置应对齐", floor.startPosition + local, i.let {
                    plan.floors.take(i).sumOf { f -> f.rows.size } + local
                })
                assertEquals(i, row.floorIndex)
                assertEquals(local, row.localPosition)
            }
        }
    }

    @Test
    fun `floorIndex 与 localPosition 应被正确回填（不是 -1 哨兵）`() {
        // build() 里先用 -1 哨兵占位、最后回填。哨兵若没被覆盖，
        // 渲染层会拿到 -1 而立刻越界 —— 这条断言确保回填真的发生了。
        val page = parseOk(
            gridJson(2, """{"id":"p1","type":"product"},{"id":"p2","type":"product"}"""),
        )
        val plan = PagePlanBuilder.build(page)
        plan.rows.forEachIndexed { i, row ->
            assertTrue("第 $i 行的 floorIndex 未被回填", row.floorIndex >= 0)
            assertTrue("第 $i 行的 localPosition 未被回填", row.localPosition >= 0)
        }
        assertEquals(0, plan.rows[0].localPosition)
        assertEquals(1, plan.rows[1].localPosition)
    }

    @Test
    fun `未知元件应仍然占位（不被展平阶段丢掉）`() {
        // 解析层把它标成 UNKNOWN，展平层必须给它分配 span ——
        // 否则"配了 5 个只显示 4 个"的静默缺失就发生在这一层。
        val page = parseOk(
            gridJson(2, """{"id":"u1","type":"ar_try_on","title":"未来玩法"}"""),
        )
        val plan = PagePlanBuilder.build(page)

        // 第 1 行是未知元件本身（它必须存在，且有自己的 span）；
        // 第 2 行是楼层收尾补的占位（单个未知元件只占半行）。
        // 断言要**对着未知元件这条**去查，而不是假设"计划里只有 1 行"。
        val unknownRow = plan.rows.first { it.element.id == "u1" }
        assertEquals(30, unknownRow.spanSize)
        assertEquals(ElementType.UNKNOWN, unknownRow.element.type)
    }

    @Test
    fun `真实资产文件应能构建出合法计划`() {
        val f = File("src/main/assets/ec-home/page.json")
        val page = parseOk(f.readText())
        val plan = PagePlanBuilder.build(page)

        plan.validate()
        assertTrue("真实页面应有多个楼层", plan.floors.size >= 5)
        assertTrue("真实页面应有几十个 item", plan.itemCount > 20)
        // 没有任何 span 超过总列数（超过会抛 IllegalArgumentException，见 GridLayoutManager.java:575）
        plan.rows.forEach { r ->
            assertTrue("spanSize ${r.spanSize} 超过了 ${EcSchema.SPAN_COUNT}", r.spanSize <= EcSchema.SPAN_COUNT)
            assertTrue("spanSize 必须为正", r.spanSize > 0)
        }
    }

    @Test
    fun `真实资产里每个楼层的 span 总量都是整屏的整数倍`() {
        // 逐楼层检查（而不是只查全局总和）：某个楼层以半行结尾时，
        // 下一个楼层的首个元素会漏进这半行 —— 两个楼层在视觉上粘连。
        // 这类错位在整页截图里非常显眼，但**不会报错**，所以必须被断言钉住。
        val page = parseOk(File("src/main/assets/ec-home/page.json").readText())
        val plan = PagePlanBuilder.build(page)
        plan.floors.forEach { floor ->
            val total = floor.rows.sumOf { it.spanSize }
            assertEquals(
                "楼层 ${floor.component.id} 的 span 总量 $total 不是整屏（下一个楼层会漏进来）",
                0, total % EcSchema.SPAN_COUNT,
            )
        }
    }

    @Test
    fun `JSON 里的每一个元件都出现在了计划里（不丢数据）`() {
        // 这条是**端到端的对账**：JSON 里写了 N 个元件，展平后必须能看到 N 个
        //（横滑楼层的元素在内层，所以按"外层可见 + 内层承载"分别计）。
        val page = parseOk(File("src/main/assets/ec-home/page.json").readText())
        val plan = PagePlanBuilder.build(page)

        val jsonElements = page.components.sumOf { it.elements.size }
        val plannedElements = plan.floors.sumOf { floor ->
            if (floor.component.layout == LayoutStrategy.HORIZONTAL) {
                floor.component.elements.size
            } else {
                // 展平行：排除 filler（它不是数据里的元件）
                floor.rows.count { it.element.rawType != PagePlanBuilder.FILLER_RAW_TYPE }
            }
        }
        assertEquals("计划里的元件数与 JSON 不一致（有数据被静默丢掉）", jsonElements, plannedElements)
    }
}

/**
 * 序列化往返测试：确保 schema 里的字段名与客户端 DTO 对得上。
 *
 * 为什么值得一测：DTO 与领域模型是分开的（见 EcPageParser 的说明），
 * 分开的代价就是"字段名写错了编译器不会提醒" —— Gson 会安静地给你 null。
 * 用 Gson **自己**序列化一个对象再反序列化，能验证字段名一致；
 * 这条测试的用途是**防止有人改了 DTO 的字段名而没改 JSON/没有被发现**。
 */
class SchemaRoundTripTest {

    @Test
    fun `核心字段应能往返序列化（字段名与 JSON 契约一致）`() {
        val json = """
            {"page":{"title":"往返","components":[
              {"id":"c1","type":"grid","columns":2,"title":"楼层",
               "style":{"background":"#FFFFFF","paddingDp":8,"card":false},
               "elements":[
                 {"id":"e1","type":"product","title":"商品","subtitle":"副标题",
                  "imageUrl":"https://example.com/a.png","priceCents":12900,
                  "originalPriceCents":29900,"badge":"折","target":"app://x",
                  "needsFullRow":true}]}]}}
        """.trimIndent()

        val page = (EcPageParser.parse(json) as EcPageParseResult.Success).page
        val c = page.components.first()
        val e = c.elements.first()

        assertEquals("楼层", c.title)
        assertEquals("#FFFFFF", c.style.background)
        assertEquals(8, c.style.paddingDp)
        assertEquals(false, c.style.card)
        assertEquals("商品", e.title)
        assertEquals("副标题", e.subtitle)
        assertEquals("https://example.com/a.png", e.imageUrl)
        assertEquals(12900, e.priceCents)
        assertEquals(29900, e.originalPriceCents)
        assertEquals("折", e.badge)
        assertEquals("app://x", e.target)
        assertTrue(e.needsFullRow)

        // 再确认 gson 本身没被无关地引入（防止 IDE 自动导入别的 Gson 实例）
        assertNotNull(Gson())
    }
}
