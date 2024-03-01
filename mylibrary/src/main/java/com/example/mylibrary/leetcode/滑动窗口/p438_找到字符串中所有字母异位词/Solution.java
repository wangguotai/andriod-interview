package com.example.mylibrary.leetcode.滑动窗口.p438_找到字符串中所有字母异位词;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Time: 2024/2/29
 * Author: wgt
 * Description:
 */
public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().findAnagrams("abab", "ab")
        );
    }

    public List<Integer> findAnagrams1(String s, String p) {
        List<Integer> result = new LinkedList<>();
        if (s == null || s.length() == 0 || p == null || p.length() == 0) {
            return result;
        }
        char[] sArr = s.toCharArray();
        int len = sArr.length;
        int pLen = p.length();
        List<Character> pList = new ArrayList<>(pLen);
        for (char ch : p.toCharArray()) {
            pList.add(ch);
        }
        List<Character> pTemp = new LinkedList<>(pList);
        int start = 0;
        int i = 0;
        while (i < len) {
            if (pTemp.contains(sArr[i])) {
                pTemp.remove(Character.valueOf(sArr[i]));
                i++;
            } else {
                if (pTemp.isEmpty()) {
                    result.add(start);
                    start++;
                } else {
                    if (pList.contains(sArr[i])) {
                        start++;
                    } else {
                        start = i + 1;
                    }
                }
                pTemp = new LinkedList<>(pList);
                i = start;
            }
        }
        if (pTemp.isEmpty()) {
            result.add(start);
        }
        return result;
    }

    public List<Integer> findAnagrams(String s, String p) {
        List<Integer> result = new LinkedList<>();
        if (s == null || s.length() == 0 || p == null || p.length() == 0 || p.length() > s.length()) {
            return result;
        }
        int sLen = s.length();
        int pLen = p.length();
        int[] sCount = new int[26];
        int[] pCount = new int[26];
        for (int i = 0; i < pLen; i++) {
            sCount[s.charAt(i) - 'a']++;
            pCount[p.charAt(i) - 'a']++;
        }
        if (Arrays.equals(sCount, pCount)) {
            result.add(0);
        }
        for (int i = 0; i < sLen - pLen; i++) {
            sCount[s.charAt(i) - 'a']--;
            sCount[s.charAt(i + pLen) - 'a']++;
            if (Arrays.equals(sCount, pCount)) {
                result.add(i + 1);
            }
        }
        return result;
    }
}