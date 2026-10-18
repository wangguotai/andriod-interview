package com.interview.ec.render

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.interview.ec.model.Component
import com.interview.ec.model.EcSchema
import com.interview.ec.model.Element
import com.interview.ec.model.ElementType
import com.interview.ec.model.LayoutStrategy
import com.interview.ec.model.PriceFormatter

/**
 * Time: 2026/10/18
 * Author: wgt
 * Description: 展平方案的**渲染层** —— 一个楼层一个 sub-adapter，用 ConcatAdapter 首尾相接。
 *
 * ─── 装配图（务必对着这张图读代码）───
 *
 * ```
 * RecyclerView（唯一的，GridLayoutManager，spanCount = EcSchema.SPAN_COUNT = 60）
 *   │  adapter = ConcatAdapter(floor0, floor1, floor2, ...)      ← 各楼层首尾相接
 *   │  spanSizeLookup = { pos -> plan.rows[pos].spanSize }        ← 占位直接查表
 *   │
 *   ├── FloorAdapter(楼层0)   items = plan.floors[0].rows
 *   ├── FloorAdapter(楼层1)   items = plan.floors[1].rows
 *   └── ...
 *        └── 横滑楼层的 SubAdapter 内部再挂一个横向 RecyclerView
 * ```
 *
 * ─── 三个关键决定，每一个都有源码依据 ───
 *
 * **① 楼层 = sub-adapter（而不是"元件类型 = sub-adapter"）**
 *
 * ConcatAdapter 是把若干个**完整 Adapter 首尾相接**：`getItemCount()` 就是各
 * wrapper 的 count 累加（`ConcatAdapterController`），它**没有**按类型重排的能力。
 * 所以若按元件类型分段（"所有商品卡一段"），页面顺序会变成"先所有商品、
 * 再所有图标"，楼层顺序彻底错乱。**连续性只能用"楼层 = 一段连续位置"来保证。**
 *
 * **② 跨楼层复用靠 `isolateViewTypes = false`，不靠分段**
 *
 * 默认配置下 ConcatAdapter 会把各 sub-adapter 的 viewType **偏移隔离**
 * （`IsolatedViewTypeStorage.obtainViewType()`，见 `ViewTypeStorage.java`），
 * 于是**每个 sub-adapter 拥有独立的 ViewHolder 池**（`ConcatAdapter.java` 的类注释
 * 明确写了 "each added adapter will have its own isolated pool of ViewHolders,
 * with no re-use in between"）。
 *
 * 后果很隐蔽：**不报错、只是变慢**。首页有 8 个楼层就有 8 个池，
 * 而商品卡在不同楼层长得完全一样，本该复用却被池边界挡死。
 *
 * 解法是 `Config.Builder().setIsolateViewTypes(false)`：此时各 adapter 的 viewType
 * **直接共用**（`SharedIdRangeViewTypeStorage.localToGlobal()` 原样返回 localType），
 * 于是 `RecyclerView` 的池按 viewType 索引 → **同种元件跨楼层复用**。
 *
 * ⚠️ **用它的前提（必须写清楚，否则这是个会炸的开关）**：
 * 共用 viewType 要求"**同一个 viewType 在所有 adapter 里都创建同一种 ViewHolder**"。
 * 所以本实现把 viewType 严格定义为 `getItemLayoutId(element)` —— 同一个 viewType
 * 必然对应同一个 layout 文件，`onCreateViewHolder` 因此不可能不一致。
 * 若哪天有人写成"同一个 viewType 下按组件不同 inflate 不同布局"，
 * 复用就会拿到错误的 ViewHolder。**viewType 必须只由 layout 决定。**
 *
 * **③ 共享池跨 LayoutManager 是安全的（已核实源码）**
 *
 * 横滑楼层的内层是 `LinearLayoutManager`，外层是 `GridLayoutManager`。
 * 两者共用 `RecyclerViewPool` 时会互相拿到对方的 ViewHolder ——
 * 而它们的 `LayoutParams` 类型不同（`GridLayoutManager.LayoutParams` vs
 * `RecyclerView.LayoutParams`），GridLayoutManager 会**硬转** LayoutParams
 * （`GridLayoutManager.java:624` 等处的 `(LayoutParams) view.getLayoutParams()`）。
 * 直觉上这会 ClassCastException。
 *
 * 但 `RecyclerView` 在**每次取用 ViewHolder 时**都会重新校验并按需重建
 * LayoutParams（`RecyclerView.java:6926`）：
 * ```
 * } else if (!checkLayoutParams(lp)) {
 *     rvLayoutParams = (LayoutParams) generateLayoutParams(lp);
 *     holder.itemView.setLayoutParams(rvLayoutParams);
 * }
 * ```
 * 复用的 ViewHolder 拿到的 LayoutParams **一定是当前 LayoutManager 的类型**。
 * 所以跨 LayoutManager 共享池是安全的 —— 但这个安全性**依赖源码行为，
 * 不依赖我们的代码正确**，所以它值得被记录（见 `docs/` 与本文件注释）。
 */
