package com.example.mylibrary.leetcode.hot100

import java.util.LinkedList

// LRU
object LRUCacheDemo{
    //
    val lruQueueCache = LinkedList<String>()
    val hashMap = mutableMapOf<String, Any>()
    const val capability = 16
    var currentSize = 0
    @Synchronized
    fun submit(str: String, obj: Any) {
        if(currentSize < capability) {
            lruQueueCache.offer(str)
            currentSize++
            hashMap[str] = obj
        } else {
            val key = lruQueueCache.poll()
            currentSize--
            hashMap.remove(key)
        }
    }
    @Synchronized
    fun getCacheItem(key: String): Any? {
        val value = hashMap[key]
        if(value!=null) {
            lruQueueCache.remove(key)
            lruQueueCache.offer(key)
        }
        return value
    }
}

fun main() {
    for (i in 0..LRUCacheDemo.capability + 2) {
        LRUCacheDemo.submit(i.toString(), i)
    }
    for (i in 0 .. 3) {
        LRUCacheDemo.getCacheItem(i.toString())
    }
}
