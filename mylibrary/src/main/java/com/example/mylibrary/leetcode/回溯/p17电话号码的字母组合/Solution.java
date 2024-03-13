package com.example.mylibrary.leetcode.回溯.p17电话号码的字母组合;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class Solution {
    private Map<Character, String> map = new HashMap<>();
    private List<String> ans = new LinkedList<>();
    private String digits;
    private int len;

    public static void main(String[] args) {
        System.out.println(
                new Solution().letterCombinations("23")
        );
    }

    public List<String> letterCombinations(String digits) {
        if (digits.isEmpty()) {
            return ans;
        }
        len = digits.length();
        this.digits = digits;
        map.put('2', "abc");
        map.put('3', "def");
        map.put('4', "ghi");
        map.put('5', "jkl");
        map.put('6', "mno");
        map.put('7', "pqrs");
        map.put('8', "tuv");
        map.put('9', "wxyz");
        backTrace(0, new StringBuilder());
        return ans;
    }

    private void backTrace(int index, StringBuilder sb) {
        if (index == len) {
            ans.add(sb.toString());
            return;
        }
        for (char ch : map.get(digits.charAt(index)).toCharArray()) {
            sb.append(ch);
            backTrace(index + 1, sb);
            sb.deleteCharAt(sb.length() - 1);
        }
    }
}
