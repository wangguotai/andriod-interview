package com.example.mylibrary.leetcode.leetcode_75.图_深度优先搜索.除法求职399;

import java.util.*;

public class Solution399 {
    public double[] calcEquation(List<List<String>> equations, double[] values, List<List<String>> queries){
        // 构建有向图
        Map<String, Map<String, Double>> graph = new HashMap<>();
        for (int i=0;i<equations.size();i++) {
            String u = equations.get(i).get(0);
            String v = equations.get(i).get(1);
            double val = values[i];
            graph.putIfAbsent(u, new HashMap<>());
            graph.putIfAbsent(v, new HashMap<>());
            graph.get(u).put(v, val);
            graph.get(v).put(u, 1/val);
        }
        
        double[] result = new double[queries.size()];

        for (int i = 0; i < queries.size(); i++) {
            String u = queries.get(i).get(0);
            String v = queries.get(i).get(1);
            if(!graph.containsKey(u) || !graph.containsKey(v)) {
                result[i] = -1.0;
            } else if (u.equals(v)) {
                result[i] = 1.0;
            } else {
                Set<String> visited = new HashSet<>();
                result[i] = dfs(graph, u, v, visited, 1.0);
            }
        }
        return result;
    }

    private double dfs(Map<String, Map<String, Double>> graph, String u, String v, Set<String> visited, double acc) {
        if(u.equals(v)) {
            return acc;
        }
        visited.add(u);
        Map<String,Double> neighbors = graph.get(u);
        for(String neighbor: neighbors.keySet()){
            if(!visited.contains(neighbor)) {
                double newAcc = acc * neighbors.get(neighbor);
                double result = dfs(graph, neighbor, v, visited, newAcc);
                if(result!=-1.0) {
                    return result;
                }
            }
        }
        return -1.0;

    }


}
