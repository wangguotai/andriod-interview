package com.example.mylibrary.leetcode.二叉树.p94_二叉树的中序遍历;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

public class Solution2 {
    public static void main(String[] args) throws IllegalAccessException {
        new Solution().inorderTraversal(TreeNode.createTreeNode(
                1, -1, 2, 3
        ));
    }

    /**
     * Definition for a binary tree node.
     * public class TreeNode {
     * int val;
     * TreeNode left;
     * TreeNode right;
     * TreeNode() {}
     * TreeNode(int val) { this.val = val; }
     * TreeNode(int val, TreeNode left, TreeNode right) {
     * this.val = val;
     * this.left = left;
     * this.right = right;
     * }
     * }
     */
    static class Solution {
        final int WHITE = 0;
        final int BLACK = 1;

        public List<Integer> inorderTraversal(TreeNode root) {
            List<Integer> result = new LinkedList<>();
            if (root == null) {
                return result;
            }
            Deque<Pair> statck = new LinkedList<>();
            statck.push(new Pair(root, BLACK));
            while (!statck.isEmpty()) {
                Pair curr = statck.poll();
                TreeNode node = curr.first;
                switch (curr.second) {
                    case WHITE: {
                        result.add(node.val);
                        break;
                    }
                    case BLACK: {
                        curr.second = WHITE;
                        if (node.right != null) {
                            statck.push(new Pair(node.right, BLACK));
                        }
                        statck.push(curr);
                        if (node.left != null) {
                            statck.push(new Pair(node.left, BLACK));
                        }
                        break;
                    }
                }
            }
            return result;
        }

        class Pair {
            TreeNode first;
            int second;

            public Pair(TreeNode node, int status) {
                first = node;
                second = status;
            }
        }
    }
}
