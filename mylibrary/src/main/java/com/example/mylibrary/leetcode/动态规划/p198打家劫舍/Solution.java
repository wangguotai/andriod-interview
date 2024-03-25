package com.example.mylibrary.leetcode.动态规划.p198打家劫舍;

public class Solution {
    /**
     * 1. 确定状态 在第i个房屋抢到的最大现金数
     * 2. 状态转移方程 dp[i] = max{dp[i-1], dp[i-2] + nums[i]}
     * 3. 确定初始值 d[0]=0, dp[1]=nums[1]
     * 4. 确定返回值 dp[n]
     */
    public int rob(int[] nums) {
        int n = nums.length;
        if (n == 1) return nums[0];
        int[] dp = new int[n + 1];
        dp[0] = 0;
        dp[1] = nums[0];
        for (int i = 1; i < n; i++) {
            dp[i + 1] = Math.max(dp[i], dp[i - 1] + nums[i]);
        }
        return dp[n];
    }
}
