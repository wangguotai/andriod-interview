package com.interview.sdk.plugin

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * SDK 治理插件的配置项。四个审计维度各有独立开关，默认全开（只读、无副作用）。
 *
 * 使用方式（app/build.gradle.kts）：
 * ```
 * plugins { id("com.interview.sdk.governance") }
 * configure<SdkGovernanceExtension> {
 *     firstPartyPrefixes.addAll("com.interview.", "com.example.myapplication.")
 * }
 * ```
 */
abstract class SdkGovernanceExtension {

    /** 审计总开关。关掉后任务存在但会立即跳过。 */
    abstract val enabled: Property<Boolean>

    /** 权限声明 ↔ 真实使用 的比对与移除建议。 */
    abstract val auditPermissions: Property<Boolean>

    /** 依赖重复 / 版本冲突（按 group 归并 + 传递依赖树）。 */
    abstract val auditDependencies: Property<Boolean>

    /** 体积归属：把产物按「贡献者（自有代码 / 每个依赖 / native）」拆分。 */
    abstract val auditSize: Property<Boolean>

    /** 线程滥用：静态扫依赖字节码里的 Thread/Executor 创建点 + 线程池配置。 */
    abstract val auditThreads: Property<Boolean>

    /**
     * 「自有代码」的包名前缀（点号或斜杠均可，内部统一）。
     *
     * ⚠️ 这个配置决定「三方 SDK」的判定边界，因而决定权限真实使用判定的结论。
     * 判错的方向是不对称的：
     *   · 前缀写得太窄 → 自家代码被当成 SDK → 权限被误判为「有使用者」→ 漏报（安全）
     *   · 前缀写得太宽 → SDK 代码被当成自家 → 权限被误判为「无人使用」→ **误删（危险）**
     * 所以默认值刻意保守，宁可漏报。首次接入务必先看报告里的「归属分布」再收窄。
     */
    abstract val firstPartyPrefixes: ListProperty<String>

    /**
     * 已知会反射 / 动态加载权限相关 API 的 SDK 包前缀扩展。
     * 静态可达性分析天然看不见反射，命中这些前缀时报告会降级为「疑似不可判定」。
     */
    abstract val reflectionPronePrefixes: ListProperty<String>

    /** 报告输出目录（相对 buildDir）。 */
    abstract val reportDirName: Property<String>

    /** 是否把报告同时写一份机器可读的 JSON（供 CI 卡口消费）。 */
    abstract val emitJson: Property<Boolean>

    init {
        enabled.convention(true)
        auditPermissions.convention(true)
        auditDependencies.convention(true)
        auditSize.convention(true)
        auditThreads.convention(true)
        emitJson.convention(true)
        reportDirName.convention("reports/sdk-governance")
        reflectionPronePrefixes.convention(
            listOf(
                "com.google.android.gms",
                "com.google.firebase",
                "com.tencent",
                "com.bytedance",
                "com.alipay",
                "com.huawei",
                "com.android.billingclient",
            )
        )
    }
}
