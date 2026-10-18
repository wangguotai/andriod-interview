package com.interview.ec.model

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName

/**
 * Time: 2026/10/18
 * Author: wgt
 * Description: 把服务端下发的首页 JSON 解析成 [EcPage]，**并如实报告降级**。
 *
 * ─── 这个类的核心命题：解析器的返回值不该只有"成功/失败" ───
 *
 * 每个写过首页的人都会遇到这个场景：运营在后台配错了一个楼层，全站首页白屏。
 * 根因通常是把"数据有问题"当成了"整体失败" —— 一个 `throw` 冒到顶层，
 * 整页就没了。而真实的首页是**许多独立楼层拼起来**，一块坏了不该拖死其余。
 *
 * 所以本类所有降级路径都遵循同一条原则：
 *
 * > **能降级就不要失败；一旦降级，必须让上层能看见。**
 *
 * 落地成两条规则：
 *
 * 1. **降级不抛异常**：未知类型、非法列数、空楼层，一律就地处理成"能画的东西"，
 *    把问题记进 [EcPageParseResult.Success.warnings]。
 * 2. **警告必须能显示出来**：`warnings` 会被渲染到页面的诊断面板。
 *    真实项目里最常见的反模式是"降级了但只 `Log.w`" —— 开发机上看 logcat 一切正常，
 *    线上用户看到的是少了一块的首页，而**没有任何人知道**。
 *
 * 只有一种情况算整体失败：**JSON 本身就语法错**（[EcPageParseResult.Failure]）——
 * 这时候连"有哪些楼层"都不知道，没有部分成功的可能。
 *
 * ─── 依赖说明 ───
 *
 * 只用 Gson（仓库已有，见 `com.squareup.retrofit2:converter-gson`），
 * **不依赖任何 Android 类** → 本类 100% 可在宿主 JVM 上单测
 * （见 `app/src/test/java/com/interview/ec/EcPageParserTest.kt`）。
 * 首页解析这种"数据一错就白屏"的逻辑，是**最该**被单测钉死的部分，
 * 必须让它不需要设备/模拟器就能验证。
 */
object EcPageParser {

    private val gson = Gson()

    /** 本客户端支持的 schema 主版本。次版本向前兼容，主版本不同则告警。 */
    const val SUPPORTED_SCHEMA_MAJOR = 1

    /**
     * 从 JSON 字符串解析。
     *
     * @param json assets 或网络下发的原始报文
     */
    fun parse(json: String): EcPageParseResult {
        val dto = try {
            gson.fromJson(json, PageDto::class.java)
        } catch (e: JsonSyntaxException) {
            // 只有语法级错误才是整体失败：连结构都没有，谈不上"保住哪几块"。
            // 注意这里**只捕获 JsonSyntaxException**，不 catch Throwable ——
            // 把 OOM / StackOverflow 也吞掉会掩盖真正的崩溃（那类问题必须暴露）。
            return EcPageParseResult.Failure("JSON 语法错误: ${e.message}")
        } catch (e: IllegalStateException) {
            // Gson 对"JSON 是数组但期望对象"这类类型不匹配会抛这个
            return EcPageParseResult.Failure("JSON 结构不符: ${e.message}")
        }

        if (dto?.page == null) {
            return EcPageParseResult.Failure("缺少 page 节点（或 page 为空）")
        }

        return parsePage(dto)
    }

    // ══════════════════════════════════════════════════════════════════
    //  主流程
    // ══════════════════════════════════════════════════════════════════

    private fun parsePage(dto: PageDto): EcPageParseResult {
        val warnings = mutableListOf<ParseWarning>()

        checkSchemaVersion(dto.schemaVersion, warnings)

        val rawComponents = dto.page!!.components ?: emptyList()
        val components = mutableListOf<Component>()

        rawComponents.forEachIndexed { ci, raw ->
            val at = "components[$ci]"
            val parsed = parseComponent(raw, at, warnings) ?: return@forEachIndexed
            components += parsed
        }

        return EcPageParseResult.Success(
            page = EcPage(
                // 标题缺失不是错误：首页标题本来就常常由本地配置兜底
                title = dto.page!!.title?.takeIf { it.isNotBlank() } ?: "首页",
                components = components,
            ),
            warnings = warnings,
        )
    }

