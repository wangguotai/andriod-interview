package com.example.mylibrary.leetcode.矩阵.p240_搜索二维矩阵2;

public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().searchMatrix(
                        new int[][]{
                                new int[]{1, 4, 7, 11, 15},
                                new int[]{2, 5, 8, 12, 19},
                                new int[]{3, 6, 9, 16, 22},
                                new int[]{10, 13, 14, 17, 24},
                                new int[]{18, 21, 23, 26, 30},
                        },
                        5
                )
        );
    }

    // 二分查找怎么用呢，可以对每一行分别二分查找
    public boolean searchMatrix1(int[][] matrix, int target) {
        int m = matrix.length;
        int n = matrix[0].length;
        for (int i = 0; i < m; i++) {
            if (binarySearch(matrix[i], target) == target) {
                return true;
            }
        }
        return false;
    }

    public int binarySearch(int[] arr, int target) {
        int lo = 0;
        int hi = arr.length - 1;
        int mid = 0;
        while (lo <= hi) {
            mid = (lo + hi) >> 1;
            if (arr[mid] > target) {
                hi = mid - 1;
            } else if (arr[mid] < target) {
                lo = mid + 1;
            } else {
                return target;
            }
        }
        return arr[mid];
    }

    // z字形查找，以左上角为原点，向下向右创建坐标系，从右上角（0，n-1）出发，寻找到（m-1，0）的区域
    // 如果target>point(x,y) x行的数都小于targt
    // 如果target<point(x,y) y行的数都大于targt
    public boolean searchMatrix(int[][] matrix, int target) {
        int m = matrix.length;
        int n = matrix[0].length;
        int i = 0;
        int j = n - 1;
        while (i < m && j >= 0) {
            if (matrix[i][j] > target) {
                j--;
            } else if (matrix[i][j] < target) {
                i++;
            } else {
                return true;
            }
        }
        return false;
    }
}
