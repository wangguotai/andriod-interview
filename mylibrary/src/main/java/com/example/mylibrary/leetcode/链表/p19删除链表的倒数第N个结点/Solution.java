package com.example.mylibrary.leetcode.链表.p19删除链表的倒数第N个结点;

import com.example.mylibrary.leetcode.链表.ListNode;

public class Solution {
    public static void main(String[] args) {
        new Solution().removeNthFromEnd(ListNode.buildList(
                1
        ), 1);
    }

    public ListNode removeNthFromEnd1(ListNode head, int n) {
        int pre = recursion(head, n);
        return pre == -1 ? head.next : head;
    }

    private int recursion(ListNode curr, int index) {
        // 遍历到最后一个结点
        if (curr == null) {
            return index - 1;
        }
        int curIndex = recursion(curr.next, index);
        if (curIndex == -1 && curr.next != null) {
            curr.next = curr.next.next;
        }
        return curIndex - 1;
    }

    /**
     * 官方最优解：双指针
     *
     * @param head
     * @param n
     * @return
     */
    public ListNode removeNthFromEnd(ListNode head, int n) {
        ListNode dummy = new ListNode();
        ListNode fast = dummy;
        ListNode slow = dummy;
        for (int i = 0; i < n; i++) {
            if (fast.next != null) {
                fast = fast.next;
            }
        }
        while (fast != null) {
            fast = fast.next;
            slow = slow.next;
        }
        if (slow.next != null) {
            slow = slow.next.next;
        }
        return dummy.next;
    }
}