class PagePlanRenderer(
    private val recyclerView: RecyclerView,
    private val plan: PagePlan,
    /**
     * ⚠️ 这个参数是**实验变量**，不是为了生产可配：
     * 它对应 ConcatAdapter 的 `isolateViewTypes`，用来做 A/B 对照
     * （开着 = 每楼层独立池；关掉 = 跨楼层共享池）。
     * 见类注释 ② 的说明 —— 生产环境应固定为 `false`。
     */
    private val isolateViewTypes: Boolean = false,
    private val stats: RenderStats = RenderStats(),
    private val onElementClick: (Element, Component) -> Unit,
) {

    private val floorAdapters: List<FloorAdapter> = plan.floors.map { floor ->
        FloorAdapter(
            floor = floor,
            // 横滑楼层的内层列表与**外层**共用同一个池 ——
            // 这是"扫过横滑卡片后，竖直方向也能命中同一批 ViewHolder"的前提。
            sharedPool = recyclerView.recycledViewPool,
            stats = stats,
            onElementClick = onElementClick,
        )
    }

    private val concatAdapter: ConcatAdapter

    init {
        // debug 下先自检：rows 与各楼层行数必须严格对齐，否则 span 会算到错误的 item 上
        // （表现是"某些卡片宽度莫名不对"而不是崩溃，所以必须主动炸出来）。
        plan.validate()

        concatAdapter = ConcatAdapter(
            ConcatAdapter.Config.Builder()
                // ★ 实验变量。默认 false = 各 adapter 的 viewType 直接共用
                //（`SharedIdRangeViewTypeStorage.localToGlobal()` 原样返回 localType），
                // 于是同一个 layout 在不同楼层里命中**同一个池**。
                .setIsolateViewTypes(isolateViewTypes)
                .build(),
            *floorAdapters.toTypedArray(),
        )

        recyclerView.layoutManager = GridLayoutManager(recyclerView.context, EcSchema.SPAN_COUNT).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    // 查表：每个 item 占多少列，在 plan 阶段就算好了。
                    // 这里**不做任何判断**（没有 if-else 布局逻辑）——
                    // 布局规则的唯一真相在 PagePlanBuilder 里。
                    //
                    // 越界保护是必要的：SpanSizeLookup 可能在 recycle 过程中
                    // 被用**旧位置**调用（数据刚变、布局还没跟上的那一瞬），
                    // 此时越界会直接崩在布局阶段。返回 1 是安全兜底
                    //（最坏情况是某个 item 变窄一帧，随后就会被正常布局覆盖）。
                    return plan.rows.getOrNull(position)?.spanSize ?: 1
                }
            }.apply {
                // 打开 span index 缓存：本页数据是**一次性构建、不增量更新**的，
                // 缓存不可能失效。这是展平方案里能拿到的、容器方案拿不到的红利
                // —— 因为占位完全由静态数据决定。
                //
                // ⚠️ 一旦将来支持"下拉刷新局部更新 span"，**必须**调用
                // invalidateSpanIndexCache()，否则会拿旧 span 算新位置，
                // 表现为"刷新后某几行错位"。这是本开关唯一的坑。
                isSpanIndexCacheEnabled = true
            }
        }
        recyclerView.adapter = concatAdapter
        recyclerView.setHasFixedSize(false)
    }

    /** 供 Activity 打点/诊断用：当前装配出来的 item 总数 */
    val assembledItemCount: Int get() = concatAdapter.itemCount

    /**
     * 各楼层的"池命中"观测点。
     *
     * 为什么要把池状态暴露出来：`isolateViewTypes` 开/关的差异**只在滚动时才看得出来**
     * （ViewHolder 的创建次数）。把它读出来打点到页面诊断面板上，
     * 学生就能亲眼看到"隔离时各池各自为政、共享时同种元件的池是同一个"，
     * 而不是只能听我们说。
     */
    fun poolStats(): String {
        val pool = recyclerView.recycledViewPool
        val byType = plan.floors
            .flatMap { it.rows }
            .map { elementViewType(it.element) }
            .distinct()
            .sorted()
            .joinToString("\n") { type ->
                val name = viewTypeName(type)
                "  viewType=$type $name → 池中 ${pool.getRecycledViewCount(type)} 个"
            }
        return "RecycledViewPool（按 viewType 索引，跨楼层共用）:\n$byType"
    }
}

// ══════════════════════════════════════════════════════════════════════
//  观测：ViewHolder 创建次数
// ══════════════════════════════════════════════════════════════════════

