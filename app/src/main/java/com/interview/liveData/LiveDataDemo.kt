package com.interview.liveData

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class LiveData<T> {
    private val observers = CopyOnWriteArrayList<(T) -> Unit>()
    private var value: T? = null

    // 观察 LiveData
    fun observe(observer: (T)-> Unit) {
        observers.add(observer)
        value?.let(observer)
    }

    // 设置LiveData的值
    fun setValue(value: T) {
        this.value = value
        notifyObservers(value)
    }
    private fun notifyObservers(value: T) {
        for (observer in observers) {
            observer(value)
        }
    }
}

fun main() {
    // 创建LiveData实例
    val liveData = LiveData<Int>()
    // 观察LiveData
    liveData.observe {
        println("Observer 1: $it")
    }
    // 在新线程中模拟异步更新 LiveData 的值
    thread {
        Thread.sleep(1000)
        liveData.setValue(42)
    }
    // 观察 LiveData
    liveData.observe { value ->
        println("Observer 2: $value")
    }
    // 在主线程中更新 LiveData 的值
    liveData.setValue(10)

    // 等待异步线程执行完成
    Thread.sleep(2000)
}