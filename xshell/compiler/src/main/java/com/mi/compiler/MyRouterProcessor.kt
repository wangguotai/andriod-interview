package com.mi.compiler

import com.google.auto.service.AutoService
import com.mi.router_annotation.MRouter
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.TypeSpec
import java.io.IOException
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Filer
import javax.annotation.processing.Messager
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedOptions
import javax.annotation.processing.SupportedSourceVersion
import javax.lang.model.SourceVersion
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic

@AutoService(Processor::class)
@SupportedOptions("wgt") // 接收 安卓工程传递过来的参数
@SupportedSourceVersion(SourceVersion.RELEASE_8) // java环境的版本
class MyRouterProcessor : AbstractProcessor() {
    override fun getSupportedAnnotationTypes() = mutableSetOf(MRouter::class.java.canonicalName)
    override fun process(
        set: MutableSet<out TypeElement>,
        roundEnvironment: RoundEnvironment
    ): Boolean {
        messager!!.printMessage(Diagnostic.Kind.NOTE, ">>>>>>>>>>>>>>>>>WGT")
        if (set.isEmpty()) {
            return false
        }
        // 获取被MRouter注解的"类节点信息"
        val elements = roundEnvironment.getElementsAnnotatedWith(MRouter::class.java)
        /** 构建一个java Demo代码
        模块一
        package com.example.helloworld;

        public final class HelloWorld {

        public static void main(String[] args) {
        System.out.println("Hello, JavaPoet!");
        }
        }
         */
        // 模板1. 一个简单的demo类
        /*for (el in elements){
            // 使用javaPoet构建类
            // 1. 方法
            val mainMethod = MethodSpec.methodBuilder("main")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(TypeName.VOID)
                .addParameter(Array<System>::class.java, "args")
                // 增添main方法里面的内容
                .addStatement("\$T.out.println(\$S)", System::class.java, "Hello, JavaPoet")
                .build()
            // 2. 类
            val testClass = TypeSpec.classBuilder("WTest")
                .addMethod(mainMethod)
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .build()
            // 3. 包
            val packageF = JavaFile.builder("com.wgt.test", testClass).build()
            // 生成文件
            try {
                packageF.writeTo(filer)
            } catch (e: IOException){
                e.printStackTrace()
                messager!!.printMessage(Diagnostic.Kind.NOTE, "生成Test文件时失败，异常 ${e.message}")
            }
        }*/
        // 模板2. 构建一个动态化的提供类访问Class的路由类
        /**
        模板：
        public class MainActivity3$$$$$$$$$ARouter {

        public static Class findTargetClass(String path) {
        return path.equals("/app/MainActivity3") ? MainActivity3.class : null;
        }

        }
         */

        for (el in elements) {
            // 包名
            val packageName = elementTool.getPackageOf(el).qualifiedName.toString()
            // 目标文件名
            val className = el.simpleName
//            (el as TypeElement).apply {
//                this.qualifiedName
//            }
            // 目标注解
            val mRouter = el.getAnnotation(MRouter::class.java)
            messager.printMessage(Diagnostic.Kind.NOTE, "被@MRouter注解的类有: $className")
            val clazz = ClassName.get(el as TypeElement)
            // 1. 方法
            val findTargetClass = MethodSpec.methodBuilder("findTargetClass")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(Class::class.java)
                .addParameter(String::class.java, "path")
                .addStatement("return path.equals(\$S) ? \$T.class : null", mRouter.path, el)
                .build()
            // 2. 类
            val myClass = TypeSpec.classBuilder("$className\$\$MRouter")
                .addModifiers(Modifier.PUBLIC)
                .addMethod(findTargetClass)
                .build()
            // 3. 包
            val packageF = JavaFile.builder(packageName, myClass).build()
            try {
                packageF.writeTo(filer)
            } catch (e: IOException) {
                e.printStackTrace()
                messager.printMessage(
                    Diagnostic.Kind.NOTE,
                    "生成 $className\$\$MRouter文件时失败，异常:${e.message}"
                )
            }
        }
        return true
    }

    // 操作Element的工具类（类，函数，属性，其实都是Element）
    private lateinit var elementTool: Elements

    // type(类信息)的工具类，包含用于操作TypeMirror的工具方法
    private val typeTool: Types? = null

    // Message用来打印 日志信息
    private lateinit var messager: Messager

    // 文件生成器，类 资源 等，最终生成的文件， 通过Filer来操作
    private var filer: Filer? = null

    @Synchronized
    override fun init(processingEnvironment: ProcessingEnvironment) {
        super.init(processingEnvironment)
        elementTool = processingEnvironment.elementUtils
        messager = processingEnvironment.messager
        filer = processingEnvironment.filer
        val value = processingEnvironment.options["wgt"]
        // 如果想要在注解处理器里面抛出异常， 可以使用Diagnostic.Kind.ERROR
        messager!!.printMessage(Diagnostic.Kind.NOTE, ">>>>>>>>>>>>>>>>>$value")
    }
}