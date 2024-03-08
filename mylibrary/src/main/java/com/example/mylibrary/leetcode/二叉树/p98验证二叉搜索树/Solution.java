package com.example.mylibrary.leetcode.二叉树.p98验证二叉搜索树;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.Deque;
import java.util.LinkedList;

public class Solution {
    final int WHITE = 0;
    final int BLACK = 1;

    public static void main(String[] args) {
        System.out.println(
                new Solution().isValidBST(
                        TreeNode.createTreeNode(5, 4, 6, -1, -1, 3, 7)
                )
        );
    }

    public boolean isValidBST1(TreeNode root) {
        return isValidBST(root, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public boolean isValidBST(TreeNode node, long lower, long upper) {
        if (node == null) {
            return true;
        } else if (node.val > lower && node.val < upper) {
            return isValidBST(node.left, lower, node.val) && isValidBST(node.right, node.val, upper);
        } else {
            return false;
        }
    }

    // 方法2. 使用中序遍历，会是一个升序序列
    public boolean isValidBST(TreeNode root) {

        Deque<Pair> stack = new LinkedList<>();
        stack.push(new Pair(root, BLACK));
        long perVal = Long.MIN_VALUE;
        while (!stack.isEmpty()) {
            Pair curr = stack.poll();
            TreeNode node = curr.first;
            switch (curr.second) {
                case BLACK: {
                    curr.second = WHITE;
                    if (node.right != null) {
                        stack.push(new Pair(node.right, BLACK));
                    }
                    stack.push(curr);
                    if (node.left != null) {
                        stack.push(new Pair(node.left, BLACK));
                    }
                    break;
                }
                case WHITE: {
                    if (node.val <= perVal) {
                        return false;
                    } else {
                        perVal = node.val;
                    }
                }
            }
        }
        return true;
    }

    // 这个解法不对，仅仅保证了根节点大于左子树小于右子树
    private boolean dfs(TreeNode node, int rootVal, boolean state) {
        if (node == null) {
            return true;
        } else if ((state && node.val < rootVal) || (!state && node.val > rootVal)) {
            return dfs(node.left, node.val, true) && dfs(node.right, node.val, false);
        } else {
            return false;
        }
    }

    static class Pair {
        TreeNode first;
        int second;

        Pair(TreeNode node, int state) {
            this.first = node;
            this.second = state;
        }
    }
}