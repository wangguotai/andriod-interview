package com.example.mylibrary.leetcode.leetcode_75.二叉树.叶子相似的树872;

import com.example.mylibrary.leetcode.二叉树.TreeNode;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

public class Solution872 {
    public boolean leafSimilar(TreeNode root1, TreeNode root2) {
        List<Integer> list1 = new LinkedList<>();
        List<Integer> list2 = new LinkedList<>();
        dfs(root1, list1);
        dfs(root2, list2);
        if(list1.size() != list2.size()) {
            return false;
        }
        for(int i=0;i<list1.size();i++){
            if(list1.get(i).intValue()!=list2.get(i).intValue()){
                return false;
            }
        }
        return true;
    }
    void dfs(TreeNode node, List<Integer> list){
        if(node.left!=null){
            dfs(node.left, list);
        }
        if(node.right!=null) {
            dfs(node.right, list);
        }
        if(node.left == null && node.right == null) {
            list.add(node.val);
        }
    }


    List<Integer> levelTravel(TreeNode root){
        Deque<TreeNode> queue = new LinkedList<>();
        List<Integer> result = new LinkedList<>();
        queue.offer(root);
        while (!queue.isEmpty()){
            int size = queue.size();
            while(size-- > 0){
                TreeNode node = queue.poll();
                if(node.left!=null){
                    queue.offer(node.left);
                }
                if(node.right!=null){
                    queue.offer(node.right);
                }
                if(node.left == null && node.right == null) {
                    result.add(node.val);
                }
            }
        }
        return result;
    }

    public boolean leafSimilar1(TreeNode root1, TreeNode root2) {
        List<Integer> list1 = levelTravel(root1);
        List<Integer> list2 = levelTravel(root2);

        if(list1.size() != list2.size()) {
            return false;
        }
        for(int i=0;i<list1.size();i++){
            if(list1.get(i).intValue()!=list2.get(i).intValue()){
                return false;
            }
        }
        return true;
    }

    public static void main(String[] args) {
        Solution872 solution872 = new Solution872();
       boolean res = solution872.leafSimilar(new TreeNode(1,
                new TreeNode(2), new TreeNode(200)),
                new TreeNode(1,
                        new TreeNode(2), new TreeNode(200))
        );
        System.out.println(res);
    }
}
