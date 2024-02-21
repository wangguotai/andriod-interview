package com.interview.依赖注入.viewModel

/**
 * 模拟Application的运行
 */
class Application {
    fun onCreate() {

    }

    fun initViewModel() {

    }
}

// 实现一个简单的依赖容器
object DIContainerV1 {
    private val dependencies = mutableMapOf<Class<*>, Any>()

    fun <T : Any> register(clazz: Class<T>, instance: T) {
        dependencies[clazz] = instance
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> resolve(clazz: Class<T>): T {
        return dependencies[clazz] as? T
            ?: throw IllegalArgumentException("Dependency not found for class:${clazz}")
    }
}

// ******************* V2 增添必要的描述提供单例模式支持 ******
//data class DependencyDescriptor<T>(val provider: ()-> T, val isSingleton: Boolean = true)
//// 标识ViewModel
//internal interface ViewModel
//internal typealias Factory = ()-> ViewModel
//object DIContainer {
//    // 存储依赖项的映射
//    private val dependencies = mutableMapOf<Class<*>, DependencyDescriptor<*>>()
//    // 存储viewModel的工厂函数
//    private val viewModelFactories = mutableMapOf<Class<out ViewModel>, Factory>()
//    // 注册依赖项
//    inline fun<reified T: Any> provide()
//}


// 使用示例
interface MyService {
    fun doSth()
}

class MyServiceImpl: MyService {
    override fun doSth() {
        println("Doing sth")
    }

}
fun main() {
    aaa {
        println("in block")
        return
    }
    println("in main")
}

//fun main() {
//    // 注册依赖项
//    DIContainerV1.register(MyService::class.java, MyServiceImpl())
//    // 解析依赖并使用
//    val myService = DIContainerV1.resolve(MyService::class.java)
//    println(myService.doSth())
//    // 模拟生命周期中的onCreate调用
////    Application().onCreate()
//}
 inline fun aaa(block: ()->Unit){
    println("in aaa")
    block()
    println("final in aaa")
}