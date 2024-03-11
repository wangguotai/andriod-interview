package com.example.mylibrary.leetcode.图论.p463岛屿的周长;

public class Solution {
    int perimeter = 0;
    private int[][] direction = new int[][]{
            new int[]{-1, 0},
            new int[]{1, 0},
            new int[]{0, -1},
            new int[]{0, 1}
    };
    private int m, n;
    private boolean isStart = false;

    public static void main(String[] args) {
        System.out.println(
                new Solution().islandPerimeter(
                        new int[][]{
//                                new int[]{0, 1, 0, 0},
//                                new int[]{1, 1, 1, 0},
//                                new int[]{0, 1, 0, 0},
//                                new int[]{1, 1, 0, 0},
                                new int[]{1, 1},
                                new int[]{1, 1},

                        }
                )
        );
    }

    public int islandPerimeter(int[][] grid) {
        m = grid.length;
        n = grid[0].length;
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                dfs(grid, i, j);
                if (perimeter != 0) {
                    return perimeter;
                }
            }
        }
        return perimeter;
    }

    private void dfs(int[][] grid, int r, int c) {
        if (isOutOfGrid(grid, r, c) || grid[r][c] == 0) {
            if (isStart) {
                perimeter += 1;
            }
            return;
        }
        if (grid[r][c] == 2) {
            return;
        }
        isStart = true;
        grid[r][c] = 2;
        for (int i = 0; i < 4; i++) {
            int x = r + direction[i][0];
            int y = c + direction[i][1];
            dfs(grid, x, y);
        }
    }

    private boolean isOutOfGrid(int[][] grid, int r, int c) {
        return r < 0 || r >= m || c < 0 || c >= n;
    }
}
