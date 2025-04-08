package com.example.mylibrary.leetcode.leetcode_75.栈.从字符串中移除星号2390;

import java.util.Deque;
import java.util.LinkedList;

public class Solution2390 {
    public String removeStars(String s) {
        Deque<Character> stack = new LinkedList<>();
        for (char c : s.toCharArray()) {
            if(c != '*') {
                stack.push(c);
            } else {
                if(!stack.isEmpty()) {
                    stack.pop();
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        while(!stack.isEmpty()) {
            sb.append(stack.pollLast());
        }
        return sb.toString();
    }
}
