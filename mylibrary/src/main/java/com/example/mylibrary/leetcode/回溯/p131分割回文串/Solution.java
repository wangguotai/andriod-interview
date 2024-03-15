package com.example.mylibrary.leetcode.回溯.p131分割回文串;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class Solution {
    List<List<String>> ans = new LinkedList<>();
    char[] arr;
    String str;
    boolean[][] dp;
    // 方法1. 回溯+动态规划预处理

    public static void main(String[] args) {
        System.out.println(
                new Solution().partition("aab")
        );
    }

    /**
     * 方法： 搜索+回溯的方法枚举所有可能得分割方法并进行判断
     * 1. 划分子串 确认是回文串和不是回文串：假设当前搜索到字符串的第i个字符，且s[0..i-1]位置的所有字符已经被分割成若干个回文串
     * 2. 并且分割结果被放入了答案数组 ans中，那么我们就需要枚举下一个回文串的右边界 j，使得 s[i..j] 是一个回文串。
     * 因此 从i开始，从小到大枚举j，对于当前的枚举值j，使用双指针判断s[i..j]是否为回文串：如果s[i..j]是回文串，加入答案 ans，
     * 并以j+1作为新的i进行下一层搜索，并在未来的回溯中奖s[i..j]从ans中移除。
     *
     * @param s
     * @return
     */
    public List<List<String>> partition(String s) {
        this.arr = s.toCharArray();
        this.str = s;
        this.dp = new boolean[arr.length][arr.length];
        dp[0][0] = true;
        for (int i = 0; i < arr.length; i++) {
            Arrays.fill(dp[i], true);
        }
        for (int i = arr.length - 1; i >= 0; i--) {
            for (int j = i + 1; j < arr.length; j++) {
                dp[i][j] = arr[i] == arr[j] && dp[i + 1][j - 1];
            }
        }
        backTrack(0, new LinkedList<>());
        return ans;
    }

    private void backTrack(int index, List<String> result) {
        if (index == arr.length) {
            if (result.size() > 0) {
                ans.add(new ArrayList<>(result));
            }
            return;
        }
        for (int i = index; i < arr.length; i++) {
//            if(isPalindrome(index, i)){
            if (dp[index][i]) {
                result.add(str.substring(index, i + 1));
                backTrack(i + 1, result);
                result.remove(result.size() - 1);
            }
        }
    }

    private boolean isPalindrome(int i, int j) {
        while (i <= j) {
            if (arr[i] != arr[j]) {
                return false;
            }
            i++;
            j--;
        }
        return true;
    }
}