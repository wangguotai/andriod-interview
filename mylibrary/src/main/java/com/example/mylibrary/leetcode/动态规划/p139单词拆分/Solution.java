package com.example.mylibrary.leetcode.动态规划.p139单词拆分;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Time: 2024/3/27
 * Author: wgt
 * Description:
 */
public class Solution {
    public static void main(String[] args) {
        List<String> list = new ArrayList<>();
        Collections.addAll(list, "leet", "code");
        System.out.println(
                new Solution().wordBreak(
                        "leetcode", list
                )
        );
    }

    /**
     * 1. 确定状态 dp[i] 表示字符串的前 i 个字符组成的字符串s[0..i-1]是否能被空格拆分成若干个字典中出现的单词
     * 2. 状态转移方程 dp[i] = dp[j] && check(s[j..i-1]) 其中check检查子串是否出现在字典里 , j 的取值为[0,i)
     * 3. 确定初始值 dp[0] = true
     */
    public boolean wordBreak(String s, List<String> wordDict) {
        int n = s.length();
        Set<String> wordSet = new HashSet<>(wordDict);
        boolean[] dp = new boolean[n + 1];
        dp[0] = true;
        for (int i = 1; i <= n; i++) {
            for (int j = 0; j < i; j++) {
                if (dp[j] && wordSet.contains(s.substring(j, i))) {
                    dp[i] = true;
                    break;
                }
            }
        }
        return dp[n];
    }
}
