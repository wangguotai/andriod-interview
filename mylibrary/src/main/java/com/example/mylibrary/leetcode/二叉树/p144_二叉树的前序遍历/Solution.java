package com.example.mylibrary.leetcode.二叉树.p144_二叉树的前序遍历;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.LinkedList;
import java.util.List;
import java.util.Stack;

/**
 * 前序遍历一颗二叉树
 */
public class Solution {

    public List<Integer> preorderTraversal(TreeNode root) {
        List<Integer> result = new LinkedList<>();

        if (root == null) {
            return result;
        }
        // 使用递归
//        recursionBiTree(root, result);
        // 使用迭代
        iterativePreorderTraversal(root, result);
        return result;
    }

    // 方法1. 通过递归的方式遍历二叉树
    private void recursionPreorderTraversal(TreeNode root, List<Integer> result) {
        if (root != null) {
            result.add(root.val);
            recursionPreorderTraversal(root.left, result);
            recursionPreorderTraversal(root.right, result);
        }
    }

    // 方法2. 通过迭代遍历二叉树
    private void iterativePreorderTraversal(TreeNode root, List<Integer> result) {
        if (root == null) {
            return;
        }
        Stack<TreeNode> stack = new Stack<>();
        stack.push(root);
        while (stack.size() > 0) {
            TreeNode curr = stack.pop();
            result.add(curr.val);
            if (curr.right != null) {
                stack.push(curr.right);
            }
            if (curr.left != null) {
                stack.push(curr.left);
            }
        }
    }

    public static void main(String[] args) throws IllegalAccessException {
        TreeNode root = TreeNode.createTreeNode(1, -1, 2, 3);
        System.out.println(new Solution().preorderTraversal(root));
    }
}
