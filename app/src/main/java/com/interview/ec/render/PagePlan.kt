package com.interview.ec.render

import com.interview.ec.model.Component
import com.interview.ec.model.EcPage
import com.interview.ec.model.EcSchema
import com.interview.ec.model.Element
import com.interview.ec.model.ElementType
import com.interview.ec.model.LayoutStrategy

/**
 * Time: 2026/10/18
 * Author: wgt
 * Description: **页面计划** —— 把"组件 + 元件"的两层树，翻译成"一个外层 item 占几列"。
 *
 * ─── 这个类为什么必须独立存在（而不是写进 Adapter）───
 *
 * 「组件内嵌容器」与「组件展平」两条路，差别最大的地方不是 Adapter 怎么写，
 * 而是**谁在算布局**：
 *
 * · 容器化：内层 ViewGroup 自己在 `onMeasure/onLayout` 里算，你不需要知道 span；
 * · 展平：**你亲手接管了布局**，必须把每个元件的占位算清楚交给 GridLayoutManager。
 *
 * 这份"占位计算"是本方案里唯一真正容易错的逻辑，所以它被抽成一个**纯函数**
 * （输入 [EcPage]，输出 [PagePlan]），不碰 View/Context →
 * 可以在宿主 JVM 上把边界情况全测掉（见 `EcPageParserTest.kt` 里的 `PagePlanTest`）。
 * Adapter 只负责"照着计划 inflate/bind"，不含任何布局判断。
 *
 * ─── 一条来自源码的硬约束：**section 必须连续** ───
 *
 * 直觉上会想"把所有商品卡归到一个 section，这样它们共用一个池"。**这是错的**，
 * 而且错得很隐蔽 —— ConcatAdapter 是"把若干个完整 Adapter 首尾相接"
 * （`ConcatAdapterController.getItemCount()` 就是把各 wrapper 的 count 累加），
 * 它**没有**任何"按类型重排"的能力。若把商品卡全归到一个 section，
 * 页面顺序会变成"先所有商品、再所有图标"，楼层顺序彻底错乱。
 *
 * 所以本设计的 section 划分只有一个选择：
 *
 * > **一个组件（楼层）= 一个 section = 一段连续位置。**
 *
 * 那"跨楼层复用商品卡"怎么办？靠 [PagePlanRenderer] 里的
 * `ConcatAdapter(Config(isolateViewTypes = false))` —— 见那个类的说明。
 * 两个机制是分开的：**连续性是位置层面的约束（这里），复用是池层面的优化（那里）**。
 *
 * ─── 与其他文件的关系 ───
 *
 * ```
 * EcPageParser（model，纯逻辑）
 *     └── EcPage
 *           └── PagePlanBuilder.build()  ← 本文件：算占位
 *                 └── PagePlan（rows 与 global position 一一对应）
 *                       ├── PagePlanRenderer：装配 ConcatAdapter + SpanSizeLookup
 *                       └── SpanSizeLookup 直接读 plan.rows[pos].spanSize
 * ```
 */
data class PagePlan(
    val title: String,
    val floors: List<FloorPlan>,
    val rows: List<Row>,
    val warnings: List<String>,
) {

    /** 外层 item 总数。**必须等于 ConcatAdapter.getItemCount()**，见 [validate] */
    val itemCount: Int get() = rows.size

    /**
     * 自检：计划与"实际会被渲染的东西"是否一致。
     *
     * ─── 为什么要写这个方法，而不是"相信代码是对的" ───
     *
     * 展平方案有一个**致命且沉默**的失效模式：计划的 `rows` 与 ConcatAdapter
     * 实际拼出来的位置错开 1 个。后果是 `SpanSizeLookup` 把 span 算到了**错误的
     * item** 上 —— 表现是"某些卡片宽度莫名其妙不对"，而不是崩溃。
     * 这种问题靠肉眼看截图几乎发现不了，靠打断点又很难意识到要看什么。
     *
     * 所以这里显式校验两条**最容易破的等式**：
     *   rows.size == 各 floor 的 row 之和            （没有漏加/多加）
     *   floor 的连续位置覆盖 0..rows.size-1 且不重叠（没有错位）
     *
     * 在 debug 构建里调用它（见 [PagePlanRenderer]），让错位**当场炸**。
     * 这类"用一次 O(n) 的校验换掉一类沉默 bug"的投入，是展平方案的必要配套。
     */
    fun validate() {
        val sum = floors.sumOf { it.rows.size }
        check(sum == rows.size) {
            "计划自相矛盾：各楼层行数之和 $sum ≠ 总行数 ${rows.size}"
        }
        var cursor = 0
        floors.forEachIndexed { index, floor ->
            check(floor.startPosition == cursor) {
                "计划自相矛盾：楼层 #$index(${floor.component.id}) 的起始位置 " +
                    "${floor.startPosition} ≠ 期望 $cursor（说明前面有楼层漏算或多算）"
            }
            cursor += floor.rows.size
        }
        check(cursor == rows.size) {
            "计划自相矛盾：楼层覆盖了 0..${cursor - 1}，但总行数是 ${rows.size}"
        }
    }
}

