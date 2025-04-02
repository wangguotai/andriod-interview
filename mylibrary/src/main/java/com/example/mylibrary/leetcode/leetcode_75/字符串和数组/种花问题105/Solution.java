package com.example.mylibrary.leetcode.leetcode_75.字符串和数组.种花问题105;

/**
 * Time: 2025/3/31
 * Author: wgt
 * Description:
 */
public class Solution {
    public static void main(String[] args) {
        new Solution().canPlaceFlowers(new int[]{
                1, 0, 0, 0, 1
        }, 1);
    }

    public boolean canPlaceFlowers(int[] flowerbed, int n) {
        int flowerIndex = 0;
        int len = flowerbed.length;
        int[] finalFlowerbed = new int[len + 2];
        System.arraycopy(flowerbed, 0, finalFlowerbed, 1, len);
        for (int i = 1; i < finalFlowerbed.length - 1; i++) {
            if (finalFlowerbed[i - 1] == 0 && finalFlowerbed[i + 1] == 0 && finalFlowerbed[i] == 0) {
                finalFlowerbed[i] = 1;
                flowerIndex++;
            }
        }
        return flowerIndex == n;
    }
}