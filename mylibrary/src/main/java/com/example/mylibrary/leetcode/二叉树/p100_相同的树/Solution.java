package com.example.mylibrary.leetcode.二叉树.p100_相同的树;

/**
 * Time: 2024/3/4
 * Author: wgt
 * Description:
 */

import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.Deque;
import java.util.LinkedList;

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
public class Solution {
    public boolean isSameTree(TreeNode p, TreeNode q) {
        // 利用广度优先搜索
        Deque<TreeNode> queue1 = new LinkedList<>();
        Deque<TreeNode> queue2 = new LinkedList<>();
        queue1.offer(p);
        queue2.offer(q);
        while (!queue1.isEmpty() && !queue2.isEmpty()) {
            TreeNode currP = queue1.poll();
            TreeNode currQ = queue2.poll();
            if (currP == null && currQ == null) {
                continue;
            } else if (currP == null && currQ != null || currQ == null && currP != null) {
                return false;
            } else {
                if (currP.val == currQ.val) {
                    if (!isSameNode(currP.left, currQ.left) || !isSameNode(currP.right, currQ.right)) {
                        return false;
                    }
                    if (currP.left != null) {
                        queue1.offer(currP.left);
                        queue1.offer(currP.right);
                    }
                    if (currQ.right != null) {
                        queue2.offer(currQ.left);
                        queue2.offer(currQ.right);
                    }
                }
            }
        }
        return true;
    }

    private boolean isSameNode(TreeNode node1, TreeNode node2) {
        if ((node2 == null && node1 != null) || (node1 == null && node2 != null)) {
            return false;
        }
        if (node2 == null && node1 == null) {
            return true;
        }
        if (node1.val == node2.val) {
            return true;
        }
        return false;
    }
}