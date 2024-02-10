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
                    Thread.sleep(200);

                    System.out.println("now lock is " + lock);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

        }).start();
        new Thread(()->{
            synchronized (lock) {
                try {
                    System.out.println("now lock is " + lock);
                    lock = new Object();
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

        }).start();
    }


}
