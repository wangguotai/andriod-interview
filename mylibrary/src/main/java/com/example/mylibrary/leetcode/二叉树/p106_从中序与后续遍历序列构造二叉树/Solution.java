package com.example.mylibrary.leetcode.二叉树.p106_从中序与后续遍历序列构造二叉树;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.HashMap;
import java.util.Map;

/**
 * 给定两个整数数组 inorder 和 postorder ，其中 inorder 是二叉树的中序遍历， postorder 是同一棵树的后序遍历，请你构造并返回这颗 二叉树 。
 * <p>
 * <p>
 * <p>
 * 示例 1:
 * 3
 * / \
 * 9   20
 * /  \
 * 15   7
 * 输入：inorder = [9,3,15,20,7], postorder = [9,15,7,20,3]
 * 输出：[3,9,20,null,null,15,7]
 * 示例 2:
 * <p>
 * 输入：inorder = [-1], postorder = [-1]
 * 输出：[-1]
 * <p>
 * <p>
 * 提示:
 * <p>
 * 1 <= inorder.length <= 3000
 * postorder.length == inorder.length
 * -3000 <= inorder[i], postorder[i] <= 3000
 * inorder 和 postorder 都由 不同 的值组成
 * postorder 中每一个值都在 inorder 中
 * inorder 保证是树的中序遍历
 * postorder 保证是树的后序遍历
 */
public class Solution {
    /*** 官方题解思路*/
    // 递归遍历中重复使用的变量，将其写在堆中，
    int postIndex;
    int[] postorder;
    int[] inorder;
    Map<Integer, Integer> idxMap = new HashMap<>();

    public static void main(String[] args) {
        System.out.println(
                new Solution().myBuildTree(
                        new int[]{9, 3, 15, 20, 7},
                        new int[]{9, 15, 7, 20, 3}
                ));
        System.out.println("finish");
    }

    public TreeNode buildTree(int[] inorder, int[] postorder) {
        this.postorder = postorder;
        this.inorder = inorder;
        // 从后序遍历中获取根节点
        postIndex = postorder.length - 1;
        // 建立（元素，下标）键值对的哈希表
        int idx = 0;
        for (Integer val : inorder) {
            idxMap.put(val, idx++);
        }
        return helper(0, inorder.length - 1);

    }

    private TreeNode helper(int inLeft, int inRight) {
        // 如果没有结点构造二叉树了，就结束
        if (inLeft > inRight) {
            return null;
        }
        // 选择postIdx位置的元素作为当前子树根结点
        int rootVal = postorder[postIndex];
        TreeNode root = new TreeNode(rootVal);

        // 根据root所在的位置分成左右两子树
        int index = idxMap.get(rootVal);
        // 下标减1
        postIndex--;
        // 构造右子树 右子树都遍历完了之后，就是左子树的
        root.right = helper(index + 1, inRight);
        // 构造左子树
        root.left = helper(inLeft, index - 1);

        return root;
    }

    /*** 官方题解思路 END */


    public TreeNode myBuildTree(int[] inorder, int[] postorder) {
        // 自己的思路，花了很长时间，但超时
        return myBuildTree(inorder, 0, inorder.length - 1, postorder, 0, postorder.length - 1);
    }

    /**
     * 通过中序遍历和后序遍历构建二叉树
     *
     * @param inorder
     * @param iStart
     * @param iEnd
     * @param postorder
     * @param pStart
     * @param pEnd
     * @return
     */
    public TreeNode myBuildTree(int[] inorder, int iStart, int iEnd, int[] postorder, int pStart, int pEnd) {
        if (iStart == iEnd) {
            return new TreeNode(inorder[iStart]);
        }
        return divideSubtree(inorder, iStart, iEnd, postorder, pStart, pEnd);
    }

    private TreeNode getRoot(int[] postorder, int end) {
        return new TreeNode(postorder[end]);
    }

    /**
     * 递归调用超时了。。。。
     *
     * @param inorder
     * @param iStart
     * @param iEnd
     * @param postorder
     * @param pStart
     * @param pEnd
     * @return
     */
    private TreeNode divideSubtree(int[] inorder, int iStart, int iEnd, int[] postorder, int pStart, int pEnd) {
        TreeNode root = getRoot(postorder, pEnd);
        int rootInorderIndex = getInorderRootIndex(inorder, iStart, iEnd, root.val);
        int leftTreeEndPostIndex = pStart - 1;
        if (rootInorderIndex > iStart) {
            // 后序遍历中左子树的结束下标
            leftTreeEndPostIndex = getpEndIndexPostorder(inorder, iStart, rootInorderIndex - 1, postorder, pStart, pEnd - 1);
            root.left = myBuildTree(inorder, iStart, rootInorderIndex - 1, postorder, pStart, leftTreeEndPostIndex);
        }
        if (rootInorderIndex < iEnd) {
            root.right = myBuildTree(inorder, rootInorderIndex + 1, iEnd, postorder, leftTreeEndPostIndex + 1, pEnd - 1);
        }
        return root;
    }

    private int getpEndIndexPostorder(int[] inorder, int iStart, int iEnd, int[] postorder, int pStart, int pEnd) {
        int maxIndex = -1;
        for (int i = iStart; i <= iEnd; i++) {
            for (int j = pStart; j <= pEnd; j++) {
                if (inorder[i] == postorder[j] && maxIndex < j) {
                    maxIndex = j;
                    break;
                }
            }
        }
        if (maxIndex == -1) {
            throw new IllegalArgumentException("两个遍历数组不匹配");
        }
        return maxIndex;
    }

    private int getInorderRootIndex(int[] arr, int iStart, int iEnd, int rootVal) {
        for (int i = iStart; i <= iEnd; i++) {
            if (arr[i] == rootVal) {
                return i;
            }
        }
        throw new IllegalArgumentException("根节点不在待搜索的数组中");
    }
}