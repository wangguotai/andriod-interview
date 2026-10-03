package com.example.mylibrary.leetcode.leetcode_75.字符串和数组.交替合并字符串1768

fun mergeAlternately(word1: String, word2: String): String {
    var i = 0
    var j = 0
    val sb = StringBuilder()
    val maxLen = word1.length.coerceAtLeast(word2.length)
    while (i < maxLen || j < maxLen) {
        if(i < word1.length) {
            sb.append(word1[i++])
        }
        if(j < word2.length) {
            sb.append(word2[j++])
        }
    }
    return sb.toString()
}

fun main() {
    mergeAlternately("ab", "pqrs")
}