package com.interview.sdk.plugin

import com.interview.sdk.plugin.io.ArtifactFacts
import com.interview.sdk.plugin.io.ArtifactScanner
import com.interview.sdk.plugin.io.ArtifactSizes
import com.interview.sdk.plugin.io.ResourceSizes
import com.interview.sdk.plugin.permission.PermissionAuditor
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * 治理审计任务：`sdkAudit<Variant>`。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  任务图位置（为什么依赖 compile 而不是 assemble）
 * ══════════════════════════════════════════════════════════════════════
 *
 *   在 assemble 之前、compile 之后：
 *   compileXxx → sdkAuditXxx → （独立于 assemble，可单独跑）
 *
 *   · 依赖 compile：需要自有代码的 .class 才能做「权限真实使用」判定
 *   · 不依赖 assemble：报告的输入是「依赖 + 自有字节码」，不需要打包成 APK。
 *     APK 层面的分析（dex 构成、R8 删除清单）已有 tools/opt/unused-report.sh，
 *     职责不重叠，也不互相等待。
 */
abstract class SdkAuditTask : DefaultTask() {

    @get:InputFiles
    abstract val dependencyArtifacts: ConfigurableFileCollection

    /**
     * 依赖清单，编码为 `绝对路径\u0001id\u0001扩展名` 的行列表。
     * 用**路径做键**而不是按下标与 [dependencyArtifacts] 配对，原因见插件的注释
     * （Set 不保序会导致静默错位）。用 `\u0001` 而非 `|`/`,`：路径里可能含任何
     * 可打印字符，控制字符是唯一安全的字段分隔符。
     */
    @get:Input
    abstract val dependencyIndex: ListProperty<String>

    @get:Input
    abstract val firstPartyPrefixes: ListProperty<String>

    @get:Input
    abstract val reflectionPronePrefixes: ListProperty<String>

    @get:Input
    abstract val auditPermissions: Property<Boolean>

    @get:Input
    abstract val auditDependencies: Property<Boolean>

    @get:Input
    abstract val auditSize: Property<Boolean>

    @get:Input
    abstract val auditThreads: Property<Boolean>

    @get:Input
    abstract val emitJson: Property<Boolean>

    /** 自有代码编译输出目录（可能多个：kotlin/javac/kapt） */
    @get:InputFiles
    @get:Optional
    abstract val firstPartyClassesDirs: ConfigurableFileCollection

    /** 宿主 manifest，用于「自有代码声明了什么权限」 */
    @get:InputFiles
    @get:Optional
    abstract val hostManifest: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val reportDir: org.gradle.api.file.DirectoryProperty

    @get:Input
    abstract val variantName: Property<String>

    /**
     * 版本冲突清单，由插件在配置期从 `ResolutionResult` 解析后注入。
     *
     * ⚠️ 用 `ListProperty<String>` 承载序列化后的条目而不是直接放 [VersionConflict]
     *    对象：Gradle 的任务输入快照需要对值做序列化与比较，
     *    自带 data class 需要它可序列化且稳定可比（含 List 字段）。
     *    用字符串编码最省事且不会因 Gradle 版本的序列化策略变化而失效。
     *
     * 编码格式：`module|selected|req1,req2|by1;by2`
     */
    @get:Input
    abstract val versionConflicts: ListProperty<String>

    @get:Internal
    abstract val moduleName: Property<String>

