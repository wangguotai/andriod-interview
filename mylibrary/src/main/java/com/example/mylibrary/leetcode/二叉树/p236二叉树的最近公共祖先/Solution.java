package com.example.mylibrary.leetcode.二叉树.p236二叉树的最近公共祖先;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.Deque;
import java.util.LinkedList;

public class Solution {
    TreeNode f, s, ans;
    int num;

    public static void main(String[] args) {
        TreeNode p = new TreeNode(5), q = new TreeNode(1);
        new Solution().lowestCommonAncestor1(
                TreeNode.createTreeNode(p, q,
                        3, 5, 1, 6, 2, 0, 8, -1, -1, 7, 4
                ), p, q
        );
    }

    /**
     * 层序遍历，从root出发查找从 node 到 p，q的路径，直至一层都查不到
     *
     * @param root
     * @param p
     * @param q
     * @return
     */
    public TreeNode lowestCommonAncestor1(TreeNode root, TreeNode p, TreeNode q) {
        Deque<TreeNode> queue = new LinkedList<>();
        f = p;
        s = q;
        if (root.left != null) {
            queue.offer(root.left);
        }
        if (root.right != null) {
            queue.offer(root.right);
        }
        ans = root;
        while (!queue.isEmpty()) {
            int size = queue.size();
            TreeNode curr = ans;
            num = 0;
            while (size-- > 0) {
                TreeNode node = queue.poll();
                // 从node出发查找 p，q
                if (num != 2) {
                    num = 0;
                    checkNode(node, null);
                    if (num == 2) {
                        curr = node;
                        if (node.left != null) {
                            queue.offer(node.left);
                        }
                        if (node.right != null) {
                            queue.offer(node.right);
                        }
                    }
                }
            }
            if (num != 2) {
                break;
            } else {
                ans = curr;
            }
        }
        return ans;
    }

    private void checkNode(TreeNode node, TreeNode target) {
        if (node == null) return;
        if (num != 2) {
            TreeNode nextTarget = null;
            if (target == null) {
                if (node == f) {
                    num++;
                    nextTarget = s;
                } else if (node == s) {
                    nextTarget = f;
                    num++;
                }
            } else {
                if (node == target) {
                    num++;
                } else {
                    nextTarget = target;
                }
            }
            checkNode(node.left, nextTarget);
            checkNode(node.right, nextTarget);
        }
    }

    public TreeNode lowestCommonAncestor(TreeNode root, TreeNode p, TreeNode q) {
        if (root == null || root == p || root == q) return root;
        TreeNode left = lowestCommonAncestor(root.left, p, q);
        TreeNode right = lowestCommonAncestor(root.right, p, q);
        if (left == null) return right;
        if (right == null) return left;
        return root;
    }
}