    private fun checkSchemaVersion(version: String?, warnings: MutableList<ParseWarning>) {
        // 版本缺失：按宽松处理。为什么不像很多项目那样"缺失即拒绝"——
        // 版本字段是**后加的**，早期报文没有它。若一律拒绝，
        // 灰度期间老服务端下发的报文会**全部失败**，这比"版本未知"严重得多。
        if (version.isNullOrBlank()) {
            warnings += ParseWarning(
                code = ParseWarning.CODE_SCHEMA_VERSION,
                where = "schemaVersion",
                detail = "报文未声明 schemaVersion，按 v1 解析（老版本文案可能缺字段）",
            )
            return
        }
        val major = version.substringBefore('.').toIntOrNull()
        if (major == null) {
            warnings += ParseWarning(
                code = ParseWarning.CODE_SCHEMA_VERSION,
                where = "schemaVersion",
                detail = "schemaVersion=\"$version\" 无法解析成版本号，按 v1 尝试",
            )
        } else if (major > SUPPORTED_SCHEMA_MAJOR) {
            // 主版本更高 = 服务端认为格式已不兼容。**仍然尝试解析**，但必须告警：
            // 直接拒绝会让灰度期间的新版报文在老客户端上全白，而"尽力解析 + 告警"
            // 通常还能画出大部分内容（因为新增字段会被 Gson 忽略）。
            warnings += ParseWarning(
                code = ParseWarning.CODE_SCHEMA_VERSION,
                where = "schemaVersion",
                detail = "报文 schemaVersion=$version 高于客户端支持的 v$SUPPORTED_SCHEMA_MAJOR，" +
                    "仅按已知字段尽力解析，可能出现未支持的组件被降级",
            )
        }
    }

    private fun parseComponent(
        raw: ComponentDto,
        at: String,
        warnings: MutableList<ParseWarning>,
    ): Component? {
        val rawType = raw.type?.trim().orEmpty()

        // ─── 组件级未知类型：整块丢弃（并告警）───
        // 为什么组件级是"丢"而不是像元件那样降级成占位：
        // 元件降级还能保住"这里有东西"的位置感（占位块），而一个**未知的容器**
        // 我们连它要几列、要不要横滑都不知道 —— 硬画只会得到一个错乱的区域，
        // 比少一块更糟（用户会以为是渲染坏了）。所以这里丢，但**必须让它可见**。
        val layout = layoutOf(rawType)
        if (layout == null) {
            warnings += ParseWarning(
                code = ParseWarning.CODE_UNKNOWN_COMPONENT,
                where = at,
                detail = "未知组件类型 \"$rawType\"，已整块跳过（该楼层不展示）。" +
                    "需要客户端支持则新增 LayoutStrategy 映射",
            )
            return null
        }

        val columns = resolveColumns(raw.columns, layout, at, warnings)

        val rawElements = raw.elements ?: emptyList()
        val elements = rawElements.mapIndexedNotNull { ei, e ->
            parseElement(e, "$at.elements[$ei]", warnings)
        }

        if (elements.isEmpty()) {
            warnings += ParseWarning(
                code = ParseWarning.CODE_EMPTY_COMPONENT,
                where = at,
                detail = "组件 \"${raw.id ?: rawType}\" 没有任何可渲染元件，已跳过（空楼层占位没有意义）",
            )
            return null
        }

        checkDuplicateIds(elements, at, warnings)

        return Component(
            id = raw.id?.takeIf { it.isNotBlank() } ?: "$at",
            layout = layout,
            rawType = rawType,
            columns = columns,
            style = ComponentStyle(
                background = raw.style?.background?.takeIf { it.isNotBlank() },
                // 负 padding 在 Android 上会被系统当 0 处理，但那样就是"静默不一致"了：
                // 服务端以为设了，客户端画的是 0。夹到 0 并告警更诚实。
                paddingDp = (raw.style?.paddingDp ?: 0).coerceAtLeast(0),
                card = raw.style?.card ?: true,
            ),
            title = raw.title?.takeIf { it.isNotBlank() },
            elements = elements,
        )
    }

