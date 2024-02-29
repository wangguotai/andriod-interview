package com.example.mylibrary.leetcode.双指针.p11_盛最多水的容器.p_283移动零;

/**
 * Time: 2024/2/29
 * Author: wgt
 * Description:
 */
// 快慢指针初始位置一至，当条件发生变化后，快指针继续前进，慢指针停留在待调整的位置
class Solution {
    public int maxArea(int[] height) {
        if (height == null || height.length < 2) {
            return 0;
        }
        int left = 0;
        int right = height.length - 1;
        int res = 0;
        while (left < right) {
            res = height[left] < height[right] ?
                    Math.max(res, (right - left) * Math.min(height[left++], height[right]))
                    : Math.max(res, (right - left) * Math.min(height[left], height[right--]));
        }
        return res;
    }
}