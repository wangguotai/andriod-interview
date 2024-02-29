package com.example.mylibrary.leetcode.双指针.p15_三数之和;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Time: 2024/2/15
 * Author: wgt
 * Description:
 */
class Solution {
    public static void main(String[] args) {
        List<List<Integer>> result = new Solution().threeSum(new int[]{-2, 0, 1, 1, 2});
        System.out.println(result);
    }

    public List<List<Integer>> threeSum(int[] nums) {
        Map<String, List<Integer>> result = new HashMap<>();
        int start = 0, end = nums.length - 1;
        Arrays.sort(nums);
        while (start + 1 < end) {
            int endIndex = end;
            int j = start + 1;
            int target = -nums[start];
            while (j < endIndex) {
                while (j < endIndex && nums[j] + nums[endIndex] > target) {
                    endIndex--;
                }
                while (j < endIndex && nums[j] + nums[endIndex] < target) {
                    j++;
                }
                if (j != endIndex && nums[j] + nums[endIndex] == target) {
                    // 由于 start<j<end 保证了有序性
                    // nums[start]<=nums[j]<=nums[endIndex] 保证了key的唯一性
                    result.put("" + nums[j] + nums[endIndex] + nums[start], Arrays.asList(nums[start], nums[j], nums[endIndex]));
                    j++;
                    endIndex--;
                }
            }
            start++;
        }
        return new ArrayList<>(result.values());
    }

    public List<List<Integer>> threeSum1(int[] nums) {
        int n = nums.length;
        Arrays.sort(nums);
        List<List<Integer>> ans = new ArrayList<List<Integer>>();
        // 枚举 a
        for (int first = 0; first < n; ++first) {
            // 需要和上一次枚举的数不相同
            if (first > 0 && nums[first] == nums[first - 1]) {
                continue;
            }
            // c 对应的指针初始指向数组的最右端
            int third = n - 1;
            int target = -nums[first];
            // 枚举 b
            for (int second = first + 1; second < n; ++second) {
                // 需要和上一次枚举的数不相同
                if (second > first + 1 && nums[second] == nums[second - 1]) {
                    continue;
                }
                // 需要保证 b 的指针在 c 的指针的左侧
                while (second < third && nums[second] + nums[third] > target) {
                    --third;
                }
                // 如果指针重合，随着 b 后续的增加
                // 就不会有满足 a+b+c=0 并且 b<c 的 c 了，可以退出循环
                if (second == third) {
                    break;
                }
                if (nums[second] + nums[third] == target) {
                    List<Integer> list = new ArrayList<Integer>();
                    list.add(nums[first]);
                    list.add(nums[second]);
                    list.add(nums[third]);
                    ans.add(list);
                }
            }
        }
        return ans;
    }
}
