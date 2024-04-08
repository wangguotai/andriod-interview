package com.mi.compiler

import com.google.auto.service.AutoService
import com.mi.router_annotation.MRouter
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Filer
import javax.annotation.processing.Messager
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedOptions
import javax.annotation.processing.SupportedSourceVersion
import javax.lang.model.SourceVersion
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic

@AutoService(Processor::class)
@SupportedOptions("wgt") // 接收 安卓工程传递过来的参数
@SupportedSourceVersion(SourceVersion.RELEASE_8) // java环境的版本
class MyRouterProcessor : AbstractProcessor() {
    override fun getSupportedAnnotationTypes() = mutableSetOf(MRouter::class.java.canonicalName)
    override fun process(p0: MutableSet<out TypeElement>?, p1: RoundEnvironment?): Boolean {
        messager!!.printMessage(Diagnostic.Kind.NOTE, ">>>>>>>>>>>>>>>>>WGT")
        return true
    }

    // 操作Element的工具类（类，函数，属性，其实都是Element）
    private var elementTool: Elements? = null

    // type(类信息)的工具类，包含用于操作TypeMirror的工具方法
    private val typeTool: Types? = null

    // Message用来打印 日志信息
    private var messager: Messager? = null

    // 文件生成器，类 资源 等，最终生成的文件， 通过Filer来操作
    private var filer: Filer? = null

    @Synchronized
    override fun init(processingEnvironment: ProcessingEnvironment) {
        super.init(processingEnvironment)
        elementTool = processingEnvironment.elementUtils
        messager = processingEnvironment.messager
        filer = processingEnvironment.filer
        val value = processingEnvironment.options["wgt"]
        // 这个代码已经下毒了，
        // 如果想要在注解处理器里面抛出异常， 可以使用Diagnostic.Kind.ERROR
        messager!!.printMessage(Diagnostic.Kind.NOTE, ">>>>>>>>>>>>>>>>>$value")
    }
}