/**
 * ViewHolder 创建次数的计数器。
 *
 * ─── 为什么必须把这个数打出来，而不是"相信池是共享的" ───
 *
 * `isolateViewTypes` 开/关的差异**只在滚动过程中体现**：
 * 隔离时，商品卡在第 1 个楼层创建的 ViewHolder 不能给第 2 个楼层用，
 * 于是同样滚一屏，`onCreateViewHolder` 被调用的次数**明显更多**。
 *
 * 但"更多"是个主观感受 —— 而这个问题**不会报错、不会崩溃、不会掉帧到肉眼可辨**
 * （除非列表很长），极易被"感觉还行"糊弄过去。所以把它变成屏幕上的数字：
 * 滚动到底，同一个动作，切换开关前后对比 `product=` 后面的数。
 * 数字差多少，就是"隔离"的代价。
 *
 * 这就是本 Lab 与"写个 demo 看看效果"的区别：**每个结论都有可读的输出**。
 */
class RenderStats {
    private val createCounts = LinkedHashMap<Int, Int>()

    fun recordCreate(viewType: Int) {
        createCounts[viewType] = (createCounts[viewType] ?: 0) + 1
    }

    fun reset() = createCounts.clear()

    fun summary(): String {
        if (createCounts.isEmpty()) return "createViewHolder 次数：（还没创建过，先滚动列表）"
        val parts = createCounts.entries
            .sortedBy { it.key }
            .joinToString("  ") { (type, count) ->
                "${viewTypeStringLabel(type)}=$count"
            }
        val total = createCounts.values.sum()
        return "createViewHolder 次数（越少越好，共用池应显著更少）：\n  $parts\n  合计=$total"
    }

    private fun viewTypeStringLabel(viewType: Int): String = when (viewType) {
        R.layout.ec_item_banner -> "banner"
        R.layout.ec_item_product -> "product"
        R.layout.ec_item_icon_entry -> "icon"
        R.layout.ec_item_section_header -> "header"
        R.layout.ec_item_promo -> "promo"
        R.layout.ec_item_unknown -> "unknown"
        R.layout.ec_item_filler -> "filler"
        R.layout.ec_floor_horizontal -> "h_floor"
        else -> "v$viewType"
    }
}

// ══════════════════════════════════════════════════════════════════════
//  viewType 定义：**只由 layout 决定**（这是共享池成立的前提，见类注释 ②）
// ══════════════════════════════════════════════════════════════════════

/**
 * 元件 → viewType。**返回 layout 资源 id 本身**。
 *
 * 用 layout id 当 viewType 而不是自定义常量，有三个好处：
 * 1. 天然保证"一个 viewType 对应一个 layout"（共享池的硬前提，不可能写歪）；
 * 2. `onCreateViewHolder` 里直接 `inflate(viewType)`，不需要 when 映射；
 * 3. 排查时 viewType 是个人能看懂的资源名（见 [viewTypeName]），
 *    而不是"3"这种要靠猜的序号。
 *
 * 占位行（`rawType == _filler`）单独给一个 0 高度布局：它不是"未知元件"，
 * 画成未知元件会让页面上出现一排"未支持"的错块。
 */
internal fun elementViewType(element: Element): Int =
    if (element.rawType == PagePlanBuilder.FILLER_RAW_TYPE) R.layout.ec_item_filler
    else getItemLayoutId(element)

internal fun getItemLayoutId(element: Element): Int = when (element.type) {
    ElementType.BANNER_IMAGE -> R.layout.ec_item_banner
    ElementType.PRODUCT -> R.layout.ec_item_product
    ElementType.ICON_ENTRY -> R.layout.ec_item_icon_entry
    ElementType.SECTION_HEADER -> R.layout.ec_item_section_header
    ElementType.PROMO -> R.layout.ec_item_promo
    ElementType.UNKNOWN -> R.layout.ec_item_unknown
}

/** viewType 的人读名，仅用于诊断输出 */
private fun viewTypeName(viewType: Int): String = when (viewType) {
    R.layout.ec_item_banner -> "banner"
    R.layout.ec_item_product -> "product"
    R.layout.ec_item_icon_entry -> "icon_entry"
    R.layout.ec_item_section_header -> "section_header"
    R.layout.ec_item_promo -> "promo"
    R.layout.ec_item_unknown -> "unknown"
    R.layout.ec_item_filler -> "filler"
    R.layout.ec_floor_horizontal -> "horizontal_floor"
    else -> "?(未知 viewType)"
}

// ══════════════════════════════════════════════════════════════════════
//  楼层 Adapter
// ══════════════════════════════════════════════════════════════════════

/**
 * 一个楼层 = 一个 sub-adapter。
 *
 * ⚠️ **不要 `setHasStableIds(true)`**：`ConcatAdapter` 默认的
 * `StableIdMode.NO_STABLE_IDS` 会**忽略** sub-adapter 的 stable id
 * （`ConcatAdapter` 类注释："Calling `setHasStableIds(boolean)` has no effect"），
 * 而且 `NestedAdapterWrapper` 还会对"有 stable id 的子 adapter"打警告。
 * 想用 stable id 必须在 `Config` 里选 `ISOLATED_STABLE_IDS` / `SHARED_STABLE_IDS`，
 * 且**所有** sub-adapter 都必须实现 —— 我们没这个需求（本页一次性构建、不做局部动画），
 * 所以干脆不用，省掉一整套"id 隔离"的心智负担。
 */
