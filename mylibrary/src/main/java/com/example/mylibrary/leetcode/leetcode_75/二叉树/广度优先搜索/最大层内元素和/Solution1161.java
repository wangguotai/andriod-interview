package com.example.mylibrary.leetcode.leetcode_75.二叉树.广度优先搜索.最大层内元素和;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.Deque;
import java.util.LinkedList;

public class Solution1161 {
    public int maxLevelSum(TreeNode root) {
        int levelIndex = 0;
        int maxLevel = 1;
        int maxSum = Integer.MIN_VALUE;
        Deque<TreeNode> queue = new LinkedList<>();
        queue.offer(root);
        while(!queue.isEmpty()) {
            levelIndex++;
            int size = queue.size();
            int sum = 0;
            while(size-->0) {
                TreeNode node = queue.poll();
                sum += node.val;
                if(node.left!=null) {
                    queue.offer(node.left);
                }
                if(node.right!=null){
                    queue.offer(node.right);
                }
            }
             if(sum > maxSum){
                 maxSum = sum;
                 maxLevel = levelIndex;
             }
        }
        return maxLevel;
    }
}
