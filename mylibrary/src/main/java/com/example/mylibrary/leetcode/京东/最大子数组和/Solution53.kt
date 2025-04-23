package com.example.mylibrary.leetcode.京东.最大子数组和

import java.util.Arrays
import kotlin.math.max

class Solution53 {
    fun maxSubArray(nums: IntArray): Int {
        // 确定状态 dp[i] 表示以nums[i]结尾的最大子数组和
        val dp: IntArray = IntArray(nums.size)
        // 初始值
        dp[0] = nums[0]
        var maxValue = dp[0]
        // 状态转移
        for(i: Int in 1 until nums.size) {
            dp[i] = (dp[i - 1] + nums[i]).coerceAtLeast(nums[i])
            maxValue = maxValue.coerceAtLeast(dp[i])
        }
        return maxValue
    }
}