/**
 * 一个组件（楼层）被展平后的样子。
 *
 * 这个对象同时是**一个 sub-adapter 的构造参数**：把 [rows] 里的元件按顺序
 * 交给 `ElementSectionAdapter` 即可。之所以不让渲染层自己去 `component.elements`
 * 取，是因为展平过程可能会**插入占位行**（见 [PagePlanBuilder.fillRemaining]），
 * 那时 floors[i].rows 与 component.elements 就不等长了 ——
 * 渲染层只认 [rows]，**不认** component.elements，这样两边不可能不一致。
 */
data class FloorPlan(
    val component: Component,
    /** 本楼层第一行在全局位置中的下标 */
    val startPosition: Int,
    val rows: List<Row>,
)

/**
 * 外层 RecyclerView 的一个 item。
 *
 * @param spanSize 占多少列。满行 = [EcSchema.SPAN_COUNT]
 * @param floorIndex 属于哪个楼层（= 哪个 sub-adapter）
 * @param localPosition 在该 sub-adapter 内的下标
 * @param element 要渲染的元件。占位行为 [ElementType.UNKNOWN] + [PagePlanBuilder.FILLER_RAW_TYPE]
 */
data class Row(
    val spanSize: Int,
    val floorIndex: Int,
    val localPosition: Int,
    val element: Element,
)

// ══════════════════════════════════════════════════════════════════════
//  构建
// ══════════════════════════════════════════════════════════════════════

object PagePlanBuilder {

    /** 占位行的 rawType 标记。渲染层据此把它画成 0 高度，而不是画成"未知元件"错误块 */
    const val FILLER_RAW_TYPE = "_filler"

    fun build(page: EcPage): PagePlan {
        val warnings = mutableListOf<String>()
        val floors = mutableListOf<FloorPlan>()
        var cursor = 0

        page.components.forEach { component ->
            val floorRows = when (component.layout) {
                LayoutStrategy.BANNER, LayoutStrategy.GRID ->
                    flatten(component, warnings)

                LayoutStrategy.HORIZONTAL ->
                    horizontal(component)
            }

            if (floorRows.isEmpty()) {
                // 解析期已经过滤过空组件，这里再判一次是为了防御"绕过解析器直接构造"。
                // 直接 return@forEach 而不是丢异常：空楼层只是少一块，不该崩。
                warnings += "楼层 ${component.id} 展平后没有任何行，已跳过"
                return@forEach
            }

            val floorIndex = floors.size
            val rows = floorRows.mapIndexed { local, r ->
                r.copy(floorIndex = floorIndex, localPosition = local)
            }
            floors += FloorPlan(
                component = component,
                startPosition = cursor,
                rows = rows,
            )
            cursor += rows.size
        }

        val plan = PagePlan(
            title = page.title,
            floors = floors,
            rows = floors.flatMap { it.rows },
            warnings = warnings,
        )
        plan.validate()
        return plan
    }

    // ──────────────────────────────────────────────────────────────
    //  展平：BANNER / GRID
    // ──────────────────────────────────────────────────────────────

