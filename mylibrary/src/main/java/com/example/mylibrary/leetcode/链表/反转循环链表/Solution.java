package com.example.mylibrary.leetcode.链表.反转循环链表;

import com.example.mylibrary.leetcode.链表.ListNode;

public class Solution {
    public static void main(String[] args) {
        ListNode root = new ListNode(1);
        root.next = new ListNode(2);
        root.next.next = new ListNode(3);
        root.next.next.next = root;

        new Solution().reverse(
                root
        );
    }

//    private ListNode reverseTwin(ListNode curr, ListNode firstNode) {
//        if (curr == firstNode) {
//            return null;
//        }
//        ListNode next = curr.next;
//        ListNode anotherStart = next.next;
//        next.next = curr;
//        ListNode nextGroup = reverseTwin(anotherStart, firstNode);
//        curr.next = nextGroup == null ? anotherStart : nextGroup;
//        return next;
//    }

    public ListNode reverse(ListNode head) {

        if (head == null || head.next == head) {
            return head;
        }
//        ListNode tail = head;
//        while (tail.next != head) {
//            tail = tail.next;
//        }
        ListNode current = head;
        ListNode prev = null;
        do {
            ListNode next = current.next;
            current.next = prev;
            prev = current;
            current = next;
        } while (current != head);
        current.next = prev;
        return prev;
    }
}
