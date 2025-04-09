package com.example.mylibrary.leetcode.leetcode_75.链表.删除链表的中间节点2095;

class ListNode {
    int val;
    ListNode next;

    ListNode() {
    }

    ListNode(int val) {
        this.val = val;
    }

    ListNode(int val, ListNode next) {
        this.val = val;
        this.next = next;
    }
}

public class Solution2095 {


    public ListNode deleteMiddle(ListNode head) {
        ListNode fast = new ListNode(0);
        fast.next = head;
        head = fast;
        ListNode slow = fast;
        while(fast.next!= null && fast.next.next!= null){
            fast = fast.next.next;
            slow = slow.next;
        }
        slow.next = slow.next.next;
        return head.next;
    }
}
