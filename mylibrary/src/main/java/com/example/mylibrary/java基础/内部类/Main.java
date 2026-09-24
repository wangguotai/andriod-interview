package com.example.mylibrary.java基础.内部类;

/**
 * 介绍内部类
 * 1. 静态内部类、非静态内部类（内存泄漏的元凶）
 */
class Outer {
    class Inner {
        private int innerVar;
        public void print() {
             int a = 3;
             // 要求 final 修饰或者 事实上final
//             a = 5;
            class Partial {
                public void test() {
                    System.out.println(a);
                }
            }
            
            new Partial().test();
        }
    }

}

public class Main {
    public static void main(String[] args) {
        Outer outer = new Outer();
        Outer.Inner inner = outer.new Inner();
        inner.print();
    }
}