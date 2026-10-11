package com.interview.sdk.plugin

import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolutionResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.api.tasks.TaskProvider

/**
 * 三方 SDK 治理插件入口：`com.interview.sdk.governance`。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  接进来之后发生了什么（任务图）
 * ══════════════════════════════════════════════════════════════════════
 *
 *   compileXxxKotlin ──┐
 *   <dependency closure>├──► sdkAuditXxx ──► build/reports/sdk-governance/
 *   AndroidManifest.xml─┘                      ├── sdk-audit.md   人读
 *                                              └── sdk-audit.json CI 卡口
 *
 *   四个维度（权限 / 依赖 / 体积 / 线程）在**一次遍历**里全部产出，
 *   原因见 [com.interview.sdk.plugin.io.ArtifactScanner] 的注释。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  ⚠️ 为什么版本冲突要读 `ResolutionResult` 而不是 `runtimeClasspath.files`
 * ══════════════════════════════════════════════════════════════════════
 *
 *  「重复依赖 / 版本冲突」需要的是**仲裁过程**的数据：
 *   · 谁请求了哪个版本（requested）
 *   · 最终选中了哪个（selected）
 *
 *  这些只有依赖图解析结果有。直接看 `configuration.files` 只能拿到
 *  最终文件列表 —— 看不到「被仲裁丢弃的版本」，而**被丢弃的版本恰恰是证据**。
 *  只看文件列表，冲突永远是 0，报告会给出一个虚假的「一切正常」。
 */
