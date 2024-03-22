package com.example.mylibrary.leetcode.动态规划.p70爬楼梯;

/**
 * Time: 2024/3/22
 * Author: wgt
 * Description:
 */
public class Solution {
    /**
     * 1. 确定状态
     * dp[i] 表示在第i个台阶是的方法
     * 2. 状态转移方程
     * dp[i] = dp[i-1] + dp[i-2]
     * 3. 确定初始值
     * dp[0] = 1
     * dp[1] = 1
     * 4. 返回值
     * dp[n]
     */
    public int climbStairs(int n) {
        int[] dp = new int[n + 1];
        dp[1] = 1;
        dp[0] = 1;
        for (int i = 2; i <= n; i++) {
            dp[i] = dp[i - 1] + dp[i - 2];
        }
        return dp[n];
    }
}