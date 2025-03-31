package com.example.mylibrary.leetcode.leetcode_75.字符串的最大公因子1071;

/**
 * Time: 2025/3/31
 * Author: wgt
 * Description:
 */
public class Solution {
    public static String gcdOfStrings(String str1, String str2) {
        // 检查是否存在公因子
        if (!(str1 + str2).equals(str2 + str1)) {
            return "";
        }

        // 计算长度的GCD
        int gcdLength = gcd(str1.length(), str2.length());
        return str1.substring(0, gcdLength);
    }

    // 递归实现辗转相除法
    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    // 测试用例
    public static void main(String[] args) {
        System.out.println(gcdOfStrings("ABCABC", "ABC")); // 输出: "ABC"
        System.out.println(gcdOfStrings("ABABAB", "ABAB")); // 输出: "AB"
        System.out.println(gcdOfStrings("LEET", "CODE"));   // 输出: ""
    }
}
