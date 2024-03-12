package com.example.mylibrary.leetcode.图论.p210课程表二;

import java.util.LinkedList;
import java.util.List;

/**
 * 方法1. 深度优先搜索
 */
public class Solution {
    // 存储有向图
    List<List<Integer>> edges;
    // 标记每个节点的状态：0=未搜索，1=搜索中，2=已完成
    int[] visited;
    // 用数组来模拟栈，下标n-1为栈底，0为栈顶
    int[] result;
    // 判断有向图中是否有环
    boolean valid = true;
    // 栈下标
    int index;

    public int[] findOrder(int numCourses, int[][] prerequisites) {
        edges = new LinkedList<>();
        for (int i = 0; i < numCourses; i++) {
            edges.add(new LinkedList<>());
        }
        visited = new int[numCourses];
        result = new int[numCourses];
        index = numCourses - 1;
        // 初始化边信息
        for (int[] info : prerequisites) {
            edges.get(info[1]).add(info[0]);
        }
        // 每次挑选一个【未搜索】的节点，开始进行深度优先搜索
        for (int i = 0; i < numCourses && valid; i++) {
            if (visited[i] == 0) {
                dfs(i);
            }
        }
        if (!valid) {
            return new int[0];
        }
        return result;
    }

    private void dfs(int u) {
        // 将节点标记为【搜索中】
        visited[u] = 1;
        // 搜索其相邻节点
        // 只要发现有环，立即停止搜索
        for (int v : edges.get(u)) {
            // 如果【未搜索】那么搜索相邻节点
            if (visited[v] == 0) {
                dfs(v);
                if (!valid) {
                    return;
                }
            } else if (visited[v] == 1) {
                valid = false;
                return;
            }
        }
        // 将节点标记为【已完成】
        visited[u] = 2;
        // 将节点入栈
        result[index--] = u;
    }
}
