package com.example.mylibrary.leetcode.京东.合并区间;


import java.util.*;

public class Solution56 {
    public int[][] merge(int[][] intervals) {
        Arrays.sort(intervals, (int[] a, int[] b)->a[0] - b[0]);
        List<int[]> result = new LinkedList<>();
        int[] currInterval = intervals[0];
        for (int i = 1; i < intervals.length; i++) {
            if(intervals[i][0] > currInterval[1]) {
                result.add(currInterval);
                currInterval = intervals[i];
            } else if(intervals[i][1]<= currInterval[1]){
                // 后者将合并至前者
                continue;
            } else {
                currInterval[1] = intervals[i][1];
            }
        }
        result.add(currInterval);
        int[][] resArr = new int[result.size()][2];
        for(int i=0;i<resArr.length;i++) {
            resArr[i] = result.get(i);
        }
        return resArr;
    }

    public static void main(String[] args) {
        Solution56 solution = new Solution56();
        int[][] res = solution.merge(
                new int[][] {
                        {1,3},
                        {2,6},
                        {8,10},
                        {15,18},
                }
        );

    }
}