internal class FloorAdapter(
    private val floor: FloorPlan,
    private val sharedPool: RecyclerView.RecycledViewPool,
    private val stats: RenderStats,
    private val onElementClick: (Element, Component) -> Unit,
) : RecyclerView.Adapter<FloorAdapter.Holder>() {

    /** 横滑楼层才需要：承载元素的内层列表。展平楼层里它是 null */
    private val innerAdapter: ElementRowAdapter? =
        if (floor.component.layout == LayoutStrategy.HORIZONTAL) {
            ElementRowAdapter(
                elements = floor.component.elements,
                stats = stats,
                onClick = onElementClick,
            )
        } else null

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        init {
            itemView.setOnClickListener {
                // 用 bindingAdapterPosition 而不是 adapterPosition：
                // ConcatAdapter 下前者会做全局→局部转换（`getBindingAdapterPosition`
                // 的语义就是"当前这个 ViewHolder 是被哪个 sub-adapter 绑的"），
                // 而 adapterPosition 拿到的是**全局**位置，
                // 直接拿去索引本楼层的数据必然越界。
                val local = bindingAdapterPosition
                if (local != RecyclerView.NO_POSITION) {
                    val element = floor.rows.getOrNull(local)?.element ?: return@setOnClickListener
                    if (element.rawType == PagePlanBuilder.FILLER_RAW_TYPE) return@setOnClickListener
                    onElementClick(element, floor.component)
                }
            }
        }
    }

    override fun getItemCount(): Int = floor.rows.size

    override fun getItemViewType(position: Int): Int {
        // 横滑楼层：整层只占外层一个 item，viewType 用"容器布局"
        if (floor.component.layout == LayoutStrategy.HORIZONTAL) return R.layout.ec_floor_horizontal
        return elementViewType(floor.rows[position].element)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        // inflate(viewType)：viewType 就是 layout id，见 elementViewType 的说明。
        // 这里**没有 when 分支** —— 少一个分支就少一处"加了新元件类型忘了加分支"的空指针。
        stats.recordCreate(viewType)
        val view = LayoutInflater.from(parent.context).inflate(viewType, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = floor.rows[position]
        val element = row.element

        // ── 横滑楼层：绑定容器（标题 + 内层列表）──
        if (floor.component.layout == LayoutStrategy.HORIZONTAL) {
            bindHorizontalFloor(holder.itemView, floor.component, innerAdapter!!)
            return
        }

        // ── 占位行：0 高度，什么都不画 ──
        // 必须显式 return：它的 type 是 UNKNOWN，若不拦住会走进 bindUnknown
        // 画出一个"未知元件"错块 —— 页面上会凭空出现一排红框。
        if (element.rawType == PagePlanBuilder.FILLER_RAW_TYPE) return

        // ★ 每次 bind 都把宽度重置为 match_parent：**让 span 来决定宽度**。
        //
        // ─── 为什么必须有这一步（共享池 + 两个 LayoutManager 的真实冲突）───
        //
        // 内层横向列表会给卡片写一个**固定宽度**（`ElementRowAdapter.applyHorizontalWidth`），
        // 而池是**跨 LayoutManager 共享**的（见 PagePlanRenderer 类注释 ③）。
        // 于是可能出现：一个在内层被改成 396px 的 ViewHolder，被外层宫格复用。
        //
        // 此时 `RecyclerView` 的重建逻辑**救不了我们**（已核对源码
        // `RecyclerView.java:6926` 一带）：
        // ```java
        // } else if (!checkLayoutParams(lp)) {
        //     rvLayoutParams = (LayoutParams) generateLayoutParams(lp);   // ← 拷贝！
        //     holder.itemView.setLayoutParams(rvLayoutParams);
        // }
        // ```
        // `checkLayoutParams` 判的是"是不是本 LayoutManager 的 LayoutParams 类型"，
        // 内层是 `RecyclerView.LayoutParams`、外层要 `GridLayoutManager.LayoutParams`，
        // 类型不符 → 走 `generateLayoutParams(lp)` **拷贝**一份新的。
        // 而 `LayoutParams(ViewGroup.LayoutParams source)` 会把 **width=396 一起拷过去**
        // （`RecyclerView.java:12544` 的 super 调用）。
        // 随后 `GridLayoutManager.measureChild` 用 `lp.width` 直接算 MeasureSpec
        // （`GridLayoutManager.java:748` → `getChildMeasureSpec`，
        //  `childDimension >= 0` 时给 **EXACTLY 396px**），**完全绕过 span 计算的宽度**。
        //
        // 结果就是"某些卡片宽度莫名其妙不对"，而且**只在高频滚动、
        // 恰好复用到一个来自内层的 ViewHolder 时出现** —— 不复现、不报错。
        //
        // 修法：**让"谁摆放谁写尺寸"在每个宿主里都成立**。
        // 外层 bind 时把宽度恢复成 match_parent，内层 bind 时写成固定 dp。
        // 因为 bind 一定发生在 measure 之前，两个方向都被覆盖，池里流出的
        // 陈旧尺寸不可能活到测量阶段。
        resetWidthToMatchParent(holder.itemView)

        bindElement(holder.itemView, element)
    }

    /** 把宽度恢复成 match_parent（span 布局的前提）。只在需要时写，避免多余 requestLayout */
    private fun resetWidthToMatchParent(view: View) {
        val lp = view.layoutParams ?: return
        if (lp.width == RecyclerView.LayoutParams.MATCH_PARENT) return
        lp.width = RecyclerView.LayoutParams.MATCH_PARENT
    }

    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        // Glide 的请求必须随复用取消：否则快速滚动时，一个 ImageView 上会
        // 挂着一串"从各楼层发起、还没回来的"请求，它们回来后会依次把图设进去
        // —— 表现是"图片乱跳"。这是列表 + 图片加载最经典的错，
        // 而且只在快速滚动时才偶发，很难复现。
        holder.itemView.findImageViews().forEach { Glide.with(it).clear(it) }
    }

    /**
     * 横滑楼层的滚动位置记忆。
     *
     * ─── 为什么必须自己记（这是内嵌列表方案的固有代价）───
     *
     * 外层 RecyclerView 在回收楼层 ViewHolder 时，**不会**替内层 RecyclerView
     * 保存/恢复它的滚动位置 —— 外层只管自己的 `layoutPosition`，
     * 内层的滚动偏移是内层自己的状态，外层没有任何理由知道它。
     * 所以在"横滑楼层被滚出屏幕 → ViewHolder 被回收 → 再滚回来"之后，
     * 内层列表会回到**第 0 个**位置：
     *
     * > 用户横滑到第 5 个商品，往下滚一屏再滚回来，发现又回到了第 1 个。
     *
     * 这个问题的恶劣之处在于**它只在特定操作序列下出现**（必须让楼层离开屏幕
     * 再回来），随手滑两下测不出来，但用户一定会遇到。
     *
     * 修法：按楼层 id 记住"内层第一个可见位置"，重新 bind 时恢复。
     * 按 **楼层 id**（而不是 ViewHolder 或 adapter 实例）作 key 是关键：
     * ViewHolder 会被复用给别的楼层，若挂在 ViewHolder 上，
     * 楼层A 的位置会被搬到楼层B 去恢复 —— 那比不复原更糟。
     */
    private val horizontalScrollPositions = mutableMapOf<String, Int>()

    private fun bindHorizontalFloor(itemView: View, component: Component, adapter: ElementRowAdapter) {
        val title = itemView.findViewById<TextView>(R.id.ec_floor_title)
        val more = itemView.findViewById<TextView>(R.id.ec_floor_more)
        val rv = itemView.findViewById<RecyclerView>(R.id.ec_floor_list)

        // 只有横滑楼层有容器来承载 title —— 展平楼层的标题必须由
        // SECTION_HEADER 元件承担。这个不对称见 PagePlanBuilder.horizontal 的说明。
        title.text = component.title
        title.visibility = if (component.title.isNullOrBlank()) View.GONE else View.VISIBLE

        more.setOnClickListener {
            onElementClick(
                Element(
                    id = "${component.id}#more",
                    type = ElementType.SECTION_HEADER,
                    rawType = "more",
                    title = component.title,
                ),
                component,
            )
        }

        rv.layoutManager = LinearLayoutManager(rv.context, RecyclerView.HORIZONTAL, false)
        // ★ 共享池：让横滑卡片与竖直方向的同种卡片共用一个池。
        //   跨 LayoutManager 共享池的安全性依据见 PagePlanRenderer 类注释 ③。
        //
        // ⚠️ 必须写成方法调用 `setRecycledViewPool(...)`，不能写属性赋值 `rv.recycledViewPool = ...`。
        //    `getRecycledViewPool()` 返回非空、而 `setRecycledViewPool(@Nullable ...)`
        //    接受可空 —— Kotlin 对"读写类型不一致"的 Java 属性**不会合成 var**，
        //    写属性形式会直接编译报错 "Val cannot be reassigned"。
        rv.setRecycledViewPool(sharedPool)
        // 内层横向列表与外层纵向列表**方向正交**，本来就不会抢手势；
        // 但仍显式关闭嵌套滚动，避免某些机型/输入源（滚轮、TV 遥控）下
        // 内层把事件吞掉导致外层滑不动。正交场景下关掉没有副作用。
        rv.isNestedScrollingEnabled = false
        rv.adapter = adapter
        rv.itemAnimator = null

        // ── 恢复该楼层上次的横向滚动位置 ──
        // 这个 ViewHolder 可能是**刚从池里捞出来的**（上一次属于别的楼层），
        // 所以这里必须**无条件**设置位置：若不恢复，它会带着上一个楼层的
        // 滚动位置出现（或停在第 0 个）。两种都是错的，而"无条件恢复"两种情况都覆盖。
        //
        // 用 scrollToPosition 而不是 smoothScrollToPosition：绑定发生在布局过程中，
        // 平滑滚动会先算出起点再动画，此时视图还没测量完，位置不可靠。
        // 这里要的是"立刻到位"，不是动画。
        val saved = horizontalScrollPositions[component.id] ?: 0
        rv.scrollToPosition(saved)

        // 记录滚动位置：监听内层滚动，把"第一个完全可见的位置"存到**楼层 id** 上。
        // 用 firstVisiblePosition 而不是 computeHorizontalScrollOffset()：
        // 前者是稳定语义（第几个 item），后者是像素偏移，在 item 宽度变化时会错位。
        //
        // ⚠️ clearOnScrollListeners 是必需的：这个 ViewHolder 会被复用，
        //    而监听器捕获的是**当前** component；不清理的话，
        //    上一个楼层的监听器会继续把位置写到**旧楼层 id** 上（数据串味）。
        rv.clearOnScrollListeners()
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                val lm = r.layoutManager as? LinearLayoutManager ?: return
                horizontalScrollPositions[component.id] = lm.findFirstVisibleItemPosition()
            }
        })
    }
}

