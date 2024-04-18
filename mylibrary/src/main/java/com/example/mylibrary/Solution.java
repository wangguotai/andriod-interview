package com.example.mylibrary;

public class Solution {
    // int 有序数组 =》其中有重复的元素；给定一个元素，找出其中出现的第一个角标
    public static int findFirstIndexOfArr(int[] arr, int el) {
        if (arr == null || arr.length == 0) {
            return -1;
        }
        int left = 0;
        int right = arr.length - 1;
        while (left < right) {
            int mid = left + (right - left) / 2;
            if (arr[mid] < el) {
                left = mid + 1;
            } else {
                right = mid;
            }
        }
        if (arr[left] == el) {
            return left;
        }
        return -1;
    }

    public static void main(String[] args) {
        int ans = findFirstIndexOfArr(new int[]{
                1, 2, 3, 3, 3, 3, 4
        }, 3);
        System.out.println(ans);
    }
}
