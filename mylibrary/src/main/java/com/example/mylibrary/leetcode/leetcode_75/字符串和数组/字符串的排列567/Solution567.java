package com.example.mylibrary.leetcode.leetcode_75.字符串和数组.字符串的排列567;

import java.util.Arrays;

public class Solution567 {
    public boolean checkInclusion(String s1, String s2) {
        int[] arrayS1 = new int[26];
        int[] arrayS2 = new int[26];
        for(char c : s1.toCharArray()) {
            arrayS1[c-'a']++;
        }
        int l=0,r=0;
        int len = s2.length();
        int i =0;
        while(r<len) {
            arrayS2[s2.charAt(r) - 'a']++;
            if(r - l +1 == s1.length()) {
                if(isSame(arrayS1, arrayS2)) {
                    return true;
                }
                arrayS2[s2.charAt(l)-'a']--;
                l++;
            }
            r++;
        }
        return false;
    }

    private boolean isSame(int[] a, int[] b) {
        for (int i = 0; i < 26; i++) {
            if(a[i]!=b[i]) {
                return false;
            }
        }
        return true;
    }
}
