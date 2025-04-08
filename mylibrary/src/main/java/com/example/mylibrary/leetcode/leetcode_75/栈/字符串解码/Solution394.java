package com.example.mylibrary.leetcode.leetcode_75.栈.字符串解码;

import java.util.Deque;
import java.util.LinkedList;

public class Solution394 {
    //    public String decodeString(String s) {
//        Deque<Character> stack = new LinkedList<>();
//        StringBuilder res = new StringBuilder();
//        for (char c : s.toCharArray()) {
//            if (c != ']') {
//                stack.push(c);
//            } else { // 遇到了右括号
//                StringBuilder encoder = new StringBuilder();
//                while (!stack.isEmpty()) {
//                    char curr = stack.pop();
//                    if (curr != '[') {
//                        encoder.append(curr);
//                    } else {
//                        // 获取重复数字
//                        int num = 0;
//                        int radium = 1;
//                        while (!stack.isEmpty()) {
//                            Character temp = stack.peek();
//                            if (temp != null && isDigit(temp)) {
//                                stack.pop();
//                                num += radium * (temp - '0');
//                                radium = radium * 10;
//                            } else {
//                                break;
//                            }
//                        }
//                        res.append(String.valueOf(encoder.reverse()).repeat(Math.max(1, num)));
//                    }
//                }
//            }
//        }
//        return res.toString();
//    }
    public String decodeString(String s) {
        Deque<Integer> countStack = new LinkedList<>();
        Deque<StringBuilder> stringStack = new LinkedList<>();
        StringBuilder currentString = new StringBuilder();
        int k = 0;

        for (char ch : s.toCharArray()) {
            if (Character.isDigit(ch)) {
                k = k * 10 + (ch - '0');
            } else if (ch == '[') {
                // 将当前的重复次数和字符串压入栈
                countStack.push(k);
                stringStack.push(currentString);
                currentString = new StringBuilder();
                k = 0;
            } else if (ch == ']') {
                // 从栈中弹出之前的字符串和重复次数
                StringBuilder decodedString = stringStack.pop();
                int count = countStack.pop();
                currentString = decodedString.append(currentString.toString().repeat(count));
            } else {
                currentString.append(ch);
            }
        }
        return currentString.toString();
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    public static void main(String[] args) {
        Solution394 solution394 = new Solution394();
        System.out.println(solution394.decodeString("2[abc]3[cd]ef"));
    }
}
