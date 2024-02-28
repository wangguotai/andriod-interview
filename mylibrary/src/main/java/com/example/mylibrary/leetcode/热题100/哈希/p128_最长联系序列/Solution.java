package com.example.mylibrary.leetcode.热题100.哈希.p128_最长联系序列;

import java.util.HashSet;
import java.util.Set;

/**
 * 128. 最长连续序列
 * 中等
 * 相关标签
 * 相关企业
 * 给定一个未排序的整数数组 nums ，找出数字连续的最长序列（不要求序列元素在原数组中连续）的长度。
 * <p>
 * 请你设计并实现时间复杂度为 O(n) 的算法解决此问题。
 * <p>
 * <p>
 * <p>
 * 示例 1：
 * <p>
 * 输入：nums = [100,4,200,1,3,2]
 * 输出：4
 * 解释：最长数字连续序列是 [1, 2, 3, 4]。它的长度为 4。
 * 示例 2：
 * <p>
 * 输入：nums = [0,3,7,2,5,8,4,6,0,1]
 * 输出：9
 * <p>
 * <p>
 * 提示：
 * <p>
 * 0 <= nums.length <= 105
 * -109 <= nums[i] <= 109
 */
public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().longestConsecutive(
                        new int[]{
                                0, 3, 7, 2, 5, 8, 4, 6, 0, 1
                        }
                )
        );
    }

    public int longestConsecutive(int[] nums) {
        if (nums.length == 0) {
            return 0;
        }
        Set<Integer> set = new HashSet<>();
        for (int i = 0; i < nums.length; i++) {
            set.add(nums[i]);
        }
        int maxSeqLen = 1;
        for (int key : set) {
            int index = key;
            if (!set.contains(index - 1)) {
                int curLen = 1;
                while (set.contains(++index)) {
                    curLen++;
                }
                if (maxSeqLen < curLen) {
                    maxSeqLen = curLen;
                }
            }
        }
        return maxSeqLen;
    }


}
