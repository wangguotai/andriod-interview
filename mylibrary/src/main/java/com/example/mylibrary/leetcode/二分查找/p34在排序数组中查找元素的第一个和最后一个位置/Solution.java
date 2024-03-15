package com.example.mylibrary.leetcode.二分查找.p34在排序数组中查找元素的第一个和最后一个位置;

public class Solution {
    public int[] searchRange(int[] nums, int target) {
        int left = 0;
        int right = nums.length - 1;
        while (left <= right) {
            int mid = ((right - left) >> 1) + left;
            if (target < nums[mid]) {
                right = mid - 1;
            } else if (target > nums[mid]) {
                left = mid + 1;
            } else {
                int start = mid;
                int end = mid;
                while (start - 1 >= left && nums[start - 1] == target) {
                    start--;
                }
                while (end + 1 <= right && nums[end + 1] == target) {
                    end++;
                }
                return new int[]{start, end};
            }
        }
        return new int[]{-1, -1};
    }
}
