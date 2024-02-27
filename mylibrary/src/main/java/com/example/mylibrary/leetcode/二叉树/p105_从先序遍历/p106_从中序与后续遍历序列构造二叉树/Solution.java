package com.example.mylibrary.leetcode.二叉树.p105_从先序遍历.p106_从中序与后续遍历序列构造二叉树;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.HashMap;
import java.util.Map;

/**
 * 给定两个整数数组 preorder 和 inorder ，其中 preorder 是二叉树的先序遍历， inorder 是同一棵树的中序遍历，请构造二叉树并返回其根节点。
 * <p>
 * <p>
 * <p>
 * 示例 1:
 * <p>
 * <p>
 * 输入: preorder = [3,9,20,15,7], inorder = [9,3,15,20,7]
 * 输出: [3,9,20,null,null,15,7]
 * 示例 2:
 * <p>
 * 输入: preorder = [-1], inorder = [-1]
 * 输出: [-1]
 * <p>
 * <p>
 * 提示:
 * <p>
 * 1 <= preorder.length <= 3000
 * inorder.length == preorder.length
 * -3000 <= preorder[i], inorder[i] <= 3000
 * preorder 和 inorder 均 无重复 元素
 * inorder 均出现在 preorder
 * preorder 保证 为二叉树的前序遍历序列
 * inorder 保证 为二叉树的中序遍历序列
 */
public class Solution {

    int[] preorder;
    int[] inorder;
    int preIndex; // 存储根节点的下标
    Map<Integer, Integer> inIndexes;

    public static void main(String[] args) {
        TreeNode root = new Solution().buildTree(
                new int[]{3, 9, 20, 15, 7},
                new int[]{9, 3, 15, 20, 7}
        );
        System.out.println(root);
    }

    /**
     * 前序遍历第一个结点会是根节点
     * 中序遍历会将二叉树分为两个子树
     *
     * @param preorder
     * @param inorder
     * @return
     */
    public TreeNode buildTree(int[] preorder, int[] inorder) {
        this.preorder = preorder;
        this.inorder = inorder;
        this.preIndex = 0;
        this.inIndexes = new HashMap<>(inorder.length);
        for (int i = 0; i < inorder.length; i++) {
            inIndexes.put(inorder[i], i);
        }
        return helper(0, inorder.length - 1);
    }

    public TreeNode helper(int inLeft, int inRight) {
        if (inLeft > inRight) {
            return null;
        }
        // 获取根节点及下标
        int rootVal = preorder[preIndex++];
        int rootIndex = inIndexes.get(rootVal);
        TreeNode root = new TreeNode(rootVal);
        root.left = helper(inLeft, rootIndex - 1);
        root.right = helper(rootIndex + 1, inRight);
        return root;
    }
}