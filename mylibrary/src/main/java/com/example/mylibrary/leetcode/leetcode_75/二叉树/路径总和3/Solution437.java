package com.example.mylibrary.leetcode.leetcode_75.二叉树.路径总和3;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.Deque;
import java.util.LinkedList;


public class Solution437 {
    public int pathSum(TreeNode root, int targetSum) {
        if (root == null) {
            return 0;
        }
        int pathsFromRoot = countPaths(root, targetSum);
        int pathsInLeft = countPaths(root.left, targetSum);
        int pathsInRight = countPaths(root.right, targetSum);
        return pathsInRight + pathsInLeft + pathsFromRoot;
    }
    int countPaths(TreeNode node, long remainingSum) {
        if(node == null) {
            return 0;
        }
        int paths = 0;
        if(node.val == remainingSum){
            paths++;
        }
        paths += countPaths(node.left, remainingSum - node.val);
        paths += countPaths(node.right, remainingSum - node.val);
        return paths;
    }



    public static void main(String[] args) {
        new Solution437().pathSum(new TreeNode(-2, null, new TreeNode(-3)), -5);
    }

}
