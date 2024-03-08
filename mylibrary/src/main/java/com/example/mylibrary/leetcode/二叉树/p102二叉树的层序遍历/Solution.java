package com.example.mylibrary.leetcode.二叉树.p102二叉树的层序遍历;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class Solution {
    public List<List<Integer>> levelOrder(TreeNode root) {
        List<List<Integer>> ans = new LinkedList<>();
        if (root == null) {
            return ans;
        }
        Queue<TreeNode> queue = new LinkedList<>();
        queue.offer(root);
        while (!queue.isEmpty()) {
            int size = queue.size();
            List<Integer> levelAns = new ArrayList<>(size);
            while (size-- > 0) {
                TreeNode node = queue.poll();
                levelAns.add(node.val);
                if (node.left != null) {
                    queue.offer(node.left);
                }
                if (node.right != null) {
                    queue.offer(node.right);
                }
            }
            ans.add(levelAns);
        }
        return ans;
    }
}
