package com.wgt.coroutines

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

//fun main(){

//}

fun main() {
    suspend fun suspendPrint() {
        withContext(Dispatchers.IO) {
            println("Thread:${Thread.currentThread().name}")
        }
    }
//        val coroutineScope = CoroutineScope(Dispatchers.Main)
//    GlobalScope.launch {
//        suspendPrint()
//        println("After suspend")
//    }
    GlobalScope.launch {
        println("Coroutine started: ${Thread.currentThread().name}")
        suspendPrint()
//        delay(1000L)
        println("Hello World!")
    }
    println("In Main")


    println("After launch:${Thread.currentThread().name}")
    Thread.sleep(2000L)
}
