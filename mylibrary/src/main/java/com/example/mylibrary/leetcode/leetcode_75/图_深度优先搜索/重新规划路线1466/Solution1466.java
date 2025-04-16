package com.example.mylibrary.leetcode.leetcode_75.图_深度优先搜索.重新规划路线1466;

public class Solution1466 {

    public int minReorder(int n, int[][] connections) {
        int num = 0;
        for (int i = 0;i<connections.length;i++){
            if(containsCity(connections[i], 0)) {
                num +=minReorder(0, i, connections);
            }
        }
        return  num;
    }

    private int minReorder(int targetCity, int currentIndex, int[][] connections) {
        int num = 0;

        for (int i = currentIndex; i < connections.length; i++) {
            int[] connect = connections[i];
            if (containsCity(connect, targetCity)) {
                int nextTargetCity = connect[0];
                if (connect[0] == targetCity) {
                    num++;
                    nextTargetCity = connect[1];
                }
                num += minReorder(nextTargetCity, i+1, connections);
            }  else {
                break;
            }
        }
        return num;
    }

    boolean containsCity(int[] connection, int targetCity) {
        for (int i = 0; i < connection.length; i++) {
            if (targetCity == connection[i]) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        new Solution1466().minReorder(6, new int[][]{
                {0, 1},
                {1, 3},
                {2, 3},
                {4, 0},
                {4, 5}
        });
    }
}
