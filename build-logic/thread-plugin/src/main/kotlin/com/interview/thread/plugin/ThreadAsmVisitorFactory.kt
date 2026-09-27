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
 * Description: 第 2/3 步 —— ASM 字节码插桩工厂
 *
 * 两个能力（可独立开关）：
 *  A. enableNaming  给匿名线程补名字（治「不可溯源」）
 *  B. enableUnify   把 new Thread 收敛到统一线程池（治「线程泛滥」）
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

    override fun createClassVisitor(
        classContext: ClassContext,
        nextClassVisitor: ClassVisitor,
    ): ClassVisitor = ThreadClassVisitor(
        next = nextClassVisitor,
        currentClass = classContext.currentClassData.className,
        enableNaming = parameters.get().enableNaming.getOrElse(false),
        enableUnify = parameters.get().enableUnify.getOrElse(false),
        excludedPackages = parameters.get().excludedPackages.getOrElse(emptyList()),
    )

    /**
     * 决定哪些类参与插桩。
     *
     * 关键取舍：这里刻意「放行」而非「拦截」——目标是改造**依赖里的 class**（三方 SDK），
     * 所以不能只处理自家代码。但框架层必须跳过，既无必要，也可避免把 ROM 类改坏。
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
