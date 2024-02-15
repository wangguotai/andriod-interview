package com.interview.leakcanary;

import java.lang.ref.PhantomReference;
import java.lang.ref.ReferenceQueue;

/**
 * Time: 2024/2/15
 * Author: wgt
 * Description:
 */
public class Main {
    public static void main(String[] args) {
        Object o1 = new Object();
        ReferenceQueue<Object> referenceQueue = new ReferenceQueue<>();
        // 虚引用只有一个构造方法，必出传入引用队列
        PhantomReference<Object> phantomReference = new PhantomReference<>(o1, referenceQueue);

        System.out.println(o1);
        System.out.println(phantomReference.get());
        System.out.println("引用队列：" + referenceQueue.poll());

        // 使 o1 = null;可以 被垃圾回收
        o1 = null;
        System.out.println("==========gc后=============");
        System.gc();
        System.out.println(o1);
        System.out.println(phantomReference.get());
        System.out.println("引用队列：" + referenceQueue.poll());
    }

}
