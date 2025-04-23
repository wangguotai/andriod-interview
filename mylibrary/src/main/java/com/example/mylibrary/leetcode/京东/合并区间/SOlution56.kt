package com.example.mylibrary.leetcode.京东.合并区间

import java.util.Arrays

class SOlution56 {
    fun merge(intervals: Array<IntArray>): Array<IntArray> {
        Arrays.sort(intervals ) { a: IntArray, b: IntArray -> a[0] - b[0] }
        val result: MutableList<IntArray> = mutableListOf()
        var currInterval: IntArray = intervals[0]
        for(i in 1 until intervals.size) {
            if(currInterval[1] < intervals[i][0]) {
                result.add(currInterval)
                currInterval = intervals[i]
            } else if(currInterval[1] >= intervals[i][0] && currInterval[1] < intervals[i][1] ) {
                currInterval[1] = intervals[i][1]
            }
        }
        result.add(currInterval);
        return result.toTypedArray()
    }
}