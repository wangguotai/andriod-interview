package com.example.mylibrary.leetcode.动态规划.p123;


import java.util.Arrays;

/**
 * 回溯算法、动态规划、贪心算法 一样，完成了一件事情，是分布决策的；
 * dfs遍历了每种可能，
 * dp做了剪枝优化
 * 贪心算法，最求局部最优
 */
public class Solution {
    // p123 买卖股票的最佳时机 三

    /**
     * 给定一个数组，它的第 i 个元素是一支给定的股票在第 i 天的价格。
     * <p>
     * 设计一个算法来计算你所能获取的最大利润。你最多可以完成 两笔 交易。
     * <p>
     * 注意：你不能同时参与多笔交易（你必须在再次购买前出售掉之前的股票）。
     * <p>
     * <p>
     * <p>
     * 示例 1:
     * <p>
     * 输入：prices = [3,3,5,0,0,3,1,4]
     * 输出：6
     * 解释：在第 4 天（股票价格 = 0）的时候买入，在第 6 天（股票价格 = 3）的时候卖出，这笔交易所能获得利润 = 3-0 = 3 。
     * 随后，在第 7 天（股票价格 = 1）的时候买入，在第 8 天 （股票价格 = 4）的时候卖出，这笔交易所能获得利润 = 4-1 = 3 。
     */

    // 方法1. DFS 每次的操作都可以通过决策树的方式展现出来，无非是增添 买入卖出操作两次的限制
    public int maxProfitDFS(int[] prices) {
        if (prices.length < 2) {
            return 0;
        }
        dfs(prices, 0, 0, 0, 2, 0);
        return res;
    }

    int res = 0;

    /**
     * @param prices
     * @param index
     * @param status  0 持有现金 1 持有股票
     * @param step
     * @param maxStep
     * @param profit
     */
    private void dfs(int[] prices, int index, int status, int step, int maxStep, int profit) {
        if (index == prices.length) {
            if (step <= maxStep) {
                if (res < profit) {
                    res = profit;
                }
            }
            return;
        }
        dfs(prices, index + 1, status, step, maxStep, profit);
        if (status == 0) { // 上步为持有现金
            // 进行一步操作 买入股票
            dfs(prices, index + 1, 1, step + 1, maxStep, profit - prices[index]);
        } else {
            // 卖出股票 获得现金
            dfs(prices, index + 1, 0, step + 1, maxStep, profit + prices[index]);
        }
    }

    // 法2： 动态规划

    /**
     * 1. 定义状态
     * - 未进行过任何操作
     * - 只进行过一次买操作
     * - 进行了一次买操作和一次卖操作，即完成了一笔交易
     * - 在完成了一笔交易的前提下进行了第二次买操作
     * - 完成了全部两笔交易
     * // 由于第一个状态的利润显然为0,因此我们可以不用将其记录，对于剩下的四个状态，我们分别将他们的最大利润记为buy1、sell1、buy2、sell2.
     * 将四个状态用dp[n][4]的数组表示
     * 2. 状态转移方程
     * dp[i][0] = max{dp[i-1][0], -prices[i]}  // 在上一天已经买了，或者在今天买，在今天这个状态取二者的最大值
     * dp[i][1] = max{dp[i-1][1], dp[i-1][0] + prices[i]} // 同理，
     * dp[i][2] = max{dp[i-1][2], dp[i-1][1] - prices[i]}
     * dp[i][3] = max{dp[i-1][3], dp[i-1][2] + prices[i]}
     * 3. 确定初始值
     * dp[i][j] = Integer.MIN_VALUE
     * dp[0][0] = -prices[0]
     * dp[0][1] = Integer.MIN_VALUE
     * dp[0][1] = Integer.MIN_VALUE
     * 4. 返回值
     *
     * @param prices
     * @return
     */
    public int maxProfit(int[] prices) {
        if (prices.length < 2) {
            return 0;
        }
        int len = prices.length;
        int[][] dp = new int[len][4];
        Arrays.fill(dp[0], Integer.MIN_VALUE);
        dp[0][0] = -prices[0];
        int maxValue = 0;
        for (int i = 1; i < len; i++) {
            Arrays.fill(dp[i], Integer.MIN_VALUE);
            dp[i][0] = Math.max(dp[i - 1][0], -prices[i]);
            if (dp[i - 1][0] != Integer.MIN_VALUE) {
                dp[i][1] = Math.max(dp[i - 1][1], dp[i - 1][0] + prices[i]); // 完成一次交易
            }
            if (dp[i - 1][1] != Integer.MIN_VALUE) {
                dp[i][2] = Math.max(dp[i - 1][2], dp[i - 1][1] - prices[i]); // 完成一次交易，在买入
            }
            if (dp[i - 1][2] != Integer.MIN_VALUE) {
                dp[i][3] = Math.max(dp[i - 1][3], dp[i - 1][2] + prices[i]); // 完成2次交易
            }
            maxValue = findMax(dp[i][1],dp[i][3], maxValue);
        }
        return maxValue;
    }
    private int findMax(int ...args) {
        int maxValue = args[0];
        for (int i=1;i<args.length;i++){
            if(maxValue < args[i]) {
                maxValue = args[i];
            }
        }
        return maxValue;
    }
    // 没有看到针对该问题的贪心算法


    public static void main(String[] args) {
        int maxProfit = new Solution().maxProfit(new int[]{
                7, 1, 5, 3, 6, 4
        });
        System.out.println(maxProfit);
    }
}
