package com.example.mylibrary.leetcode.每日一题.找出三位偶数2094

class Solution2094 {
    fun findEvenNumbers(digits: IntArray): IntArray {
        val count = IntArray(10)
        for(d in digits) {
            count[d]++
        }
        val result = mutableListOf<Int>()
        for (num in 100..998 step 2) {
            val current = IntArray(10)
            var temp = num
            while(temp > 0) {
                current[temp % 10]++
                temp = temp / 10
            }
            var valid = true
            for(i in 0..9) {
                if(current[i] > count[i]) {
                    valid = false
                    break
                }
            }
            if(valid) {
                result.add(num)
            }
        }
        return result.sorted().toIntArray()
    }
}