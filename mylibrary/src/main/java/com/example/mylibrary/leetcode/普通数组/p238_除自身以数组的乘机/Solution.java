package com.example.mylibrary.leetcode.普通数组.p238_除自身以数组的乘机;

public class Solution {
    public int[] productExceptSelf(int[] nums) {
        int n = nums.length;
        int[] L = new int[n];
        int[] R = new int[n];
        int[] ans = new int[n];
        R[n - 1] = 1;
        for (int i = n - 2; i >= 0; i--) {
            R[i] = R[i + 1] * nums[i + 1];
        }
        L[0] = 1;
        ans[0] = L[0] * R[0];
        for (int i = 1; i < n; i++) {
            L[i] = L[i - 1] * nums[i - 1];
            ans[i] = L[i] * R[i];

        }
        return ans;
    }

}
