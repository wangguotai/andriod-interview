package com.example.mylibrary.leetcode.leetcode_75.链表.链表最大孪生和2130;

import com.example.mylibrary.leetcode.链表.ListNode;

public class Solution2130 {
    public int pairSum(ListNode head) {
        ListNode prePart = new ListNode(1);
        ListNode postPart = new ListNode(1);
        prePart.next = head;

        // 1. 反转n/2->n-1,然后再比较
        ListNode slow = prePart;
        ListNode fast = prePart;
        while (fast != null && fast.next != null) {
            slow = slow.next;
            fast = fast.next.next;
        }

        slow = slow.next;
        while (slow != null) {
            ListNode next = postPart.next;
            postPart.next = slow;
            slow = slow.next;
            postPart.next.next = next;
        }
        int maxSum = 0;
        while(postPart.next!= null){
            maxSum = Math.max(maxSum, postPart.next.val + prePart.next.val);
            postPart = postPart.next;
            prePart = prePart.next;
        }
        return maxSum;
    }

    public static void main(String[] args) {
        int maxSum = new Solution2130().pairSum(
                new ListNode(5,
                        new ListNode(4, new ListNode(2,
                                new ListNode(1))))
        );
        System.out.println("result ====>" + maxSum);
    }
}
