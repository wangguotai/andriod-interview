package com.example.mylibrary.leetcode.leetcode_75.字符串和数组.字符串的最大公因子1071

fun gcdOfStrings(str1: String, str2: String): String {
    if ((str1 + str2) != (str2 + str1)) {
        return ""
    }

    return str1.substring(0, gcd(str1.length, str2.length))
}

fun gcd(a1: Int, b1: Int): Int {
    var a = a1
    var b = b1
    var remind = a % b
    while (remind != 0) {
        a = b
        b = remind
        remind = a % b
    }
    return b
}