package com.example.mylibrary.leetcode.回溯.p79单词搜索;

import java.util.HashMap;
import java.util.Map;

public class Solution {
    private final Map<Integer, Integer> marked = new HashMap<>();
    private int m;
    private int n;
    private char[][] board;
    private boolean result = false;
    private String word;
    private int len;
    private int[][] d = new int[][]{
            new int[]{1, 0},
            new int[]{-1, 0},
            new int[]{0, 1},
            new int[]{0, -1},
    };
    private StringBuilder ans = new StringBuilder();

    public static void main(String[] args) {
        System.out.println(
                new Solution().exist(new char[][]{
                        new char[]{'A', 'B', 'C', 'E'},
                        new char[]{'S', 'F', 'C', 'S'},
                        new char[]{'A', 'D', 'E', 'E'},
                }, "ABCCED")
//                new Solution().exist(
//                        new char[][]{
//                                new char[]{'a'}
//                        }, "a"
//                )
        );
    }

    public boolean exist(char[][] board, String word) {
        this.m = board.length;
        this.n = board[0].length;
        this.board = board;
        this.word = word;
        this.len = word.length();
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                backTrack(i, j, 0);
                if (result) return true;
            }
        }
        return false;
    }

    private void backTrack(int i, int j, int index) {
        if (index == len && !result) {
            result = true;
            return;
        }
        if (!isOutSide(i, j) && marked.get(i * n + j) == null && board[i][j] == word.charAt(index)) {
            marked.put(i * n + j, 1);
            ans.append(board[i][j]);
            for (int k = 0; k < d.length; k++) {
                int x = i + d[k][0];
                int y = j + d[k][1];
                backTrack(x, y, index + 1);
            }
            ans.deleteCharAt(ans.length() - 1);
            marked.remove(i * n + j);
        }
    }

    private boolean isOutSide(int i, int j) {
        return i < 0 || i >= m || j < 0 || j >= n;
    }
}