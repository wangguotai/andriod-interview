package com.interview.sdk.plugin.permission

import com.interview.sdk.plugin.DependencyInfo
import com.interview.sdk.plugin.PermissionDeclarer
import com.interview.sdk.plugin.PermissionFinding
import com.interview.sdk.plugin.PermissionUsage
import com.interview.sdk.plugin.PermissionVerdict
import com.interview.sdk.plugin.io.ArtifactFacts
import com.interview.sdk.plugin.io.ManifestParser

/**
 * 权限审计：回答三个递进的问题。
 *
 *   ① 谁声明的？   —— 权限合并（manifest merger）后责任归属
 *   ② 有没有真用？ —— 静态可达的证据（显式常量 + API 隐含）
 *   ③ 没用怎么删？ —— 给出可执行的移除方案，而不是「建议删除」
 *
 * ══════════════════════════════════════════════════════════════════════
 *  判定模型：证据分两类，结论看「证据落在谁身上」
 * ══════════════════════════════════════════════════════════════════════
 *
 *  显式证据（EXPLICIT_CONSTANT）
 *    · 字节码里的 `LDC "android.permission.X"`（SDK 主动声明/检查）
 *    · `AndroidManifest.permission.X` 常量的读取
 *
 *  隐含证据（IMPLIED_BY_API）
 *    · 调用了平台 API，而该 API 需要权限 —— **不需要任何权限字符串**
 *    · 这是漏报的重灾区，见 [ApiPermissionMap] 的注释
 *
 *  然后按「证据属于自有代码还是某个 SDK」分档：
 *
 *   自有代码用了          → USED_BY_FIRST_PARTY  → 不可移除
 *   只有 SDK 内部用       → USED_BY_SDK_ONLY     → 不可直接删；方向是「换 SDK / 调配置」
 *   谁都找不到            → DECLARED_UNUSED      → 可移除（附移除方案）
 *   声明者属反射高发名单  → REFLECTION_PRONE     → 不自动给删除建议，转人工
 *
 * ⚠️ 为什么「仅 SDK 内部使用」不能一删了之：
 *    权限声明在 manifest 合并后是**宿主 App 的责任**。删掉后 SDK 静默失效
 *    （返回 null / 抛 SecurityException），且往往在灰度阶段才暴露。
 *    正确方向是「让 SDK 不再需要」——移除该 SDK、关掉它的该功能开关、
 *    或改用不依赖该权限的版本。
 */
