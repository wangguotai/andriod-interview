package com.example.mylibrary.leetcode.矩阵.p73_矩阵置零;

import java.util.Arrays;

public class Solution {
    public void setZeros1(int[][] matrix) {
        int m = matrix.length;
        int n = matrix[0].length;
        int[][] mark = new int[m][n];
        for (int i = 0; i < m; i++) {
            mark[i] = new int[n];
        }
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                if (matrix[i][j] == 0) {
                    Arrays.fill(mark[i], -1);
                    setColumn(mark, j, -1);
                }
            }
        }
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                if (mark[i][j] == -1) {
                    matrix[i][j] = 0;
                }
            }
        }
    }

    private void setColumn(int[][] arr, int j, int value) {
        for (int i = 0; i < arr.length; i++) {
            arr[i][j] = value;
        }
    }

    private void setZeroes(int[][] matrix) {
        int m = matrix.length;
        int n = matrix[0].length;
        boolean flagColunm = false;
        for (int i = 0; i < m; i++) {
            if (matrix[i][0] == 0) {
                flagColunm = true;
            }
            for (int j = 1; j < n; j++) {
                if (matrix[i][j] == 0) {
                    matrix[i][0] = matrix[0][j] = 0;
                }
            }
        }
        for (int i = m - 1; i >= 0; i--) {
            for (int j = 1; j < n; j++) {
                if (matrix[i][0] == 0 || matrix[0][j] == 0) {
                    matrix[i][j] = 0;
                }
                if (flagColunm) {
                    matrix[i][0] = 0;
                }
            }
        }
    }
}
