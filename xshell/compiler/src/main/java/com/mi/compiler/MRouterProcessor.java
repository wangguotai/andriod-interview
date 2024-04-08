package com.mi.compiler;

import com.google.auto.service.AutoService;

import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

@AutoService(Processor.class) // 启动服务
@SupportedAnnotationTypes({"com.mi.router_annotation.MRouter"}) // 服务于哪个注解
@SupportedSourceVersion(SourceVersion.RELEASE_8)   // java环境的版本
// 接收 安卓工程传递过来的参数
@SupportedOptions("wgt")
public class MRouterProcessor extends AbstractProcessor {
    // 操作Element的工具类（类，函数，属性，其实都是Element）
    private Elements elementTool;

    // type(类信息)的工具类，包含用于操作TypeMirror的工具方法
    private Types typeTool;

    // Message用来打印 日志信息
    private Messager messager;

    // 文件生成器，类 资源 等，最终生成的文件， 通过Filer来操作
    private Filer filer;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        elementTool = processingEnvironment.getElementUtils();
        messager = processingEnvironment.getMessager();
        filer = processingEnvironment.getFiler();
        String value = processingEnvironment.getOptions().get("wgt");
        // 这个代码已经下毒了，
        // 如果想要在注解处理器里面抛出异常， 可以使用Diagnostic.Kind.ERROR
        messager.printMessage(Diagnostic.Kind.NOTE, ">>>>>>>>>>>>>>>>>" + value);
    }

    /**
     * 在编译期工作，需要有其他地方使用注解
     *
     * @param set
     * @param roundEnvironment
     * @return
     */
    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnvironment) {
        messager.printMessage(Diagnostic.Kind.NOTE, ">>>>>>>>>>>> WGT run...");
        return false;
    }
}
