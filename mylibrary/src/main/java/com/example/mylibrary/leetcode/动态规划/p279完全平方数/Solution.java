package com.example.mylibrary.leetcode.动态规划.p279完全平方数;

public class Solution {
    /**
     * 1. 确定状态  f(i) 表示最小个数的完全平方数 来表示整数 i
     * 2. 状态转移方程 f(i) = 1+fmin(1->根号n)()
     *
     * @param n
     * @return
     */
    public int numSquares(int n) {
        int[] dp = new int[n + 1];
        for (int i = 1; i <= n; i++) {
            int minn = Integer.MAX_VALUE;
            for (int j = 1; j * j <= i; j++) {
                minn = Math.min(minn, dp[i - j * j]);
            }
            dp[i] = minn + 1;
        }
        return dp[n];
    }
}
