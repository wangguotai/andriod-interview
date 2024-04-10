package com.mi.compiler.parameter.factory

import com.mi.compiler.utils.ProcessorConfig
import com.mi.compiler.utils.isNullOrEmptyKt
import com.mi.router_annotation.Parameter
import com.squareup.javapoet.ClassName
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.ParameterSpec
import javax.annotation.processing.Messager
import javax.lang.model.element.Element
import javax.lang.model.element.Modifier
import javax.lang.model.type.TypeKind
import javax.tools.Diagnostic


/*
   目的 生成以下代码：
        @Override
        public void getParameter(Object targetParameter) {
              Personal_MainActivity t = (Personal_MainActivity) targetParameter;
              t.name = t.getIntent().getStringExtra("name");
              t.sex = t.getIntent().getStringExtra("sex");
        }
 */
class ParameterFactory private constructor(builder: Builder) {
    // 方法的构建
    private lateinit var method: MethodSpec.Builder

    // 类名
    private lateinit var className: ClassName

    // messager 用来处理信息输入
    private lateinit var messager: Messager

    init {
        this.messager = builder.messager!!
        this.className = builder.className!!
        method = MethodSpec.methodBuilder(ProcessorConfig.PARAMETER_METHOD_NAME)
            .addAnnotation(Override::class.java)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(builder.parameterSpec)
    }

    /** 只有一行
     * Personal_MainActivity t = (Personal_MainActivity) targetParameter;
     */
    fun addFirstStatement() {
        method.addStatement(
            "\$T target = (\$T) ${ProcessorConfig.PARAMETER_PARAM_NAME}",
            className,
            className
        )
    }

    /** 多行 循环 复杂
     * 构建方体内容，如：t.s = t.getIntent.getStringExtra("s");
     * @param element 被注解的属性元素
     */
    fun buildStatement(element: Element) {
        // 遍历注解的属性节点 生成函数体
        val typeMirror = element.asType()
        // 获取 TypeKind 枚举类型的序列号
        val type = typeMirror.kind.ordinal  // 类型的序列号
        // 获取属性名  name  age  sex
        val fieldName = element.simpleName.toString()
        var annotationValue = element.getAnnotation(Parameter::class.java).name
        // 判断注解的值为空的情况下的处理(注解中有name的值就用注解，否则用字段名)
        annotationValue = if (annotationValue.isNullOrEmptyKt()) fieldName else annotationValue
        // 拼接不同类型的属性值获取
        val methodContent = StringBuilder()
        methodContent.append("target.$fieldName = target.getIntent().")
        if (type == TypeKind.INT.ordinal) {
            methodContent.append("getIntExtra(\$S, target.$fieldName)") // 有默认值
        } else if (type == TypeKind.BOOLEAN.ordinal) {
            methodContent.append("getBooleanExtra(\$S, target.$fieldName)") // 有默认值
        } else { // String 类型，没有序列号的提供 需要我们自己弯沉
            // t.s = t.getIntent.getStringExtra("s");
            // typeMirror.toString() java.lang.String
            if (typeMirror.toString().equals(ProcessorConfig.STRING, true)) {
                // String类型
                methodContent.append("getStringExtra(\$S)") // 没有默认值
            }
        }
        // 健壮代码
        if (methodContent.endsWith(")")) { // 抱歉  全部的 getBooleanExtra  getIntExtra   getStringExtra
            // 参数二 9 赋值进去了
            // t.age = t.getIntent().getBooleanExtra("age", t.age ==  9);
            method.addStatement(methodContent.toString(), annotationValue)
        } else {
            messager.printMessage(Diagnostic.Kind.ERROR, "目前暂支持String、int、boolean传参")
        }
    }

    fun build(): MethodSpec = method.build()

    /**
     * 为了完成Builder构建者设计模式
     */
    class Builder(// 方法参数体
        internal val parameterSpec: ParameterSpec?
    ) {
        // Messager用来报告错误，警告和其他提示信息
        internal var messager: Messager? = null

        // 类名，如：MainActivity
        internal var className: ClassName? = null

        fun setMessager(messager: Messager?): Builder {
            this.messager = messager
            return this
        }

        fun setClassName(className: ClassName?): Builder {
            this.className = className
            return this
        }

        fun build(): ParameterFactory {
            requireNotNull(parameterSpec) { "parameterSpec方法参数体为空" }
            requireNotNull(className) { "方法内容中的className为空" }
            requireNotNull(messager) { "messager为空，Messager用来报告错误、警告和其他提示信息" }
            return ParameterFactory(this)
        }
    }

}