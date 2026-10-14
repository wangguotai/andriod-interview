package com.example.mylibrary.leetcode.leetcode_75.动态规划.`第 N 个泰波那契数`

fun tribonacci(n: Int): Int {
    var t0 = 0
    var t1 = 1
    var t2 = 1
    var t3: Int
    return when(n) {
        0-> t0
        1,2 -> t1
        else ->{
            for(i in 3.. n) {
                t3 = t0 + t1 + t2
                t0 = t1
                t1 = t2
                t2 = t3
            }

            t2
        }
    }

}