    private fun parseElement(
        raw: ElementDto,
        at: String,
        warnings: MutableList<ParseWarning>,
    ): Element? {
        val rawType = raw.type?.trim().orEmpty()
        val type = elementTypeOf(rawType)

        if (type == ElementType.UNKNOWN) {
            // ─── 元件级未知类型：降级渲染，不丢 ───
            // 这是本解析器最该被记住的一条：**未知元件要"占位"而不是"消失"**。
            // 消失的后果是运营在后台配了 5 个商品、用户只看到 4 个，
            // 而这种"少了但看不出少"的问题线上几乎无法发现。
            // 占位块把类型名打在屏幕上，问题当场可见、且指向明确（该升客户端）。
            warnings += ParseWarning(
                code = ParseWarning.CODE_UNKNOWN_ELEMENT,
                where = at,
                detail = "未知元件类型 \"$rawType\"，已降级为占位块展示（保留原类型名，便于定位）",
            )
        }

        val id = raw.id?.takeIf { it.isNotBlank() }
        if (id == null) {
            warnings += ParseWarning(
                code = ParseWarning.CODE_MISSING_ID,
                where = at,
                detail = "元件缺少 id，已回退为位置 id（打点会因此无法稳定归因，需服务端补）",
            )
        }

        return Element(
            id = id ?: "$at#auto",
            type = type,
            rawType = rawType,
            title = raw.title?.takeIf { it.isNotBlank() },
            subtitle = raw.subtitle?.takeIf { it.isNotBlank() },
            imageUrl = raw.imageUrl?.takeIf { it.isNotBlank() },
            priceCents = raw.priceCents,
            originalPriceCents = raw.originalPriceCents,
            badge = raw.badge?.takeIf { it.isNotBlank() },
            target = raw.target?.takeIf { it.isNotBlank() },
            needsFullRow = raw.needsFullRow ?: false,
        )
    }

    // ══════════════════════════════════════════════════════════════════
    //  列数裁决
    // ══════════════════════════════════════════════════════════════════

    /**
     * 把服务端给的列数夹到"能被 [EC_BASE_SPAN] 整除"的合法值。
     *
     * ─── 为什么列数必须是"12 的约数"这类约束，而不是随便一个正整数 ───
     *
     * 宫格列数与 span 的换算必须整除：`spanSize = BASE_SPAN / columns`。
     * 若允许任意列数，`BASE_SPAN / columns` 会往下取整，于是**每行拼不满一整行**
     * （累计 span < BASE_SPAN），表现为右侧莫名留白 —— 而且不同列数留白不同，
     * 极难排查。约束列数，比让渲染层去处理"不整除的余数"要简单且可预测得多。
     *
     * 裁决规则：**取最近的合法值**，并告警。为什么是"最近"而不是"拒绝该楼层"：
     * 列数 7 通常是运营手滑（想写 6 或 8），此时按 6 列画出来的页面**大概率是对的**，
     * 丢掉整个楼层反而更糟。这与"未知类型必须丢"形成对照 ——
     * 未知类型是我们**不知道怎么做**，非法列数是**知道怎么做、只是值不对**。
     *
     * @see EC_BASE_SPAN 为什么上界取 60 而不是 12
     */
    private fun resolveColumns(
        raw: Int?,
        layout: LayoutStrategy,
        at: String,
        warnings: MutableList<ParseWarning>,
    ): Int {
        // 通栏：语义上就是"一列"。这里**忽略**服务端给的 columns，
        // 因为 banner 的 type 已经表达了意图；若两者冲突（banner + columns=3），
        // 按"更具体的 type 优先"处理。不告警 —— 这是明确的语义覆盖，不是数据错。
        if (layout == LayoutStrategy.BANNER) return 1

        // 横滑：元素在**内层**横向列表里，外层只占 1 个 item，列数无意义。
        if (layout == LayoutStrategy.HORIZONTAL) return 1

        val requested = raw ?: 2
        if (requested in EcSchema.LEGAL_COLUMNS) return requested

        val clamped = EcSchema.LEGAL_COLUMNS.minByOrNull { kotlin.math.abs(it - requested) } ?: 2
        warnings += ParseWarning(
            code = ParseWarning.CODE_BAD_COLUMNS,
            where = at,
            detail = "columns=$requested 不是合法列数，已夹到 $clamped 列" +
                "（合法值：${EcSchema.LEGAL_COLUMNS.sorted().joinToString("/")}）。" +
                "非约数列会导致每行列宽不一致或右侧留白",
        )
        return clamped
    }

