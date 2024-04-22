package com.wgt.coroutines

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

//fun main(){

//}
suspend fun suspendPrint() {
    withContext(Dispatchers.IO) {
        println("Thread:${Thread.currentThread().name}")
    }
}

//fun main() {

//        val coroutineScope = CoroutineScope(Dispatchers.Main)
//    GlobalScope.launch {
//        suspendPrint()
//        println("After suspend")
//    }
//    var i = 0
//    repeat(10) {
//        CoroutineScope(Executors.newFixedThreadPool(1).asCoroutineDispatcher())
//            .launch {
//                println("Coroutine started: ${Thread.currentThread().name} ${i++}")
//                suspendPrint()
////        delay(1000L)
////            println("Hello World!")
//            }
//    }
//    println("In Main")
//
//
//    println("After launch:${Thread.currentThread().name}")
//    Thread.sleep(12000L)


//}
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
@Volatile
var item = AtomicInteger(0)
fun main() {
//
//    val   num = AtomicInteger(0)
//    var   num1 = 0
//    val scope = CoroutineScope(Dispatchers.IO)
//    // 启动一个协程
//    repeat(10){
//        val job = scope.launch {
//            num.addAndGet(1)
//            num1++
//            item.addAndGet(1)
//            println("$this in ${Thread.currentThread()} 开始执行")
//            Thread.sleep((it % 5 * 1000).toLong())
//            println("$this in ${Thread.currentThread()} 执行结束")
//        }
//        job.cancel()
//    }
//    scope.cancel("自定义主动取消")
//    println(num.get())
//    println(num1)
//    println(item.get())

    runBlocking {
        val startTime = System.currentTimeMillis()
//        launch {
//
//        }
//        async {
//
//        }
        val job = launch(Dispatchers.Default) {
//        val job:Deferred<Any> = async {
            var nextPrintTime = startTime
            var i = 0

            while (i < 15) {//打印前五条消息
                if (System.currentTimeMillis() >= nextPrintTime) {//每秒钟打印两次消息
                    println("job: I'm sleeping ${i++} ...")
                    nextPrintTime += 500
                }
            }
        }
        delay(1200)//延迟1.2s
        job.cancel()
        print("等待1.2秒后")
//        job.join()
        print("协程被取消")


    }

    Thread.sleep(300000)

}

suspend fun temp1() {
    GlobalScope.launch {

    }
    CoroutineScope(Dispatchers.IO).launch {

    }
}