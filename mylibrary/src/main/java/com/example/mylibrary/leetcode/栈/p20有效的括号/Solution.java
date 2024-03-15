package com.example.mylibrary.leetcode.栈.p20有效的括号;

import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().isValid("]")
        );
    }

    public boolean isValid(String s) {
        Map<Character, Character> map = new HashMap<>();
        map.put(')', '(');
        map.put('}', '{');
        map.put(']', '[');
        Deque<Character> stack = new LinkedList<>();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '{':
                case '(':
                case '[': {
                    stack.push(ch);
                    break;
                }
                default: {
                    if (stack.poll() != map.get(ch)) {
                        return false;
                    }
                }
            }
        }
        return stack.isEmpty();
    }
}
