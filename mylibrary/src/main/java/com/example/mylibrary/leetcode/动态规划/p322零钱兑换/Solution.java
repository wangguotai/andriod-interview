package com.example.mylibrary.leetcode.动态规划.p322零钱兑换;

public class Solution {
    // 本题可以使用回溯兜底

    public static void main(String[] args) {
        System.out.println(
                new Solution().coinChange(
                        new int[]{
                                2, 5, 10, 1
                        }, 27
                )
        );
    }

    /**
     * 1. 确定状态  dp[i] 表示 组成金额i的最少硬币个数
     * 2. 状态转移方程 dp[i] = {
     * dp[i-j] + 1  , 选取可以替换的最小的dp[i-j]
     * }
     * 3. dp[0] =0
     * 4. return dp[n]
     *
     * @param coins
     * @param amount
     * @return
     */
    public int coinChange(int[] coins, int amount) {
        int[] dp = new int[amount + 1];
        for (int i = 1; i <= amount; i++) {
            dp[i] = -1;
            int curMin = Integer.MAX_VALUE;
            for (int j = 0; j < coins.length; j++) {
                if (i - coins[j] >= 0 && dp[i - coins[j]] != -1 && dp[i - coins[j]] < curMin) {
                    curMin = dp[i - coins[j]];
                    dp[i] = curMin + 1;

                }
            }
        }
        return dp[amount];
    }
}
