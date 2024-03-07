package com.example.mylibrary.leetcode.链表.p25K个一组翻转链表;

import com.example.mylibrary.leetcode.链表.ListNode;

public class Solution {
    public static void main(String[] args) {
        System.out.println(
                ListNode.getList(
                        new Solution().reverseKGroup(
                                ListNode.buildList(
                                        1, 2
                                ), 2
                        )
                )
        );
    }

    public ListNode reverseKGroup(ListNode head, int k) {
        ListNode dummy = new ListNode(0, head);
        ListNode left = dummy;
        ListNode curr = head;
        int num = 1;
        while (curr != null) {
            if (num == k) {
                ListNode nextLeft = left.next;
                ListNode nextCur = curr.next;
                reverseInternal(left, curr);
                num = 1;
                left = nextLeft;
                curr = nextCur;
            } else {
                num++;
                curr = curr.next;
            }
        }
        return dummy.next;
    }

    private void reverseInternal(ListNode preNode, ListNode end) {
        ListNode nextInternal = end.next;
        ListNode cur = preNode.next,
                pre = nextInternal; // 当前区间翻转后要指向下一个区间

        // 构建反向列表 nextInterval -> pre
        // 从[left, end]反向后，会导致 end.next指向另一边，所以要用nextInternal提前记录，cur指向原序列的下一个节点
        while (cur != null && cur != nextInternal) {
            ListNode next = cur.next;
            cur.next = pre;
            pre = cur;
            cur = next;
        }
        preNode.next = pre;
    }
}
