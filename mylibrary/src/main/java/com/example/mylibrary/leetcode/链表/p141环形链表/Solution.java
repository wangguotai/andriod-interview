package com.example.mylibrary.leetcode.链表.p141环形链表;

import com.example.mylibrary.leetcode.链表.ListNode;

import java.util.LinkedList;
import java.util.List;

public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().hasCycle(
                        new ListNode(3, new ListNode(2, new ListNode(0, new ListNode(-4))))
                )
        );
    }

    public boolean hasCycle1(ListNode head) {
        ListNode curr = head;
        List<ListNode> container = new LinkedList<>();

        while (curr != null && !container.contains(curr)) {
            container.add(curr);
            curr = curr.next;
        }
        return curr != null;
    }

    public boolean hasCycle(ListNode head) {
        if (head == null || head.next == null)
            return false;
        ListNode slow = head;
        ListNode fast = head;
        while (fast.next != null && fast.next.next != null) {
            fast = fast.next.next;
            slow = slow.next;
            if (fast == slow) {
                return true;
            }
        }
        return false;

    }
}
