package com.interview.sdk.plugin

import java.io.File

/**
 * 报告渲染。产出两份：
 *   · `sdk-audit.md`   —— 人读，含「凭什么」的证据链
 *   · `sdk-audit.json` —— CI 卡口消费（阈值判定、趋势对比）
 *
 * ⚠️ 刻意不引入 Moshi/Gson：这个插件的依赖会进入**构建脚本的类路径**，
 *    每多一个传递依赖就多一份构建期冲突风险（thread-plugin 已经有 ASM 了）。
 *    手写序列化器 + 少量转义就够用，JSON 结构是本工具自己定的、不会变。
 */
internal object ReportWriter {

    fun write(result: SdkAuditResult, outputDir: File, emitJson: Boolean): File {
        outputDir.mkdirs()
        val md = File(outputDir, "sdk-audit.md")
        md.writeText(renderMarkdown(result))
        if (emitJson) {
            File(outputDir, "sdk-audit.json").writeText(renderJson(result))
        }
        return md
    }

    // ══════════════════════════════════════════════════════════════════
    //  Markdown
    // ══════════════════════════════════════════════════════════════════

    private fun renderMarkdown(r: SdkAuditResult): String = buildString {
        appendLine("# 三方 SDK 治理审计报告")
        appendLine()
        appendLine("> variant: `${r.variant}` ｜ 依赖数: ${r.dependencies.size}（三方 ${r.dependencyReport.thirdPartyCount}）")
        appendLine("> 由 `com.interview.sdk.governance` 插件生成。**所有结论都可在本文件内找到证据。**")
        appendLine()

        // ── 摘要：先给结论，别让读者翻 ──
        val unused = r.permissions.filter { it.verdict == PermissionVerdict.DECLARED_UNUSED }
        val dangerousUnused = unused.filter { it.dangerous }
        appendLine("## 摘要")
        appendLine()
        appendLine("| 维度 | 发现 |")
        appendLine("|---|---|")
        appendLine("| 权限 | 共 ${r.permissions.size} 项；可移除 **${unused.size}** 项（其中危险权限 **${dangerousUnused.size}** 项） |")
        appendLine("| 依赖 | 版本冲突 **${r.dependencyReport.versionConflicts.size}** 组；重复类 **${r.dependencyReport.classDuplications.size}** 个；等价库并存 **${r.dependencyReport.equivalentCollisions.size}** 组 |")
        appendLine("| 体积 | 三方依赖合计 **${fmt(r.sizeReport.dependencies.sumOf { it.totalBytes })}**；最大单项 `${
            r.sizeReport.dependencies.maxByOrNull { it.totalBytes }?.id ?: "-"
        }` |")
        appendLine("| 线程 | 检出创建点 **${r.threadReport.profiles.sumOf { it.total }}** 处；高风险 **${r.threadReport.highRisk.size}** 处 |")
        appendLine()

        renderPermissionSection(r)
        renderDependencySection(r)
        renderSizeSection(r)
        renderThreadSection(r)

        appendLine("## 工具局限（必读）")
        appendLine()
        r.caveats.forEach { appendLine("- $it") }
    }

    private fun StringBuilder.renderPermissionSection(r: SdkAuditResult) {
        appendLine("## 一、权限：声明归属 · 真实使用 · 移除方案")
        appendLine()
        if (r.permissions.isEmpty()) {
            appendLine("（未发现任何权限声明）")
            appendLine()
            return
        }

        val order = listOf(
            PermissionVerdict.DECLARED_UNUSED,
            PermissionVerdict.REFLECTION_PRONE,
            PermissionVerdict.USED_BY_SDK_ONLY,
            PermissionVerdict.USED_BY_FIRST_PARTY,
            PermissionVerdict.UNCERTAIN,
        )
        val label = mapOf(
            PermissionVerdict.DECLARED_UNUSED to "✅ 可移除",
            PermissionVerdict.REFLECTION_PRONE to "❓ 反射高发，人工确认",
            PermissionVerdict.USED_BY_SDK_ONLY to "⚠️ 仅 SDK 内部使用",
            PermissionVerdict.USED_BY_FIRST_PARTY to "🔒 自有代码在用",
            PermissionVerdict.UNCERTAIN to "❔ 不可判定",
        )

        appendLine("| 权限 | 危险 | 结论 | 声明方 | 使用证据 |")
        appendLine("|---|---|---|---|---|")
        for (v in order) {
            r.permissions.filter { it.verdict == v }.forEach { p ->
                val decl = p.declaredBy.joinToString("<br>") { "`${it.id}`" }.ifEmpty { "-" }
                val use = p.usages.take(3).joinToString("<br>") { "`${it.by}` ${it.kind}" }
                    .ifEmpty { "-" } + if (p.usages.size > 3) "<br>… 共 ${p.usages.size} 条" else ""
                appendLine("| `${p.permission}` | ${if (p.dangerous) "🔴" else ""} | ${label[p.verdict]} | $decl | $use |")
            }
        }
        appendLine()

        // 移除方案单列 —— 这是本维度真正的交付物
        val actionable = r.permissions.filter { it.removalAdvice != null }
        if (actionable.isNotEmpty()) {
            appendLine("### 可执行动作")
            appendLine()
            actionable.forEach { p ->
                appendLine("<details><summary><b>${p.permission}</b>（${label[p.verdict]}）</summary>")
                appendLine()
                appendLine("```")
                appendLine(p.removalAdvice)
                appendLine("```")
                appendLine()
                appendLine("</details>")
                appendLine()
            }
        }
    }

    private fun StringBuilder.renderDependencySection(r: SdkAuditResult) {
        val d = r.dependencyReport
        appendLine("## 二、依赖重复与版本冲突")
        appendLine()

        appendLine("### 2.1 版本冲突（同一模块被多版本请求）")
        appendLine()
        if (d.versionConflicts.isEmpty()) {
            appendLine("✅ 无。")
        } else {
            appendLine("| 模块 | 请求版本 | Gradle 选中 | 请求者 |")
            appendLine("|---|---|---|---|")
            d.versionConflicts.forEach { c ->
                appendLine("| `${
                    c.module
                }` | ${c.requested.joinToString(", ")} | **${c.selected}** | ${c.requestedBy.take(4).joinToString("<br>") { "`$it`" }}${if (c.requestedBy.size > 4) "<br>…共 ${c.requestedBy.size}" else ""} |")
            }
            appendLine()
            appendLine("**解决方向**：")
            appendLine("1. 优先用 **BOM / platform()** 统一版本（androidx、compose、firebase 都有 BOM）")
            appendLine("2. 无 BOM 时用 `resolutionStrategy.force` 或 `constraints` 显式对齐")
            appendLine("3. ⚠️ **不要盲目 force 到最高版**：Gradle 默认就是取最高版；")
            appendLine("   真正要处理的是「最高版与其他依赖不兼容」，这时应该 force 到**兼容版本**而非最高版本")
            appendLine("4. 冲突列表为空不代表没问题 —— Gradle 的默认仲裁会**静默**把低版提升，")
            appendLine("   若该库未做二进制兼容（非语义化版本），运行时才会崩。建议再加 `failOnVersionConflict()` 做校验")
        }
        appendLine()

        appendLine("### 2.2 重复类（同一 class 被多个依赖打包）")
        appendLine()
        if (d.classDuplications.isEmpty()) {
            appendLine("✅ 未发现。")
        } else {
            appendLine("| 类 | 提供者 | 字节码一致 |")
            appendLine("|---|---|---|")
            d.classDuplications.take(80).forEach { c ->
                appendLine("| `${c.className}` | ${c.providers.joinToString("<br>") { "`$it`" }} | ${if (c.identical) "一致" else "**❌ 不一致**"} |")
            }
            if (d.classDuplications.size > 80) appendLine("| … 其余 ${d.classDuplications.size - 80} 个省略 | | |")
            appendLine()
            appendLine("**解决方向**：")
            appendLine("· 字节码**不一致**的最危险：最终生效的是哪一个取决于打包顺序（dex 合并的先后），")
            appendLine("  可能出现「debug 正常 / release 崩」或「换台机器构建行为就变」。")
            appendLine("  处理：`exclude group:` 掉其中一个（通常是重复引入的传递依赖），")
            appendLine("  或对真正需要的那个用 `implementation` 显式声明、其余 `exclude`。")
            appendLine("· 字节码一致的属「无害重复」，但仍是体积浪费，可通过 exclude 消除。")
        }
        appendLine()

        appendLine("### 2.3 等价库并存")
        appendLine()
        if (d.equivalentCollisions.isEmpty()) {
            appendLine("✅ 未发现。")
        } else {
            d.equivalentCollisions.forEach { c ->
                appendLine("- **${c.group}**：${c.members.joinToString { "`$it`" }}")
                appendLine("  → ${c.advice}")
            }
        }
        appendLine()
    }

    private fun StringBuilder.renderSizeSection(r: SdkAuditResult) {
        appendLine("## 三、体积归属")
        appendLine()
        appendLine("> 口径：**解压后字节数**。代码 = 类文件字节；资源 = AAR 的 `res/` + `assets/`；native = `.so`。")
        appendLine("> ⚠️ 这与「APK 里占多少」不是一回事（APK 中 dex 会重新压缩、.so 常不压缩）。")
        appendLine("> 只看相对排名与占比，别把这里的数字当 APK 增量。")
        appendLine()
        appendLine("| 贡献者 | 类型 | 代码 | 资源 | native | 合计 | 占比 | 类数 |")
        appendLine("|---|---|---|---|---|---|---|---|")
        val all = (listOf(r.sizeReport.firstParty) + r.sizeReport.dependencies)
            .sortedByDescending { it.totalBytes }
        val total = all.sumOf { it.totalBytes }.coerceAtLeast(1)
        all.take(40).forEach { s ->
            appendLine("| `${s.id}` | ${if (s.isThirdParty) "三方" else "自有"} | ${fmt(s.codeBytes)} | ${fmt(s.resBytes)} | ${fmt(s.nativeBytes)} | **${fmt(s.totalBytes)}** | ${"%.1f".format(s.totalBytes * 100.0 / total)}% | ${s.classCount} |")
        }
        if (all.size > 40) appendLine("| … 其余 ${all.size - 40} 项省略 | | | | | | | |")
        appendLine()

        // native 单列 —— 它是「降体积」里最容易被忽略也最容易见效的一块
        val natives = all.filter { it.nativeBytes > 0 }.sortedByDescending { it.nativeBytes }
        if (natives.isNotEmpty()) {
            appendLine("### native 库（体积优化的高杠杆点）")
            appendLine()
            appendLine("| 依赖 | .so 解压字节 | 占比 |")
            appendLine("|---|---|---|")
            natives.take(20).forEach {
                appendLine("| `${it.id}` | ${fmt(it.nativeBytes)} | ${"%.1f".format(it.nativeBytes * 100.0 / total)}% |")
            }
            appendLine()
        }
    }

    private fun StringBuilder.renderThreadSection(r: SdkAuditResult) {
        appendLine("## 四、线程滥用（静态画像）")
        appendLine()
        appendLine("> ⚠️ 这是**静态**画像：统计的是字节码里的线程/执行器**创建点**，")
        appendLine("> 不等于运行时真实线程数（一个创建点可能被循环调用 N 次，也可能从不执行）。")
        appendLine("> 运行时归因见 `sdk-governance-runtime`（Hook `pthread_create` + 堆栈采样）。")
        appendLine()
        if (r.threadReport.profiles.isEmpty()) {
            appendLine("✅ 未发现线程/执行器创建点。")
            appendLine()
            return
        }

        appendLine("| 依赖 | 类型 | 创建点 | 命名率 | 主要形态 |")
        appendLine("|---|---|---|---|---|")
        r.threadReport.profiles.sortedByDescending { it.total }.take(30).forEach { p ->
            val kinds = p.byKind.entries.sortedByDescending { it.value }
                .joinToString(", ") { "${it.key}×${it.value}" }
            appendLine("| `${p.owner}` | ${if (p.isThirdParty) "三方" else "自有"} | **${p.total}** | ${"%.0f".format(p.namedRatio * 100)}% | $kinds |")
        }
        appendLine()

        val high = r.threadReport.highRisk
        if (high.isNotEmpty()) {
            appendLine("### 高风险创建点（裸 `new Thread` 或**逻辑无界队列**）")
            appendLine()
            appendLine("| 类 | 方法 | 形态 | 详情 |")
            appendLine("|---|---|---|---|")
            high.take(50).forEach { s ->
                appendLine("| `${s.className}` | `${s.method.substringBefore('(')}` | ${s.kind} | `${s.detail}` |")
            }
            if (high.size > 50) appendLine("| … 其余 ${high.size - 50} 处省略 | | |")
            appendLine()
            appendLine("**为什么「无界队列」单独标红**：`new LinkedBlockingQueue()`（无参）= 容量 `Integer.MAX_VALUE`，")
            appendLine("而 `ThreadPoolExecutor` 的扩容条件是「队列满」—— 永不成立。")
            appendLine("结果是 `maximumPoolSize` 成了**死配置**，实际并发恒为 `corePoolSize`，")
            appendLine("任务在队列里无限堆积。这不是「线程多」的问题，是**迟早 OOM** 的问题。")
            appendLine()
        }
    }

    private fun fmt(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> "%.2f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    // ══════════════════════════════════════════════════════════════════
    //  JSON（手写，零依赖）
    // ══════════════════════════════════════════════════════════════════

    private fun renderJson(r: SdkAuditResult): String = buildString {
        appendLine("{")
        appendLine("  \"variant\": ${str(r.variant)},")
        appendLine("  \"summary\": {")
        appendLine("    \"dependencies\": ${r.dependencies.size},")
        appendLine("    \"thirdParty\": ${r.dependencyReport.thirdPartyCount},")
        appendLine("    \"permissionsTotal\": ${r.permissions.size},")
        appendLine("    \"permissionsRemovable\": ${r.permissions.count { it.verdict == PermissionVerdict.DECLARED_UNUSED }},")
        appendLine("    \"permissionsRemovableDangerous\": ${r.permissions.count { it.verdict == PermissionVerdict.DECLARED_UNUSED && it.dangerous }},")
        appendLine("    \"versionConflicts\": ${r.dependencyReport.versionConflicts.size},")
        appendLine("    \"classDuplications\": ${r.dependencyReport.classDuplications.size},")
        appendLine("    \"equivalentCollisions\": ${r.dependencyReport.equivalentCollisions.size},")
        appendLine("    \"threadSites\": ${r.threadReport.profiles.sumOf { it.total }},")
        appendLine("    \"threadHighRisk\": ${r.threadReport.highRisk.size}")
        appendLine("  },")
        appendLine("  \"permissions\": [")
        appendLine(r.permissions.joinToString(",\n") { p ->
            "    {\"permission\":${str(p.permission)},\"dangerous\":${p.dangerous}," +
                    "\"verdict\":${str(p.verdict.name)}," +
                    "\"declaredBy\":[${p.declaredBy.joinToString(",") { str(it.id) }}]," +
                    "\"evidenceCount\":${p.usages.size}}"
        })
        appendLine("  ],")
        appendLine("  \"versionConflicts\": [")
        appendLine(r.dependencyReport.versionConflicts.joinToString(",\n") { c ->
            "    {\"module\":${str(c.module)},\"requested\":[${c.requested.joinToString(",") { str(it) }}],\"selected\":${str(c.selected)}}"
        })
        appendLine("  ],")
        appendLine("  \"classDuplications\": [")
        appendLine(r.dependencyReport.classDuplications.joinToString(",\n") { c ->
            "    {\"class\":${str(c.className)},\"providers\":[${c.providers.joinToString(",") { str(it) }}],\"identical\":${c.identical}}"
        })
        appendLine("  ],")
        appendLine("  \"size\": [")
        appendLine((listOf(r.sizeReport.firstParty) + r.sizeReport.dependencies)
            .sortedByDescending { it.totalBytes }.joinToString(",\n") { s ->
                "    {\"id\":${str(s.id)},\"thirdParty\":${s.isThirdParty},\"code\":${s.codeBytes}," +
                        "\"res\":${s.resBytes},\"native\":${s.nativeBytes},\"classes\":${s.classCount}}"
            })
        appendLine("  ],")
        appendLine("  \"threads\": [")
        appendLine(r.threadReport.profiles.joinToString(",\n") { p ->
            "    {\"owner\":${str(p.owner)},\"thirdParty\":${p.isThirdParty},\"sites\":${p.total}," +
                    "\"namedRatio\":${"%.3f".format(p.namedRatio)}}"
        })
        appendLine("  ]")
        append("}")
    }

    private fun str(s: String): String {
        val sb = StringBuilder("\"")
        s.forEach { c ->
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        return sb.append("\"").toString()
    }
}
