package com.example.mylibrary.leetcode.链表.反转链表;

import com.example.mylibrary.leetcode.链表.ListNode;

import java.util.LinkedList;

public class Solution {
    public static void main(String[] args) {
        ListNode head = new ListNode(1, new ListNode(2, new ListNode(3, new ListNode(4))));
        new Solution().reverseList(
                head
        );
    }

    public ListNode reverseList1(ListNode head) {
        LinkedList<ListNode> stack = new LinkedList<>();
        ListNode curr, headB;
        while (head != null) {
            stack.push(head);
            head = head.next;
        }
        headB = stack.poll();
        curr = headB;
        while (!stack.isEmpty()) {
            curr.next = stack.poll();
            curr = curr.next;
        }
        if (curr != null) {
            curr.next = null;
        }
        return headB;
    }

    public ListNode reverseList(ListNode head) {
        ListNode curr = head, pre = null;
        ListNode next;
        while (curr != null) {
            next = curr.next;
            curr.next = pre;
            pre = curr;
            curr = next;
        }

        return pre;
    }
}
