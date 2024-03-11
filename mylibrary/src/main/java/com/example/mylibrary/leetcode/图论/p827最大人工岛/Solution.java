package com.example.mylibrary.leetcode.图论.p827最大人工岛;

import java.util.HashMap;
import java.util.Map;

public class Solution {
    private static int[][] d = {
            new int[]{-1, 0},
            new int[]{1, 0},
            new int[]{0, -1},
            new int[]{0, 1}
    };
    int curIndex = 2;
    Map<Integer, Integer> isLandMap = new HashMap<>();

    public static void main(String[] args) {
        System.out.println(
                new Solution().largestIsland(
                        new int[][]{
                                new int[]{1, 1},
                                new int[]{1, 1},
                        }
                )
        );
    }

    private int area(int[][] grid, int r, int c) {
        if (isOutsideGrid(grid, r, c)) {
            return 0;
        }
        int ans = 0;
        if (grid[r][c] == 1) {
            ans++;
            grid[r][c] = curIndex;
            // eachArea[r][c] = curIndex;
            ans += area(grid, r + 1, c);
            ans += area(grid, r - 1, c);
            ans += area(grid, r, c + 1);
            ans += area(grid, r, c - 1);
            return ans;
        }
        return 0;
    }

    private int changeSeaToIsland(int[][] grid, int r, int c) {
        if (isOutsideGrid(grid, r, c) || grid[r][c] != 0) {
            return 0;
        }
        int val = 1;
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < 4; i++) {
            int x = r + d[i][0];
            int y = c + d[i][1];
            if (!isOutsideGrid(grid, x, y) && grid[x][y] > 0 && map.get(grid[x][y]) == null) {
                map.put(grid[x][y], isLandMap.get(grid[x][y]));
                val += isLandMap.get(grid[x][y]);
            }
        }
        return val;
    }

    private boolean isOutsideGrid(int[][] grid, int r, int c) {
        int m = grid.length;
        int n = grid[0].length;
        return r < 0 || r >= m || c < 0 || c >= n;
    }

    // int[][] eachArea;
    public int largestIsland(int[][] grid) {
        int m = grid.length;
        int n = grid[0].length;
        // eachArea = new int[m][n];
        // DFS先更新岛屿的面积
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                int currArea = area(grid, i, j);
                if (currArea > 0) {
                    isLandMap.put(curIndex++, currArea);
                }
            }
        }
        int maxArea = 0;
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                maxArea = Math.max(maxArea, changeSeaToIsland(grid, i, j));
            }
        }
        if (maxArea == 0) {
            maxArea = isLandMap.get(curIndex - 1);
        }
        return maxArea;
    }

}
