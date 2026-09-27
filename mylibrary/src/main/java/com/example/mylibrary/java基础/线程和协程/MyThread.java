package com.example.mylibrary.java基础.线程和协程;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Java中实现线程的4种方法：
 * 1. 继承Thread
 * 2. 实现Runnable接口传入 Thread
 * 3. 使用线程池
 */
public class MyThread {
    public static class SpThread extends Thread {
        protected SpThread() {
            super();
        }
        protected SpThread (Runnable target) {
            super(target);
        }
        @Override
        public void run() {
            super.run();

        }
    }
    public static void main(String[] args) throws ExecutionException, InterruptedException {
        new SpThread().start();
        new SpThread(()-> {

        }).start();

        // 3. 使用callable + FutureTask
        Callable<Integer> task =  () -> 1 + 1;
        FutureTask<Integer> futureTask = new FutureTask<>(task);
        new Thread(futureTask);
        futureTask.get();
        futureTask.cancel(true);
        // 4. 使用线程池： ThreadPool
        ThreadPoolExecutor service = new ThreadPoolExecutor(3, 10, 10, TimeUnit.MINUTES, new SynchronousQueue<>());
        service.shutdownNow();
    }
}
