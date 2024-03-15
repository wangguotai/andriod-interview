package com.example.mylibrary.leetcode.回溯.p51N皇后;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Time: 2024/3/14
 * Author: wgt
 * Description:
 */
public class Solution1 {
    private List<List<String>> ans = new LinkedList<>();
    private char[][] chessboard;
    private boolean[][] marked;
    private int n;
    private int[][] d = new int[][]{
            new int[]{2, 1},
            new int[]{2, -1},
            new int[]{-2, 1},
            new int[]{-2, -1},

            new int[]{1, -2},
            new int[]{-1, -2},
            new int[]{1, 2},
            new int[]{-1, 2},
    };

    public static void main(String[] args) {
        System.out.println(
                new Solution1().solveNQueens(4)
        );
    }

    public List<List<String>> solveNQueens(int n) {
        chessboard = new char[n][n];
        for (int i = 0; i < n; i++) {
            Arrays.fill(chessboard[i], '.');
        }
        marked = new boolean[n][n];
        this.n = n;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                backTrace(0, i, j);
            }
        }
        return ans;
    }

    private void backTrace(int index, int i, int j) {
        if (index == n + 1) {
            ans.add(getChessboard());
            return;
        }
        if (isOutOfBoard(i, j)) {
            return;
        }
//        if(!marked[i][j]){

//            for (int k = 0; k < d.length; k++) {
//                int x = i + d[k][0];
//                int y = j + d[k][1];
//
//            }
        for (int k = 0; k < n; k++) {
            for (int l = 0; l < n; l++) {
                if (!marked[k][l]) {
                    setMarked(k, l, true);
                    chessboard[k][l] = 'Q';
                    backTrace(index + 1, k, l);
                    chessboard[k][l] = '.';
                    setMarked(k, l, false);
                }
            }
        }

//        }
    }

    private boolean isOutOfBoard(int r, int c) {
        return r < 0 || r >= n || c < 0 || c >= n;
    }

    private List<String> getChessboard() {
        List<String> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < n; j++) {
                sb.append(chessboard[i][j]);
            }
            result.add(sb.toString());
        }
        return result;
    }

    private void setMarked(int r, int c, boolean value) {
        backTraceForMark(r, c, -1, -1, value);
        backTraceForMark(r, c, -1, 1, value);
        backTraceForMark(r, c, 1, 1, value);
        backTraceForMark(r, c, 1, -1, value);
        for (int i = 0; i < n; i++) {
            marked[r][i] = value;
            marked[i][c] = value;
        }
    }

    private void backTraceForMark(int i, int j, int dx, int dy, boolean value) {
        if (isOutOfBoard(i, j)) {
            return;
        }
        marked[i][j] = value;
        backTraceForMark(i + dx, i + dy, dx, dy, value);
    }
}
