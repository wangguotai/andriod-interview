package com.example.mylibrary.leetcode.leetcode_75.字符串和数组.反转字符串中元音字母345

fun reverseVowels(s: String): String {

    val charArray = s.toCharArray()
    var head: Int = 0
    var tail: Int = charArray.size - 1
    val vowels = arrayOf('a','e','i','o','u','A','E','I','O','U')
    while (head < tail){
        var findStart = false
        var findEnd = false
        while(head < tail) {
            if(vowels.contains(charArray[head])) {
                findStart = true
                break
            }
            head++
        }
        while (tail > head) {
            if(vowels.contains(charArray[tail])) {
                findEnd = true
                break
            }
            tail--
        }
        if(findStart && findEnd) {
            val temp = charArray[head]
            charArray[head] = charArray[tail]
            charArray[tail] = temp
            head++
            tail--
        }
    }
    return String(charArray)
}

fun main() {
    println(reverseVowels("leetcode"))
}