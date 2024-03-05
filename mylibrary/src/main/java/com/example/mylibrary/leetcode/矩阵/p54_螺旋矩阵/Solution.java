package com.example.mylibrary.leetcode.矩阵.p54_螺旋矩阵;

//import java.util.HashMap;

import java.util.LinkedList;
import java.util.List;
//import java.util.Map;

public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().spiralOrder(
                        new int[][]{
                                new int[]{1, 2, 3},
                                new int[]{4, 5, 6},
                                new int[]{7, 8, 9},
                        }
                )
        );
    }

    public List<Integer> spiralOrder(int[][] matrix) {
        List<Integer> result = new LinkedList<>();
        int m = matrix.length;
        int n = matrix[0].length;
        int sumLen = m * n;
        int i = 0;
//        Map<Turn, Turn> map = new HashMap<>();
//        map.put(Turn.RIGHT, Turn.BOTTOM);
//        map.put(Turn.BOTTOM, Turn.LEFT);
//        map.put(Turn.LEFT, Turn.TOP);
//        map.put(Turn.TOP, Turn.RIGHT);
        Turn status = Turn.RIGHT;
        int right = n - 1, bottom = m - 1, left = 0, top = 0;
        while (i < sumLen) {
            switch (status) {
                case RIGHT: {
                    for (int j = left; j <= right; j++) {
                        i++;
                        result.add(matrix[top][j]);
                    }
                    top++;
                    status = Turn.BOTTOM;
                    break;
                }
                case BOTTOM: {
                    for (int j = top; j <= bottom; j++) {
                        i++;
                        result.add(matrix[j][right]);
                    }
                    right--;
                    status = Turn.LEFT;
                    break;
                }
                case LEFT: {
                    for (int j = right; j >= left; j--) {
                        i++;
                        result.add(matrix[bottom][j]);
                    }
                    bottom--;
                    status = Turn.TOP;
                    break;
                }
                case TOP: {
                    for (int j = bottom; j >= top; j--) {
                        i++;
                        result.add(matrix[j][left]);
                    }
                    left++;
                    status = Turn.RIGHT;
                    break;
                }
            }
        }
        return result;
    }

    enum Turn {
        RIGHT, BOTTOM, LEFT, TOP
    }
}
