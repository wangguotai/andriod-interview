package com.interview.ec.model

/**
 * Time: 2026/10/18
 * Author: wgt
 * Description: 数据驱动首页的**页面模型** —— 组件（抽屉）与元件（元素）两层。
 *
 * ─── 这一层为什么存在 ───
 *
 * 「组件化首页」最难的地方不是画得快，而是**把"布局"从代码里搬到数据里**。
 * 所以本模型刻意分成两级，并且让"怎么排列"成为**数据的一个字段**：
 *
 * ```
 * 页面
 *  └── 组件 Component   ← 一块区域 + 这块区域**怎么排列**子元素（排列策略 = "抽屉形式"）
 *       └── 元件 Element ← 叶子节点，只描述"我是什么、显示什么"，不描述"我在哪"
 * ```
 *
 * 关键约束（整个 demo 的立论基础）：
 *
 * 1. **元件不知道自己被摆在哪。** [Element] 里没有 width / column / row 之类的字段。
 *    同一个 `PRODUCT` 元件在「宫格 2 列」里是半宽、在「宫格 3 列」里是 1/3 宽、
 *    在「横滑」里是固定宽度 —— 这三种摆放**由组件决定，元件零感知**。
 *    这条约束一旦破了（元件里塞了 span），"数据驱动"就退化成"数据描述布局"。
 *
 * 2. **组件的排列策略是有限枚举**，不是任意表达式。见 [LayoutStrategy]。
 *    有限枚举是刻意的：客户端只需要认识这几种，服务端下发的 `type` 若不在其中，
 *    客户端**必须有确定的降级行为**（见 [EcPageParser]），而不是崩或白屏。
 *
 * ─── 与渲染层的关系（谁读谁）───
 *
 *   本文件（纯模型，无 Android 依赖）
 *        ↓ 被读取
 *   [com.interview.ec.render.PagePlan]   ← 把"组件+元件"翻译成"一行行的 span"
 *        ↓ 被读取
 *   [com.interview.ec.render.EcPageRenderer] ← 装配成 ConcatAdapter
 *
 * 本文件**不依赖任何 Android 类**，因此整层逻辑可以在宿主 JVM 上单测。
 */

// ══════════════════════════════════════════════════════════════════════
//  一、元件（叶子）
// ══════════════════════════════════════════════════════════════════════

/**
 * 元件类型 —— **客户端的"渲染能力集"**。
 *
 * 注意这里的枚举**不是**"服务端能下发什么"，而是"这个客户端会画什么"。
 * 两者的差集就是必须处理的部分：服务端一定会下发新类型（灰度、新玩法），
 * 而老客户端不可能认识它。所以：
 *
 * · 认识的类型 → 一一对应一个 layout（viewType = layout id），见 [com.interview.ec.render.getItemLayoutId]；
 * · 不认识的 → **不丢、不崩**，落成 [UNKNOWN]，保留原始 type 字符串**显示出来**。
 *
 * 为什么降级视图要"显示原始类型名"而不是渲染成空白：
 * 空白会让人以为是数据没下发（去查服务端），而实际上数据到了、只是客户端不认识
 * （该改客户端）。把类型名打在屏幕上，一眼就能把责任方分清楚。
 */
enum class ElementType {
    /** 通栏大图（通常单元素占满整行） */
    BANNER_IMAGE,

    /** 商品卡：图 + 标题 + 价格（价格是电商的核心信息，单列一类） */
    PRODUCT,

    /** 金刚区入口：图标 + 文字，通常以固定列数的宫格出现 */
    ICON_ENTRY,

    /** 楼层标题：主标题 + 副标题 + "更多" */
    SECTION_HEADER,

    /** 促销卡：主色块 + 文案，常用于横滑楼层 */
    PROMO,

    /** 占位降级：客户端不认识的类型 */
    UNKNOWN,
}

