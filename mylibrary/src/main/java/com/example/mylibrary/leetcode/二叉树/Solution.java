package com.example.mylibrary.leetcode.二叉树;

import java.util.HashMap;
import java.util.Map;

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
    // int p1;
    // int p2;
    // int i1;
    // int i2;
    // int[] p,i;

    // public TreeNode buildTree(int[] preorder, int[] inorder) {
    //     p1 = 0;
    //     p2 = preorder.length-1;
    //     i1=0;
    //     i2=inorder.length-1;
    //     return dfs();
    // }
    // private TreeNode dfs(){
    //     if(p1<=p2){
    //         TreeNode root = new TreeNode(p[p1]);
    //     }
    // }
    Map<Integer, Integer> map = new HashMap<>();

    public static void main(String[] args) {
        new Solution().buildTree(
                new int[]{
                        3, 9, 20, 15, 7
                },
                new int[]{
                        9, 3, 15, 20, 7
                }
        );
    }

    public TreeNode buildTree(int[] preorder, int[] inorder) {
        for (int i = 0; i < inorder.length; i++) {
            map.put(inorder[i], i);
        }
        return buildTree(preorder, 0, preorder.length - 1, inorder, 0, inorder.length - 1);
    }

    public TreeNode buildTree(int[] preorder, int p1, int p2, int[] inorder, int i1, int i2) {
        if (p1 <= p2) {
            TreeNode root = new TreeNode(preorder[p1]);
            // 得到中序遍历中根节点下标
            int mid = map.get(preorder[p1]);
            int l2 = p1 + (mid - i1);
            root.left = buildTree(preorder, p1 + 1, l2, inorder, i1, mid - 1);
            root.right = buildTree(preorder, l2 + 1, p2, inorder, mid + 1, i2);
            return root;
        }
        return null;
    }
}
