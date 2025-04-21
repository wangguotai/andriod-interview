package com.example.mylibrary.leetcode;


import java.util.*;

/**
 * 题目: 两数之和
 * 输入: 数组和目标数
 * 输出: 两数相加等于目标值的可能性的集合, 每个数只能用一次
 * 例子1:
 * 输入: [1, 1, 2, 3, 5, 4], 6
 * 输出: [[1, 5], [2, 4]]
 * 例子2:
 * 输入: [1, 1, 2, 4, 3, 5, 5], 6
 * 输出: [[1, 5], [1, 5], [2, 4]]
 * 例子3:
 * 输入: [1, 5, 5], 6
 * 输出: [[1, 5]]
 */


public class Solution {
    public List<List<Integer>> getTargetSet(int[] arr, int target) {
        Map<Integer, Set<Integer>> result = new HashMap<>();
        for (int i = 0; i < arr.length; i++) {
            for (int j = i+1; j<arr.length;j++){
                if(target - arr[i] == arr[j]) {
                    if(result.get(arr[j])!=null) {
                        continue;
                    }
                    result.computeIfAbsent(arr[i], key->{
                        return new HashSet<>();
                    }).add(arr[j]);
                }
            }
        }
        List<List<Integer>> list = new ArrayList<>(result.size());
        for ( int key: result.keySet()){
            for(int v: result.get(key)) {
                List<Integer> pair = new ArrayList<>(2);
                pair.add(key);
                pair.add(v);
                list.add(pair);
            }
        }
        return list;
    }

    public static void main(String[] args) {
        Solution solution = new Solution();
//        List<List<Integer>> result = solution.getTargetSet(new int[] {1, 5, 5}, 6);
        List<List<Integer>> result = solution.getTargetSet(new int[] {1, 1, 2, 4, 3, 5, 5}, 6);
    }


}
