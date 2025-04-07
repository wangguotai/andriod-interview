package com.example.mylibrary.leetcode.leetcode_75.哈希表和哈希集合.相等行列对2352;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Solution2352 {
//    public int equalPairs(int [][] grid) {
//        int n = grid.length;
//        int m = grid[0].length;
//        int res = 0;
//        Map<String, Integer> map = new HashMap<>();
//        for (int i = 0; i < n; i++) {
//            // 遍历第i行
//            StringBuilder sb = new StringBuilder(n);
//            for (int j = 0; j < n; j++) {
//                sb.append(grid[i][j]).append("_");
//            }
//            map.put(sb.toString(), map.getOrDefault(sb.toString(), 0) + 1);
//        }
//
//
//        for (int i = 0; i < m; i++) {
//            StringBuilder sb = new StringBuilder(n);
//            for (int j = 0; j < n; j++) {
//                sb.append(grid[j][i]).append("_");
//            }
//            if(map.containsKey(sb.toString())){
//                res+=map.get(sb.toString());
//            }
//        }
//        return res;
//    }


    public static void main(String[] args) {
        Solution2352 solution2352 = new Solution2352();
        int res = solution2352.equalPairs(
//                new int[][]{
//                        {3,1,2,2},
//                        {1,4,4,5},
//                        {2,4,2,2},
//                        {2,4,2,2}
//                }
                new int[][]{
                        {11,1},
                        {1,11},
                }
        );
        System.out.println(res);
    }

    public int equalPairs(int[][] grid) {
        int n = grid.length;
        Map<List<Integer>, Integer> cnt = new HashMap<List<Integer>, Integer>();
        for (int[] row : grid) {
            List<Integer> arr = new ArrayList<Integer>();
            for (int num : row) {
                arr.add(num);
            }
            cnt.put(arr, cnt.getOrDefault(arr, 0) + 1);
        }

        int res = 0;
        for (int j = 0; j < n; j++) {
            List<Integer> arr = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) {
                arr.add(grid[i][j]);
            }
            if (cnt.containsKey(arr)) {
                res += cnt.get(arr);
            }
        }
        return res;
    }

}
