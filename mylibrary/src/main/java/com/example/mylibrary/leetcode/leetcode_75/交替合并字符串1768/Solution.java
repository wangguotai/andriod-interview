package com.example.mylibrary.leetcode.leetcode_75.交替合并字符串1768;

/**
 * Time: 2025/3/31
 * Author: wgt
 * Description:
 */
public class Solution {
    public String mergeAlternately(String word1, String word2) {
        if (word1 == null) {
            return word2;
        }
        if (word2 == null) {
            return word1;
        }
        int i = 0, j = 0;
        int len1 = word1.length();
        int len2 = word2.length();
        StringBuilder result = new StringBuilder(len1 + len2);
//        boolean flag = true;
        while (i < len1 || j < len2) {
//            if(flag && i < len1 || !flag && j == len2) {
//                result.append(word1.charAt(i++));
//            } else if( j< len2){
//                result.append(word2.charAt(j++));
//            }
//            flag = !flag;
            if (i < len1) {
                result.append(word1.charAt(i++));
            }
            if (j < len2) {
                result.append(word2.charAt(j++));
            }
        }
        return result.toString();
    }


    public String mergeAlternately1(String word1, String word2) {
        int n = word1.length();
        int m = word2.length();
        StringBuilder ans = new StringBuilder(n + m); // 预分配空间
        for (int i = 0; i < n || i < m; i++) {
            if (i < n) {
                ans.append(word1.charAt(i));
            }
            if (i < m) {
                ans.append(word2.charAt(i));
            }
        }
        return ans.toString();
    }

//    作者：灵茶山艾府
//    链接：https://leetcode.cn/problems/merge-strings-alternately/solutions/2794711/jian-dan-ti-jian-dan-zuo-pythonjavaccgoj-mqmy/
//    来源：力扣（LeetCode）
//    著作权归作者所有。商业转载请联系作者获得授权，非商业转载请注明出处。
}