abstract class SdkGovernancePlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("sdkGovernance", SdkGovernanceExtension::class.java)

        val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
        if (androidComponents == null) {
            project.logger.warn("[sdk-governance] 未找到 AndroidComponentsExtension，插件跳过（仅适用于 Android 应用/库模块）")
            return
        }

        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            if (!ext.enabled.getOrElse(true)) return@onVariants

            val variantName = variant.name
            val taskName = "sdkAudit${variantName.replaceFirstChar { it.uppercase() }}"

            val task = project.tasks.register(taskName, SdkAuditTask::class.java) { t ->
                t.group = "sdk governance"
                t.description = "审计三方 SDK：权限声明与真实使用 / 依赖重复 / 体积归属 / 线程滥用（$variantName）"

                t.variantName.set(variantName)
                t.moduleName.set(project.name)
                t.firstPartyPrefixes.set(ext.firstPartyPrefixes)
                t.reflectionPronePrefixes.set(ext.reflectionPronePrefixes)
                t.auditPermissions.set(ext.auditPermissions)
                t.auditDependencies.set(ext.auditDependencies)
                t.auditSize.set(ext.auditSize)
                t.auditThreads.set(ext.auditThreads)
                t.emitJson.set(ext.emitJson)
                t.reportDir.set(project.layout.buildDirectory.dir(ext.reportDirName.get()))

                // ── 自有代码编译输出 ──
                // 用「任务依赖 + 懒求值」而不是直接读目录：
                //   直接读会在配置期拿到空目录列表（编译还没跑），表现为
                //   「第一次跑报告是空的、第二次才对」—— 这类问题最难查。
                //
                // ⚠️ 把 TaskCollection 直接交给 dependsOn，**不要**在
                //    tasks.register 的配置动作里用 tasks.matching{}.configureEach：
                //    那个位置处于「任务集合只读」的上下文，会抛
                //    "DefaultTaskCollection#configureEach(Action) on task set
                //     cannot be executed in the current context"。
                //    dependsOn(TaskCollection) 本身就是懒求值的，正好符合需求。
                t.dependsOn(
                    project.tasks.matching {
                        it.name.startsWith("compile") &&
                                (it.name.endsWith("Kotlin") || it.name.endsWith("JavaWithJavac")) &&
                                it.name.contains(variantName, ignoreCase = true)
                    }
                )
                t.dependsOn(project.tasks.matching { it.name == "preBuild" })

                t.firstPartyClassesDirs.setFrom(
                    project.provider { FirstPartyScanner.findOutputDirs(project.projectDir, variantName) }
                )

                // ── 宿主 manifest（权限归属的「自有代码」一侧）──
                t.hostManifest.setFrom(
                    project.file("src/main/AndroidManifest.xml"),
                    project.file("src/${variantName}/AndroidManifest.xml"),
                )
            }

            bindDependencyInputs(project, variantName, task)
        }
    }

    /**
     * 把 `runtimeClasspath` 的解析结果绑定到任务输入。
     *
     * ⚠️ 刻意用 `runtimeClasspath`（运行时）而不是 `compileClasspath`：
     *    治理目标是「**会进包**的东西」。compileOnly 不进包（如 AGP API、注解处理器）
     *    不该出现在报告里；而 runtimeOnly（日志实现、驱动）恰恰进包，必须覆盖。
     */
    private fun bindDependencyInputs(
        project: Project,
        variantName: String,
        task: TaskProvider<SdkAuditTask>,
    ) {
        // Configuration 名带 variant 前缀：debugRuntimeClasspath / releaseRuntimeClasspath
        val configName = "${variantName}RuntimeClasspath"
        val configuration = project.configurations.findByName(configName)
        if (configuration == null) {
            project.logger.warn("[sdk-governance] 未找到配置 $configName，依赖维度将为空")
            return
        }

        // ⚠️ 用 artifactView 而不是 configuration.files：前者携带「文件 ↔ 组件标识」的
        //    对应关系，是回答「这个 jar 属于哪个依赖」的唯一途径。
        //    lenient = 依赖偶有元数据缺 artifact（老 AAR），严格模式会让构建直接失败，
        //    而治理插件**绝不能成为构建失败的原因** —— 它是只读旁路工具。
        val artifactView = configuration.incoming.artifactView { it.lenient(true) }
        val resolved = artifactView.artifacts.resolvedArtifacts

        task.configure { t ->
            t.dependencyArtifacts.from(artifactView.files)
                // ── 依赖清单：以「绝对路径 → id」索引的形式注入 ──
                //
                // ⚠️⚠️ 这里踩过一次真实的坑，务必不要退回「按下标配对」：
                //    `resolvedArtifacts` 的类型是 `Provider<Set<...>>`，**Set 不保证顺序**，
                //    而 `artifactView.files` 也是 Set。两者各自迭代顺序可能不同，
                //    于是 id 与文件**静默错位** —— 表现为「disklrucache 报出 5.79 MB /
                //    1758 个类」（那其实是 foundation 的数字）。
                //    最坏的地方是：错位不报错，报告看起来完全正常，只有数字荒谬到
                //    离谱时才可能被注意到；体积排名、权限归属会**全部**污染。
                //
                //    正确做法：让两个输入自带可匹配的键（文件绝对路径），
                //    在任务侧按路径查表，彻底消除对迭代顺序的依赖。
                t.dependencyIndex.set(
                    resolved.map { set ->
                        set.map { artifact ->
                            "${artifact.file.absolutePath}\u0001" +
                                    describe(artifact.id.componentIdentifier) + "\u0001" +
                                    artifact.file.extension.ifEmpty { "unknown" }
                        }
                    }
                )
        }

        // ── 版本冲突：延迟到「查询时」才解析依赖图 ──
        // 用 project.provider 而不是在配置阶段直接解析：provider 的值只在
        // 被任务输入快照时求值（即执行期），避免拖慢 configuration 阶段，
        // 也避免与 AGP 自身的 variant 装配竞争。
        task.configure { t ->
            t.versionConflicts.set(
                project.provider {
                    runCatching { collectVersionConflicts(configuration.incoming.resolutionResult) }
                        .onFailure {
                            project.logger.warn("[sdk-governance] 版本冲突解析失败：${it.message}")
                        }
                        .getOrElse { emptyList() }
                        .map { c -> "${c.module}|${c.selected}|${c.requested.joinToString(",")}|${c.requestedBy.joinToString(";")}" }
                }
            )
        }
    }

    private fun describe(id: ComponentIdentifier): String = when (id) {
        is ModuleComponentIdentifier -> "${id.group}:${id.module}:${id.version}"
        is ProjectComponentIdentifier -> "project:${id.projectPath}"
        else -> id.displayName
    }

    /**
     * 从解析结果里找出「请求的版本 ≠ 最终选中的版本」的模块。
     *
     * ══════════════════════════════════════════════════════════════════════
     *  ⚠️ 判据为什么是「requested != selected」而不是「请求了多个版本」
     * ══════════════════════════════════════════════════════════════════════
     *
     *  后者会淹没在噪音里：一个稍大的工程里，几乎**每个**热门库都会被
     *  不同依赖以不同版本请求（A 依赖要 1.2.0，B 依赖要 1.3.0），
     *  Gradle 取最高版 —— 这属于正常协作，不是问题。
     *
     *  真正需要人看一眼的是：**某个依赖拿到了它没声明的版本**。
     *  Java 生态不保证二进制兼容（版本号不严格遵循语义化），
     *  被静默提升后可能 NoSuchMethodError —— 而且往往在灰度时才崩。
     *
     *  所以本方法**只暴露事实**（谁被提升到了什么版本），
     *  不判定「有问题」—— 判断是否安全需要语义化版本信息或人工，工具不该越权。
     */
    private fun collectVersionConflicts(result: ResolutionResult): List<VersionConflict> {
        // module → (requested 版本集合, selected 版本, 请求方路径)
        data class Acc(val requested: MutableSet<String>, var selected: String, val by: MutableList<String>)

        val acc = LinkedHashMap<String, Acc>()

        result.allDependencies.forEach { dep ->
            if (dep !is ResolvedDependencyResult) return@forEach
            val requested = dep.requested as? ModuleComponentSelector ?: return@forEach
            val selectedId = dep.selected.id as? ModuleComponentIdentifier ?: return@forEach

            // 只关心「被提升/降级」的：请求版本与选中版本不同
            if (requested.version == selectedId.version) return@forEach

            val key = "${selectedId.group}:${selectedId.module}"
            val a = acc.getOrPut(key) {
                Acc(mutableSetOf(), selectedId.version, mutableListOf())
            }
            a.requested.add(requested.version)
            a.selected = selectedId.version
            val from = dep.from.id.displayName
            a.by.add("$from 请求 ${requested.version} → 实际 ${selectedId.version}")
        }

        return acc.map { (module, a) ->
            VersionConflict(
                module = module,
                requested = a.requested.sorted(),
                selected = a.selected,
                requestedBy = a.by.distinct().take(20),
            )
        }.sortedByDescending { it.requested.size }
    }
}
