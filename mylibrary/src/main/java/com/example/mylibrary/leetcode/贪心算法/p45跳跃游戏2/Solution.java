package com.example.mylibrary.leetcode.贪心算法.p45跳跃游戏2;

public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().jump(
                        new int[]{2, 3, 1, 1, 4}
                )
        );
    }

    public int jump(int[] nums) {
        int maxEnd;
        int step = 0;
        int start = 0;
        int end = 0;
        while (end < nums.length - 1) {
            maxEnd = finMaxEnd(nums, start, end);
            start = end + 1;
            end = maxEnd;
            step++;
        }
        return step;
    }

    private int finMaxEnd(int[] nums, int start, int end) {
        int maxEnd = nums[start] + start;
        for (int i = start + 1; i <= end; i++) {
            maxEnd = Math.max(maxEnd, i + nums[i]);
        }
        return maxEnd;
    }
}