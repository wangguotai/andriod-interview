package com.example.mylibrary.leetcode.堆.p215第K个最大的元素;

public class Temp {
    int whoCanReferMe = 0;

    static class ICannotRefer {

    }

    class ICanRefer {
        private void test() {
            System.out.println(whoCanReferMe);
        }
    }
}
