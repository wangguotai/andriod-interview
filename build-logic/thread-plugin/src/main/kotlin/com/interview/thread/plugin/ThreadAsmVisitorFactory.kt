package com.interview.thread.plugin

import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.InstrumentationParameters
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.objectweb.asm.ClassVisitor

/**
 * Time: 2026/9/27
 * Author: wgt
 * Description: 第 2/3 步 —— ASM 字节码插桩工厂（AGP 侧的接入点）
 *
 * ══════════════════════════════════════════════════════════════════════
 * 心智模型：是框架回调你，不是你调用框架
 * ══════════════════════════════════════════════════════════════════════
 *
 * 这个类没有 main、没有显式调用点，容易让人「不知道代码什么时候跑」。
 * 实际的执行时序（AGP 在 `transformDebugClassesWithAsm` 任务里驱动）：
 *
 *   对每一个待处理的 class：
 *     ① isInstrumentable(classData)  ── 问：这个类要处理吗？
 *            │ false → 原样拷贝，结束
 *            │ true
 *            ▼
 *     ② createClassVisitor(classContext, nextClassVisitor)
 *                                       ── 问：用哪个访问器处理它？
 *            ▼
 *     ③ 返回的 ClassVisitor 逐方法被调用
 *            → ThreadClassVisitor.visitMethod 返回 MethodNode（延迟 emit）
 *            → MethodNode.visitEnd 里做变换，再 accept(next)
 *
 * ⚠️ 官方文档明确：② 和 ③「must handle asynchronous calls」——
 *    AGP 可能并发处理多个 class，实现必须无共享可变状态。
 *    本类的实现是无状态的（配置全从 parameters 读），满足该约束。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 两个能力（可独立开关）
 * ══════════════════════════════════════════════════════════════════════
 *  A. enableNaming  给匿名线程补名字（治「不可溯源」）
 *  B. enableUnify   把 new Thread 收敛到统一线程池（治「线程泛滥」）
 *
 * 具体变换逻辑见 [ThreadClassVisitor]。
 */

/**
 * 插桩参数：由 Gradle 在配置阶段注入，运行期只读。
 *
 * ⚠️ 这些字段**必须**留成 abstract / 未实现 —— AGP 用 ObjectFactory 生成实现。
 *    自己手动赋值会破坏 AGP 的配置缓存（configuration cache）支持。
 */
interface ThreadAsmParams : InstrumentationParameters {
    @get:Input
    val enableNaming: Property<Boolean>

    @get:Input
    val enableUnify: Property<Boolean>

    @get:Input
    val excludedPackages: ListProperty<String>
}

abstract class ThreadAsmVisitorFactory : AsmClassVisitorFactory<ThreadAsmParams> {

    /**
     * 对每个通过 [isInstrumentable] 的 class 调用一次。
     *
     * @param classContext   含 currentClassData（当前类信息）与
     *                       loadClassData（按名查类路径上的其他类）
     * @param nextClassVisitor 下游访问器，**变换完必须把调用透传给它**
     *                       （本类的链路：ThreadClassVisitor → MethodNode.accept(next)）
     */
    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = ThreadClassVisitor(
        next = nextClassVisitor,
        // ⚠️ className 是 JVM 内部名（斜杠分隔，如 com/interview/thread/Foo），
        //    不是点号形式。ThreadClassVisitor 在 LDC 时会自行 replace('/','.')。
        currentClass = classContext.currentClassData.className,
        enableNaming = parameters.get().enableNaming.getOrElse(false),
        enableUnify = parameters.get().enableUnify.getOrElse(false),
        excludedPackages = parameters.get().excludedPackages.getOrElse(emptyList()),
    )

    /**
     * 决定哪些类参与插桩。**插桩全流程的第一道闸门。**
     *
     * ─── 本项目的策略：「放行」而非「拦截」───
     *
     * 这里刻意只排除基础包（android/java/kotlin…），其余全部放行。
     * 设计意图是：不改「只处理自家代码」的白名单模式，
     * 因为目标是改造**依赖里的 class**（三方 SDK）——白名单会把它挡在门外。
     *
     * ⚠️ 但要注意：**能否真的处理依赖，取决于注册时的 InstrumentationScope，
     *    不是取决于本方法。** 见下方「已知问题」。
     *
     * AGP 提供的 [ClassData] 有 4 个字段，本方法只用到了 className：
     *   · className        —— JVM 内部名（斜杠），本项目唯一使用的依据
     *   · classAnnotations —— 类上的注解
     *   · interfaces       —— 自己 + 父类实现的所有接口
     *   · superClasses     —— 自己 + 父类的所有父类
     * （后三个在「按父类链判断」的场景才有用，本项目不需要）
     *
     * ⚠️ 官方文档提醒：这些信息是**插桩开始前的静态快照**，
     *    前一个 visitor 对类层次的修改不会反映到这里。
     *
     * ─── 已知问题（D13，仅标注，未改行为）───
     *
     * [ThreadMonitorPlugin] 当前用 `InstrumentationScope.PROJECT` 注册，
     * 按 AGP 语义它**只处理本项目 class，不含依赖**。
     *
     * 已通过 AGP 8.3.0 源码 `AsmClassVisitorsFactoryRegistry.kt` 验证：
     *   if (scope == InstrumentationScope.ALL) {
     *       dependenciesClassesVisitors.add(visitorEntry)   // ← 只有 ALL 才注册到依赖
     *   }
     *   projectClassesVisitors.add(visitorEntry)
     *
     * 即：此处的「放行」逻辑对三方 SDK **目前不生效**，
     * 本插件实际只能改写自有代码（那部分是 Lint 已能覆盖的范围）。
     * 若要真正治理依赖，需把 scope 改为 `InstrumentationScope.ALL`。
     */
    override fun isInstrumentable(classData: ClassData): Boolean {
        val name = classData.className
        return !name.startsWith("android.") &&
                !name.startsWith("androidx.") &&
                !name.startsWith("java.") &&
                !name.startsWith("kotlin.") &&
                !name.startsWith("kotlinx.") &&
                // 插桩自身与统一线程池不能被自己改造，否则自举出问题
                !name.startsWith("com.interview.thread.plugin.") &&
                !name.startsWith("com.interview.thread.UnifiedThread")
    }
}
