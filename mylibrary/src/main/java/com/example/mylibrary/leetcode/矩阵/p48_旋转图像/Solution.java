package com.example.mylibrary.leetcode.矩阵.p48_旋转图像;

public class Solution {
    /**
     * 方法1. 辅助矩阵
     * 旋转后: (i,j)=>(j,n-i-1)
     *
     * @param matrix
     */
    public void rotate1(int[][] matrix) {
        int m = matrix.length;
        int n = matrix[0].length;
        int[][] temp = new int[m][n];
        for (int i = 0; i < m; i++) {
            temp[i] = new int[n];
        }
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                temp[j][n - i - 1] = matrix[i][j];
            }
        }
        System.arraycopy(temp, 0, matrix, 0, m);
    }

    /**
     * 原地旋转
     *
     * @param matrix
     */
    public void rotate(int[][] matrix) {
        int m = matrix.length;
        int n = matrix[0].length;
        for (int i = 0; i < m / 2; i++) {
            for (int j = 0; j < (n + 1) / 2; j++) {
                int temp = matrix[i][j];
                matrix[i][j] = matrix[n - j - 1][i];
//                matrix[n - j - 1][i] = temp;
                matrix[n - j - 1][i] = matrix[n - i - 1][n - j - 1];
                matrix[n - i - 1][n - j - 1] = matrix[j][n - i - 1];
                matrix[j][n - i - 1] = temp;
            }
        }
    }
}
