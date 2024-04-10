package com.mi.compiler.processor

import com.google.auto.service.AutoService
import com.mi.compiler.parameter.factory.ParameterFactory
import com.mi.compiler.utils.ProcessorConfig
import com.mi.compiler.utils.isNotNullOrEmptyKt
import com.mi.compiler.utils.isNullOrEmptyKt
import com.mi.router_annotation.Parameter
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.ParameterSpec
import com.squareup.javapoet.TypeName
import com.squareup.javapoet.TypeSpec
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Filer
import javax.annotation.processing.Messager
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedSourceVersion
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic

@AutoService(Processor::class)
@SupportedSourceVersion(SourceVersion.RELEASE_8)
class MyParameterProcessor : AbstractProcessor() {
    // 操作Element的工具类（类，函数，属性，其实都是Element）
    private lateinit var elementUtils: Elements

    // type(类信息)的工具类，包含用于操作TypeMirror的工具方法
    private lateinit var typeUtils: Types

    // Message用来打印 日志信息
    private lateinit var messager: Messager

    // 文件生成器，类 资源 等，最终生成的文件， 通过Filer来操作
    private var filer: Filer? = null

    //
    private val parameterMap = mutableMapOf<TypeElement, MutableList<Element>>()


    @Synchronized
    override fun init(processingEnvironment: ProcessingEnvironment) {
        super.init(processingEnvironment)
        elementUtils = processingEnvironment.elementUtils
        typeUtils = processingEnvironment.typeUtils
        messager = processingEnvironment.messager
        filer = processingEnvironment.filer

    }

    override fun getSupportedAnnotationTypes() =
        setOf(ProcessorConfig.MROUTER_ANNOTATION_PARAMETER_CANONICALNAME)

    /**
     * Process
     *
     * @param set
     * @param roundEnvironment
     * @return 返回true会执行两次，第二次set为空，返回false仅执行一遍 内部机制检测build中的内容是否生成成功
     */
    override fun process(
        set: MutableSet<out TypeElement>,
        roundEnvironment: RoundEnvironment
    ): Boolean {
        if (set.isNotNullOrEmptyKt()) {
            val elements = roundEnvironment.getElementsAnnotatedWith(Parameter::class.java)
            if (elements.isNotNullOrEmptyKt()) {
                for (el in elements) { // 此时的element对应的是字段上的注解
                    val enclosingElement = el.enclosingElement as TypeElement
                    // 存储位于字段上的Element
                    val list = parameterMap.getOrPut(enclosingElement) {
                        ArrayList()
                    }
                    list.add(el) // 加入缓存
                }
                // 判断是否有需要生成的类文件
                if (parameterMap.isNullOrEmptyKt()) {
                    return true
                }
                val activityType = elementUtils.getTypeElement(ProcessorConfig.ACTIVITY_PACKAGE)
                val parameterType =
                    elementUtils.getTypeElement(ProcessorConfig.MROUTER_API_PARAMETER_GET)
                // 1. 生成方法 方法参数
                val parameterSpec =
                    ParameterSpec.builder(TypeName.OBJECT, ProcessorConfig.PARAMETER_PARAM_NAME)
                        .build()
                // 循环遍历 缓存 parameterMap key: TypeElement[Activity] value: name[field]
                for (entry in parameterMap) {
                    val typeElement = entry.key
                    // 是Activity
                    if (!typeUtils.isSubtype(typeElement.asType(), activityType.asType())) {
                        throw RuntimeException("@Parameter注解目前仅用于Activity类之上")
                    }
                    val className = ClassName.get(typeElement)
                    // 方法内容的构建
                    val factory = ParameterFactory.Builder(parameterSpec)
                        .setMessager(messager)
                        .setClassName(className)
                        .build()
                    // Personal_MainActivity t = (Personal_MainActivity) targetParameter;
                    factory.addFirstStatement()
                    // 处理多行，存在一定的动态化 Boolean、Int、String, 其他形式传值暂不考虑
                    for (element in entry.value) {
                        factory.buildStatement(element)
                    }
                    // 最终生成的类文件名 (类名$$Parameter)
                    val finalClassName =
                        "${typeElement.simpleName}${ProcessorConfig.PARAMETER_FILE_NAME}"
                    messager.printMessage(
                        Diagnostic.Kind.NOTE, "APT生成获取参数类文件：" +
                                className.packageName() + "." + finalClassName
                    )
                    // 开始生成文件
                    try {
                        JavaFile.builder(
                            className.packageName(), // 包名
                            TypeSpec.classBuilder(finalClassName)
                                .addSuperinterface(ClassName.get(parameterType))
                                .addMethod(factory.build()) // 方法的构建
                                .build() // 类构建完成
                        ).build() // JavaFile 方法构建完成
                            .writeTo(filer) // 文件生成器开始生成类文件
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        return false
    }
}