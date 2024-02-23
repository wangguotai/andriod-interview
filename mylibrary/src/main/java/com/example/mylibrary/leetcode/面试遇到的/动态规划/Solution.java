package com.example.mylibrary.leetcode.面试遇到的.动态规划;


import java.util.Arrays;

/**
 * 回溯算法、动态规划、贪心算法 一样，完成了一件事情，是分布决策的；
 * dfs遍历了每种可能，
 * dp做了剪枝优化
 * 贪心算法，最求局部最优
 */
public class Solution {
    // p121  先做一下面试题中遇到题目的前向问题，一次买入卖出
    public int maxProfitV1(int[] prices) {
        if (prices == null || prices.length < 2) {
            return 0; // 如果价格数组为空或长度小于2，无法进行交易，返回 0
        }
        int minPrice = prices[0]; // 初始化最低价格为第一天的价格
        int maxProfit = 0;   // 初始化最大利润为 0
        for (int i = 1; i < prices.length; i++) {
            if (prices[i] < minPrice) {
                minPrice = prices[i]; // 更新最低价格
            } else {
                int profit = prices[i] - minPrice; // 计算当前利润
                if (profit > maxProfit) {
                    maxProfit = profit; // 更新最大利润
                }
            }
        }
        return maxProfit;
    }

    // p122 本次遇到的题目

    // 方法1. 暴力搜索 通过回溯搜索
    private int res;
    public int maxProfitDFS(int[] prices) {
        int len = prices.length;
        if (len < 2) {
            return 0;
        }
        this.res = 0;
        dfs(prices, 0, len, 0, res);
        return res;
    }
    /**
     * @param prices 股价数组
     * @param index  当前是第几天，从 0 开始
     * @param status 0 表示不持有股票，1表示持有股票，
     * @param profit 当前收益
     */
    private void dfs(int[] prices, int index, int len, int status, int profit) {
        if(index == len) {
            this.res = Math.max(this.res, profit);
            return;
        }
        // 什么都不操作
        dfs(prices, index+1, len, status, profit);
        if(status == 0){ // 不持有股票
            // 可以尝试转向 1
            dfs(prices, index+1, len, 1, profit-prices[index]);
        } else {
            // 此时 已经购买了股票，卖掉
            dfs(prices, index+1, len, 0, profit+prices[index]);
        }
    }
    // 方法2. 动态规划

    /**
     * 1. 第一步 定义状态
     * dp[i][j]定义如下：
     * dp[i][j]表示到下标i的这一天，持股状态为j时，我们手上拥有的最大现金数。
     * 第一维 i 表示下标为 i 的那一天（ 具有前缀性质，即考虑了之前天数的交易 ）；
     * 第二维 j 表示下标为 i 的那一天是持有股票，还是持有现金。这里 0 表示持有现金（cash），1 表示持有股票（stock）。
     * 2. 状态转移方程：
     *      dp[i][1] = max{dp[i-1][1], dp[i-1][0]-price[i]}
     *      dp[i][0] = max{dp[i-1][0], dp[i-1][1]+price[i]}
     *
     * 3. 确定初始值
     *  起始时：
     *      如果什么都不做， dp[0][0] = 0
     *      如果持有股票，当前拥有的现金数是当天股价的相反数，即dp[0][1] = -prices[0];
     * 4. 确定输出值
     *  终止的时候，输出dp[len-1][0] 最后一天卖出，肯定大于持有
     */
    public int maxProfitDP(int[] prices) {
        if(prices == null || prices.length < 2) {
            return 0;
        }
        int len = prices.length;
        int[][] dp = new int[len][2];
        dp[0][1] = -prices[0];
        dp[0][0] = 0;
        for (int i=1;i<len;i++) {
            dp[i][0] = Math.max(dp[i-1][0], dp[i-1][1]+prices[i]);
            dp[i][1] = Math.max(dp[i-1][1], dp[i-1][0]-prices[i]);
        }
        return dp[len-1][0];
    }
    // 贪心算法
    public int maxProfit(int[] prices){
        int len = prices.length;
        if(len < 2) {
            return 0;
        }
        int res = 0;
        for (int i=1; i<len; i++) {
            res += Math.max(prices[i]-prices[i-1], 0);
        }
        return res;
    }

//  自己dp的思路是错的
//    public int maxProfit(int[] prices) {
//        int n = prices.length;
//        int[] dp = new int[n + 1];
//        int[] sellIndex = new int[n+1]; // 记录出卖是的index
//        Arrays.fill(sellIndex,-1);
//        // 构建状态转移方程 dp[0]=0, dp[1] = 0, dp[i]=max{全局，dp[i-1]+curProfit}
//        for (int i = 1; i < n; i++) {
//            int minPrice = prices[0];
//            int maxProfit = 0;
//            for (int j = 0; j <= i; j++) {
//                if(prices[j] < minPrice) {
//                    minPrice = prices[j];
//                } else {
//                    int profit = prices[j] - minPrice;
//                    if(profit>maxProfit) {
//                        maxProfit = profit;
//                        sellIndex[i] = j;
//                    }
//                }
//            }
//            if(dp[i-1] != 0 && sellIndex[i-1] != -1) {
//                int curProfit =prices[i]- prices[sellIndex[i-1]];
//                if(curProfit > 0) {
//                    dp[i] = Math.max(maxProfit, dp[i-1] + curProfit);
//                } else {
//                    dp[i] = Math.max(maxProfit, dp[i-1]);
//                }
//            } else {
//                dp[i] = maxProfit;
//            }
//        }
//        return dp[n-1];
//    }





    public static void main(String[] args) {
        int maxProfit = new Solution().maxProfit(new int[]{
                7, 1, 5, 3, 6, 4
        });
        System.out.println(maxProfit);
    }
}
