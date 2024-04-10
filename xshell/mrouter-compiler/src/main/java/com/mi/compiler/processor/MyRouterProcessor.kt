package com.mi.compiler.processor

import com.mi.compiler.utils.ProcessorConfig
import com.mi.compiler.utils.RouterHelper
import com.mi.compiler.utils.isNotNullOrEmptyKt
import com.mi.compiler.utils.isNullOrEmptyKt
import com.mi.router_annotation.MRouter
import com.mi.router_annotation.bean.RouterBean
import com.mi.router_annotation.bean.RouterBean.TypeEnum
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeSpec
import com.squareup.javapoet.WildcardTypeName
import java.io.IOException
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Filer
import javax.annotation.processing.Messager
import javax.annotation.processing.ProcessingEnvironment
import javax.annotation.processing.RoundEnvironment
import javax.annotation.processing.SupportedOptions
import javax.annotation.processing.SupportedSourceVersion
import javax.lang.model.SourceVersion
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types
import javax.tools.Diagnostic


//@AutoService(Processor::class)
@SupportedOptions(ProcessorConfig.OPTIONS, ProcessorConfig.APT_PACKAGE) // 接收 安卓工程传递过来的参数
@SupportedSourceVersion(SourceVersion.RELEASE_8) // java环境的版本
class MyRouterProcessor : AbstractProcessor() {

    // 操作Element的工具类（类，函数，属性，其实都是Element）
    private lateinit var elementTool: Elements

    // type(类信息)的工具类，包含用于操作TypeMirror的工具方法
    private lateinit var typeTool: Types

    // Message用来打印 日志信息
    private lateinit var messager: Messager

    // 文件生成器，类 资源 等，最终生成的文件， 通过Filer来操作
    private var filer: Filer? = null

    // 仓库一 Path 缓存
    private val mAllPathMap = mutableMapOf<String, MutableList<RouterBean>>()

    // 仓库二 Group 缓存
    private val mAllGroupMap = mutableMapOf<String, String>()

    // module名，由 android gradle传入
    private lateinit var moduleName: String

    // packageNameForAPT
    private lateinit var packageNameForAPT: String
    override fun getSupportedAnnotationTypes() = mutableSetOf(MRouter::class.java.canonicalName)

    @Synchronized
    override fun init(processingEnvironment: ProcessingEnvironment) {
        super.init(processingEnvironment)
        elementTool = processingEnvironment.elementUtils
        typeTool = processingEnvironment.typeUtils
        messager = processingEnvironment.messager
        filer = processingEnvironment.filer
        moduleName = processingEnvironment.options[ProcessorConfig.OPTIONS].toString()
        packageNameForAPT = processingEnvironment.options[ProcessorConfig.APT_PACKAGE].toString()
        if (moduleName.isNotNullOrEmptyKt() && packageNameForAPT.isNotNullOrEmptyKt()) {
            messager.printMessage(Diagnostic.Kind.NOTE, "APT 环境搭建完成....")
            // 如果想要在注解处理器里面抛出异常， 可以使用Diagnostic.Kind.ERROR
            messager.printMessage(
                Diagnostic.Kind.NOTE,
                ">>>>>>>>>>>>>>>>>$moduleName, $packageNameForAPT"
            )
        } else {
            messager.printMessage(
                Diagnostic.Kind.NOTE,
                "APT 环境有问题，请检查 options 与 aptPackage 为null..."
            )
        }
    }


