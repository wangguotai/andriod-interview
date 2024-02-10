package com.example.mylibrary.leetcode.栈.p150;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

public class Solution {
    public int evalRPN(String[] tokens) {
        Deque<Integer> stack = new LinkedList<>();
        List<String> operators = new ArrayList<>(4);
        operators.add("+");
        operators.add("-");
        operators.add("*");
        operators.add("/");
        for (String token : tokens) {
            if (operators.contains(token)) {
                int opNum1 = stack.pop();
                int opNum2 = stack.pop();
                stack.push(operate(opNum1, opNum2, token));
            } else {
                stack.push(Integer.parseInt(token));
            }
        }
        return stack.pop();
    }

    private int operate(int num1, int num2, String operator) {
        int result = 0;
        switch (operator) {
            case "+": {
                result = num1 + num2;
                break;
            }
            case "-": {
                result = num2 - num1;
                break;
            }
            case "*": {
                result = num1 * num2;
                break;
            }
            case "/": {
                result = num2 / num1;
                break;
            }
        }
        return result;
    }

    public static void main(String[] args) {
        new Solution().evalRPN(new String[]{
                "4", "13", "5", "/", "+"
        });
    }
}
