package com.example.mylibrary.leetcode.链表.p148排序链表;

import com.example.mylibrary.leetcode.链表.ListNode;

public class Solution {
    public static void main(String[] args) {
        System.out.println(
                ListNode.getList(
                        new Solution().sortList(
                                ListNode.buildList(
                                        4, 2, 1, 3
                                )
                        )
                )
        );
    }

    public ListNode sortList(ListNode head) {
        if (head == null) {
            return head;
        }
        return sortList(head, null);
    }

    private ListNode sortList(ListNode start, ListNode end) {
        if (start == end) {
            return start;
        }

        ListNode mid = getMid(start, end);
        ListNode temp = mid.next;
        mid.next = null;
        if (end != null) {
            end.next = null;
        }
        ListNode l1 = sortList(start, mid);
        ListNode l2 = sortList(temp, end);
        return merge(l1, l2);
    }

    private ListNode merge(ListNode l1, ListNode l2) {
        ListNode dummy = new ListNode();
        ListNode curr = dummy;
        while (l1 != null && l2 != null) {
            if (l1.val < l2.val) {
                curr.next = l1;
                l1 = l1.next;
            } else {
                curr.next = l2;
                l2 = l2.next;
            }
            curr = curr.next;
        }
        if (l1 != null) {
            curr.next = l1;
        }
        if (l2 != null) {
            curr.next = l2;
        }
        return dummy.next;
    }

    private ListNode getMid(ListNode start, ListNode end) {
        ListNode slow = start;
        ListNode fast = start;
        while (fast.next != end && fast.next.next != end) {
            fast = fast.next.next;
            slow = slow.next;
        }
        return slow;
    }
}
