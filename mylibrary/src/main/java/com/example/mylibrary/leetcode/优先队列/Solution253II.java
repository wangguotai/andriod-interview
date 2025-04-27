package com.example.mylibrary.leetcode.优先队列;

import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * 这道题 “会议室 II” 的核心问题是，给定一系列会议的开始和结束时间，
 * 计算出为了让所有会议都能顺利进行，最少需要多少个会议室。以下为你详细介绍两种不同的解法。
 */
public class Solution253II {
    public int minMeetingRooms(int[][] intervals) {
        if(intervals == null || intervals.length == 0) {
            return 0;
        }
        // 按照会议开始时间排序
        Arrays.sort(intervals, (a, b)-> a[0]-b[0]);
        // 最小堆，存储会议结束时间
        PriorityQueue<Integer> minHeap = new PriorityQueue<>();
        // 把最后一个会议的结束时间加入堆
        minHeap.offer(intervals[0][1]);
        for(int i = 1;i<intervals.length;i++){
            if(intervals[i][0] > minHeap.peek()) {
                minHeap.poll();
            }
            minHeap.offer(intervals[i][1]);
        }
        return minHeap.size();
    }
}