// ══════════════════════════════════════════════════════════════════════
//  横滑楼层的内层 Adapter
// ══════════════════════════════════════════════════════════════════════

/**
 * 横滑楼层内层的横向列表。
 *
 * 它也要用**同样的 viewType 规则**（layout id），否则与共享池对不上 ——
 * 池是按 viewType 索引的，内层若给商品卡编了另一个 viewType，
 * 就会在池里另开一格，跨方向复用直接失效（**静默失效，只是变慢**）。
 * 所以这里复用 [elementViewType]，不另立规则。
 *
 * ─── 为什么这里必须覆盖 LayoutParams 的宽度 ───
 *
 * 这是"元件不描述位置"这条原则在**横向列表**里必须付的一次代价，值得讲清楚：
 *
 * 所有元件的 layout 宽度都写 `match_parent`（因为展平方案下宽度由 span 决定，
 * 见各 layout 的注释）。但 `LinearLayoutManager(orientation = HORIZONTAL)`
 * 对 `match_parent` 宽度的 item，会**按父容器宽度**测量 ——
 * 结果是每张卡片占满整屏，一屏只能看到一张，横滑变成"一屏一张"。
 *
 * 两种解法：
 *   a) 在 layout 里写死宽度 → 但宫格场景需要 match_parent，元件就被"位置"污染了，
 *      违背整个 demo 的立论；
 *   b) **由宿主（这个 adapter）在 bind 时按需覆盖** → 元件保持纯粹，
 *      "谁放着谁负责尺寸"。
 *
 * 选 (b)。这也正是 `RecyclerView` 的 LayoutParams 机制的本意：
 * LayoutManager 在每次取用 ViewHolder 时都会重建/校验 LayoutParams
 * （`RecyclerView.java:6926`），**布局参数本来就属于"谁摆放谁决定"**。
 */
