package com.example.mylibrary.leetcode.热题100.滑动窗口.p438_找到字符串中所有的字母异位词;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * 438. 找到字符串中所有字母异位词
 * 中等
 * 相关标签
 * 相关企业
 * 给定两个字符串 s 和 p，找到 s 中所有 p 的 异位词 的子串，返回这些子串的起始索引。不考虑答案输出的顺序。
 * <p>
 * 异位词 指由相同字母重排列形成的字符串（包括相同的字符串）。
 * <p>
 * <p>
 * <p>
 * 示例 1:
 * <p>
 * 输入: s = "cbaebabacd", p = "abc"
 * 输出: [0,6]
 * 解释:
 * 起始索引等于 0 的子串是 "cba", 它是 "abc" 的异位词。
 * 起始索引等于 6 的子串是 "bac", 它是 "abc" 的异位词。
 * 示例 2:
 * <p>
 * 输入: s = "abab", p = "ab"
 * 输出: [0,1,2]
 * 解释:
 * 起始索引等于 0 的子串是 "ab", 它是 "ab" 的异位词。
 * 起始索引等于 1 的子串是 "ba", 它是 "ab" 的异位词。
 * 起始索引等于 2 的子串是 "ab", 它是 "ab" 的异位词。
 * <p>
 * <p>
 * 提示:
 * <p>
 * 1 <= s.length, p.length <= 3 * 104
 * s 和 p 仅包含小写字母
 */
public class Solution {

    public static void main(String[] args) {
        System.out.println(
                new Solution().findAnagrams("baa", "aa")
        );
    }

    public List<Integer> findAnagrams(String s, String p) {
        List<Integer> result = new LinkedList<>();
        if (s.length() < p.length()) {
            return result;
        }
        int[] arrS = new int[26];
        int[] arrP = new int[26];
        for (int i = 0; i < p.length(); i++) {
            arrP[p.charAt(i) - 'a']++;
            arrS[s.charAt(i) - 'a']++;
        }
        if (Arrays.equals(arrP, arrS)) {
            result.add(0);
        }
        for (int k = p.length(), i = 0; k < s.length(); k++) {
            arrS[s.charAt(i++) - 'a']--;
            arrS[s.charAt(k) - 'a']++;
            if (Arrays.equals(arrP, arrS)) {
                result.add(i);
            }
        }
        return result;
    }
}
