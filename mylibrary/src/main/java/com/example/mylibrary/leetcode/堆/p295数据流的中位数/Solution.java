package com.example.mylibrary.leetcode.堆.p295数据流的中位数;

import java.util.PriorityQueue;

public class Solution {
    public static void main(String[] args) {
        MedianFinder obj = new MedianFinder();
        obj.addNum(1);
        obj.addNum(2);
        System.out.println(obj.findMedian());
        obj.addNum(3);
        System.out.println(obj.findMedian());
    }

    static class MedianFinder {
        PriorityQueue<Integer> queA; // 小顶堆存储大的部分
        PriorityQueue<Integer> queB; // 大顶堆存储小的一半

        public MedianFinder() {
            queA = new PriorityQueue<>();  // 默认的小顶堆
            queB = new PriorityQueue<>((a, b) -> b - a);
        }

        public void addNum(int num) {
            if (queA.size() != queB.size()) {
                queA.add(num);
                queB.add(queA.poll());
            } else {
                queB.add(num);
                queA.add(queB.poll());
            }
        }

        public double findMedian() {
            if (queB.size() > queA.size()) {
                return queA.peek();
            } else {
                return (queA.peek() + queB.peek()) / 2.0;
            }
        }
    }

/**
 * Your MedianFinder object will be instantiated and called as such:
 * MedianFinder obj = new MedianFinder();
 * obj.addNum(num);
 * double param_2 = obj.findMedian();
 */
}
