package com.example.mylibrary.leetcode.leetcode_75.字符串和数组.递增的三元子序列334;

import java.util.Arrays;

/**
 * Time: 2025/4/1
 * Author: wgt
 * Description:
 */
public class Solution334 {
    public static void main(String[] args) {
        new Solution334().increasingTriplet(new int[]{
                2, 1, 5, 0, 4, 6
        });
    }

    public boolean increasingTriplet(int[] nums) {
        int len = nums.length;
        // 动态规划定义状态 dp[i]表示以nums[i] 结尾的最长递增子序列的长度
        int[] dp = new int[len];
        // 状态转移方程
        // 对于每个i,检查所有 j<i
        // dp[i]=max(dp[i],dp[j]+1)如果 nums[j]<nums[i]
        // 出时化 dp[i] = 1
        Arrays.fill(dp, 1);
        // 动态规划计算dp[i]
        for (int i = 1; i < len; i++) {
            for (int j = 0; j < i; j++) {
                if (nums[j] < nums[i]) {
                    dp[i] = Math.max(dp[i], dp[j] + 1);
                }
            }
//            if(dp[i] >= 3) {
//                return true;
//            }
        }
        return false;
    }
}
