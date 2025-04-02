package com.example.mylibrary.leetcode.leetcode_75.字符串和数组.反转字符串中的单词151;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

/**
 * Time: 2025/4/1
 * Author: wgt
 * Description:
 */
public class Solution {
    public String reverseWords(String s) {
        List<String> result = new LinkedList<>();
        StringBuilder current = new StringBuilder();
        boolean start = false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != ' ') {
                start = true;
                current.append(s.charAt(i));
            } else if (start) {
                result.add(current.toString());
                current.delete(0, current.length());
                start = false;
            }
        }
        if (current.length() > 0) {
            current.append(' ');
        }

        for (int i = result.size() - 1; i >= 0; i--) {
            current.append(result.get(i));
            current.append(' ');
        }
        current.deleteCharAt(current.length() - 1);
        return current.toString();
    }

    // 使用双端队列
    public String reverseWords2(String s) {
        Deque<String> deque = new LinkedList<>();
        int left = 0, right = s.length();
        StringBuilder word = new StringBuilder();
        boolean start = false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) != ' ') {
                start = true;
                word.append(s.charAt(i));
            } else if (start) {
                deque.offerFirst(word.toString());
                word.setLength(0);
//                word.delete(0, word.length());
                start = false;
            }
        }
        if (!deque.isEmpty()) {
            word.append(String.join(" ", deque));
        }
        return word.toString();
    }

}
