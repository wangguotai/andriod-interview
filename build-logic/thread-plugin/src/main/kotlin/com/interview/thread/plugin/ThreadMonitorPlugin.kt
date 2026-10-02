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
 * ══════════════════════════════════════════════════════════════════════
 * 接入链路（想搞懂「代码什么时候被调用」，从这里看起）
 * ══════════════════════════════════════════════════════════════════════
 *
 *   app/build.gradle.kts:4   id("com.interview.thread.monitor")
 *           ↓
 *   settings.gradle.kts      includeBuild("./build-logic")   ← 插件作为独立构建引入
 *           ↓
 *   ThreadMonitorPlugin.apply()                              ← 本类
 *           ↓
 *   onVariants { variant.instrumentation.transformClassesWith(...) }
 *           ↓
 *   ThreadAsmVisitorFactory                                  ← AGP 回调入口
 *           ↓
 *   ThreadClassVisitor                                       ← 指令级变换
 *
 * 用法（app/build.gradle.kts）：
 *   plugins { id("com.interview.thread.monitor") }
 *   configure<ThreadMonitorExtension> {
 *       enableNaming.set(true)    // 给匿名线程补名字
 *       enableUnify.set(false)    // 收敛到统一池（有风险，默认关）
 *   }
 *
 * ══════════════════════════════════════════════════════════════════════
 * ⚠️ 已知问题（D13）：当前只作用于本项目，治理不了三方 SDK
 * ══════════════════════════════════════════════════════════════════════
 *
 * 下面注册时用的是 `InstrumentationScope.PROJECT`，按 AGP 语义
 * **只插桩本项目 class，不包含依赖**。这与「治理三方 SDK」的设计目标不符。
 *
 * 依据（AGP 8.3.0 源码 `AsmClassVisitorsFactoryRegistry.kt`）：
 *
 *   if (scope == InstrumentationScope.ALL) {
 *       dependenciesClassesVisitors.add(visitorEntry)   // ← 只有 ALL 才注册到依赖
 *   }
 *   projectClassesVisitors.add(visitorEntry)
 *
 * 实测旁证：本次构建中，依赖里的 R 类被打包进
 * `transformDebugClassesWithAsm/jars/0.jar` 但字节码未被改写；
 * 而项目内的 com/interview 包下的类确实被插桩（javap 可验证）。
 *
 * 后果：本插件当前能改写的只有自有代码 —— 那部分恰好是 Lint（thread-lint）
 * 已经能覆盖的范围，等于没有补上 Lint 的能力缺口。
 *
 * 修法：`PROJECT` → `InstrumentationScope.ALL`。
 * 代价：覆盖面变大、构建变慢；且需对加固/加壳的 SDK 做防御
 * （这类 SDK 本就插不进去，需靠第 4 层 Native Hook 兜底）。
 *
 * 状态：仅标注，**未改动行为** —— 改 scope 会改变构建产物范围，应由项目负责人决定。
 *
 * ⚠️ 另一个必须遵守的约束：transformClassesWith 接收的是 Kotlin 的
 *    `Function1<ParamT, Unit>`，**Java lambda 无法满足该签名**
 *    （编译报 "void 无法转换为 Unit"）→ 本插件必须用 Kotlin 编写。
 */
abstract class ThreadMonitorPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        // ① 暴露配置 DSL：configure<ThreadMonitorExtension> { ... }
        val ext = project.extensions.create("threadMonitor", ThreadMonitorExtension::class.java)

        // ② 只对 Android 模块生效（纯 JVM 模块没有 AndroidComponentsExtension）
        val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
        if (androidComponents == null) {
            project.logger.warn("[thread-monitor] 未找到 AndroidComponentsExtension，插件跳过（仅适用于 Android 模块）")
            return
        }

        // ③ 为每个 variant 注册插桩（debug/release 都会走一遍）
        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            variant.instrumentation.transformClassesWith(
                ThreadAsmVisitorFactory::class.java,
                // ⚠️ 见类注释「已知问题 D13」：PROJECT 只含本项目，不含依赖。
                //    想治理三方 SDK 需要改成 InstrumentationScope.ALL。
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
