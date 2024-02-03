package com.example.mylibrary.多线程;

public class SyncronizedDemo {
    private static Object lock = new Object();
    public static void main(String[] args) {
        testSyncronized();
    }
    private static void testSyncronized() {
        new Thread(()->{
            synchronized (lock) {
                try {
//                    lock = new Object();
                    System.out.println("now lock is " + lock);
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

        }).start();
        new Thread(()->{
            synchronized (lock) {
                try {
//                    lock = new Object();
                    System.out.println("now lock is " + lock);
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

        }).start();
    }


}
