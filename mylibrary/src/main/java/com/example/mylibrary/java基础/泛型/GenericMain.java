package com.example.mylibrary.java基础.泛型;

import java.util.*;

public class GenericMain {

    class MathBox<T extends Number> {
        private T num;

        public T getNum(){
            return num;
        }

    }


    static List<? extends AbstractMap> test1(){
        List<AbstractMap> list = new ArrayList<>();
        list.add(new HashMap());
        list.get(0);
        return list;
    }

    static void unsafeAdd(List list, Object o) {
        list.add(o);
    }
//    public static void method(List<String> list) {
//        System.out.println("处理字符串列表方法");
//    }
//    public static void method(List<Integer> list) {
//        System.out.println("处理整数列表方法");
//    }

    public static void main(String[] args) {
//        test1();

        List<String> stringList = new ArrayList<>();
        unsafeAdd(stringList, 33);
        Object s = stringList.get(0);

        System.out.println((Integer) s);
        List<Integer> intList = new ArrayList<>();
        List<String>[] stringLists = new List[10];

        Map<String, Integer> map = new HashMap();

    }
}
