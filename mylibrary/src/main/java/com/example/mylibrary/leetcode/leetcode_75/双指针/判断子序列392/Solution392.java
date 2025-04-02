package com.example.mylibrary.leetcode.leetcode_75.双指针.判断子序列392;

/**
 * Time: 2025/4/2
 * Author: wgt
 * Description:
 */
public class Solution392 {
    public boolean isSubsequence(String s, String t) {
        int lenS = s.length();
        int lenT = t.length();
        if (lenS == 0) {
            return true;
        }
        int i = 0;
        int j = 0;
        for (; j < lenT; j++) {
            if (s.charAt(j) == t.charAt(i)) {
                i++;
            }
            if (i == lenS) {
                return true;
            }
        }
        return false;
    }
}
