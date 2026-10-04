package com.example.mylibrary.leetcode.leetcode_75.双指针.移动0

/**
 * 283. 移动零
 * 双指针：j 指向下一个待放置非零元素的位置。
 * 遍历一次，遇到非零元素就与 j 位置交换（i == j 时无需交换），
 * 每个非零元素最多移动一次，操作次数最少。
 */
//fun moveZeroes(nums: IntArray): Unit {
//    var insertPos = 0
//    for(i in nums.indices) {
//        if(nums[i]!=0) {
//            nums[insertPos] = nums[i]
//            insertPos++
//        }
//    }
//    while (insertPos < nums.size) {
//        nums[insertPos] = 0
//        insertPos++
//    }
//}
fun moveZeroes(nums: IntArray): Unit {
    var j = 0
    for (i in nums.indices) {
        if(nums[i]!=0) {
            if(i != j) {
                nums[j] = nums[i].also {nums[i] = nums[j]}
            }
            j++
        }
    }
}