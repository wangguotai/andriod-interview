package com.example.mylibrary.leetcode.回溯.p46全排列;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class Solution {
    private List<List<Integer>> ans;
    private int[] nums;
    private int len;
    private int[] marked;

    public static void main(String[] args) {
        System.out.println(
                new Solution().permute(
                        new int[]{
                                1, 2, 3, 4
                        }
                )
        );
    }

    public List<List<Integer>> permute(int[] nums) {
        ans = new LinkedList<>();
        this.nums = nums;
        this.len = nums.length;
        this.marked = new int[len];
//        backTrack1(0, new ArrayList(len));
        backTrack(0, new ArrayList(len));
        return ans;
    }

    private void backTrack1(int index, List<Integer> result) {
        if (index == len) {
            ans.add(new ArrayList<>(result));
            return;
        }
        for (int i = 0; i < len; i++) {
            if (marked[i] == 1) {
                continue;
            }
            result.add(nums[i]);
            marked[i] = 1;
            backTrack1(index + 1, result);
            result.remove((Object) nums[i]);
            marked[i] = 0;
        }
    }

    /**
     * 将已经填过的数据放在左边，只遍历没填的数据
     *
     * @param index
     * @param result
     */
    private void backTrack(int index, List<Integer> result) {
        if (index == len) {
            ans.add(new ArrayList<>(result));
        }

        for (int i = index; i < len; i++) {
            result.add(nums[i]);
            swap(i, index);
            backTrack(index + 1, result);
            swap(i, index);
            result.remove((Integer) nums[i]);
        }
    }

    private void swap(int i, int j) {
        int temp = nums[i];
        nums[i] = nums[j];
        nums[j] = temp;
    }
}