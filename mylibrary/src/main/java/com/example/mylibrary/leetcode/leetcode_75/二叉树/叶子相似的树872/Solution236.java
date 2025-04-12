package com.example.mylibrary.leetcode.leetcode_75.二叉树.叶子相似的树872;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.Deque;
import java.util.LinkedList;

public class Solution236 {
    public TreeNode lowestCommonAncestor(TreeNode root, TreeNode p, TreeNode q) {
        // 如果当前节点为空，或者当前节点等于p或q，返回当前节点
        if (root == null || root == p || root == q) {
            return root;
        }
        // 递归处理左子树
        TreeNode left = lowestCommonAncestor(root.left, p, q);
        // 递归处理右子树
        TreeNode right = lowestCommonAncestor(root.right, p, q);
        // 如果左子树和右子树都能找到p或q，说明当前节点是最近公共祖先；
        if (left != null && right != null) {
            return root;
        }
        // 如果左子树能够找到p或q，返回左子树的结果
        if (left != null){
            return left;
        }
        // 否则返回右子树的结果
        return right;
    }

    public static void main(String[] args) {
        TreeNode root = new TreeNode(3);
        root.left = new TreeNode(5);
        root.right = new TreeNode(1);
        root.left.left = new TreeNode(6);
        root.left.right = new TreeNode(2);
        root.right.left = new TreeNode(0);
        root.right.right = new TreeNode(8);
        root.left.right.left = new TreeNode(7);
        root.left.right.right = new TreeNode(4);
        TreeNode p = root.left;
        TreeNode q = root.right;
        new Solution236().lowestCommonAncestor(
                root, p, q
        );
    }
}
