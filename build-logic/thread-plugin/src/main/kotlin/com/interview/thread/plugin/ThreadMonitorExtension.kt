package com.interview.thread.plugin

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * 插件配置项。
 */
abstract class ThreadMonitorExtension {
    /** 是否给匿名线程补名字（Thread() → Thread(String)）。默认开。 */
    abstract val enableNaming: Property<Boolean>

    /** 是否把 new Thread 收敛到统一线程池。默认关（有运行时风险）。 */
    abstract val enableUnify: Property<Boolean>

    /** 不参与插桩的包名前缀。 */
    abstract val excludedPackages: ListProperty<String>

    init {
        enableNaming.convention(true)
        enableUnify.convention(false)
        excludedPackages.convention(
            listOf(
                "com.interview.thread.plugin.",
                "com.interview.thread.UnifiedThread",
            )
        )
    }
}
