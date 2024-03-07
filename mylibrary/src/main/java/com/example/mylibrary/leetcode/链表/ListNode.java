package com.example.mylibrary.leetcode.链表;

import java.util.LinkedList;
import java.util.List;

public class ListNode {
    public int val;
    public ListNode next;

    public ListNode() {
    }

    public ListNode(int x) {
        val = x;
        next = null;
    }

    public ListNode(int x, ListNode next) {
        val = x;
        this.next = next;
    }

    public static ListNode buildList(int... arg) {
        ListNode head = new ListNode();
        ListNode curr = head;
        for (int i = 0; i < arg.length; i++) {
            curr.next = new ListNode(arg[i]);
            curr = curr.next;
        }
        return head.next;
    }

    public static List getList(ListNode node) {
        List<Integer> ans = new LinkedList<>();
        while (node != null) {
            ans.add(node.val);
            node = node.next;
        }
        return ans;
    }
}
