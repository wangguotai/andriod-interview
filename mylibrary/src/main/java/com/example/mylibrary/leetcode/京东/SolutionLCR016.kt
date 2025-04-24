package com.example.mylibrary.leetcode.京东

import kotlin.math.max

class SolutionLCR016 {
    fun lengthOfLongestSubstring(s: String): Int {
        val n = s.length
        val map: MutableMap<Char?, Int?> = HashMap<Char?, Int?>()
        var left = 0
        var right = 0
        var longest = 0
        while (right < n) {
            val ch = s.get(right)
            if (!map.containsKey(ch)) {
                map.put(ch, right)
                longest = max(longest, map.size)
            } else {
                val newLeft: Int = map.get(ch)!!
                while (left < newLeft) {
                    map.remove(s.get(left++))
                }
                map.put(ch, right)
                left++
            }
            right++
        }
        return max(longest, map.size)
    }

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val solutionLCR016 = SolutionLCR016()
            solutionLCR016.lengthOfLongestSubstring("cdd")
        }
    }
}
