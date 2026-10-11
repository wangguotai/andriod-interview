package com.example.mylibrary.leetcode.leetcode_75

class LRUCache(capacity: Int) {
class DLinkedNode {
        var key: Int? = null
        var value: Int? = null
        var prev: DLinkedNode? = null
        var next: DLinkedNode? = null
        constructor()
        constructor(key: Int, value: Int) {
            this.key = key
            this.value = value
        }
    }

    private val cache = HashMap<Int, DLinkedNode>()
    private var size: Int = 0
    private val capacity: Int = capacity
    private var head: DLinkedNode? = DLinkedNode()
    private var tail: DLinkedNode? = DLinkedNode()

    init {
        head?.next = tail
        tail?.prev = head
    }

    fun get(key: Int): Int {
        val node = cache.get(key) ?: return -1
        // 如果key存在，调整到头部
        moveToHead(node)
        return node.value ?: -2
    }


    fun put(key: Int, value: Int) {
        val node = cache.get(key)
        if(node == null) {
            val newNode = DLinkedNode(key, value)
            size++
            cache[key] = newNode
            addToHead(newNode)
            if(size > capacity) {
                // 删除队尾
                val res = tail?.prev
                if(res != null){
                    removeNode(res)
                    cache.remove(res.key)
                    --size
                }
            }

        } else {
            node.value = value
            moveToHead(node)
        }

    }

    fun moveToHead(node: DLinkedNode) {
        removeNode(node)
        addToHead(node)
    }

    private fun removeNode(node: DLinkedNode){
        node.prev?.next = node.next
        node.next?.prev = node.prev
    }

    private fun addToHead(node: DLinkedNode) {
        node.prev = head
        node.next = head?.next
        head?.next?.prev = node
        head?.next = node
    }

}

fun main() {
    var obj = LRUCache(2)
}

/**
 * Your LRUCache object will be instantiated and called as such:
 * var obj = LRUCache(capacity)
 * var param_1 = obj.get(key)
 * obj.put(key,value)
 */