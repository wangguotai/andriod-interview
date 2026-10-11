package com.example.mylibrary.leetcode.leetcode_75.回溯.电话号码的字母组合17

fun letterCombinations(digits: String): List<String> {
    val mapping = mapOf(
        Pair('2', "abc"),
        Pair('3', "def"),
        Pair('4', "ghi"),
        Pair('5', "jkl"),
        Pair('6', "mno"),
        Pair('7', "pqrs"),
        Pair('8', "tuv"),
        Pair('9', "wxyz"),
    )
    val result = mutableListOf<String>()
    val len = digits.length

    fun backtrace(index: Int, sb: StringBuilder) {
        if (index == len) {
            result.add(sb.toString())
            return
        }
        val str = mapping[digits[index]]
        for (ch in str!!.toCharArray()) {
            sb.append(ch)
            backtrace(index+1, sb)
            sb.deleteAt(index)
        }
    }
    backtrace(0, StringBuilder())
    return result
}
