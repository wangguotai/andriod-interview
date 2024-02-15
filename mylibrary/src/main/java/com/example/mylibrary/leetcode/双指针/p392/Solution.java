package com.example.mylibrary.leetcode.双指针.p392;

/**
 * Time: 2024/2/15
 * Author: wgt
 * Description:
 * 给定字符串 s 和 t ，判断 s 是否为 t 的子序列。
 * <p>
 * 字符串的一个子序列是原始字符串删除一些（也可以不删除）字符而不改变剩余字符相对位置形成的新字符串。（例如，"ace"是"abcde"的一个子序列，而"aec"不是）。
 * <p>
 * 进阶：
 * <p>
 * 如果有大量输入的 S，称作 S1, S2, ... , Sk 其中 k >= 10亿，你需要依次检查它们是否为 T 的子序列。在这种情况下，你会怎样改变代码？
 */
public class Solution {
    public boolean isSubsequence(String s, String t) {
        if (s.isEmpty()) {
            return true;
        } else if (t.isEmpty()) {
            return false;
        } else {
            char[] source = t.toCharArray();
            char[] pattern = s.toCharArray();
            int n = source.length;
            int m = pattern.length;
            int j=0;
            for (int i = 0; i < n; i++) {
                if(source[i] == pattern[j]) {
                    j++;
                    if (j == m) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

//    public static void main(String[] args) {
//        new Solution().isSubsequence("abc")
//    }
}