/**
 * 一个元件。
 *
 * ─── 为什么字段这么"平"，而不是给每种类型一个独立的 data class ───
 *
 * 真实的首页 schema 是**服务端演进**的：新玩法会不断往元件上加字段。
 * 若客户端为每种元件写一个强类型 data class，那么服务端每加一个字段，
 * 客户端都得跟着改结构体（否则解析失败或字段丢失），灰度期间必然对不上。
 *
 * 所以这里用"**超集 + 可空**"：所有元件的字段并集，各类型只用其中一部分。
 * 好处是老客户端遇到新字段**默认忽略**（Gson 本来就会忽略未知字段），
 * 事件上真正需要的是"**这个类型要用的字段有没有值**" —— 这交给渲染层判断
 * （比如 [PRODUCT] 没有 imageUrl 就画占位灰块），而不是交给解析层拒绝。
 *
 * 代价也要说清楚：**类型安全变弱了**。`PRODUCT` 缺 `priceCents` 这种情况
 * 编译器不会拦。这是拿编译期安全换运行时的向前兼容，是刻意的取舍。
 *
 * @param type 客户端**认识**的类型；不认识的一律为 [ElementType.UNKNOWN]
 * @param rawType 服务端下发的原始类型字符串。**只有** [type] == [UNKNOWN] 时有意义，
 *                但它始终保留 —— 便于打点上报"哪个新类型被降级了"。
 * @param priceCents 价格，单位「分」。**必须用整数存钱**，见 [PriceFormatter]。
 */
data class Element(
    val id: String,
    val type: ElementType,
    val rawType: String,
    val title: String? = null,
    val subtitle: String? = null,
    val imageUrl: String? = null,
    val priceCents: Int? = null,
    val originalPriceCents: Int? = null,
    val badge: String? = null,
    /** 点击目标（路由串）。本 demo 只弹出来展示，不真跳转 —— 路由不是本 Lab 的命题 */
    val target: String? = null,
    /**
     * **对自身的要求**：我需要独占一整行。
     *
     * ─── 这个字段为什么不算"破坏封装" ───
     *
     * 前面反复强调"元件不知道自己被摆在哪"。那这个字段是不是自我打脸？不是 ——
     * 关键在于它是**关于元件自己的陈述**，而不是**坐标**：
     *
     * · 它说的是 `grid-column: 1 / -1`（"我要横跨全部列"），
     *   **不是** `row 3, col 2`（"我在第 3 行第 2 列"）。
     * · 元件仍然不知道自己会落在第几行、屏幕有多宽、组件是几列。
     *   `GRID(columns = 4)` 里它是 12/12，`GRID(columns = 2)` 里它**还是** 12/12，
     *   元件侧代码一个字都不用改。
     *
     * CSS 的 `flex-basis` / `grid-column: span 2` 正是这个模型：
     * 子元素表达需求，父容器做最终裁决。若连这个都不允许，那么
     * 「楼层标题 + 商品卡混排」就只能靠"给标题单开一个组件"来绕，
     * schema 会立刻退化成"每个视觉块一个组件"，组件化就名存实亡了。
     *
     * @see com.interview.ec.render.PagePlan 里它是怎么被裁决成具体 span 的
     */
    val needsFullRow: Boolean = false,
)

// ══════════════════════════════════════════════════════════════════════
//  二、组件（抽屉 / 容器）
// ══════════════════════════════════════════════════════════════════════

