package com.example.mylibrary.leetcode.链表.p234回文链表;

import com.example.mylibrary.leetcode.链表.ListNode;

public class Solution {
    private ListNode frontPointer;

    public boolean isPalindrome(ListNode head) {
        frontPointer = head;
        return recursivelyCheck(head);
    }

    private boolean recursivelyCheck(ListNode currNode) {
        if (currNode != null) {
            if (!recursivelyCheck(currNode.next)) {
                return false;
            }
            if (currNode.val != frontPointer.val) {
                return false;
            }
            frontPointer = frontPointer.next;
        }
        return true;
    }
}