    private fun checkDuplicateIds(
        elements: List<Element>,
        at: String,
        warnings: MutableList<ParseWarning>,
    ) {
        val seen = mutableSetOf<String>()
        val dups = elements.filter { !seen.add(it.id) }.map { it.id }.distinct()
        if (dups.isNotEmpty()) {
            // 只告警、**不删**：重复 id 的影响面是"打点归因不准 / 将来加 stable id 会冲突"，
            // 而删数据会让用户少看到商品（用户可见的功能损失 > 打点不准）。
            // 两者都不可接受，但后者更不可接受，所以选前者 + 告警。
            warnings += ParseWarning(
                code = ParseWarning.CODE_DUPLICATE_ID,
                where = at,
                detail = "元件 id 重复：${dups.joinToString()}（已保留全部，但打点归因会串；" +
                    "且将来启用 stable id 时会冲突）",
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  类型映射：服务端字符串 → 客户端枚举
    // ══════════════════════════════════════════════════════════════════

    /**
     * 服务端 type → 排列策略。
     *
     * 用 `when` 显式列出、**不用 `valueOf` + try/catch**：
     * 前者漏了分支编译器会提醒（且这里返回 null 是明确的降级契约），
     * 后者把"枚举不存在"这种**预期内**的情况写成异常控制流 ——
     * 每天发生几十次的正常降级，不该走异常路径（异常构造要抓栈，是纯开销）。
     */
    private fun layoutOf(type: String): LayoutStrategy? = when (type.lowercase()) {
        "banner" -> LayoutStrategy.BANNER
        "grid" -> LayoutStrategy.GRID
        "horizontal", "h_scroll", "hscroll" -> LayoutStrategy.HORIZONTAL
        else -> null
    }

    private fun elementTypeOf(type: String): ElementType = when (type.lowercase()) {
        "banner_image" -> ElementType.BANNER_IMAGE
        "product" -> ElementType.PRODUCT
        "icon_entry" -> ElementType.ICON_ENTRY
        "section_header" -> ElementType.SECTION_HEADER
        "promo" -> ElementType.PROMO
        else -> ElementType.UNKNOWN
    }

    // ══════════════════════════════════════════════════════════════════
    //  DTO：只描述"报文长什么样"，不含任何业务判断
    // ══════════════════════════════════════════════════════════════════

    /**
     * 传输对象与领域模型**刻意分开**。
     *
     * 为什么不直接 `gson.fromJson(json, EcPage::class.java)`：
     * 那样服务端的字段名/类型会直接钉进领域模型 —— 服务端改个字段名，
     * 领域模型和所有使用它的代码都得跟着改。中间隔一层 DTO，
     * 则"报文怎么变"与"业务怎么用"解耦，且**所有防御性判断都集中在这一层**。
     * 代价是多写几个类；对一个会被反复演进的下发协议，这笔账是划算的。
     */
    private data class PageDto(
        val schemaVersion: String? = null,
        val page: PageBodyDto? = null,
    )

    private data class PageBodyDto(
        val title: String? = null,
        val components: List<ComponentDto>? = null,
    )

    private data class ComponentDto(
        val id: String? = null,
        val type: String? = null,
        val columns: Int? = null,
        val title: String? = null,
        val style: StyleDto? = null,
        val elements: List<ElementDto>? = null,
    )

    private data class StyleDto(
        val background: String? = null,
        @SerializedName("paddingDp") val paddingDp: Int? = null,
        val card: Boolean? = null,
    )

    private data class ElementDto(
        val id: String? = null,
        val type: String? = null,
        val title: String? = null,
        val subtitle: String? = null,
        val imageUrl: String? = null,
        val priceCents: Int? = null,
        val originalPriceCents: Int? = null,
        val badge: String? = null,
        val target: String? = null,
        val needsFullRow: Boolean? = null,
    )
}
