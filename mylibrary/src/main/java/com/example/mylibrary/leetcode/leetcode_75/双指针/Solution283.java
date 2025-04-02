package com.example.mylibrary.leetcode.leetcode_75.双指针;

/**
 * Time: 2025/4/2
 * Author: wgt
 * Description:
 */
public class Solution283 {
    public static void main(String[] args) {
        new Solution283().moveZeroes(new int[]{
                0, 1, 0, 3, 12
        });
    }

    public void moveZeroes(int[] nums) {
        int len = nums.length;
        for (int i = 0, j = 0; i < len; i++) {
            if (nums[i] != 0) {
                if (i == j) {
                    j++;
                } else {
                    nums[j++] = nums[i];
                    nums[i] = 0;
                }
            }
        }
    }
}
