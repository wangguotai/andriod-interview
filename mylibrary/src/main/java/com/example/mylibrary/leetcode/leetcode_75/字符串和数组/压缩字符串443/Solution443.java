package com.example.mylibrary.leetcode.leetcode_75.字符串和数组.压缩字符串443;

import java.util.Deque;
import java.util.LinkedList;

/**
 * Time: 2025/4/1
 * Author: wgt
 * Description:
 */
public class Solution443 {
    public static void main(String[] args) {
        new Solution443().compress(new char[]{
//                'a','a','b','b','c','c','c'
                'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a',
                'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a',
                'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a',
                'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a',
                'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a',
                'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a',
                'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a',
                'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a',
                'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a',
                'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a', 'a',
        });
    }

    public int compress(char[] chars) {
        int len = chars.length;
        int lIndex = 0;
        char curr = chars[0];
        int count = 1;
        for (int i = 1; i <= len; i++) {
            if (i < len && chars[i] == curr) {
                count++;
            } else {
                chars[lIndex++] = curr;
                if (count > 1) {
                    Deque<Character> stack = new LinkedList<>();
                    while (count > 0) {
                        stack.push((char) (count % 10 + '0'));
                        count = count / 10;
                    }
                    while (!stack.isEmpty()) {
                        chars[lIndex++] = stack.pop();
                    }
                }
                if (i < len) {
                    curr = chars[i];
                }
                count = 1;
            }
        }

        return lIndex;
    }
}
