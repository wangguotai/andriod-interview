package com.example.mylibrary.leetcode.二分查找.p153寻找旋转数组中的最小值;

public class Solution {
    public int findMin(int[] nums) {
        int n = nums.length;
        int left = 0;
        int right = n - 1;
        while (left <= right) {
            int mid = (left + right) >> 1;

            if (nums[0] <= nums[mid]) { // 0-mid升序
                if (nums[0] > nums[n - 1]) { // nums[0]非原序列的起始点
                    left = mid + 1; // 最小值在 [mid+1， right]
                } else { // 原序列为整体升序
                    return nums[0];
                }
            } else {  // nums[0] > mid  [mid,n-1] 升序
                // 寻找升序的左端点
                if (nums[mid] < nums[mid - 1]) { // mid 为升序的起点
                    return nums[mid];
                } else {
                    right = mid - 1;
                }
            }
        }
        return -1;
    }

}
