package com.example.mylibrary.leetcode.leetcode_75.哈希表和哈希集合.确定两个字符串是否接近1657;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Time: 2025/4/7
 * Author: wgt
 * Description:
 */
public class Solution1657 {
    //    public boolean closeStrings(String word1, String word2) {
//        Map<Character, Integer> map1 = new HashMap<>();
//        Map<Character, Integer> map2 = new HashMap<>();
//
//        int len1 = word1.length();
//        int len2 = word2.length();
//        for (int i = 0; i < len1; i++) {
//            char ch = word1.charAt(i);
//            map1.put(ch, map1.getOrDefault(ch, 0) + 1);
//        }
//
//        for (int i = 0; i < len2; i++) {
//            char ch = word2.charAt(i);
//            map2.put(ch, map2.getOrDefault(ch, 0) + 1);
//        }
//        // 判断keys
//        Set<Character> set1 = map1.keySet();
//        Set<Character> set2 = map2.keySet();
//        if (set1.size() != set2.size()) {
//            return false;
//        }
//        for (Character ch : set1) {
//            if (!set2.contains(ch)) {
//                return false;
//            }
//        }
//
//        //  判断values
//        List<Integer> values1 = new ArrayList<>(map1.values());
//        List<Integer> values2 = new ArrayList<>(map2.values());
//        Collections.sort(values1);
//        Collections.sort(values2);
//        int len = values1.size();
//        for (int i = 0; i < len; i++) {
//            if (values1.get(i).intValue() != values2.get(i).intValue()) {
//                return false;
//            }
//        }
//        return true;
//    }
    public boolean closeStrings(String word1, String word2) {
        int[] charArr1 = new int[26];
        int[] charArr2 = new int[26];
        for (char ch : word1.toCharArray()) {
            charArr1[ch - 'a']++;
        }
        for (char ch : word2.toCharArray()) {
            charArr2[ch - 'a']++;
        }
        // 保证所有key的种类由于一致
        for (int i = 0; i < 26; i++) {
            if (charArr1[i] > 0 && charArr2[i] == 0 || charArr1[i] == 0 && charArr2[i] > 0) {
                return false;
            }
        }
        // 再考虑values
        Arrays.sort(charArr1);
        Arrays.sort(charArr2);
        return Arrays.equals(charArr1, charArr2);
    }

    public static void main(String[] args) {
        new Solution1657().closeStrings(
                "iiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiii",
                "iiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiiii");
    }
}
