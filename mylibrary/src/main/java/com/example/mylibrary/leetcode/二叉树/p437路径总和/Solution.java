package com.example.mylibrary.leetcode.二叉树.p437路径总和;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.HashMap;
import java.util.Map;

public class Solution {
    // 方法2. 前缀和
    private Map<Long, Integer> prefixMap = new HashMap<>();
    private int targetSum;

    public static void main(String[] args) {
        new Solution().pathSum1(
                TreeNode.createTreeNode(
                        1000000000, 1000000000, -1, 294967296, -1, 1000000000, -1, 1000000000, -1, 1000000000
                ), 0
        );
    }

    // 方法1.暴力破解，深度优先搜索
    public int pathSum1(TreeNode root, int targetSum) {
        if (root == null) return 0;
        int ret = rootSum(root, targetSum);
        ret += pathSum1(root.left, targetSum);
        ret += pathSum1(root.right, targetSum);
        return ret;
    }

    private int rootSum(TreeNode root, int targetSum) {
        int ret = 0;
        if (root == null) {
            return ret;
        }
        int val = root.val;
        if (val == targetSum) {
            ret++;
        }
        ret += rootSum(root.left, targetSum - val);
        ret += rootSum(root.right, targetSum - val);
        return ret;
    }

    public int pathSum(TreeNode node, int target) {
        prefixMap.put(0l, 1);
        targetSum = target;
        return dfs(node, 0);
    }

    /**
     * 如果存在一条路径「p0,p1,...,pk」的和等于 preSum - targetSum, 则必定有「pk+1，...,node」其和为targetSum，
     * dfs遍历以node结点为最后一个结点寻找满足条件的「pk+1，...,node」，这样的路径为满足条件的路径
     *
     * @param node
     * @param preSum 当前结点之前的前缀和
     * @return
     */
    private int dfs(TreeNode node, long preSum) {
        if (node == null) {
            return 0;
        }
        preSum += node.val;
        int ret = prefixMap.getOrDefault(preSum - targetSum, 0);
        prefixMap.put(preSum, prefixMap.getOrDefault(preSum, 0) + 1);
        ret += dfs(node.left, preSum);
        ret += dfs(node.right, preSum);
        prefixMap.put(preSum, prefixMap.getOrDefault(preSum, 0) - 1);
        return ret;
    }
}
