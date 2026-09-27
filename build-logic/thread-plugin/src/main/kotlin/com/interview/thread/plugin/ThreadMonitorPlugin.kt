package com.interview.thread.plugin

import com.android.build.api.instrumentation.InstrumentationScope
import com.android.build.api.variant.AndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Time: 2026/9/27
 * Author: wgt
 * Description: 线程治理 Gradle 插件入口
 *
 * 用法（app/build.gradle.kts）：
 *   plugins { id("com.interview.thread.monitor") }
 *   configure<ThreadMonitorExtension> {
 *       enableNaming.set(true)    // 给匿名线程补名字
 *       enableUnify.set(false)    // 收敛到统一池（有风险，默认关）
 *   }
 *
 * 通过 AGP 的 AsmClassVisitorFactory 接入，作用于 transformClassesWithAsm 阶段，
 * 天然支持处理**依赖中的 class**（这正是治理三方 SDK 的关键）。
 */
abstract class ThreadMonitorPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("threadMonitor", ThreadMonitorExtension::class.java)

        val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
        if (androidComponents == null) {
            project.logger.warn("[thread-monitor] 未找到 AndroidComponentsExtension，插件跳过（仅适用于 Android 模块）")
            return
        }

        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            variant.instrumentation.transformClassesWith(
                ThreadAsmVisitorFactory::class.java,
                InstrumentationScope.PROJECT,
            ) { params ->
                params.enableNaming.set(ext.enableNaming)
                params.enableUnify.set(ext.enableUnify)
                params.excludedPackages.set(ext.excludedPackages)
            }
            project.logger.lifecycle("[thread-monitor] 已为 variant '{}' 注册线程插桩", variant.name)
        }
    }
}
