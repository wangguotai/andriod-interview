package com.example.mylibrary.leetcode.链表.p24两两交换链表中的节点;

import com.example.mylibrary.leetcode.链表.ListNode;

public class Solution {
    private ListNode sStart;

    public static void main(String[] args) {
        new Solution().swapPairs(
                ListNode.buildList(1, 2, 3, 4)
        );
    }

    public ListNode swapPairs(ListNode head) {
        ListNode dummy = new ListNode(0, head);
        sStart = dummy;
        recursive(dummy, 2);
        return dummy.next;
    }

    private void recursive(ListNode curr, int num) {
        if (curr != null) {
            if (num == 0) {
                ListNode next = sStart.next;
                sStart.next = curr;
                next.next = next.next.next;
                curr.next = next;
                sStart = next;
                recursive(curr.next, 2);
            } else {
                recursive(curr.next, num - 1);
            }
        }
    }
}
