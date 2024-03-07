package com.example.mylibrary.leetcode.链表.p138随机链表复制;


import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

class Solution {
    /**
     * 使用hash
     *
     * @param head
     * @return
     */
    private Map<Node, Node> map = new HashMap<>();

    public static void main(String[] args) {
        new Solution().copyRandomList(
                Node.buildList(
                        new int[]{7, -1},
                        new int[]{13, 7},
                        new int[]{11, 1},
                        new int[]{10, 11},
                        new int[]{1, 7}
                )
        );
    }

    public Node copyRandomList(Node head) {
        Node dummy = new Node(-1);
        Node curr = dummy;
        while (head != null) {
            curr.next = forkNode(head);
            if (head.random != null) {
                curr.next.random = forkNode(head.random);
            }
            head = head.next;
            curr = curr.next;
        }
        return dummy.next;
    }

    private Node forkNode(Node node) {
        if (map.containsKey(node)) {
            return map.get(node);
        } else {
            Node newNode = new Node(node.val);
            map.put(node, newNode);
            return newNode;
        }
    }
}

class Node {
    int val;
    Node next;
    Node random;

    public Node(int val) {
        this.val = val;
        this.next = null;
        this.random = null;
    }

    public static Node buildList(int[]... arg) {
        Node dummy = new Node(-1);
        Node curr = dummy;
        Map<Integer, Node> map = new HashMap<>();
        for (int i = 0; i < arg.length; i++) {
            Node temp;
            if (map.containsKey(arg[i][0])) {
                temp = map.get(arg[i][0]);
            } else {
                temp = new Node(arg[i][0]);
                map.put(arg[i][0], temp);
            }
            curr.next = temp;
            if (arg[i][1] != -1) {
                if (map.containsKey(arg[i][1])) {
                    temp = map.get(arg[i][1]);
                } else {
                    temp = new Node(arg[i][1]);
                    map.put(arg[i][1], temp);
                }
                curr.next.random = temp;
            }
            curr = curr.next;
        }
        return dummy.next;
    }

    public static List getList(Node node) {
        List<Integer> ans = new LinkedList<>();
        while (node != null) {
            ans.add(node.val);
            node = node.next;
        }
        return ans;
    }
}