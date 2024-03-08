package com.example.mylibrary.leetcode.二叉树.p108将有序数组转化为二叉搜索树;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

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
class Solution {
    public TreeNode sortedArrayToBST(int[] nums) {
        return merge(nums, 0, nums.length - 1);
    }

    private TreeNode merge(int[] nums, int start, int end) {
        if (start > end) {
            return null;
        }
        if (start == end) {
            return new TreeNode(nums[start]);
        }
        int mid = (start + end) / 2;
        TreeNode root = new TreeNode(nums[mid]);
        root.left = merge(nums, start, mid - 1);
        root.right = merge(nums, mid + 1, end);
        return root;
    }
}