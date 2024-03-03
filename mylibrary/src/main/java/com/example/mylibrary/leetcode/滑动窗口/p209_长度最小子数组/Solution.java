package com.example.mylibrary.leetcode.滑动窗口.p209_长度最小子数组;

/**
 * Time: 2024/2/29
 * Author: wgt
 * Description:
 */
public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().minSubArrayLen(7, new int[]{
                        2, 3, 1, 2, 4, 3
                })
        );
    }

    public int minSubArrayLen(int target, int[] nums) {
        int start = 0;
        int end = 0;
        int sum = 0;
        int minLen = nums.length + 1;
        while (end < nums.length) {
            sum += nums[end];
            if (sum < target) {
                end++;
            } else {
                if (minLen > end - start + 1) {
                    minLen = end - start + 1;
                }
                sum -= nums[start] + nums[end];
                start++;
            }
        }
        return minLen == nums.length + 1 ? 0 : minLen;
    }

}