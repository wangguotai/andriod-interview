package com.mi.slide_card.dao

interface Container {
    fun <T : Any> createBean(clazz: Class<T>): T
    fun <T : Any> provide(clazz: Class<T>, provider: () -> T, isSingleton: Boolean = false)
}

class BeanDescriptor<T>(val provider: () -> T, val isSingleton: Boolean = false) {
    val instance: T by lazy { provider() }
}

object DIContainer : Container {


    private val dependencies = mutableMapOf<Class<*>, BeanDescriptor<*>>()
    override fun <T : Any> createBean(clazz: Class<T>): T {
        val descriptor =
            dependencies[clazz]
                ?: throw java.lang.IllegalArgumentException("$clazz Dependency not fond")
        return if (descriptor.isSingleton) {
            descriptor.instance as T
        } else {
            descriptor.provider() as T
        }
    }

    override fun <T : Any> provide(clazz: Class<T>, provider: () -> T, isSingleton: Boolean) {
        dependencies[clazz] = BeanDescriptor(provider, isSingleton)
    }
}