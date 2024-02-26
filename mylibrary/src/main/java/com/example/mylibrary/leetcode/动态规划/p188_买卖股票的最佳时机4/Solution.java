package com.example.mylibrary.leetcode.动态规划.p188_买卖股票的最佳时机4;

import java.util.Arrays;

/**
 * 给你一个整数数组 prices 和一个整数 k ，其中 prices[i] 是某支给定的股票在第 i 天的价格。
 * <p>
 * 设计一个算法来计算你所能获取的最大利润。你最多可以完成 k 笔交易。也就是说，你最多可以买 k 次，卖 k 次。
 * <p>
 * 注意：你不能同时参与多笔交易（你必须在再次购买前出售掉之前的股票）。
 * <p>
 * <p>
 * <p>
 * 示例 1：
 * <p>
 * 输入：k = 2, prices = [2,4,1]
 * 输出：2
 * 解释：在第 1 天 (股票价格 = 2) 的时候买入，在第 2 天 (股票价格 = 4) 的时候卖出，这笔交易所能获得利润 = 4-2 = 2 。
 * 示例 2：
 * <p>
 * 输入：k = 2, prices = [3,2,6,5,0,3]
 * 输出：7
 * 解释：在第 2 天 (股票价格 = 2) 的时候买入，在第 3 天 (股票价格 = 6) 的时候卖出, 这笔交易所能获得利润 = 6-2 = 4 。
 * 随后，在第 5 天 (股票价格 = 0) 的时候买入，在第 6 天 (股票价格 = 3) 的时候卖出, 这笔交易所能获得利润 = 3-0 = 3 。
 * <p>
 * 提示：
 * 1 <= k <= 100
 * 1 <= prices.length <= 1000
 * 0 <= prices[i] <= 1000
 */
class Solution {
    /**
     * 方法1. 我可以使用DFS进行回溯，收拢每条路径上小于等于k次交易 将期间获取的最大利润进行比较，获取最大值
     * 但会出现超时的情况
     * 每天的操作状态有，持有现金或持有股票
     *
     * @param k
     * @param prices
     * @return
     */
    public int maxProfitDFS(int k, int[] prices) {
        if (prices.length < 2) {
            return 0;
        }
        dfs(prices, 0, 0, 0, k * 2, 0);
        return res;
    }

    private int res;

    /**
     * @param prices
     * @param index
     * @param status  0 持有现金， 1 持有股票
     * @param step
     * @param maxStep
     * @param profit
     */
    private void dfs(int[] prices, int index, int status, int step, int maxStep, int profit) {
        if (step > maxStep) {
            return;
        }
        if (index == prices.length) {
            if (res < profit) {
                res = profit;
            }
            return;
        }
        // 不操作
        dfs(prices, index + 1, status, step, maxStep, profit);
        if (status == 0) {
            dfs(prices, index + 1, 1, step + 1, maxStep, profit - prices[index]);
        } else {
            dfs(prices, index + 1, 0, step + 1, maxStep, profit + prices[index]);
        }
    }

    /**
     * 方法2. 动态规划 类似最多操作两次的场景 增添多个状态转移，但多个状态之间的转移存在规律性
     * 1. 定义状态 每种操作下第 i 天的最大利润
     * dp[i][0]: 未进行任何操作 该状态只能是一条道走到黑，没有任何操作
     * dp[i][1]: 进行了一次买入
     * dp[i][2]: 进行了一次买入卖出，完成了一次交易
     * dp[i][3]: 进行了一次买入卖出，再买入
     * dp[i][4]: 进行了一次买入卖出，再买入，再卖出
     * ...
     * dp[i][2k-1]
     * dp[i][2k]
     * 2. 状态转移方程
     * 第i天的第k个状态
     * f(i)(k) = max(f(i-1)(k), f(i-1)(k-1) if(k%2 == 0) +price(i) else -price(i))
     * 3. 确定初始值
     * <p>
     * 4. 确定返回值
     *
     * @param k
     * @param prices
     * @return
     */
    public int maxProfit(int k, int[] prices) {
        if (prices.length < 2) {
            return 0;
        }
        int[][] dp = new int[prices.length][k * 2 + 1];
        // 第1天（下标为0）最多只能进行一次买入 一次卖出
        Arrays.fill(dp[0], Integer.MIN_VALUE);
        dp[0][0] = 0;
        dp[0][1] = -prices[0];
        dp[0][2] = 0;
        int maxValue = 0;
        // 第二天开始更新状态
        for (int i = 1; i < prices.length; i++) {
            Arrays.fill(dp[i], Integer.MIN_VALUE);
            dp[i][0] = 0;
            // 再第i天的状态最多有： min{2(i+1)+1, 2k+1}
            int statusNum = Math.min(2 * (i + 1) + 1, 2 * k + 1);
            for (int j = 1; j < statusNum; j++) {
                if (j % 2 == 0) {
                    if (dp[i - 1][j] == Integer.MIN_VALUE) {
                        dp[i][j] = dp[i][j-1] + prices[i];
                    } else {
                        dp[i][j] = Math.max(dp[i - 1][j], dp[i - 1][j - 1] + prices[i]);
                    }
                } else {
                    dp[i][j] = Math.max(dp[i - 1][j], dp[i - 1][j - 1] - prices[i]);
                }
            }
            maxValue = findMax(maxValue, dp[i]);
        }
        return maxValue;
    }
    private int findMax(int curr, int[] args) {
        int maxValue = curr;
        for (int i=1;i<args.length;i++){
            if(maxValue < args[i]) {
                maxValue = args[i];
            }
        }
        return maxValue;
    }

    public static void main(String[] args) {
        System.out.println(
                new Solution().maxProfit(2, new int[]{
                        3, 2, 6, 5, 0, 3
                })
        );
    }
}