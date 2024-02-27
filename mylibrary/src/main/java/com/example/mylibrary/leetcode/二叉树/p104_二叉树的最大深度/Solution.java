package com.example.mylibrary.leetcode.二叉树.p104_二叉树的最大深度;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.Deque;
import java.util.LinkedList;

/**
 * Time: 2024/2/27
 * Author: wgt
 * Description:
 */
public class Solution {
    // 方法1. 使用dfs解决问题
    int depth = 0;

    public static void main(String[] args) throws IllegalAccessException {
        int depth = new Solution().myMaxDepth(
                TreeNode.createTreeNode(3, 9, 20, -1, -1, 15, 7)
        );
        System.out.println(depth);
    }

    public int myMaxDepth(TreeNode root) {
        if (root == null) {
            return 0;
        }
        dfs(root, 1);
        return depth;
    }

    private void dfs(TreeNode node, int curDepth) {
        if (node.left != null) {
            dfs(node.left, curDepth + 1);
        } else {
            if (depth < curDepth) {
                depth = curDepth;
            }
        }
        if (node.right != null) {
            dfs(node.right, curDepth + 1);
        } else if (depth < curDepth) {
            depth = curDepth;
        }
    }

    // 方法2. 使用队列广度优先搜索
    public int maxDepth(TreeNode root) {
        if (root == null) {
            return 0;
        }
        // 构建搜索队列
        Deque<TreeNode> treeNodeDeque = new LinkedList<>();
        treeNodeDeque.offer(root);
        int res = 0;
        while (!treeNodeDeque.isEmpty()) {
            int size = treeNodeDeque.size();
            while (size > 0) {
                TreeNode node = treeNodeDeque.poll();
                // 将下一层入队
                if (node.left != null) {
                    treeNodeDeque.offer(node.left);
                }
                if (node.right != null) {
                    treeNodeDeque.offer(node.right);
                }
                // 当前层出队列
                size--;
            }
            res++;
        }
        return res;
    }
}
