package com.example.mylibrary.leetcode.堆.p215第K个最大的元素;


//public class Temp<T extends ListNode> {
//    private T id;
//  public void  setId()

//    public class Test<T>{
//        public T[] getArray(int length){
//            Class<T> clazz = T.class;
//        }
//    }
//}

interface AA<T> {
    T add(T parms);
}

public class Test implements AA<Integer> {


//    private Test<Integer> test;
//    private T item;
//    public static <A> void copy(List<? super A> dest, List<? extends A> src){
//
//    }

//    public static void main(String[] args) {
////        // 1. 泛型不变性
////         ArrayList<Object> objectList;
////         ArrayList<String> stringList = new ArrayList<>();
//////         objectList = stringList;
//////         答案是不可以，
//////         objectList.add(new Shit());
//////         String str = stringList.get(0); // 此处会发生类型转换错误的
////
////    // 2. 泛型的协变
////         List<Double> doubleList = new ArrayList<>();
////         doubleList.add(2d);
////         sum(doubleList);
//
//        //3. 泛型的逆变
//        List<Double> doubleList = new ArrayList<Double>();
//        Filter<Number> filter = new Filter<Number>(){
//            @Override
//            public boolean test(Number element){
//                return element.doubleValue() > 100;
//            }
//        };
//        List<Double> res = removeIf(doubleList, filter);
//    }

    @Override
    public Integer add(Integer parms) {
        return parms;
    }

    interface Filter<E> {
        public boolean test(E element);
    }

//    public static <E> List<E> removeIf(List<E> list, Filter<E> filter) {
//    public static <E> List<E> removeIf(List<E> list, Filter<? super E> filter) {
//        List<E> removeList = new ArrayList<>();
//        for (E e : list) {
//            if (filter.test(e)) {
//                removeList.add(e);
//            }
//        }
//        list.removeAll(removeList);
//        return removeList;
//    }

//
////     public static<T extends Number> double sum (List<T> list){
//     public static double sum (List<? extends Number> list){
////         T a = (T)2d;
////         list.add(2d);
//         double result = 0;
//         for(Number number: list){
//             result+=number.doubleValue();
//         }
//         return result;
//     }


}