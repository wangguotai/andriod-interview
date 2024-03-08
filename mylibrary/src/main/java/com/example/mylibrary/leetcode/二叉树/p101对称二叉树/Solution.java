package com.example.mylibrary.leetcode.二叉树.p101对称二叉树;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.Deque;
import java.util.LinkedList;

public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().isSymmetric(
                        TreeNode.createTreeNode(1, 2, 2, 3, 4, 4, 3)
                )
        );
    }

    public boolean isSymmetric1(TreeNode root) {
        if (root.left == null && root.right == null) {
            return true;
        } else if (root.left == null || root.right == null) {
            return false;
        }

        Deque<TreeNode> queue = new LinkedList<>();
        queue.offer(root.left);
        queue.offer(root.right);
        while (!queue.isEmpty()) {
            TreeNode first = queue.pollFirst();
            TreeNode end = queue.pollLast();
            if (first == null && end == null) {
                continue;
            } else if (first == null || end == null) {
                return false;
            } else if (first.val != end.val) {
                return false;
            }
            if (first.left != null || first.right != null) {
                queue.offerFirst(first.right);
                queue.offerFirst(first.left);
            }
            if (end.left != null || end.right != null) {
                queue.offerLast(end.left);
                queue.offerLast(end.right);
            }
        }
        return true;
    }

    public boolean isSymmetric(TreeNode root) {
        return isSymmetric(root.left, root.right);
    }

    private boolean isSymmetric(TreeNode left, TreeNode right) {
        if (left == null && right == null) {
            return true;
        } else if (left == null || right == null) {
            return false;
        } else if (left.val != right.val) {
            return false;
        } else {
            return isSymmetric(left.left, right.right) && isSymmetric(left.right, right.left);
        }
    }
}
