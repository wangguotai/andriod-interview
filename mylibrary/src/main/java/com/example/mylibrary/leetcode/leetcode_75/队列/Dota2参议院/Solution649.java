package com.example.mylibrary.leetcode.leetcode_75.队列.Dota2参议院;

import java.util.LinkedList;
import java.util.Queue;

public class Solution649 {
    public String predictPartyVictory(String senate) {
        int n = senate.length();
        // 存储天辉阵营参议院索引的队列
        Queue<Integer> radiantQueue = new LinkedList<>();
        // 存储夜魇阵营参议员索引的队列
        Queue<Integer> direQueue = new LinkedList<>();
        // 将参议员的索引根据阵营加入对应的队列
        for (int i = 0; i < n; i++) {
            if(senate.charAt(i) == 'R'){
                radiantQueue.add(i);
            } else {
                direQueue.add(i);
            }
        }
        // 模拟投票过程，直到有一个阵营的参议院全部被淘汰
        while(!radiantQueue.isEmpty() && !direQueue.isEmpty()){
            int radiantIndex = radiantQueue.poll();
            int direIndex = direQueue.poll();

            if(radiantIndex < direIndex){
                // 天辉阵营的参议员先行使权利，将其加入下一轮投票队列
                radiantQueue.add(radiantIndex + n);
            } else {
                // 夜魇阵营的参议员先行使权利，将其加入下一轮投票队列
                direQueue.add(direIndex + n);
            }
        }
        // 返回获胜阵营
        return radiantQueue.isEmpty() ? "Dire" : "Radiant";
    }
}
