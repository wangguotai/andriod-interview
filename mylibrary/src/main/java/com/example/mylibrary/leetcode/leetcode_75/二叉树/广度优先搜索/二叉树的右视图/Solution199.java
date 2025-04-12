package com.example.mylibrary.leetcode.leetcode_75.二叉树.广度优先搜索.二叉树的右视图;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

public class Solution199 {
    public List<Integer> rightSideView(TreeNode root) {
        List<Integer> result = new LinkedList<>();

        if(root == null) {
            return result;
        }
        Deque<TreeNode> queue = new LinkedList<>();
        queue.offer(root);
        while(!queue.isEmpty()){
            int size = queue.size();
            boolean addLeast = false;
            while(size-->0) {
                TreeNode node = queue.poll();
                if(!addLeast) {
                    result.add(node.val);
                    addLeast = true;
                }
                if(node.right!=null) {
                    queue.offer(node.right);
                }
                if(node.left!=null) {
                    queue.offer(node.left);
                }
            }
        }
        return result;
    }
}
