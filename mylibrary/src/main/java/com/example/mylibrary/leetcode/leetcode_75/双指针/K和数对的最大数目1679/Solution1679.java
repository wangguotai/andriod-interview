package com.example.mylibrary.leetcode.leetcode_75.双指针.K和数对的最大数目1679;

import java.util.Arrays;

/**
 * Time: 2025/4/2
 * Author: wgt
 * Description:
 */
public class Solution1679 {
    public int maxOperations(int[] nums, int k) {
        Arrays.sort(nums);
        int left = 0;
        int right = nums.length - 1;
        int count = 0;
        while (left < right) {
            int sum = nums[left] + nums[right];
            if (sum == k) {
                count++;
                left++;
                right--;
            } else if (sum < k) {
                left++;
            } else {
                right--;
            }
        }
        return count;
    }
}
