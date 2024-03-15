package com.example.mylibrary.leetcode.二分查找.p35搜索插入位置;

public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().searchInsert(new int[]{1, 3, 5, 6}, 2)
        );
    }

    public int searchInsert(int[] nums, int target) {
        int left = 0;
        int right = nums.length - 1;
        int ans = nums.length;
        while (left <= right) {
//            int mid = (left+right)>>1;
            // 防溢出
            int mid = left + (right - left) >> 1;
            if (target <= nums[mid]) {
                ans = mid;
                right = mid - 1;
            } else if (target > nums[mid]) {
                left = mid + 1;
            }
        }
        return ans;
    }
}
