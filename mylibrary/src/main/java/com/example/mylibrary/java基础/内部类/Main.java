package com.example.mylibrary.java基础.内部类;


class Outer {
    class Inner {
        private int innerVar;
        public void print() {
            System.out.println("test");
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