package com.example.mylibrary.leetcode.二维动态规划;

import java.util.Arrays;

class Solution {
    public int uniquePathsWithObstacles(int[][] obstacleGrid) {
        int m = obstacleGrid.length;
        int n = obstacleGrid[0].length;
        int[][] dp = new int[m + 1][n + 1];
        dp[1][0] = 1;
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                if (i == 1 || j == 1) {
                    if (obstacleGrid[i - 1][j - 1] == 1) {
                        dp[i][j] = 0;
                    } else {
                        dp[i][j] = i == 1 ? dp[i][j - 1] : dp[i - 1][j];
                    }
                } else {
                    if (obstacleGrid[i - 1][j - 1] == 1) {
                        dp[i][j] = 0;
                    } else {
                        if (dp[i - 1][j] == 0) {
                            dp[i][j] = dp[i][j - 1];
                        } else if (dp[i][j - 1] == 0) {
                            dp[i][j] = dp[i - 1][j];
                        } else {
                            dp[i][j] = dp[i - 1][j] + dp[i][j - 1];
                        }
                    }
                }
            }
        }
        return dp[m][n];
    }

    public static void main(String[] args) {
//        new Solution().uniquePathsWithObstacles(new int[][]{{1, 0}});
        System.out.println(new Solution().uniquePathsWithObstacles(new int[][]{{0, 0, 0}, {0, 1, 0}, {0, 0, 0}}));
    }
}