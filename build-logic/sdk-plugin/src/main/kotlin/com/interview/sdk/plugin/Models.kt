package com.interview.sdk.plugin

/**
 * SDK 治理四个维度的数据模型。
 *
 * 设计原则：**每个结论都要能回答「凭什么」**。
 * 所以模型里没有裸结论字段，全部是「证据 + 结论」，且证据字段是必填的。
 * 这是本工具与「跑个 gradle dependencies 看树」的本质区别。
 */

// ══════════════════════════════════════════════════════════════════════
//  维度 0：依赖清单（其余三个维度的公共底座）
// ══════════════════════════════════════════════════════════════════════

/** 一个已解析到产物的依赖。 */
data class DependencyInfo(
    /** group:name:version，已解析后的实际版本 */
    val id: String,
    val group: String,
    val name: String,
    val version: String,
    /** 产物绝对路径 */
    val file: String,
    /** 产物类型：aar / jar / dir（本地 project 依赖） */
    val kind: String,
    /** 该依赖是否被判定为「三方 SDK」（按 firstPartyPrefixes 划分） */
    val isThirdParty: Boolean,
    /** 对于 project 依赖（本地模块），记录其 project 路径 */
    val projectPath: String? = null,
)

// ══════════════════════════════════════════════════════════════════════
//  维度 1：权限声明与真实使用
// ══════════════════════════════════════════════════════════════════════

/** 权限的声明者。 */
data class PermissionDeclarer(
    val id: String,
    val isThirdParty: Boolean,
)

/** 权限的使用证据。 */
data class PermissionUsage(
    /** 使用方（依赖 id，或 "first-party" 表示自有代码） */
    val by: String,
    /** 证据类型：EXPLICIT_CONSTANT（Manifest.permission.X 或字符串字面量）/ IMPLIED_BY_API（调用需要该权限的 API） */
    val kind: String,
    /** 具体证据：类#方法 或 API 签名 */
    val evidence: String,
)

enum class PermissionVerdict {
    /** 自有代码里有明确使用证据 */
    USED_BY_FIRST_PARTY,
    /** 仅 SDK 内部使用（自有代码未用） */
    USED_BY_SDK_ONLY,
    /** 全工程（自有 + 已扫描依赖）都找不到使用证据 */
    DECLARED_UNUSED,
    /** 声明者属反射高发名单，静态分析不可信 */
    REFLECTION_PRONE,
    /** 无自有代码，且无任何证据 —— 但与平台/SDK 版本强相关，需人工确认 */
    UNCERTAIN,
}

data class PermissionFinding(
    val permission: String,
    /** 是否危险权限（运行时申请）—— 决定移除后的运行期风险 */
    val dangerous: Boolean,
    val declaredBy: List<PermissionDeclarer>,
    val usages: List<PermissionUsage>,
    val verdict: PermissionVerdict,
    /** 移除方案。verdict 为 USED_* 时为空 */
    val removalAdvice: String?,
)

// ══════════════════════════════════════════════════════════════════════
//  维度 2：依赖重复与版本冲突
// ══════════════════════════════════════════════════════════════════════

/** group:name 相同但被多个版本请求 —— Gradle 仲裁前的冲突。 */
data class VersionConflict(
    val module: String,
    /** 被请求的所有版本 */
    val requested: List<String>,
    /** 最终选中的版本 */
    val selected: String,
    /** 谁请求的（依赖路径） */
    val requestedBy: List<String>,
)

/** 同一个类出现在多个依赖里 —— 真正的「重复打包」，比版本冲突更危险。 */
data class ClassDuplication(
    val className: String,
    /** 提供该类字节码的所有依赖 id */
    val providers: List<String>,
    /** 不同提供者之间字节码是否一致（不一致 = 行为不确定，谁被打进去取决于顺序） */
    val identical: Boolean,
)

/** 功能等价但坐标不同的库并存（support↔androidx、okhttp3↔okhttp 等）。 */
data class EquivalentGroupCollision(
    val group: String,
    val members: List<String>,
    val advice: String,
)

data class DependencyReport(
    val total: Int,
    val thirdPartyCount: Int,
    val versionConflicts: List<VersionConflict>,
    val classDuplications: List<ClassDuplication>,
    val equivalentCollisions: List<EquivalentGroupCollision>,
)

// ══════════════════════════════════════════════════════════════════════
//  维度 3：体积归属
// ══════════════════════════════════════════════════════════════════════

data class SizeContribution(
    val id: String,
    val isThirdParty: Boolean,
    /** 代码（.class/.dex 前身）解压字节数 */
    val codeBytes: Long,
    /** 资源解压字节数（AAR 的 res/ + assets/） */
    val resBytes: Long,
    /** native .so 解压字节数 */
    val nativeBytes: Long,
    val classCount: Int,
) {
    val totalBytes: Long get() = codeBytes + resBytes + nativeBytes
}

data class SizeReport(
    val firstParty: SizeContribution,
    /** 按 totalBytes 降序 */
    val dependencies: List<SizeContribution>,
)

// ══════════════════════════════════════════════════════════════════════
//  维度 4：线程滥用（静态）
// ══════════════════════════════════════════════════════════════════════

enum class ThreadKind {
    NEW_THREAD,
    NEW_THREAD_SUBCLASS,
    EXECUTOR_FACTORY,
    THREAD_POOL_EXECUTOR,
    SCHEDULED_EXECUTOR,
    HANDLER_THREAD,
    TIMER,
    ASYNC_TASK,
    COROUTINE_NEW_SINGLE_THREAD, // newSingleThreadContext 等
}

/** 一处线程/执行器创建点。 */
data class ThreadCreationSite(
    val owner: String,
    val className: String,
    val method: String,
    val kind: ThreadKind,
    /** 附加信息：线程池参数、队列是否无界、线程名等 */
    val detail: String,
)

/** 按依赖聚合的线程画像。 */
data class ThreadProfile(
    val owner: String,
    val isThirdParty: Boolean,
    val sites: List<ThreadCreationSite>,
) {
    val total: Int get() = sites.size
    val byKind: Map<ThreadKind, Int> get() = sites.groupingBy { it.kind }.eachCount()
    /** 有名字的线程点占比 —— 命名率低 = 线上不可溯源 */
    val namedRatio: Float
        get() = if (sites.isEmpty()) 0f
        else sites.count { it.detail.contains("name=") }.toFloat() / sites.size
}

data class ThreadReport(
    val profiles: List<ThreadProfile>,
) {
    /** 最可能造成线程数量失控的形态：无界队列 + 每实例新建。 */
    val highRisk: List<ThreadCreationSite>
        get() = profiles.flatMap { it.sites }
            .filter { it.detail.contains("queue=unbounded") || it.kind == ThreadKind.NEW_THREAD }
}

// ══════════════════════════════════════════════════════════════════════
//  汇总
// ══════════════════════════════════════════════════════════════════════

data class SdkAuditResult(
    val variant: String,
    val dependencies: List<DependencyInfo>,
    val permissions: List<PermissionFinding>,
    val dependencyReport: DependencyReport,
    val sizeReport: SizeReport,
    val threadReport: ThreadReport,
    /** 工具自身的局限与未覆盖项，报告末尾必须原样输出 */
    val caveats: List<String>,
)
