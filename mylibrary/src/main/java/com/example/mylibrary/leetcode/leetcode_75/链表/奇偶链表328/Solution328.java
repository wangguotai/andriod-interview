package com.example.mylibrary.leetcode.leetcode_75.链表.奇偶链表328;

import com.example.mylibrary.leetcode.链表.ListNode;

public class Solution328 {
    public ListNode oddEvenList(ListNode head) {
        ListNode odd = new ListNode(0);
        ListNode oddHead = odd;
        ListNode even = new ListNode(0);
        ListNode evenHead = even;
        while(head!=null && head.next!=null){
            even.next = head.next;
            even = even.next;
            odd.next = head;
            odd = odd.next;
            head = head.next.next;
        }
        if(head!=null){
            odd.next = head;
            odd = odd.next;
        }
        even.next = null;
        odd.next = evenHead.next;
        return oddHead.next;
    }
}
