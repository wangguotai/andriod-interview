package com.interview.ec

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.interview.ec.model.EcPageParseResult
import com.interview.ec.model.EcPageParser
import com.interview.ec.model.EcSchema
import com.interview.ec.model.Element
import com.interview.ec.model.PriceFormatter
import com.interview.ec.render.PagePlanBuilder
import com.interview.ec.render.PagePlanRenderer
import com.interview.ec.render.RenderStats

/**
 * Time: 2026/10/18
 * Author: wgt
 * Description: **数据驱动的组件化电商首页** Lab —— 展平方案（单一 RecyclerView + ConcatAdapter）。
 *
 * ─── 一句话说清它在证明什么 ───
 *
 * > 页面的"有哪些楼层、每层什么内容、用什么方式排列"**全部来自一份 JSON**，
 * > 客户端只提供"渲染能力"（几种排列策略 + 几种元件），
 * > 改页面 = 改 JSON，**不改代码、不重新发版**。
 *
 * ─── 三层结构（对照代码看）───
 *
 * ```
 * assets/ec-home/page.json                  ← 服务端"应该"下发的东西（数据）
 *       ↓ EcPageParser                      ← 解析 + 降级（纯逻辑，可单测）
 * EcPage(components → elements)             ← 领域模型
 *       ↓ PagePlanBuilder                   ← 把"两层树"翻译成"每个 item 占几列"（纯逻辑）
 * PagePlan(floors → rows)                   ← 展平计划
 *       ↓ PagePlanRenderer                  ← 装配 ConcatAdapter + SpanSizeLookup
 * RecyclerView（唯一的 GridLayoutManager）    ← 渲染
 * ```
 *
 * ─── 为什么用"展平"而不是"每个楼层一个内嵌 RecyclerView" ───
 *
 * 这是本 Lab 最核心的取舍，两条路的差别是**根本性**的：
 *
 * | | 内嵌容器方案 | 展平方案（本实现） |
 * |---|---|---|
 * | 布局由谁算 | 内层 ViewGroup 的 onMeasure/onLayout | **我们**，手算 span 交给 GridLayoutManager |
 * | ViewHolder 复用范围 | **仅限本楼层**（外层看不到内层的 item） | **跨楼层**（同种元件共用一个池） |
 * | 滚动流畅度 | 楼层多时明显掉帧（每个都独立滚动容器） | 一屏内是**一个**连续的布局过程 |
 * | 布局灵活性 | 高（想怎么排怎么排） | 受"网格 + span"约束 |
 * | 实现复杂度 | 低（各楼层各写各的） | 高（要自己算占位、处理断行，见 PagePlanBuilder） |
 *
 * 电商首页**恰好**是"楼层多、元素规整、复用率极高"的场景 → 展平方案收益最大。
 * 若是"每个楼层都是完全不同的奇葩布局"，展平就得处处迁就，那时候内嵌更合适。
 * **没有普适最优解，只有匹配场景的解** —— 这是本 Lab 想传达的判断，而不是"展平更好"。
 *
 * ─── 建议的操作步骤（课堂/面试演示）───
 *
 *  1. 直接进入本页：看到 7 个楼层（JSON 里有 8 个组件，其中一个类型客户端不认识，被整块跳过）。
 *  2. 看顶部「装配诊断」：**警告 3 条**，分别对应
 *     未知组件 / 未知元件 / 非法列数 —— 数据里故意埋了这三种异常。
 *  3. 找页面上那几张**黄虚线**的卡片：那是"未知元件"的降级占位，上面打着原始类型名。
 *     它证明了"客户端不认识 ≠ 静默丢弃"。
 *  4. 点「滚动到底并统计」，记下 `product=` 的创建次数。
 *  5. 打开「隔离 viewType」开关 → 点「重建页面」→ 再滚一次。
 *     对比 `product=` 的次数：隔离后明显变大 —— 这就是"每楼层一个池"的代价。
 *  6. 横向滑动「限时秒杀」楼层：它是唯一保留容器的排列策略（横滑无法展平）。
 */
class EcHomeActivity : AppCompatActivity() {

    private lateinit var recyclerView: androidx.recyclerview.widget.RecyclerView
    private lateinit var tvSummary: TextView
    private lateinit var tvWarnings: TextView
    private lateinit var tvPool: TextView
    private lateinit var tvStatHint: TextView
    private lateinit var swIsolate: Switch

    private lateinit var rawJson: String
    private lateinit var stats: RenderStats
    private var renderer: PagePlanRenderer? = null

