package com.interview.thread.plugin

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * 插件配置项。
 *
 * 阅读顺序建议先看 [ThreadClassVisitor]（变换原理），再回来看这里（只是开关）。
 */
abstract class ThreadMonitorExtension {
    /** 是否给匿名线程补名字（Thread() → Thread(String)）。默认开，低风险。 */
    abstract val enableNaming: Property<Boolean>

    /** 是否把 new Thread 收敛到统一线程池。默认关（改变运行时语义，有风险）。 */
    abstract val enableUnify: Property<Boolean>

    /** 不参与插桩的包名前缀（前缀匹配，由 [ThreadClassVisitor.isExcluded] 消费）。 */
    abstract val excludedPackages: ListProperty<String>

    init {
        enableNaming.convention(true)
        enableUnify.convention(false)
        excludedPackages.convention(
            listOf(
                // 插桩自身 + 收敛目标类：不能被自己改造，否则自举出问题
                "com.interview.thread.plugin.",
                "com.interview.thread.UnifiedThread",
            )
        )
    }
}