internal class ElementRowAdapter(
    private val elements: List<Element>,
    private val stats: RenderStats,
    private val onClick: (Element, Component) -> Unit,
) : RecyclerView.Adapter<ElementRowAdapter.Holder>() {

    class Holder(itemView: View) : RecyclerView.ViewHolder(itemView)

    override fun getItemCount(): Int = elements.size

    override fun getItemViewType(position: Int): Int = elementViewType(elements[position])

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        stats.recordCreate(viewType)
        return Holder(LayoutInflater.from(parent.context).inflate(viewType, parent, false))
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val element = elements[position]
        bindElement(holder.itemView, element)
        applyHorizontalWidth(holder.itemView, element)
        holder.itemView.setOnClickListener { onClick(element, FAKE_COMPONENT) }
    }

    /**
     * 按元件类型给出横滑场景下的固定宽度。
     *
     * 为什么是"按类型给不同宽度"而不是统一一个值：
     * 促销卡（"抢/整点开抢"）本来就该窄，商品卡要能放下长度不定的标题与价格。
     * 这个映射是**元件的自然宽度**，属于渲染层知识 ——
     * 服务端下发的数据里不含宽度，正是为了避免"运营随手填 800dp 把横滑搞崩"。
     */
    private fun applyHorizontalWidth(view: View, element: Element) {
        val widthDp = when (element.type) {
            ElementType.PROMO -> 84
            ElementType.PRODUCT -> 132
            ElementType.ICON_ENTRY -> 72
            ElementType.SECTION_HEADER -> 160
            ElementType.BANNER_IMAGE -> 240
            ElementType.UNKNOWN -> 120
        }
        val widthPx = view.context.dp(widthDp)

        // ⚠️ 判据是"宽度**等于**目标值才跳过"，不是"不等于 WRAP_CONTENT 才跳过"。
        // 后者看着等价，其实是错的：从布局 inflate 出来的宽度是 MATCH_PARENT(-1)，
        // 用"不等于 WRAP_CONTENT"做条件会**恰好把需要覆盖的 match_parent 跳过**，
        // 于是横滑又变回"一屏一张卡"。这类"条件写反"的 bug 不会崩、只会画错，
        // 所以判据必须直接对着目标值写。
        val lp = view.layoutParams
        if (lp != null && lp.width == widthPx) return

        view.layoutParams = (lp ?: RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.WRAP_CONTENT,
            RecyclerView.LayoutParams.WRAP_CONTENT,
        )).apply { width = widthPx }
    }

    override fun onViewRecycled(holder: Holder) {
        super.onViewRecycled(holder)
        holder.itemView.findImageViews().forEach { Glide.with(it).clear(it) }
    }

    private companion object {
        // 内层点击回调需要 Component 参数（与 FloorAdapter 统一签名），
        // 但内层列表不关心组件。给一个空壳而不是把签名改得不一致 ——
        // 保持"点击回调"在所有调用点形状一致，比省一个对象更值。
        val FAKE_COMPONENT = Component(
            id = "inner",
            layout = LayoutStrategy.HORIZONTAL,
            rawType = "horizontal",
            columns = 1,
            style = com.interview.ec.model.ComponentStyle(),
            elements = emptyList(),
        )
    }
}

