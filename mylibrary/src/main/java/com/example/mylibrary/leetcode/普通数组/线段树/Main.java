package com.example.mylibrary.leetcode.普通数组.线段树;

public class Main {
    /**
     * @param arr   线段树
     * @param pos   正在访问的数组下标
     * @param left  当前正在处理区间的左端点
     * @param right 当前正在处理区间的右端点
     * @param value 待插入的数字
     */
    public static void treeInsert(int[] arr, int pos, int left, int right, int value) {
        // 区间left-right保存的数字+1
        arr[pos]++;
        // 当前的区间是单位区间，且对应value的存储位置
        if (value == right && value == left) {
            return;
        }
        // 计算区间的中点
        int mid = (left + right) / 2;
        int leftChild = pos * 2 + 1;
        int rightChild = pos * 2 + 2;
        if (value <= mid) {
            treeInsert(arr, leftChild, left, mid, value);
        } else {
            treeInsert(arr, rightChild, mid + 1, right, value);
        }
    }

    /**
     * 线段树的查找
     *
     * @param arr
     * @param pos
     * @param left
     * @param right
     * @param num
     */
    public static int treeSearch(int[] arr, int pos, int left, int right, int num) {
        if (num > right || num < left)
            return -1;
        // 正在查询的数字num和左右端点相同
        if (num == right && num == left) {
            return arr[pos];
        }
        int mid = (left + right) / 2;
        int leftChild = pos * 2 + 1;
        int rightChild = pos * 2 + 2;

        if (num <= mid) {
            return treeSearch(arr, leftChild, left, mid, num);
        } else {
            return treeSearch(arr, rightChild, mid + 1, right, num);
        }
    }

    /**
     * 线段树的打印
     *
     * @param arr
     * @param pos
     * @param left
     * @param right
     */
    public static void printTree(int[] arr, int pos, int left, int right) {
        System.out.printf("[%d,%d] arr[%d]=%d\n", left, right, pos, arr[pos]);
        if (left == right) {
            return;
        }
        int mid = (left + right) / 2;
        printTree(arr, 2 * pos + 1, left, mid);
        printTree(arr, 2 * pos + 2, mid + 1, right);
    }

    public static void main(String[] args) {
        int[] a = new int[100];
        int left = 0;
        int right = 5;
        treeInsert(a, 0, left, right, 3);
        treeInsert(a, 0, left, right, 5);
        treeInsert(a, 0, left, right, 2);

        for (int i = 0; i <= 5; i++) {
            if (treeSearch(a, 0, left, right, i) != 0) {
                System.out.printf("%d is in tree.\n", i);
            }
        }
        printTree(a, 0, left, right);
    }

    public class Node {
        public int value;
        public int left;
        public int right;
    }
}
