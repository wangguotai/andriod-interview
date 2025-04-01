package com.example.mylibrary.leetcode.leetcode_75.反转字符串中元音字母345;

import java.util.Arrays;
import java.util.List;

/**
 * Time: 2025/3/31
 * Author: wgt
 * Description:
 */
public class Solution {
    public static void main(String[] args) {
        System.out.println(new Solution().reverseVowels(" apG0i4maAs::sA0m4i0Gp0"));
    }

    public String reverseVowels(String s) {
        // 使用双指针
        int i = 0;
        int len = s.length();
        int j = len - 1;
        StringBuilder sb = new StringBuilder(s);
        List<Character> vowel = Arrays.asList('A', 'E', 'I', 'O', 'U', 'a', 'e', 'i', 'o', 'u');
        boolean findLeft = false;
        boolean findRight = false;
        while (i < j) {
            if (!findLeft && vowel.contains(sb.charAt(i++))) {
                findLeft = true;
                i--;
            }
            if (!findRight && vowel.contains(sb.charAt(j--))) {
                findRight = true;
                j++;
            }
            if (findRight && findLeft) {
                char tempChar = sb.charAt(j);
                sb.setCharAt(j--, sb.charAt(i));
                sb.setCharAt(i++, tempChar);
                findRight = false;
                findLeft = false;
            }

        }
        return sb.toString();
    }

}