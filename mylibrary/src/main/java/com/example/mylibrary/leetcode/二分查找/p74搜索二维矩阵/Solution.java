package com.example.mylibrary.leetcode.二分查找.p74搜索二维矩阵;

public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().searchMatrix(new int[][]{
                        new int[]{1, 3, 5, 7},
                        new int[]{10, 11, 16, 20},
                        new int[]{23, 30, 34, 60},
                }, 13)
        );
    }

    public boolean searchMatrix(int[][] matrix, int target) {
        int m = matrix.length;
        int n = matrix[0].length;
        int left = 0;
        int right = m - 1;
        while (left <= right) {
            int mid = left + (right - left) >> 1;
            if (target < matrix[mid][0]) {
                if (mid > left && target >= matrix[mid - 1][0]) {
                    return findInner(matrix[mid - 1], target);
                } else if (mid == left) {
                    return findInner(matrix[left], target);
                } else {
                    right = mid - 1;
                }
            } else if (target > matrix[mid][0]) {
                if (target <= matrix[mid][n - 1]) {
                    return findInner(matrix[mid], target);
                } else {
                    left = mid + 1;
                }
            } else {
                return true;
            }
        }
        return false;
    }

    private boolean findInner(int[] arr, int target) {
        int left = 0;
        int right = arr.length - 1;
        while (left <= right) {
            int mid = ((right - left) >> 1) + left;
            if (target == arr[mid]) {
                return true;
            } else if (target > arr[mid]) {
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }
        return false;
    }
}