    /**
     * 把 GRID/BANNER 组件的元件展平成若干行。
     *
     * ─── 一个必须纠正的直觉（本文件第一版就写错了，靠单测抓出来）───
     *
     * 最初的假设是："满行元素若只给 spanSize = spanCount，GridLayoutManager 可能把它
     * 挤进当前行的剩余空间里，导致标题位置不确定"。**这个假设是错的。**
     *
     * 源码 `GridLayoutManager.SpanSizeLookup.getSpanIndex()`（1.3.2，约 987 行）第一句就是：
     * ```java
     * int positionSpanSize = getSpanSize(position);
     * if (positionSpanSize == spanCount) {
     *     return 0; // quick return for full-span items
     * }
     * ```
     * 满 span 的元素**永远从新行的第 0 列开始**，不存在"被挤进半行"的情况。
     * 所以"为了防止标题错位而补齐"这个理由是**假的** —— 单测
     * `满行元素占满整行 且其前面的半行会被补齐` 的断言把这一点暴露了出来。
     *
     * ─── 那补齐到底还需要吗？需要，但理由是另一个（而且更重要）───
     *
     * 展平后所有楼层是**一条连续的位置序列**，楼层之间**没有任何"换行标记"**。
     * `GridLayoutManager` 只做一件事：沿位置顺序不断往里塞，塞不下才换行。
     * 于是出现这个真实问题：
     *
     * > 楼层 A 的最后一行只排了一半（比如 2 列宫格里剩 30 span），
     * > 而楼层 B 的第一个元素恰好也是 30 span → **它会被塞进楼层 A 的那半行里**。
     *
     * 结果就是"楼层 B 的第一个商品跑到了楼层 A 的末尾"，视觉上两个楼层粘在一起。
     * 这不是理论问题：`assets` 里 `c_bad_columns`（6 列、7 个元素）就会以半行结尾。
     *
     * 所以补齐的**真正目的**是：**让每个楼层的 span 总量都是 spanCount 的整数倍**，
     * 从而"楼层边界"在布局层面必然是一条行边界，后面楼层的元素不可能漏进来。
     * 这个不变量是可断言的（见 `PagePlanTest`），而且
     * `plan.validate()` 之外还有单测逐楼层核对它。
     *
     * 两处补齐分别对应两个来源的"残缺行"：
     *   · 满行元素到来时当前行没排满 → 补满（该行被"放弃"的剩余空间）；
     *   · 楼层结束时当前行没排满 → 补满（防止下一个楼层漏进来）。
     *
     * 代价是几个 0 高度的占位 ViewHolder —— 可观测、一次性；换来的是
     * "楼层边界必然对齐"这一确定性，以及一个可被测试钉死的不变量。
     *
     * ⚠️ 为什么不能"把标题的 spanSize 调大来补满"这种更省事的写法：
     * `GridLayoutManager` 会直接抛
     * `IllegalArgumentException: Item at position N requires X spans but GridLayoutManager has only M spans`
     * （`GridLayoutManager.java:575`）。spanSize **不得超过 spanCount** 是硬约束。
     */
    private fun flatten(component: Component, warnings: MutableList<String>): List<Row> {
        val spanPerElement = EcSchema.spanPerElement(component.columns)
            ?: error(
                "组件 ${component.id} 的列数 ${component.columns} 非法。" +
                    "解析期应已夹到合法值；走到这里说明有人绕过了 EcPageParser 直接构造 Component —— " +
                    "这属于编程错误（改代码），不是数据错误（改配置），必须当场响",
            )

        val rows = mutableListOf<Row>()
        var usedSpan = 0

        component.elements.forEach { element ->
            val wantsFullRow = element.needsFullRow || element.type == ElementType.BANNER_IMAGE
            val span = if (wantsFullRow) EcSchema.SPAN_COUNT else spanPerElement

            if (wantsFullRow && usedSpan != 0) {
                // 当前行没排满，而下一个元素要独占整行 → 该行剩余空间会被布局算法放弃。
                // 显式补满，让"楼层内的 span 总量"始终是整屏的整数倍。
                rows += fillerRows(usedSpan, spanPerElement)
                usedSpan = 0
            }

            // floorIndex / localPosition 由 build() 统一回填，这里给 -1 占位。
            // 用哨兵而不是猜 0：万一后面忘了回填，-1 会立刻越界暴露，
            // 而猜 0 会安静地渲染成第 0 楼层（一个几乎发现不了的错渲染）。
            rows += Row(span, -1, -1, element)
            usedSpan = if (wantsFullRow) 0 else ((usedSpan + span) % EcSchema.SPAN_COUNT)
        }

        // ★ 楼层收尾补满：**这是防止"下一个楼层漏进本楼层最后一行"的唯一手段**。
        //   没有它，`c_bad_columns`（以半行结尾）之后那个楼层的首个元素
        //   会被塞进这半行里，两个楼层在视觉上粘连。
        if (usedSpan != 0) {
            rows += fillerRows(usedSpan, spanPerElement)
        }

        if (component.elements.isEmpty()) {
            warnings += "楼层 ${component.id} 没有元件"
        }
        return rows
    }

