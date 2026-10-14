package com.example.mylibrary.leetcode.leetcode_75.字符串和数组.反转字符串中的单词151

import java.lang.ref.WeakReference
import java.util.LinkedList

fun reverseWords(s: String): String {
    val word = StringBuilder()
    val stack = LinkedList<String>()
    // 标记单词
    for (i in 0 ..< s.length) {
        if(s[i]==' ') {
            if(word.isNotEmpty()){
                stack.push(word.toString())
                word.clear()
            }
            continue
        }
        word.append(s[i])
    }
    val deleteFlag = word.isEmpty()

    while (stack.isNotEmpty()) {
        word.append(' ' + stack.pop())
    }
    if(deleteFlag) {
        word.deleteCharAt(0)
    }
    return word.toString()
}

fun main() {
    println(reverseWords("the sky is blue"))
}