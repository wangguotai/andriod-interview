package com.example.mylibrary.leetcode.滑动窗口;

public class Temp1 {
//    public static int maxCommPrefix(String text1, String text2) {
//        if (text1 == null || text2 == null) {
//            return 0;
//        }
//        int m = text1.length();
//        int n = text2.length();
//
//
//        int ans = 0;
//        // int maxLen = Math.max(m,n);
//        // int minLne = Math.min(m,n);
//        for (int i = 0; i < m; i++) {
//            int l = i;
//            int r = i;
//            int indexI = i;
//            for (int j = 0; j < n; j++) {
//                if (indexI < m && text2.charAt(j) == text1.charAt(indexI++)) {
//                    r++;
//                } else {
//                    ans = Math.max(ans, r - l);
//                    l = indexI;
//                }
//            }
//            ans = Math.max(ans, r - l);
//        }
//        return ans;
//    }

    public static Node reverseNode(Node head) {
        if (head == null || head.next == null) {
            return head;
        }
//        Node dummy = new Node(-1);
        Node prev = null;
        Node curr = head;
        Node next;
        while (curr != null) {
            next = curr.next;
            curr.next = prev;
            prev = curr;
            curr = next;
        }
        return prev;
    }

    public static void main(String[] args) {
        System.out.println("Hello World!");
//        int result = maxCommPrefix("abcde", "abc");
//        System.out.println(result);
    }

    static class Node {
        int val;
        Node next;

        public Node(int value) {
            this.val = value;
        }

    }

}