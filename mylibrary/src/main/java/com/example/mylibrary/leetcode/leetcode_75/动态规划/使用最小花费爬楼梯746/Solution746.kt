package com.example.mylibrary.leetcode.leetcode_75.动态规划.使用最小花费爬楼梯746

fun minCostClimbingStairs(cost: IntArray): Int {
    val dp = IntArray(cost.size)
    // dp[i] = min{dp[i-1] + cost[i-1], dp[i-2] + cost[i-2]}
    for(i in 2 ..< cost.size) {
        dp[i] = (dp[i - 1] + cost[i - 1]).coerceAtMost(dp[i - 2] + cost[i - 2])
    }
    return (dp[cost.size-1] + cost[cost.size -1]).coerceAtMost(dp[cost.size-2] + cost[cost.size - 2])
}

fun main() {
    minCostClimbingStairs(intArrayOf(1,100,1,1,1,100,1,1,100,1))
}