    /** 上次装配的 item 数，用于"重建页面"时对比 */
    private var lastItemCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ec_home)

        recyclerView = findViewById(R.id.ec_recycler)
        tvSummary = findViewById(R.id.ec_diag_summary)
        tvWarnings = findViewById(R.id.ec_diag_warnings)
        tvPool = findViewById(R.id.ec_diag_pool)
        tvStatHint = findViewById(R.id.ec_stat_hint)
        swIsolate = findViewById(R.id.ec_sw_isolate)

        rawJson = readAsset(ASSET_PATH)

        findViewById<Button>(R.id.ec_btn_scroll_stats).setOnClickListener { scrollToBottomAndReport() }
        findViewById<Button>(R.id.ec_btn_reset).setOnClickListener { rebuild() }
        findViewById<Button>(R.id.ec_btn_print_json).setOnClickListener { showRawJson() }

        // 切换开关时**不自动重建**：必须用户显式点"重建页面"。
        // 为什么：若自动重建，用户会分不清"因为开关变了"还是"因为重建了"——
        // 实验里**一次只改一个变量**，且改动要有明确的手动触发点，
        // 否则结论不可归因。这是做对照实验的基本纪律。
        swIsolate.setOnCheckedChangeListener { _, _ ->
            tvStatHint.visibility = View.VISIBLE
            tvStatHint.text = "开关已改。点「重建页面」生效（一次只改一个变量）"
        }

        buildPage()
    }

    // ══════════════════════════════════════════════════════════════════
    //  建页
    // ══════════════════════════════════════════════════════════════════

    private fun buildPage() {
        // ── 1. 解析（数据 → 领域模型），**失败也不白屏** ──
        val parsed = EcPageParser.parse(rawJson)

        when (parsed) {
            is EcPageParseResult.Failure -> {
                // 整体失败只有一种：JSON 本身语法错。此时连"有哪些楼层"都不知道，
                // 没有部分成功的可能 → 显示错误而不是空列表。
                // 不抛异常：崩掉只会让用户看到"应用已停止"，而他需要的是
                // "这条 JSON 坏了"这个信息。
                tvSummary.text = "解析失败：${parsed.message}"
                tvWarnings.text = "无法渲染任何内容（JSON 语法级错误）"
                tvWarnings.visibility = View.VISIBLE
                recyclerView.adapter = null
                return
            }

            is EcPageParseResult.Success -> renderPage(parsed)
        }
    }

    private fun renderPage(parsed: EcPageParseResult.Success) {
        val page = parsed.page

        // ── 2. 展平（领域模型 → 占位计划），纯逻辑 ──
        val plan = PagePlanBuilder.build(page)

        // ── 3. 装配 ──
        stats = RenderStats()
        val isolate = swIsolate.isChecked
        renderer = PagePlanRenderer(
            recyclerView = recyclerView,
            plan = plan,
            isolateViewTypes = isolate,
            stats = stats,
        ) { element, component ->
            // 点击**只弹出来展示**，不做真实路由：
            // 路由不是本 Lab 的命题，且一个假的跳转只会让人以为"这里有功能"。
            // 但由于**列表复用**，点击回调必须能区分"点的是哪个元件" ——
            // 这正是 FloorAdapter 里用 bindingAdapterPosition 而不是 adapterPosition 的原因
            //（ConcatAdapter 下 adapterPosition 是全局位置，直接索引本楼层数据会错位）。
            val price = element.priceCents?.let { "  ${PriceFormatter.format(it)}" } ?: ""
            Toast.makeText(
                this,
                "点击元件：${element.title ?: element.rawType}$price\n" +
                    "类型=${element.type}  楼层=${component.id}\n" +
                    "target=${element.target ?: "(未配置)"}",
                Toast.LENGTH_SHORT,
            ).show()
        }

        // ── 4. 把"数据出了什么事"如实显示出来 ──
        val isolated = if (isolate) "隔离" else "共用"
        val floorDesc = plan.floors.joinToString(" / ") { "${it.component.id}(${it.rows.size})" }
        tvSummary.text = buildString {
            append("组件 ${page.components.size} → 楼层 ${plan.floors.size} / item ${plan.itemCount}")
            append(" / spanCount ${EcSchema.SPAN_COUNT} / 警告 ${parsed.warnings.size + plan.warnings.size}")
            append("\nviewType $isolated 模式")
            if (lastItemCount >= 0) append("  |  上次 item=$lastItemCount")
            append("\n楼层：$floorDesc")
        }
        lastItemCount = plan.itemCount

        val allWarnings = parsed.warnings.map { "[解析] ${it.code} @${it.where}" } +
            plan.warnings.map { "[展平] $it" }
        tvWarnings.text = if (allWarnings.isEmpty()) {
            "无警告（数据完全合法）"
        } else {
            // 把 json 里的**原始 detail** 也打出来一行 —— 只说"有警告"没用，
            // 要让人知道**我们做了什么处理**（降级？跳过？文本夹到几列？）。
            // 完整 detail 太长放不下，这里打 code+位置，
            // 完整信息在 logcat（TAG=EcHome），不连 adb 也能从"位置"对上 JSON。
            "警告 ${allWarnings.size} 条：\n" + allWarnings.joinToString("\n") { "· $it" }
        }

        tvPool.text = renderer?.poolStats()

        android.util.Log.i(
            TAG,
            "解析警告:\n" + parsed.warnings.joinToString("\n") { "  ${it.code} @${it.where}: ${it.detail}" },
        )
        android.util.Log.i(
            TAG,
            "展平警告:\n" + plan.warnings.joinToString("\n") { "  $it" },
        )
    }

    // ══════════════════════════════════════════════════════════════════
    //  实验动作
    // ══════════════════════════════════════════════════════════════════

    /**
     * 重建页面。
     *
     * 注意这里**重新走了一遍解析 + 展平**（而不是只把 adapter 换掉），
     * 因为实验要观测的是"整条链路的产物"，而不只是最后一步。
     * `stats` 也一并重置 —— 否则两次实验的数字会累加，对比就失效了。
     */
    private fun rebuild() {
        recyclerView.adapter = null
        recyclerView.recycledViewPool.clear()
        buildPage()
        recyclerView.scrollToPosition(0)
        tvStatHint.visibility = View.VISIBLE
        tvStatHint.text = "已重建。滚到底后看屏幕上的 createViewHolder 次数"
    }

    /**
     * 滚到底并汇报创建次数。
     *
     * 为什么是"滚到底"而不是"滚一屏"：要让**全部楼层都经历过创建/回收**，
     * 计数才包含所有 viewType。滚一屏的话下面的楼层根本没被创建过，
     * 数字之间没法对比。
     *
     * 用 `scrollToPosition(itemCount - 1)` 而不是 `smoothScrollTo`：
     * 平滑滚动是动画，中途可能被中断；这里需要的是"确定地到达底部"。
     */
    private fun scrollToBottomAndReport() {
        val r = renderer ?: return
        val last = r.assembledItemCount - 1
        if (last < 0) return
        recyclerView.scrollToPosition(last)

        // 下一帧再读统计：scrollToPosition 只是**请求**布局，
        // 本帧还没真的创建 ViewHolder。用 post 推到下一帧，数字才是完整的。
        // （这类"以为已经生效、其实还没"的时序问题，是 UI 统计最常见的假数据来源。）
        recyclerView.post {
            tvStatHint.visibility = View.VISIBLE
            tvStatHint.text = stats.summary()
            tvPool.text = r.poolStats()
        }
    }

    private fun showRawJson() {
        AlertDialog.Builder(this)
            .setTitle("assets/ec-home/page.json（fullscreen 可滚动）")
            .setMessage(rawJson)
            .setPositiveButton("关闭", null)
            .show()
    }

    // ══════════════════════════════════════════════════════════════════
    //  数据来源
    // ══════════════════════════════════════════════════════════════════

    /**
     * 从 assets 读 JSON。
     *
     * ─── 为什么用 assets 而不是硬编码 Kotlin 数据 ───
     *
     * 因为"数据驱动"这个命题的**关键在"改数据不用改代码"**：
     * · 硬编码在 Kotlin 里 → 改一个标题要重新编译、重新发版，
     *   这就退化成"用代码描述布局"，命题不成立；
     * · 放 assets → 至少演示了"数据是**外部输入**、客户端只是解释器"，
     *   而且可以直接改 assets 里的 json（甚至 push 到设备）观察页面变化，不用重编。
     *
     * ⚠️ 诚实边界：assets 是**随包**的，真实项目里这份 JSON 来自网络下发
     * （配合版本号 + 灰度 + 兜底）。本 Lab 不接网络，因为网络链路已在
     * `com.interview.net` 那个 Lab 里单独讲过，此处再引入只会稀释命题。
     * 从 assets 换成网络，**只需要换 [rawJson] 的来源**，其余代码一行不动 ——
     * 这本身就是"数据驱动"分层正确的证据。
     *
     * 读取失败不吞异常（返回空串），而是让后续解析给出明确的 Failure：
     * 空串会让 Gson 直接报"JSON 语法错误"，比我在这里编一个错误信息更准确。
     */
    private fun readAsset(path: String): String = try {
        assets.open(path).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        android.util.Log.e(TAG, "读取 assets/$path 失败", e)
        ""
    }

    private companion object {
        const val TAG = "EcHome"
        const val ASSET_PATH = "ec-home/page.json"
    }
}
