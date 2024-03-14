package com.example.mylibrary.leetcode.回溯.p39组合总和;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public class Solution {
    private List<List<Integer>> ans = new LinkedList<>();
    private int[] candidates;
    private int target;

    public List<List<Integer>> combinationSum(int[] candidates, int target) {
        this.candidates = candidates;
        this.target = target;
        backTrack(0, new LinkedList<>(), 0);
        return ans;
    }

    private void backTrack(int index, List<Integer> result, int sum) {
        if (sum == target) {
            ans.add(new ArrayList(result));
        }
        for (int i = index; i < candidates.length; i++) {
            if (sum + candidates[i] <= target) {
                sum += candidates[i];
                result.add(candidates[i]);
                backTrack(i, result, sum);
                sum -= candidates[i];
                result.remove((Integer) candidates[i]);
            }
        }
    }
}
