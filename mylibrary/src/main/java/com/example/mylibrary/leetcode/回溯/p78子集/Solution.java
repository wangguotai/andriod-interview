package com.example.mylibrary.leetcode.回溯.p78子集;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class Solution {

    List<List<Integer>> ans;
    List<Integer> item = new LinkedList<>();
    int[] nums;

    /**
     * 1. 迭代法实现子集枚举
     * 原序列中元素总数为n，每个数字ai的状态有两个，在子集和不在子集中，用1表示在子集中，用0表示不在子集，
     * 则每个子集可以对应一个长度为n的0、1序列{000}、{001}等。共有2^n个。  0 1 2
     *
     * @param nums
     * @return
     */
    public List<List<Integer>> subsets1(int[] nums) {
        int n = nums.length;
        int len = 1 << n;
        List<List<Integer>> ans = new ArrayList<>(len);
        for (int mask = 0; mask < len; mask++) {
            List<Integer> item = new LinkedList<>();
            // nums中的每个元素位置[111] 与mask 与运算
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    item.add(nums[i]);
                }
            }
            ans.add(item);
        }
        return ans;
    }

    /**
     * 方法2. 递归法实现子集枚举
     *
     * @param nums
     * @return
     */
    public List<List<Integer>> subsets(int[] nums) {
        int n = nums.length;
        int len = 1 << n;
        ans = new ArrayList<>(len);
        this.nums = nums;
        dfs(0);
        return ans;
    }

    private void dfs(int curr) {
        if (curr == nums.length) {
            ans.add(new ArrayList<>(item));
            return;
        }
        item.add(nums[curr]);
        dfs(curr + 1);
        item.remove((Integer) nums[curr]);
        dfs(curr + 1);
    }

}