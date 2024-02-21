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
object DIContainer {
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
    // 注册依赖项
    DIContainer.register(MyService::class.java, MyServiceImpl())
    // 解析依赖并使用
    val myService = DIContainer.resolve(MyService::class.java)
    println(myService.doSth())


    // 模拟生命周期中的onCreate调用
//    Application().onCreate()
}