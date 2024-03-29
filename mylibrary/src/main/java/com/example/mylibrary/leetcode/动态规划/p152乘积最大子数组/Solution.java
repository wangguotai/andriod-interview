package com.example.mylibrary.leetcode.动态规划.p152乘积最大子数组;

public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().maxProduct(
                        new int[]{2, 3, -2, 4}
                )
        );
    }

    /**
     * 状态: dp[i]表示以第i个元素结尾的乘积最大子数组的乘积，
     * 状态转移方程： dp[i] =
     *
     * @param nums
     * @return
     */
    public int maxProduct(int[] nums) {
        int n = nums.length;
        int[] maxDp = new int[n];
        int[] minDp = new int[n];
        System.arraycopy(nums, 0, maxDp, 0, n);
        System.arraycopy(nums, 0, minDp, 0, n);
        int ans = maxDp[0];
        for (int i = 1; i < n; i++) {
            maxDp[i] = Math.max(maxDp[i - 1] * nums[i], Math.max(nums[i], minDp[i - 1] * nums[i]));
            minDp[i] = Math.min(minDp[i - 1] * nums[i], Math.min(nums[i], maxDp[i - 1] * nums[i]));
            if (ans < maxDp[i]) {
                ans = maxDp[i];
            }
        }
        return ans;
    }
}
