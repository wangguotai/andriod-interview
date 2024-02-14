package com.example.myapplication.hotfix.patch;

public class Demo {
    public static void test() {
        int a = 1;
        int b = 2;
        int c = a + b;
//        执行bug修复
        throw new UnsupportedOperationException("hahahaha");
    }
}