    @TaskAction
    fun run() {
        val prefixes = firstPartyPrefixes.getOrElse(emptyList()).map { it.replace('.', '/') }

        // ── 依赖清单：按「文件绝对路径」建索引 ──
        // 绝不用下标与 FileCollection 配对 —— 那条路会因 Set 迭代顺序不同而静默错位。
        val files = dependencyArtifacts.files.toList()
        val byPath = HashMap<String, Pair<String, String>>() // path → (id, kind)
        dependencyIndex.getOrElse(emptyList()).forEach { line ->
            val parts = line.split('\u0001')
            if (parts.size >= 3) byPath[parts[0]] = parts[1] to parts[2]
        }

        val dependencyFiles = LinkedHashMap<String, java.io.File>()
        val deps = files.mapNotNull { f ->
            val (id, kind) = byPath[f.absolutePath] ?: return@mapNotNull null
            dependencyFiles[id] = f
            val parts = id.split(":")
            DependencyInfo(
                id = id,
                group = parts.getOrElse(0) { "" },
                name = parts.getOrElse(1) { "" },
                version = parts.getOrElse(2) { "" },
                file = f.absolutePath,
                kind = kind,
                isThirdParty = prefixes.none { id.startsWith(it) } && !id.startsWith("project:"),
                projectPath = if (id.startsWith("project:")) id.removePrefix("project:") else null,
            )
        }

        logger.lifecycle("[sdk-governance] 开始扫描 ${deps.size} 个依赖（自有代码前缀：${firstPartyPrefixes.getOrElse(emptyList())}）")

        // ── 单次遍历：所有依赖的字节码事实 ──
        val artifacts = HashMap<String, ArtifactFacts>()
        val sizes = HashMap<String, ResourceSizes>()
        var done = 0
        deps.forEach { dep ->
            val f = dependencyFiles[dep.id]
            if (f == null || !f.exists()) {
                logger.warn("[sdk-governance] 产物缺失，跳过：${dep.id}")
                return@forEach
            }
            artifacts[dep.id] = ArtifactScanner.scan(f, dep.id)
            sizes[dep.id] = ArtifactSizes.measure(f)
            if (++done % 25 == 0) logger.lifecycle("[sdk-governance] 已扫描 $done/${deps.size}")
        }

        // ── 自有代码 ──
        val firstPartyDirs = firstPartyClassesDirs.files.filter { it.isDirectory }
        val (fpFacts, fpBytes) = FirstPartyScanner.scan(firstPartyDirs)
        artifacts[PermissionAuditor.FIRST_PARTY] = fpFacts

        val hostManifestFile = hostManifest.files.firstOrNull { it.isFile }

        // ── 维度 1：权限 ──
        val permissions = if (auditPermissions.getOrElse(true)) {
            PermissionAuditor(
                firstPartyPrefixes = firstPartyPrefixes.getOrElse(emptyList()),
                reflectionPronePrefixes = reflectionPronePrefixes.getOrElse(emptyList()),
            ).audit(hostManifestFile, artifacts, dependencyFiles, deps)
        } else emptyList()

        // ── 维度 2：依赖重复 ──
        val dependencyReport = if (auditDependencies.getOrElse(true)) {
            DependencyAuditor(prefixes).audit(deps, artifacts, decodeConflicts())
        } else emptyDependencyReport()

        // ── 维度 3：体积 ──
        val sizeReport = if (auditSize.getOrElse(true)) {
            SizeAuditor().audit(deps, artifacts, sizes, fpFacts, fpBytes)
        } else SizeReport(
            SizeContribution(PermissionAuditor.FIRST_PARTY, false, 0, 0, 0, 0),
            emptyList(),
        )

        // ── 维度 4：线程 ──
        val threadReport = if (auditThreads.getOrElse(true)) {
            ThreadAuditor(prefixes).audit(deps, artifacts)
        } else ThreadReport(emptyList())

        val result = SdkAuditResult(
            variant = variantName.get(),
            dependencies = deps,
            permissions = permissions,
            dependencyReport = dependencyReport,
            sizeReport = sizeReport,
            threadReport = threadReport,
            caveats = Caveats.forRun(firstPartyDirs.size, artifacts.size, deps.size),
        )

        val outDir = reportDir.get().asFile
        val md = ReportWriter.write(result, outDir, emitJson.getOrElse(true))
        logger.lifecycle("[sdk-governance] 报告已生成：$md")
        logger.lifecycle(
            "[sdk-governance] 摘要：可移除权限 ${result.permissions.count { it.verdict == PermissionVerdict.DECLARED_UNUSED }}，" +
                    "版本冲突 ${result.dependencyReport.versionConflicts.size}，" +
                    "重复类 ${result.dependencyReport.classDuplications.size}，" +
                    "线程创建点 ${result.threadReport.profiles.sumOf { it.total }}"
        )
    }

    private fun emptyDependencyReport() = DependencyReport(0, 0, emptyList(), emptyList(), emptyList())

    /**
     * 反解插件注入的版本冲突编码：`module|selected|req1,req2|by1;by2`。
     *
     * ⚠️ 用 `\u0001` 分隔「请求方说明」内部字段，而不是 `|` —— 依赖的 displayName
     *    里本来就含 `:` 甚至 `|`，选错分隔符会导致字段错位且**静默**。
     */
    private fun decodeConflicts(): List<VersionConflict> =
        versionConflicts.getOrElse(emptyList()).mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size < 4) return@mapNotNull null
            VersionConflict(
                module = parts[0],
                requested = parts[2].split(',').filter { it.isNotBlank() },
                selected = parts[1],
                requestedBy = parts[3].split(';').filter { it.isNotBlank() },
            )
        }
}
