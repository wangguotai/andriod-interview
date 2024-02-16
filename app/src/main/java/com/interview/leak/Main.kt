package com.interview.leak

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * Time: 2024/2/15
 * Author: wgt
 * Description:
 */

fun main() {
    val referenceQueue = ReferenceQueue<Any>()

    // 创建一些弱引用，并将它们注册到引用队列中
    val weakRefs = mutableListOf<WeakReference<Any>>()
    repeat(5) {
        val obj = Any()
        val weakRef = WeakReference(obj, referenceQueue)
        weakRefs.add(weakRef)
    }

    // 手动触发垃圾回收
    System.gc()

    // 等待一段时间以确保垃圾回收被执行
    Thread.sleep(1000)

    // 检查 ReferenceQueue 中是否有引用对象
    var ref: Any?
    while (referenceQueue.poll().also { ref = it?.get() } != null) {
        println("Weak reference enqueued: $ref")
    }
}
