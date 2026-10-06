package com.example.mylibrary.leetcode.leetcode_75.链表.删除链表的中间节点2095
import com.example.mylibrary.leetcode.链表.ListNode

fun deleteMiddle(head: ListNode?): ListNode? {

    if(head == null) return null

    val pHead = ListNode()
    pHead.next = head
    var slow = pHead
    var fast: ListNode = pHead
    while(fast.next!=null && fast.next.next != null) {
        fast = fast.next.next
        slow = slow.next
    }
    slow.next = slow.next.next
    return head
}


fun main() {
    deleteMiddle(ListNode.buildList(1,3,4,7,1,2,6))
}