    override fun process(
        set: MutableSet<out TypeElement>,
        roundEnvironment: RoundEnvironment
    ): Boolean {
        if (set.isEmpty()) {
            return false
        }
        messager.printMessage(
            Diagnostic.Kind.NOTE,
            ">>>>>>>>>>>>>>>>>注解处理程序开始工作 $supportedAnnotationTypes"
        )
        // 获取被MRouter注解的"类节点信息"
        val elements = roundEnvironment.getElementsAnnotatedWith(MRouter::class.java)

        // 通过Element工具类，获取Activity，Callback类型
        val activityType = elementTool.getTypeElement(ProcessorConfig.ACTIVITY_PACKAGE)
        // 显示类信息 （获取被注解的节点，类节点） 也叫做自描述 Mirror
        val activityMirror = activityType.asType()

        // 增添 group pathList数据
        for (el in elements) {
            // 获取简单类名，例如：MainActivity
            val clazzName = el.simpleName
            messager.printMessage(Diagnostic.Kind.NOTE, "被@ARetuer注解的类有: $clazzName")
            // 拿到注解
            val mRouter = el.getAnnotation(MRouter::class.java)
            val routerBean = RouterBean.Builder()
                .addGroup(mRouter.group)
                .addPath(mRouter.path)
                .addElement(el)
                .build()
            // MRouter注解的类 必须继承自Activity
            val elementMirror = el.asType()
            if (typeTool.isSubtype(elementMirror, activityMirror)) {
                routerBean.typeEnum = TypeEnum.ACTIVITY // 最终证明是 Activity
            } else {
                // 不匹配抛出异常，这里谨慎使用！考虑维护问题
                throw RuntimeException("@ARouter注解目前仅限用于Activity类之上")
            }
            // 检验注解的规范性
            if (RouterHelper.checkoutRouterPath(routerBean, messager, moduleName)) {
                messager.printMessage(Diagnostic.Kind.NOTE, "RouterBean Check Success: $routerBean")
                // 向mAllPathMap中赋值
                val routerBeans = mAllPathMap.getOrPut(routerBean.group) {
                    mutableListOf()
                }
                routerBeans.add(routerBean)
            } else {
                messager.printMessage(
                    Diagnostic.Kind.ERROR,
                    "@MRouter注解未按规范配置，必须为/{group}/{path}"
                )
            }
        }
        // 定义（生成类文件实现的接口） 有 Path Group
        val pathClassName = ClassName.get(
            ProcessorConfig.MROUTER_API_PACKAGE,
            ProcessorConfig.MROUTER_API_MROUTERPATH_SIMPLENAME
        )
        val pathType = elementTool.getTypeElement(ProcessorConfig.MROUTER_API_PATH) // ARouterPath描述
        val groupClassName = ClassName.get(
            ProcessorConfig.MROUTER_API_PACKAGE,
            ProcessorConfig.MROUTER_API_MROUTERGROUP_SIMPLENAME
        )
        // 第一大步: 系列PATH
        try {
            createPathFile(pathType, pathClassName) // 生成 Path类
        } catch (e: IOException) {
            e.printStackTrace()
            messager.printMessage(Diagnostic.Kind.NOTE, "在生成PATH模板时，异常了 e:" + e.message)
        }
        // 第二大步： 组头
        try {
            createGroupFile(groupClassName, pathClassName)
        } catch (e: IOException) {
            e.printStackTrace()
            messager.printMessage(Diagnostic.Kind.NOTE, "在生成GROUP模板时，异常了: ${e.message}")
        }
        return true  // 必须写返回值， 表示@MRouter注解完成
    }

    /**
     * 生成路由组Group文件，如 MRouter$$Group$${group}
     * Create group file
     *
     * @param groupClassName
     * @param pathClassName
     */
    private fun createGroupFile(groupClassName: ClassName, pathClassName: ClassName) {
        // 仓库二 判断是否有需要生成的类文件
        if (mAllGroupMap.isNullOrEmptyKt() || mAllPathMap.isNullOrEmptyKt()) {
            return
        }
        // 返回值 Map<String, Class<? extends MRouterPath>>
        val methodReturns = ParameterizedTypeName.get(
            ClassName.get(Map::class.java),
            ClassName.get(String::class.java),
            // Class<? extends MRouterPath>
            ParameterizedTypeName.get(
                ClassName.get(Class::class.java),
                // ? extends
                WildcardTypeName.subtypeOf(pathClassName) // 泛型下界
            )
        )
        // 1. 方法 public Map<String, Class<? extends MRouterPath> getGroupMap(){}
        val methodBuilder = MethodSpec.methodBuilder(ProcessorConfig.GROUP_METHOD_NAME) // 方法名
            .addAnnotation(Override::class.java)
            .addModifiers(Modifier.PUBLIC)
            .returns(methodReturns)
        // Map<String, Class<? extends MRouterPath>> groupMap = new HashMap<>();
        methodBuilder.addStatement(
            "\$T<\$T, \$T> \$N = new \$T<>()",
            ClassName.get(Map::class.java),
            ClassName.get(String::class.java),
            ParameterizedTypeName.get(
                ClassName.get(Class::class.java),
                WildcardTypeName.subtypeOf(pathClassName)
            ), // ? extends MRouterPath
            ProcessorConfig.GROUP_VAR1,
            ClassName.get(HashMap::class.java)
        )
        // groupMap.put("order", MRouter$$Path$${group});
        // 将每个group下的 groupPathList 都添加到 groupMap中
        for (entry in mAllGroupMap) {
            methodBuilder.addStatement(
                "\$N.put(\$S, \$T.class)",
                ProcessorConfig.GROUP_VAR1, // groupMap.put
                entry.key,
                ClassName.get(packageNameForAPT, entry.value)
            )
        }
        // return groupMap;
        methodBuilder.addStatement("return \$N", ProcessorConfig.GROUP_VAR1)
        val finalClassName = ProcessorConfig.GROUP_FILE_NAME + moduleName
        messager.printMessage(
            Diagnostic.Kind.NOTE,
            "APT生成路由组Group类文件：$packageNameForAPT.$finalClassName"
        )
        // 生成类文件： MRouter$$Group$$app
        JavaFile.builder(
            packageNameForAPT, // 包名
            TypeSpec.classBuilder(finalClassName) // 类名
                .addSuperinterface(groupClassName)
                .addModifiers(Modifier.PUBLIC)
                .addMethod(methodBuilder.build())
                .build()  // 类构建完成
        ).build() // JavaFile构建完成
            .writeTo(filer) // 文件生成器开始生成类文件
    }

