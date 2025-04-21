package com.example.mylibrary.leetcode.京东.字符串中单词反转181;

import java.util.Deque;
import java.util.LinkedList;
import java.util.Queue;

public class Solution181 {
    public String reverseMessage(String message) {
        Deque<String> stack = new LinkedList<>();
        StringBuilder word = new StringBuilder();
        int left = 0, right = message.length() -1;
        while(message.charAt(left) == ' '){
            left++;
        }
        while(message.charAt(right) == ' '){
            right--;
        }
        boolean flag = false;
        while(left <= right) {
            char ch = message.charAt(left++);
            if(ch == ' '){
                flag = true;
            } else {
                if(flag){
                    if(!stack.isEmpty()){
                        word.append(' ');
                    }
                    stack.push(word.toString());
                    flag = false;
                    word.delete(0, word.length());
                }
                word.append(ch);
            }
        }
        if(!stack.isEmpty()){
            word.append(' ');
        }
        while(!stack.isEmpty()){
            word.append(stack.poll());
        }
        return word.toString();
    }
}
