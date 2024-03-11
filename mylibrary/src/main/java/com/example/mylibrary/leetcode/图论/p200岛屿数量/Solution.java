package com.example.mylibrary.leetcode.图论.p200岛屿数量;

/**
 * 在 LeetCode 中，「岛屿问题」是一个系列系列问题，比如：
 * <p>
 * L200. 岛屿数量 （Easy）
 * 463. 岛屿的周长 （Easy）
 * 695. 岛屿的最大面积 （Medium）
 * 827. 最大人工岛 （Hard）
 * <p>
 * 1. 网格类问题的基本概念  <a href="https://leetcode.cn/problems/number-of-islands/solutions/211211/dao-yu-lei-wen-ti-de-tong-yong-jie-fa-dfs-bian-li-/?envType=study-plan-v2&envId=top-100-liked">链接</a>
 * 网格问题是由 m×n个小方格组成一个网格，每个小方格与其上下左右四个方格认为是相邻的，要在这样的网格上进行某种搜索。
 * 岛屿问题是一类典型的网格问题。每个格子中的数字可能是 0 或者 1。我们把数字为 0 的格子看成海洋格子，数字为 1 的格子看成陆地格子，这样相邻的陆地格子就连接成一个岛屿。
 */
public class Solution {
    // 方法：图的DFS遍历
    public int numIslands(char[][] grid) {
        int m = grid.length;
        int n = grid[0].length;
        int ans = 0;
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                if (dfs(grid, i, j) > 0) {
                    ans++;
                }
            }
        }
        return ans;
    }

    private int dfs(char[][] grid, int r, int c) {
        if (isOutsideGrid(grid, r, c)) {
            return 0;
        }
        int ans = 0;
        if (grid[r][c] == '1') {
            ans++;
            grid[r][c] = '2';
            ans += dfs(grid, r + 1, c);
            ans += dfs(grid, r - 1, c);
            ans += dfs(grid, r, c - 1);
            ans += dfs(grid, r, c + 1);
            return ans;
        } else {
            return 0;
        }

    }

    private boolean isOutsideGrid(char[][] grid, int r, int c) {
        int m = grid.length;
        int n = grid[0].length;
        return r < 0 || r >= m || c < 0 || c >= n;


    }

//    public static void main(String[] args) {
//        System.out.println(
//                new Solution().numIslands(
//                        new
//                )
//        );
//    }
}