/**
 * 排列策略 —— 组件**怎么摆放**它的子元素。这就是需求里说的"抽屉形式"。
 *
 * 三种策略在渲染层的实现方式**根本不同**，这不是风格差异，而是架构分叉：
 *
 * | 策略 | 子元素去哪 | 有"容器"吗 | ViewHolder 复用边界 |
 * |---|---|---|---|
 * | [BANNER] | 展平进外层 RecyclerView | ❌ 无 | 与外层其它楼层**同池**（视配置） |
 * | [GRID] | 展平进外层 RecyclerView，按列数占 span | ❌ 无 | 同上 |
 * | [HORIZONTAL] | 塞进一个**内嵌**的横向 RecyclerView | ✅ 有 | 与其它横滑楼层共享一个池（视配置） |
 *
 * `BANNER` 与 `GRID` 在实现上其实是**同一件事**（都是展平 + 占若干 span），
 * 单独留下 [BANNER] 是因为它表达的是**语义**（通栏、单元素、整屏焦点），
 * 而不是新的布局能力 —— 它等价于 `GRID(columns = 1)`。
 * 数据里这么写，是为了让服务端下发时可读；渲染层会把两者归一到同一条代码路径。
 *
 * 这个"**语义与能力分离**"是组件化 schema 设计里最容易被做歪的一处：
 * 一旦为 `BANNER` 写一套独立的渲染代码，接下来 `BIG_PIC_L1`、`BIG_PIC_L2`
 * 就会各来一套，能力集就从"3 种排列"膨胀成"无数种专题页"。正确做法是
 * **排列能力收敛成有限枚举，语义靠数据组合表达**。
 */
enum class LayoutStrategy {
    /** 通栏：子元素逐个占满整行（等价于 GRID 单列，保留独立语义） */
    BANNER,

    /** 宫格：按 [Component.columns] 列摆放，每个子元素占 spanCount / columns 个 span */
    GRID,

    /** 横滑：子元素放进内嵌的横向 RecyclerView，组件本身在外层只占 1 个 item */
    HORIZONTAL,
}

/**
 * 组件（"抽屉"）：一块区域 + 这块区域怎么排列子元素。
 *
 * @param columns 仅对 [LayoutStrategy.GRID] 有意义。必须是 [EcSchema.LEGAL_COLUMNS] 之一；
 *                非法值会在**解析期**被夹到最近合法值并留下一条 warning（不静默吞掉）。
 * @param title 组件标签。⚠️ **只有 [LayoutStrategy.HORIZONTAL] 会渲染它** ——
 *              因为只有它有"容器"这个 View。展平的组件没有容器，标签只能由
 *              [ElementType.SECTION_HEADER] 元件承担。这个不对称是展平架构的
 *              **固有代价**，不是 bug，详见 render 层文档。
 */
data class Component(
    val id: String,
    val layout: LayoutStrategy,
    val rawType: String,
    val columns: Int,
    val style: ComponentStyle,
    val title: String? = null,
    val elements: List<Element>,
)

/**
 * 组件外观。刻意只留"布局容器能直接生效"的字段，不做万能 style。
 *
 * 真实项目里这个对象会迅速膨胀成一个小型 CSS，然后失控。
 * 本 demo 只放三个真的会被容器用到的：背景、内边距、是否显示卡片圆角。
 */
data class ComponentStyle(
    /** 背景色，`#RRGGBB` / `#AARRGGBB`；空则用页面底色 */
    val background: String? = null,
    /** 容器内边距（dp） */
    val paddingDp: Int = 0,
    /** 是否作为"卡片"呈现（白色圆角 + 外边距）。展平组件靠它把每个元件框成卡片 */
    val card: Boolean = true,
)

// ══════════════════════════════════════════════════════════════════════
//  三、schema 契约常量
// ══════════════════════════════════════════════════════════════════════

/**
 * schema 层的**共享常量** —— 服务端与客户端都要遵守的契约。
 *
 * 放在 model 层而不是 render 层，是为了让依赖方向保持单向：
 * `model`（纯逻辑，可单测） ← `render`（依赖 Android）。
 * 如果基准 span 定义在 render 层，那么 [EcPageParser]（model 层）
 * 校验列数时就得反向依赖 render 层 —— 一个纯 JSON 解析器被迫拖上 Android 依赖，
 * 单测也就跑不起来了。
 */
object EcSchema {

