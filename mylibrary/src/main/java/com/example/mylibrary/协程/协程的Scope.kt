package com.example.mylibrary.协程

import kotlinx.coroutines.*

suspend fun loadAll() = coroutineScope {
    Dispatchers.Unconfined
    launch {
        delay(1000)
        println("任务A完成")
    }
    launch {
        delay(500)
        println("任务B完成")
    }
    println("coroutineScope 代码块结束")
    // 这里会挂起，等 A、B 都完成才返回
}

//fun main() {
//    val scope = CoroutineScope(Job() + Dispatchers.Default)
//    scope.launch {
//        repeat(5) { i->
//            delay(500)
//            println("子协程仍在运行: $i")
//        }
//
//    }
//    Thread.sleep(1200   )
////    delay(1200)
//    println(">>> 取消作用域")
//    scope.cancel()
////    delay(1000)
//    println(">>> main结束，子协程已停止")
//}

//fun main() = runBlocking {
//    println("开始")
//    loadAll()
//    println("loadAll 返回，全部完成")
//
//}

suspend fun supervised() = supervisorScope {
    launch {
        delay(300)
        throw RuntimeException("A 失败")   // 只有它自己失败
    }
    launch {
        delay(800)
        println("B 完成")   // 会打印
    }

}

//fun main(): kotlin.Unit = runBlocking {
//    try {
//        supervised()
//    } catch (e: Exception) {
//        println("捕获异常: ${e.message}")
//    }
//}

// async + Scope

//suspend fun fetchUser(): String {
//    delay(1000)
//    return "用户数据"
//}
//
//suspend fun fetchOrders(): String {
//    delay(1000)
//    return "订单数据"
//}
//
//suspend fun loadDashboard() = coroutineScope {
//    val start = System.currentTimeMillis()
//    val user = async { fetchUser() }
//    val orders = async { fetchOrders() }
//    val u = user.await()
//    val o = orders.await()
//
//    val cost = System.currentTimeMillis() - start
//    println("耗时: ${cost}ms")
//    "$u + $o"
//}
//
//fun main() {
// val re =  runBlocking {
//        println("Start")
//        val result = withContext(Dispatchers.IO) {
//            delay(1000)
//            "Hello, Kotlin Coroutines!"
//        }
//        println(result)
//        result
//    }
//    println("主线程执行结束 $re")
////    Thread.sleep(2000)
//}


fun main() = runBlocking {
    // 统一异常处理器
    val handler = CoroutineExceptionHandler { context, throwable ->
        println("【统一收集】捕获到异常: ${throwable.message}")
        // 这里可以做：上报、日志、告警、降级等
    }

    // SupervisorJob + Handler
    val scope = CoroutineScope(SupervisorJob() + handler + Dispatchers.Default)

    // 启动多个子协程
    scope.launch {
        delay(100)
        throw RuntimeException("任务A失败")
    }

    scope.launch {
        delay(200)
        println("任务B正常完成")   // 不受 A 影响
    }

    scope.launch {
        delay(300)
        throw RuntimeException("任务C失败")
    }

    delay(100000)
    scope.cancel()
}

fun test(){
    Dispatchers.Default
}
