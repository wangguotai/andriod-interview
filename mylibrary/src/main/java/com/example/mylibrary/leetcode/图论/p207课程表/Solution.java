package com.example.mylibrary.leetcode.图论.p207课程表;

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * 本题是一道经典的[拓扑排序]问题
 * 给定一个包含n个节点的有向图G，我们给出它的节点编号的一种排列，如果满足：
 * 对于图G中的任意一条有向边(u,v),u在排列中都出现在v的前面。
 * 那么称该排列是图G的【拓扑排序】。
 */
public class Solution {


    public static void main(String[] args) {
        System.out.println(
                new Solution().canFinish(
                        2, new int[][]{
                                new int[]{1, 0},
//                                new int[]{0, 1},
                        }
                )
        );
    }

    /**
     * 方法2. 广度优先搜索
     *
     * @param numCourses
     * @param prerequisites
     * @return
     */
    public boolean canFinish(int numCourses, int[][] prerequisites) {
        // 存储有向图
        List<List<Integer>> edges = new LinkedList<>();
        // 存储每个节点的入度
        int[] indeg = new int[numCourses];
        Queue<Integer> queue = new LinkedList<>();
        List<Integer> result = new LinkedList<>();
        // 初始化每个课程节点
        for (int i = 0; i < numCourses; i++) {
            edges.add(new LinkedList<>());
        }
        // 向节点中添加边信息，初始化入度
        for (int[] vector : prerequisites) {
            edges.get(vector[1]).add(vector[0]);
            indeg[vector[0]]++;
        }

        boolean flag;
        do {
            for (int i = 0; i < numCourses; i++) {
                if (indeg[i] == 0) {
                    queue.offer(i);
                    indeg[i] = -1;
                }
            }
            int size = queue.size();
            flag = size > 0;
            while (size-- > 0) {
                int node = queue.poll();
                result.add(node);
                for (int i : edges.get(node)) {
                    indeg[i]--;
                }
            }

        } while (flag);
        // 如果存在环则结果数组不满足课程数量
        return result.size() == numCourses;
    }
}
