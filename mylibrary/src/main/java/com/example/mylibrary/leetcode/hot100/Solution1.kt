package com.example.mylibrary.leetcode.hot100

import java.util.Arrays

class Solution1 {
    fun twoSum(nums: IntArray, target: Int): IntArray {
        val valueToKeyMap = mutableMapOf<Int, Int>()

        for (i in 0 until nums.size) {
            valueToKeyMap[nums[i]] = i
        }
        Arrays.sort(nums)
        val result = mutableListOf<Int>()
        var a = 0
        var b = nums.size - 1

        while (a < b) {
            val tempRes = nums[a] + nums[b]
            if (tempRes < target) {
                a++
            } else if (tempRes > target) {
                b--
            } else {
                result.add(valueToKeyMap[nums[a]]!!)
                result.add(valueToKeyMap[nums[b]]!!)
                break;
            }
        }
        return result.toIntArray()
    }

}

fun main(args: Array<String>) {
    println(Solution1().twoSum(intArrayOf(2, 7, 11, 15), 9).contentToString())
}