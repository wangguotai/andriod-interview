package com.example.mylibrary.leetcode.每日一题.最长相邻不相等子序列II



class Solution2901 {

    fun getWordsInLongestSubsequence(words: Array<String>, groups: IntArray): List<String> {

        // 1. 确定状态 dp[i] 表示以下标i为结尾的最长子序列长度，设HammingDistance(s,t)表示两个字符串s,t的[汉明距离]
        // 2. 状态转移方程 dp[i]=max(dp[i],dp[j]+1)
        // if groups[i] != groups[j],HammingDistance(words[i],words[j])=1
        // 3. 确定初始值 dp[0] = 0

        val n = groups.size
        val dp = IntArray(n)
        dp.fill(1)
        val prev = IntArray(n)
        prev.fill(-1)

        var maxIndex = 0

        for (i in 1 until n) {
            for (j in 0 until i) {
                if (check(words[i], words[j]) && dp[j] + 1 > dp[i] && groups[i] != groups[j]) {
                    dp[i] = dp[j] + 1
                    prev[i] = j
                }
            }
            if (dp[i] > dp[maxIndex]) {
                maxIndex = i
            }
        }
        val ans = mutableListOf<String>()
        var i = maxIndex
        while (i >= 0) {
            ans.add(words[i])
            i = prev[i]
        }
        return ans.reversed()
    }

    fun check(s1: String, s2: String): Boolean {
        if (s1.length != s2.length) {
            return false
        }
        var diff = 0
        for (i in 0 until s1.length) {
            if (s1[i] != s2[i]) {
                if (++diff > 1) {
                    return false
                }
            }
        }
        return diff == 1
    }
}

fun main() {
    Solution2901().getWordsInLongestSubsequence(
        arrayOf("cb", "dc", "ab", "aa", "ac", "bb", "ca", "bcc", "cdd", "aad", "bba", "bc", "ddb"),
        intArrayOf(12, 6, 10, 11, 4, 8, 9, 11, 2, 11, 3, 2, 5)
    )
}