    /**
     * 渲染层基准 span，即外层 `GridLayoutManager` 的 spanCount。
     *
     * ─── 为什么是 60，而不是更"常识"的 12 ───
     *
     * 这是本 demo 最值得讲的一个设计推导，因为它来自一条**硬约束**：
     *
     * > 一个 RecyclerView 只有**一个** GridLayoutManager，也就只有**一个** spanCount。
     *
     * 而首页里不同楼层要用不同列数（金刚区 5 列、推荐 2 列、清单 4 列……）。
     * 要让同一套 spanCount 同时容纳所有列数，唯一办法是：
     *
     * > **spanCount 必须能被每一种用到的列数整除**。
     *
     * 若按"12"来定：12 的约数是 1/2/3/4/6/12 —— **5 不是**。而 5 列恰恰是
     * 金刚区最典型的列数。用 12 的话，5 列只能算成 `12/5 = 2`（取整），
     * 5 个元素只占 10 span，每行**右侧会空掉 2/12**，且列宽与其它楼层不一致。
     *
     * 所以取支持列数的**最小公倍数**：`lcm(1,2,3,4,5,6) = 60`。
     * 于是每种列数都能**整除**、没有任何取整误差：
     *
     * | 列数 | 每元素 span |
     * |---|---|
     * | 2 | 30 |
     * | 3 | 20 |
     * | 4 | 15 |
     * | 5 | **12** |
     * | 6 | 10 |
     *
     * ─── 代价（必须说清楚，否则这条设计就成了"银弹"）───
     *
     * · spanCount 变大后，GridLayoutManager 内部 `mCachedBorders` 数组也变大
     *   （61 个 float），并且 `getSpanIndex` 在未命中缓存时要循环累加 span。
     *   实测对 60 这个量级影响可忽略（每行最多循环 60 次整数加法），
     *   但它**确实是个成本**，再往上加（比如为了让 7 列也可用而取 420）
     *   就该考虑换方案了。
     * · 服务端**不需要**知道 60 这个数 —— 它下发的仍是"几列"，
     *   span 换算完全在客户端（见 [com.interview.ec.render.PagePlan]）。
     *   协议常量与渲染参数解耦，这也是 [BASE_SPAN] 与列数分开定义的原因。
     */
    const val SPAN_COUNT = 60

    /**
     * 允许的宫格列数 —— 定义为 **[SPAN_COUNT] 的约数**，而不是手写一份清单。
     *
     * 这是刻意的"**约束即定义**"：只要写了 `SPAN_COUNT % c == 0`，
     * 就**不可能**出现"某个合法列数除不尽基准"的组合 —— 因为那种组合压根
     * 进不了这个集合。手写清单则不然：今天写 `setOf(1,2,3,4,6)`，
     * 明天有人把 SPAN_COUNT 改成 12，清单不会有任何报错，但 5 列就悄悄坏了。
     *
     * 上界取 6：再多的列在手机宽度下每列不足 60dp，图标/文字已不可读，
     * 列数再大不是"能力"而是"事故"。
     */
    val LEGAL_COLUMNS: List<Int> = (1..6).filter { SPAN_COUNT % it == 0 }

    /** 服务端可下发的组件类型契约名（与 [EcPageParser] 的映射保持一致） */
    val COMPONENT_TYPES: Set<String> = setOf("banner", "grid", "horizontal")

    /** 某个列数下，每个元素应占的 span。列数非法时返回 null（由调用方决定降级） */
    fun spanPerElement(columns: Int): Int? =
        if (columns in LEGAL_COLUMNS) SPAN_COUNT / columns else null
}

// ══════════════════════════════════════════════════════════════════════
//  四、整个页面 + 解析结果
// ══════════════════════════════════════════════════════════════════════

/** 一个页面 = 一串组件（顺序即展示顺序，服务端可任意重排 —— 这就是"组件化"的价值） */
data class EcPage(
    val title: String,
    val components: List<Component>,
)

