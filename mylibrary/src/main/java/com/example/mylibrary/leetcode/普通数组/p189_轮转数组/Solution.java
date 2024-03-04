package com.example.mylibrary.leetcode.普通数组.p189_轮转数组;

public class Solution {
    public void rotate1(int[] nums, int k) {
        int n = nums.length;
        int[] newArr = new int[n];
        for (int i = 0; i < n; i++) {
            newArr[(i + k) % n] = nums[i];
        }
        System.arraycopy(newArr, 0, nums, 0, n);
    }

    public void rotate2(int[] nums, int k) {
        reverse(nums, 0, nums.length - 1);
        reverse(nums, 0, k - 1);
        reverse(nums, k, nums.length - 1);
    }

    private void reverse(int[] nums, int s, int e) {
        int temp;
        for (int start = s, end = e; start < end; start++, end--) {
            temp = nums[start];
            nums[start] = nums[end];
            nums[end] = temp;
        }
    }
}
