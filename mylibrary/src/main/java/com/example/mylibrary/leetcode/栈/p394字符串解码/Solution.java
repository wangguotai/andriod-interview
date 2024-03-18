package com.example.mylibrary.leetcode.栈.p394字符串解码;

import java.util.LinkedList;

/**
 * Time: 2024/3/17
 * Author: wgt
 * Description:
 */
public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().decodeString("3[a2[c]]")
        );
    }

    public String decodeString(String s) {
        LinkedList<String> stack = new LinkedList<>();
        StringBuilder ans = new StringBuilder();
        int ptr = 0;
        while (ptr < s.length()) {
            char cur = s.charAt(ptr);
            StringBuilder ret = new StringBuilder();
            // 获取一个数字并进栈
            while (Character.isDigit(cur)) {
                cur = s.charAt(ptr++);
                ret.append(cur);
                cur = s.charAt(ptr);
            }
            if (ret.length() > 0) {
                stack.push(ret.toString());
            }
            // 处理字母 和[
            if (Character.isLetter(cur) || cur == '[') {
                stack.push(String.valueOf(cur));
                ptr++;
            } else { // ]右括号
                ptr++;
                StringBuilder sb = new StringBuilder();
                while (!"[".equals(stack.peek())) {
                    sb.insert(0, stack.pop());
                }
                stack.pop();
                int repTime = Integer.parseInt(stack.pop());
                StringBuilder temp = new StringBuilder();
                for (int i = 0; i < repTime; i++) {
                    temp.append(sb);
                }
                stack.push(temp.toString());
            }
        }
        while (!stack.isEmpty()) {
            ans.append(stack.removeLast());
        }
        return ans.toString();
    }
}