internal class PermissionAuditor(
    private val firstPartyPrefixes: List<String>,
    private val reflectionPronePrefixes: List<String>,
) {

    /**
     * @param hostManifest 宿主（自有代码）的 src/main/AndroidManifest.xml
     * @param artifacts    每个依赖 id → 其字节码事实
     * @param dependencyFiles  依赖 id → 产物文件（用于读 AAR manifest）
     */
    fun audit(
        hostManifest: java.io.File?,
        artifacts: Map<String, ArtifactFacts>,
        dependencyFiles: Map<String, java.io.File>,
        dependencies: List<DependencyInfo>,
    ): List<PermissionFinding> {

        // ── ① 收集声明者 ──
        val declarers = HashMap<String, MutableList<PermissionDeclarer>>()

        // 宿主 manifest
        // ⚠️ 宿主声明里要**区分用途**：有的是为了自己用，有的只是为了「接住」SDK 的合并声明。
        //    这个区分在报告里有实际意义（见 PermissionDeclarer.selfDeclared 的处理），
        //    但在本版本里统一按「自有代码声明」记录，交由 verdict 阶段用使用证据区分。
        hostManifest?.let { m ->
            ManifestParser.permissionsFromTextManifest(m).forEach { p ->
                declarers.getOrPut(p) { mutableListOf() }
                    .add(PermissionDeclarer(id = FIRST_PARTY, isThirdParty = false))
            }
        }

        // 每个依赖的 AAR manifest
        dependencyFiles.forEach { (id, file) ->
            if (!file.isFile || !file.name.endsWith(".aar", ignoreCase = true)) return@forEach
            val isThird = dependencies.firstOrNull { it.id == id }?.isThirdParty ?: true
            ManifestParser.permissionsFromAar(file).forEach { p ->
                val list = declarers.getOrPut(p) { mutableListOf() }
                if (list.none { it.id == id }) list.add(PermissionDeclarer(id, isThird))
            }
        }

        // ── ② 收集使用证据 ──
        // 证据必须带「类#方法」位置，否则报告只能告诉人「用了」，
        // 而无法回答「在哪儿用的」—— 后者才是能去改的东西。
        val usages = HashMap<String, MutableList<PermissionUsage>>()
        fun addUsage(perm: String, usage: PermissionUsage) {
            val list = usages.getOrPut(perm) { mutableListOf() }
            if (list.none { it.by == usage.by && it.evidence == usage.evidence }) list.add(usage)
        }

        artifacts.forEach { (id, facts) ->
            // 2a. 显式：字符串常量 "android.permission.X"（含位置）
            facts.stringConstants.forEach { (s, where) ->
                if (s.startsWith("android.permission.")) {
                    addUsage(
                        s.removePrefix("android.permission."),
                        PermissionUsage(by = id, kind = "EXPLICIT", evidence = where),
                    )
                }
            }
            // 2b. 显式：Manifest.permission.X 字段读取。
            //     多数写法会被编译期内联成 LDC（即 2a 已覆盖），
            //     但通过反射或字段访问的写法不会内联，这里补齐。
            facts.memberRefs.forEach { ref ->
                if (ref.startsWith("android/Manifest\$permission#")) {
                    addUsage(
                        ref.substringAfter('#'),
                        PermissionUsage(by = id, kind = "EXPLICIT", evidence = ref),
                    )
                }
            }
            // 2c. 隐含：API 反查 —— 唯一能发现「零字符串但确实依赖权限」的途径
            facts.memberRefs.forEach { ref ->
                val owner = ref.substringBefore('#')
                val method = ref.substringAfter('#')
                ApiPermissionMap.lookup(owner, method).forEach { short ->
                    addUsage(
                        short,
                        PermissionUsage(by = id, kind = "API→权限", evidence = ref),
                    )
                }
            }
        }

        // ── ③ 合并成结论 ──
        // 之所以以「声明集合」为遍历基准，而不是「使用集合」：
        // 治理的对象是「被声明出来的权限」。用的但没声明的权限由系统管，
        // 不在这份报告的责任范围内。
        val allPerms = (declarers.keys + usages.keys).sorted()
        return allPerms.map { perm ->
            val decl = declarers[perm].orEmpty()
            val uses = usages[perm].orEmpty()
            val firstPartyUses = uses.filter { isFirstParty(it.by) }
            val sdkUses = uses.filterNot { isFirstParty(it.by) }
            val declarerReflectionProne = decl.any { d ->
                // ⚠️ 只有「三方 + 命中反射高发名单」才算不可判定。
                //    曾经的条件写成 `!d.isThirdParty || ...`，把**自有代码**也判成了
                //    反射高发 —— 后果是自家 manifest 里的权限全部落进「人工确认」，
                //    掩盖了真正该看的结论（谁知道自己的代码有没有用这个权限）。
                //    第一方代码是我们能读的，永远不该以「可能反射」为由放弃判定。
                d.isThirdParty && reflectionPronePrefixes.any { p -> d.id.startsWith(p) }
            }

            val verdict = when {
                firstPartyUses.isNotEmpty() -> PermissionVerdict.USED_BY_FIRST_PARTY
                sdkUses.isNotEmpty() -> PermissionVerdict.USED_BY_SDK_ONLY
                decl.isEmpty() -> PermissionVerdict.UNCERTAIN
                declarerReflectionProne -> PermissionVerdict.REFLECTION_PRONE
                else -> PermissionVerdict.DECLARED_UNUSED
            }

            PermissionFinding(
                permission = perm,
                dangerous = ApiPermissionMap.isDangerous(perm),
                declaredBy = decl,
                usages = uses,
                verdict = verdict,
                removalAdvice = removalAdvice(perm, verdict, decl, sdkUses),
            )
        }
    }

    private fun isFirstParty(id: String): Boolean =
        id == FIRST_PARTY || firstPartyPrefixes.any { id.startsWith(it) }

    private fun removalAdvice(
        perm: String,
        verdict: PermissionVerdict,
        decl: List<PermissionDeclarer>,
        sdkUses: List<PermissionUsage>,
    ): String? = when (verdict) {
        PermissionVerdict.USED_BY_FIRST_PARTY -> null

        PermissionVerdict.USED_BY_SDK_ONLY -> buildString {
            appendLine("⚠️ 不可直接删除 —— 以下 SDK 内部依赖它：")
            sdkUses.map { it.by }.distinct().forEach { appendLine("   · $it") }
            appendLine("删除会导致这些 SDK 静默失效（返回 null / SecurityException），且灰度期不易发现。")
            appendLine("正确方向（按优先级）：")
            appendLine("   1. 移除该 SDK，或改用不依赖此权限的替代库")
            appendLine("   2. 查阅 SDK 文档，关闭需要该权限的功能开关")
            appendLine("   3. 若 SDK 支持，升级到已不再申请该权限的版本")
            appendLine("   4. 上述都不可行 → 保留，但在隐私合规清单里登记用途")
        }

        PermissionVerdict.DECLARED_UNUSED -> buildString {
            appendLine("✅ 可移除（静态分析未发现任何使用证据）")
            val fromSdk = decl.filter { it.isThirdParty }
            if (fromSdk.isNotEmpty()) {
                appendLine("声明来源：${fromSdk.joinToString { it.id }}")
                appendLine("移除方式：在宿主 manifest 用 tools:node=\"remove\" 覆盖 SDK 的声明：")
                appendLine("")
                appendLine("    <uses-permission android:name=\"android.permission.$perm\"")
                appendLine("        tools:node=\"remove\" />")
                appendLine("")
                appendLine("⚠️ 需要 <manifest xmlns:tools=\"http://schemas.android.com/tools\">")
            } else {
                appendLine("声明来源：自有代码（src/main/AndroidManifest.xml）")
                appendLine("移除方式：直接删掉该行 <uses-permission> 即可。")
            }
            if (ApiPermissionMap.isDangerous(perm)) {
                appendLine("💡 这是危险权限 —— 移除后请在隐私合规文档里同步删除对应条目。")
            }
            appendLine("⚠️ 落地前必须做：真机跑一遍相关功能。静态「未使用」不等于运行时不使用")
            appendLine("   （反射 / ServiceLoader / 只在特定机型分支调用，都可能逃过静态扫描）。")
        }

        PermissionVerdict.REFLECTION_PRONE -> buildString {
            append("❓ 不自动判定 —— 声明方（${decl.joinToString { it.id }}）在反射高发名单内。")
            append("这类 SDK 常通过反射/动态加载访问需要权限的 API，静态可达性分析不可信。")
            append("请人工确认：抓一次运行时权限申请日志（adb shell dumpsys package <pkg> | grep -A50 requested permissions）")
        }

        PermissionVerdict.UNCERTAIN ->
            "❓ 未发现声明也未发现使用 —— 可能来自 manifest 合并的间接来源或工具版本差异，建议人工核对最终 APK 的合并 manifest。"
    }

    companion object {
        const val FIRST_PARTY = "first-party"
    }
}
