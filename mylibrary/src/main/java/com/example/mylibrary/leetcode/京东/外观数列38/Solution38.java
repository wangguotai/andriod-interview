package com.example.mylibrary.leetcode.京东.外观数列38;

public class Solution38 {
    public String countAndSay(int n) {
        if(n == 1) {
            return "1";
        }
        String prev = countAndSay(n-1);
        int len = prev.length();
        char prevChar = prev.charAt(0);
        int count = 1;
        StringBuilder sb = new StringBuilder();
        if(len > 1) {
            for(int i=1;i<len;i++){
                if(prevChar == prev.charAt(i)) {
                    count++;
                } else {
                    sb.append(count).append(prevChar);
                    prevChar = prev.charAt(i);
                    count = 1;
                }
            }
        }
        sb.append(count).append(prevChar);
        return sb.toString();
    }

    public static void main(String[] args) {
        Solution38 solution38 = new Solution38();
        System.out.println(solution38.countAndSay(3));
    }
}
