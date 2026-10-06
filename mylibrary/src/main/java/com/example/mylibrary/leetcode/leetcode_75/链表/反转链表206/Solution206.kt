package com.example.mylibrary.leetcode.leetcode_75.链表.反转链表206

import com.example.mylibrary.leetcode.链表.ListNode
import java.util.LinkedList

fun reverseList(head: ListNode?): ListNode? {
    if (head == null) return null
    var p = head
    val stack = LinkedList<ListNode>()
    while (p != null) {
        stack.push(p)
        p = p.next
    }
    val virtualNode = ListNode()
    var newHead = stack.pop()
    virtualNode.next = newHead
    while (stack.isNotEmpty()) {
        newHead.next = stack.pop()
        newHead = newHead.next
    }
    newHead.next = null
    return virtualNode.next
}

fun reverseList1(head: ListNode?): ListNode? {
    if (head == null) return null
    val pHead = ListNode()
    var p = head
    while (p!=null){
        val currentNode = p
        p = p.next
        val temp = pHead.next
        pHead.next = currentNode
        pHead.next.next = temp
    }
    return pHead.next
}


fun main() {
    reverseList1(ListNode.buildList(1,2,3,4,5))
}