    /**
     * 生成"把当前行补齐到整行"所需的占位行。
     *
     * 占位元件用 [ElementType.UNKNOWN] + [FILLER_RAW_TYPE] 表达。**为什么复用
     * UNKNOWN 而不新开一个 ElementType**：占位行完全不参与业务语义，
     * 给它一个 enum 值会让人以为它是一种"业务元件"（进而问它该长什么样）。
     * 用 `rawType` 区分更准确 —— `type` 表示"客户端把它当什么画"（当未知处理、
     * 画成 0 高度），`rawType` 表示"它到底是什么"（内部填充物）。
     * 渲染层会先判 `rawType == FILLER_RAW_TYPE` 直接 return，所以它永远不会
     * 走到"未知元件"那条渲染分支上去。
     *
     * @param usedSpan 当前行已占的 span
     */
    private fun fillerRows(usedSpan: Int, spanPerElement: Int): List<Row> {
        val out = mutableListOf<Row>()
        var remaining = EcSchema.SPAN_COUNT - usedSpan
        var index = 0
        while (remaining > 0) {
            val span = minOf(spanPerElement, remaining)
            out += Row(
                spanSize = span,
                floorIndex = -1,
                localPosition = -1,
                element = Element(
                    id = "_filler_$index",
                    type = ElementType.UNKNOWN,
                    rawType = FILLER_RAW_TYPE,
                ),
            )
            remaining -= span
            index++
        }
        return out
    }

    // ──────────────────────────────────────────────────────────────
    //  横滑：HORIZONTAL
    // ──────────────────────────────────────────────────────────────

    /**
     * 横滑楼层：**在外层只占一行**，元素全部交给内嵌的横向 RecyclerView。
     *
     * ─── 为什么横滑不能也展平 ───
     *
     * 展平的前提是"所有元素在同一个滚动方向上线性排列"。横滑元素在**另一个轴**上，
     * 而 GridLayoutManager 只沿竖直方向布局 —— 展平后它们会变成竖排，语义丢失。
     * 所以横滑是三种策略里**唯一真正需要"容器"这一层**的。
     *
     * ─── 代价：展平架构里"组件标题"只对横滑楼层成立 ───
     *
     * 这是本方案的一处**固有不对称**，必须讲清楚：
     *
     * · 横滑楼层有容器 → 容器可以渲染 `component.title`（"限时秒杀"）；
     * · 展平楼层没有容器 → 没有任何 View 承载 `component.title`。
     *
     * 所以展平楼层的标题**只能由 [ElementType.SECTION_HEADER] 元件承担**
     * （见 assets 里的 `e_hdr_rec`）。也就是说：**同一份 schema 里，
     * 标题有两个来源**（组件的 title 字段 / 标题元件），取决于布局策略。
     *
     * 这是设计缺陷还是合理取舍？诚实地说：**是个瑕疵**，但它换来了
     * "标题也参与展平排布"的能力 —— 楼层标题因此可以与其他元素**混排在同一行**
     * （4 列宫格里标题占满一行、旁边就是商品卡），这是纯容器方案做不到的。
     *
     * 收敛办法（已在 schema 层落地）：展平楼层的 `title` 字段**不参与渲染**，
     * 仅作为诊断信息（在页面诊断面板里显示"该楼层标题未渲染，因为它被展平了"）。
     * 宁可显式不渲染 + 说明原因，也不要"有时候渲染有时候不渲染"。
     */
    private fun horizontal(component: Component): List<Row> {
        // 横滑楼层在外层只需要**一行**：这一行承载内嵌的横向列表。
        // 代表元件用第一个元素（渲染层从 floor.component 拿到标题与样式，
        // 这里只需要一个"非占位"的元件让 viewType 解析出正确的类型）。
        val head = component.elements.firstOrNull() ?: return emptyList()
        return listOf(
            Row(
                spanSize = EcSchema.SPAN_COUNT,
                floorIndex = -1,
                localPosition = 0,
                element = head,
            ),
        )
    }
}
