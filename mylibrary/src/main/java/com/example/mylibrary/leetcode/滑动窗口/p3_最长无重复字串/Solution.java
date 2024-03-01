package com.example.mylibrary.leetcode.滑动窗口.p3_最长无重复字串;

import java.util.HashMap;
import java.util.Map;

/**
 * Time: 2024/2/29
 * Author: wgt
 * Description:
 */
public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().lengthOfLongestSubstring("abcabcbb")
        );
    }

    public int lengthOfLongestSubstring(String s) {
        if (s == null || s.length() == 0) {
            return 0;
        }
        int k = 0, j = 0;
        int maxLen = 0;
        Map<Character, Integer> window = new HashMap<>();
        char[] charArr = s.toCharArray();
        for (int i = 0; i < charArr.length; i++) {
            if (!window.containsKey(charArr[i])) {
                window.put(charArr[i], i);
                j++;
            } else {
                int index = window.get(charArr[i]);
                if (j - k > maxLen) {
                    maxLen = j - k;
                }
                for (; k <= index; k++) {
                    window.remove(charArr[i]);
                }
                window.put(charArr[i], i);
                j++;
            }
        }
        return Math.max(j - k, maxLen);
    }
}