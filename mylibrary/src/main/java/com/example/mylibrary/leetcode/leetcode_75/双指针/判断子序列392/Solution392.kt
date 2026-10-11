package com.example.mylibrary.leetcode.leetcode_75.双指针.判断子序列392

fun isSubsequence1(s: String, t: String): Boolean {
    var j = 0
    for(i in 0..< s.length) {
        if(s[j] == t[i]) {
            j++
        }
        if(j == s.length) {
            return true
        }
    }
    return false
}



fun isSubsequence2(s: String, t: String): Boolean {
    // 动态规划
    val n = s.length
    val m = t.length
    val dp = Array(t.length + 1) { IntArray(26) }
    for (i in 0..<26) {
        dp[m][i] = m
    }
    for (i in t.length-1 .. 0) {
        for (j in 0 ..< 26) {
            if(t[i] == 'a' + j) {
                dp[i][j] = i
            } else {
                dp[i][j] = dp[i+1][j]
            }
        }
    }
    var add = 0
    for (i in 0 ..s.length-1) {
        if(dp[add][s[i]-'a'] == m) {
            return false
        }
        add = dp[add][s[i]-'a'] + 1
    }
    return true
}
class SubsequenceChecker(private val t: String) {
    private val positions: Array<MutableList<Int>> = Array(26) {mutableListOf()}
    init {
        for((idx, c) in t.withIndex()) {
            positions[c-'a'].add(idx)
        }
    }

    fun isSubsequence(s: String): Boolean {
        var pos = -1
        for (c in s){
            val list = positions[c-'a']
            // 二分查找第一个 > pos的位置
            val idx = lowerBound(list, pos + 1)
            if(idx == list.size) return false
            pos = list[idx]
        }
        return true
    }

    private fun lowerBound(list: List<Int>, target: Int): Int {
        var lo = 0
        var hi = list.size
        while(lo < hi) {
            val mid = (lo + hi) ushr 1
            if(list[mid] < target) lo = mid + 1
            else hi = mid
        }
        return lo
    }
}
fun isSubsequence(s: String, t: String): Boolean {
    // 动态规划
    return SubsequenceChecker(t).isSubsequence(s)
}



fun main() {
    println(isSubsequence("abc", "ahbgdc"))
}