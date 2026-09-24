package com.interview


class Solution1768 {
    fun mergeAlternately(word1: String, word2: String): String {

        val len1 = word1.length
        val len2 = word2.length
        var minLen : Int
        val maxLen : Int
        val maxWord: String =
            if(len1 < len2) {
                minLen = len1
                maxLen = len2
                word2

            } else {
                minLen = len2
                maxLen = len1
                word1
            }
        var index = 0
        val sb = StringBuilder()
        while(index++ < minLen) {
            sb.append(word1[index])
            sb.append(word2[index])
        }
        while(index++ < maxLen) {
            sb.append(maxWord[index])
        }
        return sb.toString()
    }
}

fun main() {
    Solution1768().mergeAlternately("prd", "sb")
}