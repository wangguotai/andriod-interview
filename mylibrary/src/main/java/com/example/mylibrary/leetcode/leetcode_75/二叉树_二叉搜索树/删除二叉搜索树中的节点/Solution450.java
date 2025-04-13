package com.example.mylibrary.leetcode.leetcode_75.二叉树_二叉搜索树.删除二叉搜索树中的节点;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

public class Solution450 {
    public TreeNode deleteNode(TreeNode root, int key) {
        if(root == null){
            return root;
        }
        if(root.val < key) {
            root.right = deleteNode(root.right, key);
        } else if(root.val > key){
            root.left = deleteNode(root.left, key);
        } else {
            if(root.left == null) {
                root = root.right;
            } else if(root.right == null) {
                root = root.left;
            } else {
                root.val = minValue(root.right);
                root.right = deleteNode(root.right, root.val);
            }
        }
        return root;
    }
    private int minValue(TreeNode root) {
        int minV = root.val;
        while (root.left != null) {
            minV = root.left.val;
            root = root.left;
        }
        return minV;
    }
    public static void main(String[] args) {
        new Solution450().deleteNode(
                TreeNode.createTreeNode(5,3,6,2,4,-1,7), 3
        );
    }
}