    /**
     * 生成java文件放置在APT的生成目录中，全局配置放在ProcessorConfig中
     * Create path file MRouter$$Path${path}
     *
     * @param pathType
     */
    private fun createPathFile(pathType: TypeElement?, pathClassName: ClassName) {
        // 判断map仓库中，是否有需要生成的文件
        if (mAllPathMap.isNullOrEmptyKt()) {
            return
        }
        // 倒序生成代码
        // 所有的class类型，需要进行包装
        // Map<String, RouterBean>
        val methodReturn = ParameterizedTypeName.get(
            ClassName.get(Map::class.java),
            ClassName.get(String::class.java),
            ClassName.get(RouterBean::class.java)
        )
        // 遍历 path 仓库 依据该注解对应的封装bean 生成 该group的缓存map
        for (entry in mAllPathMap) {
            // 1. 方法
            val methodBuilder = MethodSpec.methodBuilder(ProcessorConfig.PATH_METHOD_NAME)
                .addAnnotation(Override::class.java) // 给方法上添加注解  @Override
                .addModifiers(Modifier.PUBLIC)       // public修饰符
                .returns(methodReturn)               // 把Map<String, RouterBean> 加入方法返回
                // Map<String, RouterBean> pathMap = new HashMap<>(); // $N == 变量 为什么是这个，因为变量有引用 所以是$N
                .addStatement(
                    "\$T<\$T,\$T> \$N = new \$T<>()",
                    ClassName.get(Map::class.java),
                    ClassName.get(String::class.java),
                    ClassName.get(RouterBean::class.java),
                    ProcessorConfig.PATH_VAR1,
                    ClassName.get(HashMap::class.java)
                )
            val pathList = entry.value
            // 使用循环，处理多个类
            // pathMap.put("/personal/Personal_Main2Activity", RouterBean.create(RouterBean.TypeEnum.ACTIVITY,
            // Personal_Main2Activity.class);
            // pathMap.put("/personal/Personal_MainActivity", RouterBean.create(RouterBean.TypeEnum.ACTIVITY));
            // $L == TypeEnum.ACTIVITY
            for (bean in pathList) {
                methodBuilder.addStatement(
                    "\$N.put(\$S, \$T.create(\$T.\$L, \$T.class, \$S, \$S))",
                    ProcessorConfig.PATH_VAR1,  // pathMap.put
                    bean.path,  // "/personal/Personal_Main2Activity"
                    ClassName.get(RouterBean::class.java),  // RouterBean
                    ClassName.get(TypeEnum::class.java),  // RouterBean.Type
                    bean.typeEnum,  // 枚举类型：ACTIVITY
                    ClassName.get(bean.element as TypeElement),  // MainActivity.class Main2Activity.class
                    bean.path,  // 路径名
                    bean.group // 组名
                )
            }
            // return pathMap
            methodBuilder.addStatement("return \$N", ProcessorConfig.PATH_VAR1)
            //  注意：不能像以前一样，1.方法，2.类  3.包， 因为这里面有implement 实现接口 ，所以 方法和类要合为一体生成才行，这是特殊情况
            // 最终生成的类文件名 MRouter$$Path${path}
            val finalClassName = ProcessorConfig.PATH_FILE_NAME + entry.key
            messager.printMessage(
                Diagnostic.Kind.NOTE,
                "APT生成路由Path类文件：$packageNameForAPT.$finalClassName"
            );
            // 生成类文件
            val className = if (pathType == null) {
                pathClassName
            } else {
                ClassName.get(pathType)
            }
            JavaFile.builder(
                packageNameForAPT, // 包名 APT 存放的路径
                TypeSpec.classBuilder(finalClassName) // 类名
                    .addSuperinterface(className)
                    .addModifiers(Modifier.PUBLIC)
                    .addMethod(methodBuilder.build())
                    .build()
            )
                .build() // JavaFile构建完成
                .writeTo(filer)
            mAllGroupMap[entry.key] = finalClassName
        }
    }

}