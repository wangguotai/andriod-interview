package com.example.mylibrary.leetcode.链表.p61旋转链表;

import com.example.mylibrary.leetcode.链表.ListNode;

public class Solution {
    public ListNode rotateRight(ListNode head, int k) {
        if (k == 0 || head == null || head.next == null) {
            return head;
        }
        ListNode curr = head;
        ListNode pre = head;
        for (int i = 0; i < k; i++) {
            if (curr.next == null) {
                curr = head;
            } else {
                curr = curr.next;
            }
        }
        if (curr == null) curr = head;
        while (curr.next != null) {
            curr = curr.next;
            pre = pre.next;
        }
        curr.next = head;
        head = pre.next;
        pre.next = null;
        return head;
    }
}
