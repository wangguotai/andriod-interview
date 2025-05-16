package com.example.mylibrary.leetcode.每日一题.字符串转换后的长度3335

class Solution3335 {
    fun lengthAfterTransformations(s: String, t: Int): Int {
        val MOD = 1e9.toInt() + 7
        var cnt = IntArray(26)
        s.toCharArray().forEach {
            cnt[it - 'a']++
        }

        repeat(t) {
            val nxt = IntArray(26)
            nxt[0] = cnt[25]
            nxt[1] = (cnt[25] + cnt[0]) % MOD
            for (i in 2..25) {
                nxt[i] = cnt[i - 1]
            }
            cnt = nxt
        }
        return cnt.reduce { acc, value -> (acc + value) % MOD }
    }
}