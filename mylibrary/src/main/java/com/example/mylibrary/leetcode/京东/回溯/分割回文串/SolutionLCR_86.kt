package com.example.mylibrary.leetcode.京东.回溯.分割回文串

class SolutionLCR_86 {
    fun partition(s: String): Array<Array<String>> {
        val result : MutableList<List<String>> = mutableListOf()
        backtrack(s, 0, mutableListOf(), result)
        return result.map { it.toTypedArray() }.toTypedArray()
    }
    private fun backtrack(s: String, start: Int, currentPartition: MutableList<String>, result: MutableList<List<String>>) {
        if (start == s.length) {
            result.add(ArrayList(currentPartition))
            return
        }

        for (end in start until s.length) {
            if (isPalindrome(s, start, end)) {
                currentPartition.add(s.substring(start, end + 1))
                backtrack(s, end + 1, currentPartition, result)
                currentPartition.removeAt(currentPartition.size - 1)
            }
        }
    }

    private fun isPalindrome(s: String, left: Int, right: Int): Boolean {
        var l = left
        var r = right
        while (l < r) {
            if (s[l] != s[r]) {
                return false
            }
            l++
            r--
        }
        return true
    }
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            val solutionlcr86 = SolutionLCR_86()
            solutionlcr86.partition("google")
        }
    }

}