// ══════════════════════════════════════════════════════════════════════
//  元件绑定（所有元件类型共用的绘制逻辑）
// ══════════════════════════════════════════════════════════════════════

/**
 * 把元件的数据画到 View 上。
 *
 * ─── 为什么绑定逻辑放在文件级函数，而不是每个元件一个 ViewHolder 类 ───
 *
 * 若每个元件类型都写一个 `RecyclerView.ViewHolder` 子类，会得到 6 个几乎同构的类，
 * 每个都持有"自己的那几个 View" —— 但**展平架构下 ViewHolder 的全部价值
 * 只在于"缓存 findViewById 的结果"**，而 `findViewById` 在现代 ConstraintLayout 上
 * 是一次 O(子View数) 的遍历，对 5~6 个控件的布局来说成本可以忽略。
 *
 * 换来的是：**绑定逻辑集中在一处**，`Product` 的价格格式化、`Banner` 的图片加载
 * 都在同一个文件里，改一次就全都改到。这比"6 个类各写一份 bind"更不容易漂移。
 *
 * 代价说清楚：**丢了 ViewHolder 的字段缓存**，每次 bind 都要 findViewById。
 * 如果元件的布局很复杂（几十个控件）或列表滚动极快，这个开销会显现出来。
 * 到那个时候正确的做法不是"给每个元件加 ViewHolder 类"，
 * 而是引入 ViewBinding/DataBinding 由编译期生成缓存 —— 本项目 `:app` 未开启
 * ViewBinding（全仓库用 findViewById 的风格），所以这里保持手动查找。
 * 这是一个**有意识的取舍**，不是忘了优化。
 */
internal fun bindElement(itemView: View, element: Element) {
    when (element.type) {
        ElementType.BANNER_IMAGE -> bindBanner(itemView, element)
        ElementType.PRODUCT -> bindProduct(itemView, element)
        ElementType.ICON_ENTRY -> bindIconEntry(itemView, element)
        ElementType.SECTION_HEADER -> bindSectionHeader(itemView, element)
        ElementType.PROMO -> bindPromo(itemView, element)
        ElementType.UNKNOWN -> bindUnknown(itemView, element)
    }
}

