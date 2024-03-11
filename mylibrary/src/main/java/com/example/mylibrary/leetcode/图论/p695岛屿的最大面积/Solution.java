package com.example.mylibrary.leetcode.图论.p695岛屿的最大面积;

public class Solution {
    public int maxAreaOfIsland(int[][] grid) {
        int m = grid.length;
        int n = grid[0].length;
        int maxArea = 0;
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                maxArea = Math.max(maxArea, area(grid, i, j));
            }
        }
        return maxArea;
    }

    private int area(int[][] grid, int r, int c) {
        if (isOutOfGrid(grid, r, c)) {
            return 0;
        }
        int ans = 0;
        if (grid[r][c] == 1) {
            ans++;
            grid[r][c] = 2;
            ans += area(grid, r + 1, c);
            ans += area(grid, r - 1, c);
            ans += area(grid, r, c + 1);
            ans += area(grid, r, c - 1);
            return ans;
        }
        return 0;
    }

    private boolean isOutOfGrid(int[][] grid, int r, int c) {
        int m = grid.length;
        int n = grid[0].length;
        return r < 0 || r >= m || c < 0 || c >= n;
    }
}