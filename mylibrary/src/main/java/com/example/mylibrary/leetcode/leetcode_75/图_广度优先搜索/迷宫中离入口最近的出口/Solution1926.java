package com.example.mylibrary.leetcode.leetcode_75.图_广度优先搜索.迷宫中离入口最近的出口;

import java.util.LinkedList;
import java.util.Queue;

public class Solution1926 {
    public int nearestExit(char[][] maze, int[] entrance) {
        int m = maze.length;
        int n = maze[0].length;
        int[][] visited = new int[m][n];
        visited[entrance[0]][entrance[1]] = 1;
        Queue<int[]> queue = new LinkedList<>();
        queue.offer(new int[]{entrance[0], entrance[1], 0});
        int[][] direction = new int[][]{
                {-1, 0}, {1, 0}, {0, 1}, {0, -1}
        };
        int path = -1;
        while (!queue.isEmpty()) {
            int[] curPos = queue.poll();
            int x = curPos[0];
            int y = curPos[1];
            int step = curPos[2];

            // 检查是否为出口
            if((x ==0 || x == m-1 || y ==0 || y==n-1) && (x!=entrance[0] || y!=entrance[1])) {
                return step;
            }

            for (int i =0;i<direction.length;i++) {
                int newX = x + direction[i][0];
                int newY = y + direction[i][1];
                // 新位置在迷宫范围内
                if(newX>=0 && newX < m && newY>=0 && newY < n && maze[newX][newY] == '.' && visited[newX][newY] == 0) {
                    queue.offer(new int[]{newX, newY, step+1});
                    visited[newX][newY] = 1;
                }
            }
        }
        return -1;
    }
}
