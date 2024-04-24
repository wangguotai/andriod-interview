package com.example.mylibrary.leetcode.堆.p215第K个最大的元素;

import java.util.concurrent.CountDownLatch;

public class Temp {
    //    int whoCanReferMe = 0;
//
//    static class ICannotRefer {
//
//    }
//
//    class ICanRefer {
//        private void test() {
//            System.out.println(whoCanReferMe);
//        }
//    }
    private static volatile int num = 1;

    public static void main(String[] args) {
        Thread[] threads = new Thread[3];
//    ReentrantLock lock =new ReentrantLock();
        CountDownLatch latch = new CountDownLatch(6);
        final int[] index = {1, 2, 3};
        Object lock = new Object();
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(new Runnable() {
                private int id; // 1 ,  2, 3

                @Override
                public void run() {
                    while (true) {
                        synchronized (lock) {
                            if (id == num % 4) {
                                System.out.println(num++);
                                lock.notifyAll();
                            } else {
                                try {
                                    lock.wait();
                                } catch (InterruptedException e) {
                                    throw new RuntimeException(e);
                                }
                            }
                        }
                    }


//                if( index[id] == latch.getCount() % 4){
//                    System.out.println(num++);
//                    latch.countDown();
//                }
//
                }
            });
            threads[i].start();
//        threads[i].getId() = 1;
        }
    }

}
