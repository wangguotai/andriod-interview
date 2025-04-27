package com.interview.coroutine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext

class CoroutineDemo {
//    @OptIn(DelicateCoroutinesApi::class)
@OptIn(DelicateCoroutinesApi::class)
fun test() {
        val listner: ()-> Unit = {
            println("In listener")
        }

        runBlocking {
            val scope = CoroutineScope(Dispatchers.Default)
            scope.launch {
                delay(1000)
                println("Coroutines are awesome!")
            }
            println("Main thread is not blocked")
        }

//        val scope = CoroutineScope(EmptyCoroutineContext)
//         scope.launch {
//             println("CoroutineScope.launch")
//         }
//
    val context = newFixedThreadPoolContext(20, "MyThreadPool")
    val scope = CoroutineScope(context)
    context.close()

    }
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            CoroutineDemo().test()
        }
    }
}