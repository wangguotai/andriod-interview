package com.example.mylibrary.leetcode.图论.p994腐烂的橘子;

import java.util.LinkedList;
import java.util.Queue;

public class Solution {
    private static int[][] d = new int[][]{
            new int[]{-1, 0},
            new int[]{1, 0},
            new int[]{0, -1},
            new int[]{0, 1},
    };
    private int m, n;

    public int orangesRotting(int[][] grid) {
        m = grid.length;
        n = grid[0].length;
        int time = 0;
        int freshOrangeNums = 0;
        Queue<int[]> queue = new LinkedList<>();
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                if (grid[i][j] == 2) {
                    queue.offer(new int[]{i, j});
                }
                if (grid[i][j] == 1) {
                    freshOrangeNums++;
                }
            }
        }
        while (!queue.isEmpty()) {
            int size = queue.size();
            time++;
            while (size-- > 0) {
                int[] index = queue.poll();
                for (int k = 0; k < 4; k++) {
                    int x = index[0] + d[k][0];
                    int y = index[1] + d[k][1];
                    if (!isOutOfGrid(x, y) && grid[x][y] == 1) {
                        freshOrangeNums--;
                        grid[x][y] = 2;
                        queue.offer(new int[]{x, y});
                    }
                }
            }
        }
        return freshOrangeNums > 0 ? -1 : time > 0 ? time - 1 : 0;
    }

    private boolean isOutOfGrid(int r, int c) {
        return c < 0 || c >= n || r < 0 || r >= m;
    }
}
