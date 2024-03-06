package com.example.mylibrary.leetcode.链表.p142环形链表2;

import com.example.mylibrary.leetcode.链表.ListNode;


public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().detectCycle(
                        new ListNode(3, new ListNode(2, new ListNode(0, new ListNode(-4))))
                )
        );
    }

    /**
     * 这是一道数学题，当快慢指针相遇后，slow继续往前走，head位置的prt同样向前走，最终会相遇于入环点
     *
     * @param head
     * @return
     */
    public ListNode detectCycle(ListNode head) {
        if (head == null)
            return null;
        ListNode fast = head;
        ListNode slow = head;
        ListNode ptr = head;
        while (fast.next != null && fast.next.next != null) {
            fast = fast.next.next;
            slow = slow.next;
            if (slow == fast) {
                while (ptr != slow) {
                    ptr = ptr.next;
                    slow = slow.next;
                }
                return ptr;
            }
        }
        return null;
    }
}
