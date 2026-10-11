package com.example.mylibrary.leetcode.leetcode_75.二叉树.叶子相似的树872

import com.example.mylibrary.leetcode.二叉树.TreeNode
import java.util.Arrays

fun leafSimilar(root1: TreeNode?, root2: TreeNode?): Boolean {
    if (root1 == null || root2 == null) return false
    val list1 = mutableListOf<Int>()
    val list2 = mutableListOf<Int>()
    dfs(root1, list1)
    dfs(root2, list2)
    return list2 == list1
    if(list1.size != list2.size) return false
    for (i in list1.indices) {
        if(list1[i] != list2[i]) return false
    }
    return true
}

private fun dfs(root: TreeNode, result: MutableList<Int>) {
    if (root.left == null && root.right == null) {
        result.add(root.`val`)
        return
    }
    if (root.left != null) {
        dfs(root.left, result)
    }
    if (root.right != null) {
        dfs(root.right, result)
    }
}