private fun bindBanner(view: View, element: Element) {
    view.findViewById<TextView>(R.id.ec_banner_title).text = element.title
    val subtitle = view.findViewById<TextView>(R.id.ec_banner_subtitle)
    subtitle.text = element.subtitle
    subtitle.visibility = if (element.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
    bindBadge(view.findViewById(R.id.ec_banner_badge), element.badge)
    loadImage(view.findViewById(R.id.ec_banner_image), element.imageUrl)
}

private fun bindProduct(view: View, element: Element) {
    loadImage(view.findViewById(R.id.ec_product_image), element.imageUrl)
    view.findViewById<TextView>(R.id.ec_product_title).text = element.title

    val price = view.findViewById<TextView>(R.id.ec_product_price)
    price.text = element.priceCents?.let { PriceFormatter.format(it) } ?: "—"

    // 原价：**只在真的更贵时才显示划掉的原价**。
    // 用价格量级比较（Int 分）而不是显示字符串比较 —— 后者对
    // "¥199" 与 "¥199.0" 会判断错误。这也正是价格必须用整数存的收益之一。
    val original = view.findViewById<TextView>(R.id.ec_product_original_price)
    val originalCents = element.originalPriceCents
    val showOriginal = originalCents != null &&
        element.priceCents != null &&
        originalCents > element.priceCents
    if (showOriginal) {
        original.text = PriceFormatter.format(originalCents!!)
        original.paintFlags = original.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        original.visibility = View.VISIBLE
    } else {
        original.visibility = View.GONE
    }

    bindBadge(view.findViewById(R.id.ec_product_badge), element.badge)
}

private fun bindIconEntry(view: View, element: Element) {
    loadImage(view.findViewById(R.id.ec_icon_image), element.imageUrl)
    view.findViewById<TextView>(R.id.ec_icon_title).text = element.title
}

private fun bindSectionHeader(view: View, element: Element) {
    view.findViewById<TextView>(R.id.ec_header_title).text = element.title
    val subtitle = view.findViewById<TextView>(R.id.ec_header_subtitle)
    subtitle.text = element.subtitle
    subtitle.visibility = if (element.subtitle.isNullOrBlank()) View.GONE else View.VISIBLE
}

private fun bindPromo(view: View, element: Element) {
    view.findViewById<TextView>(R.id.ec_promo_title).text = element.title
    view.findViewById<TextView>(R.id.ec_promo_subtitle).text = element.subtitle
    bindBadge(view.findViewById(R.id.ec_promo_badge), element.badge)
}

/**
 * 未知元件的降级渲染。
 *
 * ★ 这里把**服务端下发的原始类型名**打在屏幕上，是刻意的设计：
 * 若渲染成空白，看到的人会以为是"数据没下发"→ 去查服务端；
 * 而事实上数据到了、只是客户端不认识 → 该发版。把类型名显示出来，
 * 一眼就能把责任方分清楚。同时它**保留了占位**，
 * 所以"配了 5 个只显示 4 个"这种静默缺失不会发生。
 */
private fun bindUnknown(view: View, element: Element) {
    view.findViewById<TextView>(R.id.ec_unknown_type).text = element.rawType
    val title = view.findViewById<TextView>(R.id.ec_unknown_title)
    title.text = element.title
    title.visibility = if (element.title.isNullOrBlank()) View.GONE else View.VISIBLE
}

private fun bindBadge(badge: TextView, text: String?) {
    if (text.isNullOrBlank()) {
        badge.visibility = View.GONE
    } else {
        badge.text = text
        badge.visibility = View.VISIBLE
    }
}

/**
 * 图片加载统一入口。
 *
 * 用 Glide（仓库既有依赖，见 `WaterfallAdapter`）而不是自己写网络+解码：
 * 本 Lab 的命题是**布局架构**，不是图片加载。图片在这里只是"一个异步到达的内容"，
 * 用它来观察"复用时必须 cancel"这类列表问题即可。
 *
 * `centerCrop` + 明确尺寸：ImageView 在布局里已给出确定宽高，
 * Glide 会据此降采样（这条链路的原理见 `com.interview.image` 那个 Lab，
 * 两个 Lab 在这里正好对接上）。
 */
private fun loadImage(imageView: ImageView, url: String?) {
    if (url.isNullOrBlank()) {
        // 无图 → 给一个确定的浅灰底，而不是留白：
        // 留白会让人分不清"没图"与"还没加载出来"。
        imageView.setImageDrawable(null)
        imageView.setBackgroundColor(Color.parseColor("#E8E8E8"))
        return
    }
    imageView.setBackgroundColor(Color.TRANSPARENT)
    Glide.with(imageView)
        .load(url)
        .centerCrop()
        .into(imageView)
}

// ══════════════════════════════════════════════════════════════════════
//  小工具
// ══════════════════════════════════════════════════════════════════════

/**
 * 递归找出 itemView 里所有 ImageView，供 `onViewRecycled` 时统一取消 Glide 请求。
 *
 * 为什么递归查找而不是让 ViewHolder 持有引用：
 * 见 [bindElement] 的说明 —— 本实现没给每种元件写 ViewHolder 类，
 * 所以没有"持有引用"的地方。这个遍历只在**回收时**发生（不是每次 bind），
 * 且 itemView 的层级很浅（3~4 层），成本可接受。
 */
private fun View.findImageViews(): List<ImageView> {
    val result = mutableListOf<ImageView>()
    fun walk(v: View) {
        if (v is ImageView) result += v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
    }
    walk(this)
    return result
}

/** dp → px。布局里能写的都写在布局里，这里只给"运行时才定的值"（如容器 padding）用 */
internal fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

/**
 * 解析 `#RRGGBB` / `#AARRGGBB` 色彩串。
 *
 * 不直接用 `Color.parseColor` 的原因：它对非法串会抛 `IllegalArgumentException`，
 * 而颜色**来自服务端下发**—— 一个拼错的色值不该崩掉整个首页。
 * 这里选择"解析失败就返回 null，由调用方退回默认色"，并保留原串以便诊断。
 */
internal fun parseColorOrNull(raw: String?): Int? {
    if (raw.isNullOrBlank()) return null
    return try {
        Color.parseColor(raw.trim())
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** 给容器加圆角背景（卡片）。颜色与圆角都在这里统一，避免各处硬编码不一致 */
internal fun buildCardBackground(color: Int, radiusPx: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusPx.toFloat()
    }

/** 线性容器（横滑楼层内的元素被塞进内层 RV，但组件容器本身用这个） */
internal fun linearParams(width: Int, height: Int, marginStart: Int = 0): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(width, height).apply { this.marginStart = marginStart }
