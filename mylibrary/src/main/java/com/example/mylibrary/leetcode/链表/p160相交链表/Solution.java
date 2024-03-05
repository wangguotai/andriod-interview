package com.example.mylibrary.leetcode.链表.p160相交链表;

import java.util.HashSet;
import java.util.Set;

public class Solution {
    /**
     * 相交链表
     * 1. 哈希集合
     *
     * @param headA
     * @param headB
     * @return
     */
    public ListNode getIntersectionNode1(ListNode headA, ListNode headB) {
        Set<ListNode> visited = new HashSet<>();
        ListNode temp = headA;
        while (temp != null) {
            visited.add(temp);
            temp = temp.next;
        }
        temp = headB;
        while (temp != null) {
            if (visited.contains(temp)) {
                return temp;
            }
            temp = temp.next;
        }
        return null;
    }

    /**
     * 方法2. 双指针
     * - A B 有交点
     * a+c=A
     * b+c=B
     * 都走完a+b+c 会到达交点
     * - A B 无交点
     * 都走完 m+n 会到达空
     *
     * @param headA
     * @param headB
     * @return
     */
    public ListNode getIntersectionNode(ListNode headA, ListNode headB) {
        if (headA == null || headB == null) {
            return null;
        }

        ListNode pA = headA;
        ListNode pB = headB;
        while (pA != pB) {
            pA = pA == null ? headB : pA.next;
            pB = pB == null ? headA : pB.next;
        }
        return pA;
    }
}
