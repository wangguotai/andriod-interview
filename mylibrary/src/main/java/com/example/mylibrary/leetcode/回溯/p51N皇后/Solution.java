package com.example.mylibrary.leetcode.回溯.p51N皇后;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class Solution {
    List<List<String>> ans = new LinkedList<>();
    int[] queens;
    int n;
    Set<Integer> columns = new HashSet<>();
    Set<Integer> diagonals1 = new HashSet<>();
    Set<Integer> diagonals2 = new HashSet<>();

    public static void main(String[] args) {
        System.out.println(
                new Solution().solveNQueens(4)
        );
    }

    public List<List<String>> solveNQueens(int n) {
        this.queens = new int[n];
        this.n = n;
        Arrays.fill(queens, -1);
        backTrack(0);
        return ans;
    }

    private void backTrack(int row) {
        if (row == n) {
            ans.add(chessBoardToList());
        }
        for (int i = 0; i < n; i++) {
            if (!columns.contains((i)) && !diagonals1.contains(row + i) && !diagonals2.contains(row - i)) {
                columns.add(i);
                diagonals1.add(row + i);
                diagonals2.add(row - i);
                queens[row] = i;
                backTrack(row + 1);
                columns.remove(i);
                diagonals1.remove(row + i);
                diagonals2.remove(row - i);
                queens[row] = -1;
            }
        }
    }

    private List<String> chessBoardToList() {
        List<String> result = new ArrayList<>(n);

        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < n; j++) {
                if (queens[i] == j) {
                    sb.append('Q');
                } else {
                    sb.append('.');
                }
            }
            result.add(sb.toString());
        }
        return result;
    }
}
