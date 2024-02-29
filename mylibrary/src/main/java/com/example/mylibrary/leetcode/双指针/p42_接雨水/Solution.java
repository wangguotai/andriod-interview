package com.example.mylibrary.leetcode.双指针.p42_接雨水;

/**
 * Time: 2024/2/15
 * Author: wgt
 * Description:
 */
class Solution {
    public static void main(String[] args) {
//        List<List<Integer>> result = new Solution().threeSum(new int[]{-2, 0, 1, 1, 2});
//        System.out.println(result);
    }

    public int trap(int[] height) {
        int sum = 0;
        int max = getMax(height);
        int n = height.length;
        for (int i = 1; i <= max; i++) { // 按行计算雨滴
            boolean isStart = false; // 标记是否更新 存在雨滴
            int tempSum = 0;
            for (int j = 0; j < height.length; j++) {
                if (isStart && height[j] < i) {
                    tempSum++;
                }
                if (height[j] >= i) {
                    sum = sum + tempSum;
                    tempSum = 0;
                    isStart = true;
                }
            }
        }
        return sum;
    }

    private int getMax(int[] height) {
        int max = 0;
        for (int i = 0; i < height.length; i++) {
            if (height[i] > max) {
                max = height[i];
            }
        }
        return max;
    }
}
