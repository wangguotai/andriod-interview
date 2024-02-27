package com.example.mylibrary.leetcode.二叉树.p94_二叉树的中序遍历.p144_二叉树的前序遍历;


import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Stack;

/**
 * 前序遍历一颗二叉树
 */
public class Solution {

    public List<Integer> inorderTraversal(TreeNode root) {
        List<Integer> result = new LinkedList<>();

        if (root == null) {
            return result;
        }
        // 使用递归
//        recursionInorderTraversal(root, result);
        // 使用迭代
        iterativeInorderTraversal(root, result);
        return result;
    }

    private void recursionInorderTraversal(TreeNode root, List<Integer> result) {
        if (root != null) {
            recursionInorderTraversal(root.left, result);
            result.add(root.val);
            recursionInorderTraversal(root.right, result);
        }
    }

    // 方法2. 通过迭代遍历二叉树
    private void iterativeInorderTraversal(TreeNode root, List<Integer> result) {
        if (root == null) {
            return;
        }
        traversalWithTag(root, result);
    }

    /**
     * Leetcode中有人给出的颜色标记法
     *
     * @param root
     * @param result
     */

    private void traversalWithTag(TreeNode root, List<Integer> result) {
        if (root == null) {
            return;
        }
        int WHITE = 0;
        int GREY = 1;
        Deque<Pair<Integer, TreeNode>> stack = new LinkedList<>();
        stack.push(new Pair<>(WHITE, root));
        while (!stack.isEmpty()) {
            Pair<Integer, TreeNode> curr = stack.pop();
            if (curr.second == null) {
                continue;
            }
            if (curr.first == WHITE) {
                stack.push(new Pair<>(WHITE, curr.second.right));
                stack.push(new Pair<>(GREY, curr.second));
                stack.push(new Pair<>(WHITE, curr.second.left));
            } else {
                result.add(curr.second.val);
            }
        }

    }

    class Pair<T, S> {
        public T first;
        public S second;
        public Pair(T first, S second){
            this.first = first;
            this.second = second;
        }
    }

    public static void main(String[] args) throws IllegalAccessException {
        TreeNode root = TreeNode.createTreeNode(1, -1, 2, 3);
        System.out.println(new Solution().inorderTraversal(root));
    }
}
