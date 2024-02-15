package com.example.mylibrary.leetcode.双指针.p167;

/**
 * Time: 2024/2/15
 * Author: wgt
 * Description:
 */
class Solution {
    public int[] twoSum(int[] numbers, int target) {
        int len = numbers.length;
        int left = 0, right = len - 1;
        while (left < right) {
            int indexI = left;
            int indexJ = right;
            int res = numbers[indexI] + numbers[indexJ];
            while (res > target && indexJ > indexI) {
                indexJ--;
                res = numbers[indexI] + numbers[indexJ];
            }
            while (res < target && indexI < indexJ) {
                indexI++;
                res = numbers[indexI] + numbers[indexJ];
            }
            if (target == res) {
                return new int[]{indexI, indexJ};
            } else if (res < target) {
                left++;
            } else {
                right--;
            }
        }
        return null;
    }

    public static void main(String[] args) {
       int[] result =  new Solution().twoSum(new int[]{2,7,11,15}, 9);
        System.out.println(result);
    }
}
