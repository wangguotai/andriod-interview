package com.example.mylibrary;

import java.util.List;

/**
 * 213
 *
 * 你是一个专业的小偷，计划偷窃沿街的房屋。每间房内都藏有一定的现金，影响你偷窃的唯一制约因素就是相邻的房屋装有相互连通的防盗系统，如果两间相邻的房屋在同一晚上被小偷闯入，系统会自动报警。
 *
 * 给定一个代表每个房屋存放金额的非负整数数组，计算你 不触动警报装置的情况下 ，一夜之内能够偷窃到的最高金额。
 *
 *
 * 示例 1：
 *
 * 输入：[1,2,3,1]
 * 输出：4
 * 解释：偷窃 1 号房屋 (金额 = 1) ，然后偷窃 3 号房屋 (金额 = 3)。
 *      偷窃到的最高金额 = 1 + 3 = 4 。
 * 示例 2：
 *
 * 输入：[2,7,9,3,1]
 * 输出：12
 * 解释：偷窃 1 号房屋 (金额 = 2), 偷窃 3 号房屋 (金额 = 9)，接着偷窃 5 号房屋 (金额 = 1)。
 *      偷窃到的最高金额 = 2 + 9 + 1 = 12 。
 *
 *
 * 提示：
 *
 * * 1 <= nums.length <= 100
 * * 0 <= nums[i] <= 400
 *
 */

class Solution1 {
    public int rob(int[] neighbors) {
        int n = neighbors.length;
        int[] dp = new int[n+1];
        dp[0] = 0;
        dp[1] = Math.max(neighbors[0], neighbors[n-2]);
        int maxV = dp[1];
        for(int i=2;i<=n;i++) {
            dp[i] = Math.max(dp[i-1], dp[i-2] + neighbors[i-1]);
            maxV = Math.max(dp[i], maxV);
        }
        return maxV;
    }
}

public class SolutionFor {
//    public int rob(int[] neighbors){
//        int n = neighbors.length;
//        int[] dp = new int[n+1];
//        dp[0] = 0;
//        dp[1] = neighbors[0];
//        int maxV = dp[1];
//        for(int i=2;i<=n;i++) {
//            dp[i] = Math.max(dp[i-1], dp[i-2] + neighbors[i-1]);
//            maxV = Math.max(dp[i], maxV);
//        }
//        return maxV;
//    }
//
//    public static void main(String[] args) {
//        SolutionFor solution = new SolutionFor();
//        System.out.println(solution.maxValue(new int[] {2,7,9,3,1}));
//    }
}

/**
 * 198
 *
 * 你是一个专业的小偷，计划偷窃沿街的房屋，每间房内都藏有一定的现金。这个地方所有的房屋都 围成一圈 ，这意味着第一个房屋和最后一个房屋是紧挨着的。同时，相邻的房屋装有相互连通的防盗系统，如果两间相邻的房屋在同一晚上被小偷闯入，系统会自动报警 。
 *
 * 给定一个代表每个房屋存放金额的非负整数数组，计算你 在不触动警报装置的情况下 ，今晚能够偷窃到的最高金额。
 *
 *
 *
 * 示例 1：
 *
 * 输入：nums = [2,3,2]
 * 输出：3
 * 解释：你不能先偷窃 1 号房屋（金额 = 2），然后偷窃 3 号房屋（金额 = 2）, 因为他们是相邻的。
 * 示例 2：
 *
 * 输入：nums = [1,2,3,1]
 * 输出：4
 * 解释：你可以先偷窃 1 号房屋（金额 = 1），然后偷窃 3 号房屋（金额 = 3）。
 *      偷窃到的最高金额 = 1 + 3 = 4 。
 * 示例 3：
 *
 * 输入：nums = [1,2,3]
 * 输出：3
 *
 *
 * 提示：
 *
 * 1 <= nums.length <= 100
 * 0 <= nums[i] <= 1000
 *
 * Solution Template:
 * ```
 * class Solution {
 *     public int rob(int[] nums) {
 *
 *     }
 * }
 * ```
 */
