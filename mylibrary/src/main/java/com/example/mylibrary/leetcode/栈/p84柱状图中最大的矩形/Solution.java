package com.example.mylibrary.leetcode.栈.p84柱状图中最大的矩形;

import java.util.Deque;
import java.util.LinkedList;

public class Solution {
    /**
     * 暴力破解法，枚举宽度【左右边界】 左右边界确定后找高度
     * o(N^2)
     *
     * @param heights
     * @return
     */
    public int largestRectangleArea1(int[] heights) {
        int n = heights.length;
        int ans = 0;
        // 枚举左边界
        for (int left = 0; left < n; left++) {
            int minHeight = Integer.MAX_VALUE;
            // 枚举右边界
            for (int right = left; right < n; right++) {
                // 确定高度
                minHeight = Math.min(minHeight, heights[right]);
                // 计算面积
                ans = Math.max(ans, (right - left + 1) * minHeight);
            }
        }
        return ans;
    }

    /**
     * 方法2. 单调栈 枚举高度 O(N)
     *
     * @param heights
     * @return
     */
    public int largestRectangleArea(int[] heights) {
        int n = heights.length;
        int[] left = new int[n];
        int[] right = new int[n];
        Deque<Integer> monoStack = new LinkedList<>();
        for (int i = 0; i < n; i++) {
            // 利用单调栈，找到当前柱子左侧比他小的一根，作为左边界，之间的柱子都是比 i高的
            while (!monoStack.isEmpty() && heights[monoStack.peek()] >= heights[i]) {
                monoStack.pop();
            }
            left[i] = monoStack.isEmpty() ? -1 : monoStack.peek();
            monoStack.push(i);
        }
        monoStack.clear();
        for (int i = n - 1; i >= 0; i--) {
            while (!monoStack.isEmpty() && heights[monoStack.peek()] >= heights[i]) {
                monoStack.pop();
            }
            right[i] = monoStack.isEmpty() ? n : monoStack.peek();
            monoStack.push(i);
        }
        int ans = 0;
        for (int i = 0; i < n; i++) {
            ans = Math.max(ans, (right[i] - left[i] - 1) * heights[i]);
        }
        return ans;
    }
}
