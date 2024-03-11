package com.example.mylibrary.leetcode.二叉树;


import java.util.LinkedList;
import java.util.Queue;

public class TreeNode {
    public int val;
    public TreeNode left;
    public TreeNode right;

    TreeNode() {
    }

    public TreeNode(int val) {
        this.val = val;
    }

    TreeNode(int val, TreeNode left, TreeNode right) {
        this.val = val;
        this.left = left;
        this.right = right;
    }

    public static TreeNode createTreeNode(int... levelOrder) {
        TreeNode root = new TreeNode(levelOrder[0]);
        Queue<TreeNode> queue = new LinkedList<>();
        queue.offer(root);
        int i = 1;
        while (!queue.isEmpty() && i < levelOrder.length) {
            TreeNode current = queue.poll();
            if (levelOrder[i] != -1) {
                current.left = new TreeNode(levelOrder[i]);
                queue.offer(current.left);
            }
            i++;
            if (i < levelOrder.length && levelOrder[i] != -1) {
                current.right = new TreeNode(levelOrder[i]);
                queue.offer(current.right);
            }
            i++;
        }
        return root;
    }

    public static TreeNode createTreeNode(TreeNode p, TreeNode q, int... levelOrder) {
        TreeNode root;
        if (levelOrder[0] == p.val) {
            root = p;
        } else if (levelOrder[0] == q.val) {
            root = q;
        } else {
            root = new TreeNode(levelOrder[0]);
        }
        Queue<TreeNode> queue = new LinkedList<>();
        queue.offer(root);
        int i = 1;
        while (!queue.isEmpty() && i < levelOrder.length) {
            TreeNode current = queue.poll();
            if (levelOrder[i] != -1) {
                if (levelOrder[i] == p.val) {
                    current.left = p;
                } else if (levelOrder[i] == q.val) {
                    current.left = q;
                } else {
                    current.left = new TreeNode(levelOrder[i]);
                }
                queue.offer(current.left);
            }
            i++;
            if (i < levelOrder.length && levelOrder[i] != -1) {
//                current.right = new TreeNode(levelOrder[i]);
                if (levelOrder[i] == p.val) {
                    current.right = p;
                }
                if (levelOrder[i] == q.val) {
                    current.right = q;
                } else {
                    current.right = new TreeNode(levelOrder[i]);
                }
                queue.offer(current.right);
            }
            i++;
        }
        return root;
    }
}
