package com.interview.依赖注入.viewModel

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
data class DependencyDescriptor<T>(val provider: () -> T, val isSingleton: Boolean = true) {
    val instance: T by lazy {
        provider()
    }
}

object DIContainer {
    // 存储依赖项的映射
    val dependencies = mutableMapOf<Class<*>, DependencyDescriptor<*>>()
    // 注册依赖项
    inline fun <reified T : Any> provide(noinline provider: () -> T, isSingleton: Boolean = true) {
        dependencies[T::class.java] = DependencyDescriptor(provider, isSingleton)
    }

    // 获取依赖项
    inline fun <reified T : Any> get(): T {
        val descriptor = dependencies[T::class.java]
            ?: throw java.lang.IllegalArgumentException("${T::class.java} Dependency not fond")
        return if (descriptor.isSingleton) {
            descriptor.instance as T
        } else {
            descriptor.provider() as T
        }
    }
}


// 使用示例
interface MyService {
    fun doSth()
}

class MyServiceA : MyService {
    override fun doSth() {
        println("Doing sthA")
    }
}
class MyServiceB : MyService {
    override fun doSth() {
        println("Doing sthB")
    }
}
fun main() {
    DIContainer.provide<MyServiceA>({ MyServiceA() })
    DIContainer.provide<MyServiceB>({ MyServiceB() }, false)
    val myServiceA1 = DIContainer.get<MyServiceA>()
    val myServiceA2 = DIContainer.get<MyServiceA>()
    val myServiceB1 = DIContainer.get<MyServiceB>()
    val myServiceB2 = DIContainer.get<MyServiceB>()

    println(myServiceA1.doSth())

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
