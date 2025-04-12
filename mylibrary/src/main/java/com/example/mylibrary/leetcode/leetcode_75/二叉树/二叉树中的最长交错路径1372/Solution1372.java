package com.example.mylibrary.leetcode.leetcode_75.二叉树.二叉树中的最长交错路径1372;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

public class Solution1372 {
    int DIRECTION_LEFT = 0;
    int DIRECTION_RIGHT = 1;

     public int longestZigZag(TreeNode root) {
         if(root == null) {
             return 0;
         }
         int longest = 0;
         longest = Math.max(dfs(root.left, DIRECTION_RIGHT, 0), longest);
         longest = Math.max(dfs(root.right, DIRECTION_LEFT, 0), longest);
         return longest;
     }

     int dfs(TreeNode node, int nextDirection, int currentLength){
         if(node == null) {
             return currentLength;
         }
         currentLength++;
         if(nextDirection == DIRECTION_LEFT) {
             currentLength = dfs(node.left, DIRECTION_RIGHT, currentLength);
             currentLength = Math.max(currentLength, dfs(node.right,DIRECTION_LEFT,0));
         } else {
             currentLength = dfs(node.right, DIRECTION_LEFT, currentLength);
             currentLength = Math.max(currentLength, dfs(node.left,DIRECTION_RIGHT,0));
         }
         return currentLength;
     }

//    int maxLength = 0;
//    public int longestZigZag(TreeNode root) {
//        if(root == null) {
//            return 0;
//        }
//        dfs(root.left, DIRECTION_RIGHT, 0);
//        dfs(root.right, DIRECTION_LEFT, 0);
//        return maxLength;
//    }
//
//    void dfs(TreeNode node, int nextDirection, int currentLength){
//       if(node == null) {
//           return;
//       }
//       currentLength++;
//       maxLength = Math.max(currentLength, maxLength);
//        if(nextDirection == DIRECTION_LEFT) {
//            dfs(node.left, DIRECTION_RIGHT, currentLength);
//            dfs(node.right,DIRECTION_LEFT,0);
//        } else {
//            dfs(node.right, DIRECTION_LEFT, currentLength);
//            dfs(node.left,DIRECTION_RIGHT,0);
//        }
//    }


}
