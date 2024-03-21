package com.example.mylibrary.leetcode.贪心算法.p122买卖股票的最佳时机;

class Solution {
    // 方法1. 贪心算法
    public int maxProfit(int[] prices) {
        int prePrice = prices[0];
        int profit = 0;
        for (int price : prices) {
            if (price - prePrice > 0) {
                profit += price - prePrice;
            }
            prePrice = price;
        }
        return profit;
    }

    // 方法3. 动态规划
    /**
     1. 确定初始值
     dp[0][0] = 0; // 持有现金
     dp[0][1] = -price[0]; // 持有股票
     2. 动态转移方程
     * 在第i天
     dp[i] = {
     dp[i][0] = max{dp[i-1][0], dp[i-1][1]+price}    i-1天，持有现金;
     dp[i][1] = max{dp[i-1][1], dp[i-1][0]-price}    i-1天，持有股票;
     }
     3. 确定返回值
     dp[n-1][0]
     */
    // public int maxProfit(int[] prices){
    //     int n = prices.length;
    //     if(n<=1){
    //         return 0;
    //     }
    //     int[][] dp = new int[n][n];
    //     dp[0][0] = 0;
    //     dp[0][1] = -prices[0];
    //     for(int i=1;i<n;i++){
    //         dp[i][0] = Math.max(dp[i-1][0], dp[i-1][1]+prices[i]);
    //         dp[i][1] = Math.max(dp[i-1][1], dp[i-1][0]-prices[i]);
    //     }
    //     return dp[n-1][0];
    // }
    // // 方法2. 回溯 每次可以选择持有现金 or 股票
    // public int maxProfit2(int[] prices){
    //     this.prices = prices;
    //     dfs(0,0,0);
    //     return finalProfit;
    // }
    // int finalProfit = 0;
    // int[] prices;
    // //
    // private void dfs(int index,int status, int profit){
    //     if(index == prices.length){
    //         if(profit>finalProfit){
    //             finalProfit = profit;
    //         }
    //         return;
    //     }
    //     dfs(index+1, status, profit);
    //     if(status == 0){
    //         dfs(index+1, 1, profit-prices[index]);
    //     } else {
    //         dfs(index+1, 0, profit+prices[index]);
    //     }
    // }

}