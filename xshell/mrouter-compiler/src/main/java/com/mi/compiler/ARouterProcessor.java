//package com.mi.compiler;
//
//import com.mi.router_annotation.MRouter;
//import com.squareup.javapoet.ClassName;
//import com.squareup.javapoet.JavaFile;
//import com.squareup.javapoet.MethodSpec;
//import com.squareup.javapoet.TypeSpec;
//
//import java.io.IOException;
//import java.lang.reflect.Type;
//import java.util.HashSet;
//import java.util.Set;
//
//import javax.annotation.processing.AbstractProcessor;
//import javax.annotation.processing.Filer;
//import javax.annotation.processing.Messager;
//import javax.annotation.processing.ProcessingEnvironment;
//import javax.annotation.processing.Processor;
//import javax.annotation.processing.RoundEnvironment;
//import javax.annotation.processing.SupportedAnnotationTypes;
//import javax.annotation.processing.SupportedOptions;
//import javax.annotation.processing.SupportedSourceVersion;
//import javax.lang.model.SourceVersion;
//import javax.lang.model.element.Element;
//import javax.lang.model.element.Modifier;
//import javax.lang.model.element.TypeElement;
//import javax.lang.model.util.Elements;
//import javax.lang.model.util.Types;
//import javax.tools.Diagnostic;
//
////@AutoService(Processor.class) // 启用服务
////@SupportedAnnotationTypes({"com.derry.arouter_annotations.ARouter"}) // 注解
//@SupportedSourceVersion(SourceVersion.RELEASE_8) // 环境的版本
//
//
//// 接收 安卓工程传递过来的参数
//@SupportedOptions("wgt")
//
//public class ARouterProcessor extends AbstractProcessor {
//
//    // 操作Element的工具类（类，函数，属性，其实都是Element）
//    private Elements elementTool;
//
//    // type(类信息)的工具类，包含用于操作TypeMirror的工具方法
//    private Types typeTool;
//
//    // Message用来打印 日志相关信息
//    private Messager messager;
//
//    // 文件生成器， 类 资源 等，就是最终要生成的文件 是需要Filer来完成的
//    private Filer filer;
//
//    @Override
//    public Set<String> getSupportedAnnotationTypes() {
////        return super.getSupportedAnnotationTypes();
//        Set<String> set = new HashSet<>();
//        set.add(MRouter.class.getCanonicalName());
//        return set;
//    }
//
//    @Override
//    public synchronized void init(ProcessingEnvironment processingEnvironment) {
//        super.init(processingEnvironment);
//
//        elementTool = processingEnvironment.getElementUtils();
//        messager = processingEnvironment.getMessager();
//        filer = processingEnvironment.getFiler();
//
//        String value = processingEnvironment.getOptions().get("student");
//        // 这个代码已经下毒了
//        // 如果我想在注解处理器里面抛出异常 可以使用Diagnostic.Kind.ERROR
//        messager.printMessage(Diagnostic.Kind.NOTE, ">>>>>>>>>>>>>>>>>>>>>>"+value);
//    }
//
//    // 服务：在编译的时候干活
//    // 坑：如果没有在任何地方使用，次函数是不会工作的
//    @Override
//    public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnvironment) {
//        // 这个代码已经下毒了
//        messager.printMessage(Diagnostic.Kind.NOTE, ">>>>>>> Derry run...");
//        return true; // false不干活了      true干完了
//    }
//}
