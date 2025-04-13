package com.example.mylibrary.leetcode.leetcode_75.图_深度优先搜索.钥匙和房间841;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class Solution841 {
    public boolean canVisitAllRooms(List<List<Integer>> rooms) {
        int len = rooms.size();
        int[] visited = new int[len];
        visited[0] = 1;
        dfs(rooms, visited, 0);
        for (int i = 0; i < len; i++) {
            if(visited[i] == 0) {
                return false;
            }
        }
        return true;
    }
    private void dfs(List<List<Integer>>rooms, int[]visited, int index){
        List<Integer> keys = rooms.get(index);
        for(int key : keys) {
            if(visited[key] == 0) {
                visited[key] = 1;
                dfs(rooms, visited, key);
            }
        }
    }

    public static void main(String[] args) {
        new Solution841().canVisitAllRooms(new LinkedList<>());
    }
}
