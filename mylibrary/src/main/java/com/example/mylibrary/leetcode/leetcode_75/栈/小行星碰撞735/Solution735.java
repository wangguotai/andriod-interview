package com.example.mylibrary.leetcode.leetcode_75.栈.小行星碰撞735;

import java.util.Deque;
import java.util.LinkedList;

public class Solution735 {
    // 1. 产生碰撞
    // 2. 永远不会产生碰撞
    public int[] asteroidCollision(int[] asteroids) {
        Deque<Integer> stack = new LinkedList<>();
        for (int i = 0; i < asteroids.length; i++) {
            boolean needPush = true;
            while (!stack.isEmpty()) {
                int peek = stack.peek();
                // 非环形轨道
                // 仅当栈顶元素向右，当前元素向左，才会碰撞
                if (peek * asteroids[i] < 0 && peek > 0) {
                    int absPeek = Math.abs(peek);
                    int absCurr = Math.abs(asteroids[i]);
                    if (absPeek < absCurr) {
                        stack.pop();
                    } else if (absPeek == absCurr) {
                        stack.pop();
                        needPush = false;
                        break;
                    } else {
                        break;
                    }
                } else {
                    stack.push(asteroids[i]);
                    break;
                }
            }
            if (stack.isEmpty() && needPush) {
                stack.push(asteroids[i]);
            }
        }
        int[] res = new int[stack.size()];
        for (int i = 0; i < res.length; i++) {
            res[i] = stack.removeLast();
        }
        return res;
    }
}
