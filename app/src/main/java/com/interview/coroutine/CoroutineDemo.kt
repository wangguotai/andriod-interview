package com.interview.coroutine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class CoroutineDemo {
//    @OptIn(DelicateCoroutinesApi::class)
    fun test() {
        val listner: ()-> Unit = {
            println("In listener")
        }

        runBlocking {


            val async1 = async {
                println("async1")
            }
            val async2 = async {
                println("async2")
            }



            val scope = CoroutineScope(Dispatchers.Default)


            scope.launch {
                delay(1000)
                println("Coroutines are awesome!")
            }
            println("Main thread is not blocked")
        }

    }
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            CoroutineDemo().test()
        }
    }
}