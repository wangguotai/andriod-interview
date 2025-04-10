package com.example.mylibrary.leetcode.leetcode_75.二叉树.统计二叉树中好节点的数目1448;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

public class Solution1448 {
    public int goodNodes(TreeNode root) {
        return dfs(root, root.val);
    }
    int dfs(TreeNode node, int maxVal){
        int num = 0;
        if(node.val>=maxVal){
            num++;
            maxVal = Math.max(node.val, maxVal);
        }
        if(node.left!=null){
            num += dfs(node.left, maxVal);
        }
        if(node.right!=null){
            num += dfs(node.right, maxVal);
        }
        return num;

    }
}
