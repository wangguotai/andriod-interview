package com.example.mylibrary.leetcode.子串.p560_和为k的子数组;

import java.util.HashMap;
import java.util.Map;

/**
 * Time: 2024/3/3
 * Author: wgt
 * Description:
 */
public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().subarraySum(
                        new int[]{
                                1, 1, 1
                        }, 2
                )
        );
    }

    /**
     * 方法1.枚举
     * 考虑以i结尾的连续子数组个数，我们需要统计符合条件的下标j的个数，其中0<=j<=i
     * 且[j..i]这样的子数组的和恰好为k。
     *
     * @param nums
     * @param k
     * @return
     */
    public int subarraySum1(int[] nums, int k) {
        int count = 0;
        for (int i = 0; i < nums.length; i++) {
            int sum = 0;
            for (int end = i; end >= 0; end--) {
                sum += nums[end];
                if (sum == k) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 方法2：前缀和 + 哈希表优化
     * 方法1的瓶颈在于对每个i，我们需要枚举所有的j来判断是否符合条件，这一步是否可以优化
     *
     * @param nums
     * @param k
     * @return
     */
    public int subarraySum(int[] nums, int k) {
        int count = 0;
//        int[] pre = new int[nums.length];
//        pre[0] = nums[0];
//        for (int i = 1; i < nums.length; i++) {
//            pre[i] = pre[i-1] + nums[i];
//        }
        int pre = 0;
        Map<Integer, Integer> map = new HashMap<>();
        // 当pre[i] = k时，所以需要一个这样的初始化
        map.put(0, 1);
        for (int i = 0; i < nums.length; i++) {
            pre += nums[i];
            if (map.containsKey(pre - k)) {
                count += map.get(pre - k);
            }
            map.put(pre, map.getOrDefault(pre, 0) + 1);
//            if(map.containsKey(pre[i]-k)){
//                count += map.get(pre[i]-k);
//            }
//            map.put(pre[i],map.getOrDefault(pre[i], 0)+1);
        }
        return count;
    }
}

