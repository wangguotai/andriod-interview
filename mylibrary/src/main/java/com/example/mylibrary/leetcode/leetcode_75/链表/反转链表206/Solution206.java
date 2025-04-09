package com.example.mylibrary.leetcode.leetcode_75.链表.反转链表206;

import com.example.mylibrary.leetcode.链表.ListNode;

public class Solution206 {
    public ListNode reverseList(ListNode head){
        ListNode virtualNode = new ListNode(0);
        ListNode p = head;
        while(p!=null) {
            ListNode current = p;
            p = p.next;
            ListNode temp = virtualNode.next;
            virtualNode.next = current;
            virtualNode.next.next = temp;
        }
        return virtualNode.next;
    }
}