/**
 * 解析结果 —— **带警告的返回值**，而不是"要么成功要么抛异常"。
 *
 * 为什么解析必须能"部分成功"：
 * 服务端下发的是一个**整体 JSON**，其中任何一处不合法（多了一个未知 type、
 * 某个 GRID 列数写成了 7）都不该让**整页白屏**。电商首页一次白屏就是一次事故，
 * 而某个楼层挂了只是少一块。所以策略是：
 *
 * · **元件级**问题 → 降级渲染（[ElementType.UNKNOWN] 占位），页面照常；
 * · **组件级**问题 → 丢弃该组件 + 记录 warning，其余楼层照常；
 * · **整页级**问题（JSON 语法错）→ 才返回 [EcPageParseResult.Failure]。
 *
 * [warnings] 会被渲染到页面的诊断面板上 —— **不是只打日志**。
 * 日志在真机上要连 adb 才看得到，而"哪些楼层被降级了"是产品/测试必须当场知道的事。
 */
sealed class EcPageParseResult {
    data class Success(
        val page: EcPage,
        val warnings: List<ParseWarning>,
    ) : EcPageParseResult()

    data class Failure(
        val message: String,
    ) : EcPageParseResult()
}

/**
 * 一条降级/修正记录。
 *
 * @param code 稳定的机器可读码（用于打点聚合，别用 message 做聚合键 —— 文案会改）
 * @param where 出问题的位置，如 `components[3].elements[1]`
 * @param detail 人能读的说明。**必须包含"我们做了什么"**，否则看的人不知道
 *               这条是"丢了"还是"改了"。
 */
data class ParseWarning(
    val code: String,
    val where: String,
    val detail: String,
) {
    companion object {
        const val CODE_UNKNOWN_COMPONENT = "unknown_component"
        const val CODE_UNKNOWN_ELEMENT = "unknown_element"
        const val CODE_BAD_COLUMNS = "bad_columns"
        const val CODE_EMPTY_COMPONENT = "empty_component"
        const val CODE_DUPLICATE_ID = "duplicate_id"
        const val CODE_MISSING_ID = "missing_id"
        const val CODE_SCHEMA_VERSION = "schema_version"
    }
}

// ══════════════════════════════════════════════════════════════════════
//  五、价格格式化（纯函数，单测覆盖）
// ══════════════════════════════════════════════════════════════════════

/**
 * 价格格式化。
 *
 * ─── 为什么价格必须用"分"（Int）而不是 Double ───
 *
 * `0.1 + 0.2 != 0.3` 是浮点数的常识，但真正的坑在**比较与累加**：
 * 限时秒杀要判断"现价 < 原价"，用 Double 做会有 `1299.0 < 1299.0` 为 true 的
 * 边界翻车（两个不同的十进制数落进同一个 double）。金额用整数分是硬规矩，
 * 格式化只发生**显示的最后一步**。
 *
 * ─── 格式化规则（按电商惯例，不是四舍五入到两位）───
 *
 * 电商展示短、且不显示无意义的零：
 *   · 整元      129900  → `¥1,299`      （不显示 `.00`）
 *   · 一位小数   19990  → `¥199.9`
 *   · 两位小数    1999  → `¥19.99`
 *
 * 判定依据是「分位是否为 0」而不是"看起来像什么"，这样对任何金额都是确定的。
 */
object PriceFormatter {

    fun format(cents: Int): String {
        val negative = cents < 0
        val abs = if (negative) -cents.toLong() else cents.toLong()

        val yuan = abs / 100
        val fen = (abs % 100).toInt()

        val body = when {
            fen == 0 -> group(yuan)
            fen % 10 == 0 -> "${group(yuan)}.${fen / 10}"
            else -> "${group(yuan)}.${fen.toString().padStart(2, '0')}"
        }
        return (if (negative) "-¥" else "¥") + body
    }

    /** 千分位分组。手写而不用 String.format("%.2f")：后者要过 Locale，中东/欧洲会出逗号小数点 */
    private fun group(value: Long): String {
        val s = value.toString()
        if (s.length <= 3) return s
        val sb = StringBuilder()
        val firstGroup = s.length % 3
        if (firstGroup != 0) sb.append(s, 0, firstGroup)
        var i = firstGroup
        while (i < s.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(s, i, i + 3)
            i += 3
        }
        return sb.toString()
    }
}
