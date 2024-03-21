package com.example.mylibrary.leetcode.贪心算法.p55跳跃游戏;

public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().canJump(
                        new int[]{2, 3, 1, 1, 4}
                )
        );
    }

    //    public boolean canJump(int[] nums){
//        return dfs(0);
//    }
//    int[] nums;
//    private boolean dfs(int i){
//        if(i==nums.length-1){
//            return true;
//        }
//        for (int j = 1; j <= nums[i]; j++) {
//            if(i+j<nums.length && dfs(i+j)){
//                return true;
//            }
//        }
//        return false;
//    }
    public boolean canJump(int[] nums) {
        int k = 0;
        for (int i = 0; i < nums.length; i++) {
            if (k < i) return false;
            k = Math.max(k, i + nums[i]);
        }
        return true;
    }
}
