package com.example.mylibrary.leetcode.热题100.哈希.p1_两数之和.p128_最长联系序列;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 1. 两数之和
 * 已解答
 * 简单
 * 相关标签
 * 相关企业
 * 提示
 * 给定一个整数数组 nums 和一个整数目标值 target，请你在该数组中找出 和为目标值 target  的那 两个 整数，并返回它们的数组下标。
 * <p>
 * 你可以假设每种输入只会对应一个答案。但是，数组中同一个元素在答案里不能重复出现。
 * <p>
 * 你可以按任意顺序返回答案。
 * <p>
 * <p>
 * <p>
 * 示例 1：
 * <p>
 * 输入：nums = [2,7,11,15], target = 9
 * 输出：[0,1]
 * 解释：因为 nums[0] + nums[1] == 9 ，返回 [0, 1] 。
 * 示例 2：
 * <p>
 * 输入：nums = [3,2,4], target = 6
 * 输出：[1,2]
 * 示例 3：
 * <p>
 * 输入：nums = [3,3], target = 6
 * 输出：[0,1]
 * <p>
 * <p>
 * 提示：
 * <p>
 * 2 <= nums.length <= 104
 * -109 <= nums[i] <= 109
 * -109 <= target <= 109
 * 只会存在一个有效答案
 * <p>
 * <p>
 * 进阶：你可以想出一个时间复杂度小于 O(n2) 的算法吗？
 */
public class Solution {
    public static void main(String[] args) {
        System.out.println(
                Arrays.toString(new Solution().twoSum(new int[]{
                        3, 2, 4
                }, 6))
        );
    }

    /**
     * 这道题之前做过，使用双指针
     *
     * @param nums
     * @param target
     * @return
     */
//    public int[] twoSum(int[] nums, int target) {
//        Arrays.sort(nums);
//        int start = 0;
//        int end = nums.length - 1;
//        while (start < end) {
//        int leftIndex = start;
//        int rightIndex = end;
//        while (nums[leftIndex] + nums[rightIndex] < target && leftIndex + 1 < rightIndex) {
//            leftIndex++;
//        }
//        while (nums[leftIndex] + nums[rightIndex] > target && rightIndex - 1 > leftIndex) {
//            rightIndex--;
//        }
//        if (nums[leftIndex] + nums[rightIndex] == target) {
//
//            return new int[]{leftIndex, rightIndex};
//        }
////        }
//        return null;
//    }

    /**
     * 使用哈希表来解决问题
     *
     * @param nums
     * @param target
     * @return
     */
    public int[] twoSum(int[] nums, int target) {
        Map<Integer, Integer> map = new HashMap<>();
        for (int i = 0; i < nums.length; i++) {
            if (map.containsKey(target - nums[i])) {
                return new int[]{map.get(target - nums[i]), i};
            }
            map.put(nums[i], i);
        }
        return null;
    }

}
