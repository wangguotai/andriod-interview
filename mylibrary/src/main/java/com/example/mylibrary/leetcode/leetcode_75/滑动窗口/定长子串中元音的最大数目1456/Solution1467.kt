package com.example.mylibrary.leetcode.leetcode_75.滑动窗口.定长子串中元音的最大数目1456

fun maxVowels(s: String, k: Int): Int {
    val vowels = arrayListOf<Char>('a', 'e', 'i', 'o', 'u')
    var currentLen = 0
    for (i in 0..<k) {
        if (vowels.contains(s[i])) {
            currentLen++
        }
    }
   var  maxVowelLength = currentLen
    for (i in k..<s.length) {
        if(vowels.contains(s[i-k])) {
            currentLen--
        }
        if(vowels.contains(s[i])){
            currentLen++
        }
        maxVowelLength = currentLen.coerceAtLeast(maxVowelLength)
    }
    return maxVowelLength
}
fun main() {
    maxVowels("a", 1)
}