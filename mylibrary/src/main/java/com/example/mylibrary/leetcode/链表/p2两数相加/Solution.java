package com.example.mylibrary.leetcode.链表.p2两数相加;

import com.example.mylibrary.leetcode.链表.ListNode;

public class Solution {
    public static void main(String[] args) {
        System.out.println(
                new Solution().addTwoNumbers(
                        ListNode.buildList(
                                3, 7
                        ),
                        ListNode.buildList(
                                9, 2
                        )
                )
        );
    }

    public ListNode addTwoNumbers(ListNode l1, ListNode l2) {
        ListNode ans = new ListNode();
        ListNode cur = ans;
        int carry = 0;
        while (l1 != null && l2 != null) {
            cur.next = l1;
            int sum = l1.val + l2.val + carry;
            l1.val = sum % 10;
            carry = sum / 10;
            l1 = l1.next;
            l2 = l2.next;
            cur = cur.next;
        }
        cur.next = l1 == null ? l2 : l1;
        if (carry != 0) {
            while (cur.next != null) {
                int sum = cur.next.val + carry;
                carry = sum / 10;
                cur.next.val = sum % 10;
                cur = cur.next;
            }
            if (carry != 0) {
                cur.next = new ListNode(carry);
            }
        }
        return ans.next;
    }
}
