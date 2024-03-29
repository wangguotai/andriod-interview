package com.example.mylibrary.leetcode.动态规划.p300最长递增子序列;

import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedList;

public class Solution {
    /**
     * 1.状态 dp[i]
     * 2.
     */
    int ans;
    int len;
    int[] nums;

    public static void main(String[] args) {
        System.out.println(
                new Solution().lengthOfLIS(
                        new int[]{10, 9, 2, 5, 3, 7, 101, 18}
                )
        );
    }

    public int lengthOfLIS1(int[] nums) {
        this.nums = nums;
        this.len = nums.length;
        dfs(0, new LinkedList<Integer>());
        return ans;
    }

    private void dfs(int index, Deque<Integer> result) {
        if (index == len) {
            if (result.size() > ans) {
                ans = result.size();
            }
            return;
        }
        if (result.isEmpty() || result.peek() < nums[index]) {
            result.push(nums[index]);
            dfs(index + 1, new LinkedList<>(result));
        } else {
            dfs(index + 1, new LinkedList<>(result));
            while (!result.isEmpty() && result.peek() >= nums[index]) {
                result.pop();
            }
            result.push(nums[index]);
            dfs(index + 1, new LinkedList<>(result));
        }
    }

    /**
     * 1.状态 dp[i] 为前i个元素，以第i个数字结尾的最长上升子序列的长度
     * 2. 状态转移方程：
     * 从小到大计算dp数组的值，在计算dp[i]之前，已经计算出了dp[0..i-1]的值
     * dp[i]= max(dp[j])+1, 其中 0<= j <i, 且 nums[j]<nums[i]
     *
     * @param nums
     * @return
     */
    public int lengthOfLIS(int[] nums) {
        int[] dp = new int[nums.length + 1];
        Arrays.fill(dp, 1);
        int maxV = 1;
        for (int i = 2; i <= nums.length; i++) {
            for (int j = 1; j < i; j++) {
                if (nums[i - 1] > nums[j - 1]) {
                    dp[i] = Math.max(dp[i], dp[j] + 1);
                }
            }
            if (dp[i] > maxV) {
                maxV = dp[i];
            }
        }
        return maxV;
    }
}
