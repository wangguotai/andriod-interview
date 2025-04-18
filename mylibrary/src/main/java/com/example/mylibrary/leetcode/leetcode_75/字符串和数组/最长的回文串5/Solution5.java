package com.example.mylibrary.leetcode.leetcode_75.字符串和数组.最长的回文串5;

public class Solution5 {
    public String longestPalindrome(String s) {
        int n = s.length();
        int start = 0, end = 0;
        StringBuilder result = new StringBuilder();
        int count = 0;
        for (int i = 0; i < n; i++) {
            // 以单个字符为中心扩展
            int len1 = expandAroundCenter(s, i, i);
            // 以相邻两个字符为中心扩展
            int len2 = expandAroundCenter(s, i, i + 1);
            int len = Math.max(len1, len2);
            if (len > end - start) {
                start = i - (len - 1) / 2;
                end = i + len / 2;
            }
        }
        return s.substring(start, end + 1);
    }

    private int expandAroundCenter(String s, int left, int right) {
        while (left >= 0 && right < s.length() && s.charAt(left) == s.charAt(right)) {
            left--;
            right++;
        }
        return right - left - 1;
    }

    public static void main(String[] args) {
        Solution5 solution = new Solution5();
        String s1 = "babad";
        System.out.println(solution.longestPalindrome(s1));
        String s2 = "cbbd";
        System.out.println(solution.longestPalindrome(s2));
    }


}
