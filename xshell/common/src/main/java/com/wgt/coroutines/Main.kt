package com.wgt.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

//fun main(){

//}
suspend fun suspendPrint() {
    withContext(Dispatchers.IO) {
        println("Thread:${Thread.currentThread().name}")
    }
}

fun main() {

//        val coroutineScope = CoroutineScope(Dispatchers.Main)
//    GlobalScope.launch {
//        suspendPrint()
//        println("After suspend")
//    }
    var i = 0
    repeat(10) {
        CoroutineScope(Executors.newFixedThreadPool(1).asCoroutineDispatcher())
            .launch {
                println("Coroutine started: ${Thread.currentThread().name} ${i++}")
                suspendPrint()
//        delay(1000L)
//            println("Hello World!")
            }
    }
    println("In Main")


    println("After launch:${Thread.currentThread().name}")
    Thread.sleep(12000L)
}
//
//fun main(){
//    Thread{
//        var i = 0
//        repeat(100, {
//            val job = GlobalScope.launch {
//                println("Coroutine started on thread:${Thread.currentThread()}, ${i++}")
//            }
////            runBlocking {
////                job.join()
////            }
//        })
//    }
//}