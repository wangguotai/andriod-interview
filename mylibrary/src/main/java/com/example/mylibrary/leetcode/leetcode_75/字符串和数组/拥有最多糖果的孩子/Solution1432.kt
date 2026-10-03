package com.example.mylibrary.leetcode.leetcode_75.字符串和数组.拥有最多糖果的孩子

fun kidsWithCandies(candies: IntArray, extraCandies: Int): List<Boolean> {
    val candidateVal = candies.max()  - extraCandies
    val result = ArrayList<Boolean>(candies.size)
    for (i in 0..< candies.size) {
        result.add(candidateVal < candies[i])
    }
    return result
}

fun main() {
    kidsWithCandies(intArrayOf(2,3,5,1,3), 3)
}

