package com.example.mylibrary.leetcode.二叉树.p543二叉树的直径;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

public class Solution {
    int ans;

    public int diameterOfBinaryTree(TreeNode root) {
        ans = 1;
        depth(root);
        return ans - 1;
    }

    public int depth(TreeNode node) {
        if (node == null) {
            return 0;
        }
        int L = depth(node.left);
        int R = depth(node.right);
        ans = Math.max(ans, L + R + 1);
        return Math.max(L, R) + 1;
    }
}
