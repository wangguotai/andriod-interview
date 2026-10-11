package com.example.mylibrary.leetcode.leetcode_75.二叉树.p104_二叉树的最大深度

import com.example.mylibrary.leetcode.二叉树.TreeNode
import java.util.LinkedList

class Solution104 {
    fun maxDepth(root: TreeNode?): Int {
//        if(root == null) return 0
//        val depthLeft = maxDepth(root.left)
//        val depthRight = maxDepth(root.right)
//        return depthRight.coerceAtLeast(depthLeft) + 1

        if(root == null) return 0
        val queue = LinkedList<TreeNode>()
        var maxDepth = 0
        queue.offer(root)
        while (queue.isNotEmpty()) {
            val size = queue.size
            for (i in 0 ..<size) {
                val node = queue.poll()
                if(node.left!=null) queue.offer(node.left)
                if(node.right!=null) queue.offer(node.right)
            }
            maxDepth++
        }
        return maxDepth
    }
}