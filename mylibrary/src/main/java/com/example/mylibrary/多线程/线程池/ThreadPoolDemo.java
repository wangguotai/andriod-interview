package com.example.mylibrary.多线程.线程池;


import java.util.concurrent.*;

public class ThreadPoolDemo {
    public static void main(String[] args) {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            // 2. 提交多个异步任务
            Future<String> future1 = executor.submit(new Task("任务1", 1000));
            Future<String> future2 = executor.submit(new Task("任务3", 2000));
            Future<String> future3 = executor.submit(new Task("任务3", 1500));

            // 3. 获取任务结果
            System.out.println(future1.get()); // 阻塞直到任务1完成
            System.out.println(future2.get(2500, TimeUnit.MILLISECONDS)); // 带超时获取
            System.out.println(future3.get());
        } catch (InterruptedException e) {
            System.err.println("任务被中断: " + e.getMessage());
        } catch (ExecutionException e) {
            System.err.println("任务执行异常: " + e.getCause().getMessage());
        } catch (TimeoutException e) {
            System.err.println("获取结果超时");
        } finally {
            // 4. 关闭线程池
            executor.shutdown();
            try {
                if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
        }
    }


    static class Task implements Callable<String> {

        private final String name;
        private final long delay;

        public  Task(String name, long delay) {
            this.name = name;
            this.delay = delay;
        }

        @Override
        public String call() throws Exception {
            System.out.println(Thread.currentThread().getName() + " 开始执行 " + name);
            Thread.sleep(delay);

            if ("任务2".equals(name)) {
                throw new RuntimeException("任务2模拟异常");
            }
            return name + " 完成，耗时 " + delay + "ms";
        }
    }
}
