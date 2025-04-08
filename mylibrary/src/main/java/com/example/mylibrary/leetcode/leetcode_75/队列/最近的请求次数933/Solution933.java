package com.example.mylibrary.leetcode.leetcode_75.队列.最近的请求次数933;

import java.util.Deque;
import java.util.LinkedList;

public class Solution933 {

//    class Node {
//        Node pre;
//        Node next;
//        int time;
//    }
//    class MyQueue {
//        private Node tail;
//        void offer(int time){
//            Node node = new Node();
//            node.time = time;
//            if(tail == null){
//                tail = node;
//            }else {
//                node.pre = tail;
//                tail.next = node;
//                tail = node;
//            }
//        }
//        int countForTime(int baseTime) {
//            Node cur = tail;
//            int count = 0;
//            while(cur != null){
//                if( baseTime-3000 <= cur.time) {
//                    count++;
//                    cur = cur.pre;
//                } else{
//                    cur.next.pre = null;
//                    break;
//                }
//            }
//            return count;
//        }
//    }
//    class RecentCounter {
//
//        MyQueue queue;
//        public RecentCounter() {
//            queue = new MyQueue();
//        }
//
//        public int ping(int t) {
//            this.queue.offer(t);
//            return this.queue.countForTime(t);
//        }
//    }
class RecentCounter {

    Deque<Integer> queue;
    public RecentCounter() {
        queue = new LinkedList<>();
    }

    public int ping(int t) {
        queue.offer(t);
        while(queue.peek() < t-3000) {
            queue.poll();
        }
        return queue.size();
    }
}

/**
 * Your RecentCounter object will be instantiated and called as such:
 * RecentCounter obj = new RecentCounter();
 * int param_1 = obj.ping(t);
 */

}
