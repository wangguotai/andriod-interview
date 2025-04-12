package com.example.mylibrary.leetcode.leetcode_75.二叉树_二叉搜索树.二叉搜索树中的搜索;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

public class Solution700 {
    public TreeNode searchBST(TreeNode root, int val) {
        if(root == null || root.val == val) {
            return root;
        }
        if(root.val < val) {
            return searchBST(root.right, val);
        } else {
            return searchBST(root.left, val);
        }
    }
}
