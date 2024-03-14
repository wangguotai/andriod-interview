package com.example.mylibrary.leetcode.回溯.p22括号生成;

import java.util.LinkedList;
import java.util.List;

public class Solution {
    List<String> ans = new LinkedList<>();
    int len;

    public static void main(String[] args) {
        System.out.println(
                new Solution().generateParenthesis(3)
        );
    }

    public List<String> generateParenthesis(int n) {
        this.len = n;
        backTrack(0, 0, 0, new StringBuilder());
        return ans;
    }

    private void backTrack(int index, int usedL, int usedR, StringBuilder sb) {
        if (index == len * 2) {
            ans.add(sb.toString());
            return;
        }

        if (usedL < len) {
            backTrack(index + 1, usedL + 1, usedR, sb.append('('));
            sb.deleteCharAt(sb.length() - 1);
        }
        if (usedR < usedL) {
            backTrack(index + 1, usedL, usedR + 1, sb.append(')'));
            sb.deleteCharAt(sb.length() - 1);
        }

    }
}