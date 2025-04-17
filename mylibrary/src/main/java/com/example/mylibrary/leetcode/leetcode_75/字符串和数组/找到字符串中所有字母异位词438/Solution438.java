package com.example.mylibrary.leetcode.leetcode_75.字符串和数组.找到字符串中所有字母异位词438;

import java.util.LinkedList;
import java.util.List;

public class Solution438 {
    public List<Integer> findAnagrams(String s, String p) {
        int[] countS = new int[26];
        int[] countP = new int[26];
        List<Integer> result = new LinkedList<>();
        for(char c : s.toCharArray()) {
            countS[c-'a']++;
        }
        int left =0, right = 0;
        while(right < p.length()) {
            countP[p.charAt(right)-'a']++;
            if(right - left + 1 == s.length()) {
                if(isSame(countS, countP)) {
                    result.add(left);
                }
                countP[p.charAt(left)]--;
                left++;
            }
            right++;
        }
        return result;
    }
    private boolean isSame(int[] a, int[] b) {
        for(int i=0;i<26;i++){
            if(a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) {
        List<Integer> list = new Solution438().findAnagrams(
                "cbaebabacd", "abc"
        );
    }
}
