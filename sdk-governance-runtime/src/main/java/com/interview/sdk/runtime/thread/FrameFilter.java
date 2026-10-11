package com.interview.sdk.runtime.thread;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 堆栈帧过滤：把「建线程的机制本身」和「平台/框架代码」与「业务调用点」分开。
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  为什么这个过滤器是归因质量的决定性因素
 * ══════════════════════════════════════════════════════════════════════════
 *
 * 取到的堆栈长这样（从内到外）：
 *
 *   java.lang.Thread.<init>
 *   java.lang.Thread.start
 *   java.util.concurrent.ThreadPoolExecutor$Worker.<init>   ← 机制
 *   java.util.concurrent.ThreadPoolExecutor.addWorker        ← 机制
 *   java.util.concurrent.ThreadPoolExecutor.execute          ← 机制
 *   com.google.android.gms.internal.zzabc.run                ← 真正的责任方
 *   com.example.myapp.MainActivity.onCreate                 ← 业务调用方
 *
 * 不做过滤的话，你会看到「100% 的线程都是 Thread.start 创建的」——
 * 这句话正确但毫无用处。归因的价值全部来自**跳过前几层机制帧**。
 *
 * ⚠️ 但「跳多少层」不能靠固定数字（`stack[3]` 这种写法）：
 *    线程池、Coroutine、RxJava、HandlerThread 的调用深度各不相同，
 *    固定下标在某些路径上会跳过真正的责任方，在另一些路径上会停在机制帧。
 *    所以必须**按类名做语义过滤**，而不是按下标。
 */
public final class FrameFilter {

    /** 建线程的机制本身：这些帧永远不是「责任方」。 */
    private static final Set<String> MECHANISM_PREFIXES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "java.lang.Thread",
            "java.lang.Throwable",
            "java.lang.Object",
            "java.util.concurrent.ThreadPoolExecutor",
            "java.util.concurrent.ScheduledThreadPoolExecutor",
            "java.util.concurrent.Executors",
            "java.util.concurrent.ForkJoin",
            "java.util.concurrent.ThreadFactory",
            "java.util.Timer",
            "android.os.HandlerThread",
            "android.os.AsyncTask",
            "android.os.Handler",
            "kotlin.concurrent.ThreadsKt",
            "kotlinx.coroutines.internal.ThreadContextKt",
            "kotlinx.coroutines.scheduling",
            "kotlinx.coroutines.CoroutineDispatcher",
            "io.reactivex.internal.schedulers",
            "com.interview.sdk.runtime.thread"   // 本工具自身
    )));

    /**
     * 平台 / AndroidX 框架帧。
     *
     * ⚠️ 与机制帧**分开归类**是有意的：框架帧里的
     * `androidx.arch.core.executor.DefaultTaskExecutor` 之类，
     * 其实是**有价值的答案**（说明线程是 AndroidX 架构组件建的）。
     * 如果把它和机制帧一起丢掉，就永远答不出「这个线程是 AndroidX 建的」。
     */
    private static final Set<String> PLATFORM_PREFIXES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "android.",
            "androidx.",
            "com.android.",
            "dalvik.",
            "libcore.",
            "com.google.android.material."
    )));

    private FrameFilter() {}

    /** 是否是「建线程的机制」帧 —— 归因时必须跳过。 */
    public static boolean isMechanismFrame(String className) {
        return startsWithAny(className, MECHANISM_PREFIXES);
    }

    /** 是否是平台帧 —— 保留为线索，但不算「业务责任方」。 */
    public static boolean isPlatformFrame(String className) {
        return startsWithAny(className, PLATFORM_PREFIXES);
    }

    /**
     * 归因到「责任方类名」。
     *
     * 判定顺序（**不能颠倒**）：
     *   1. 跳过机制帧 —— 否则会停在 Thread.start 上
     *   2. 平台帧**优先返回**（若它出现在机制帧之后、业务帧之前）
     *      → 这是 AndroidX / 系统的 DefaultTaskExecutor 之类的场景，
     *        它们本身就是答案
     *   3. 否则返回第一个真正的业务帧
     *
     * @return 责任方类名（点号形式）；全被过滤时返回 null
     */
    public static String blame(String[] classNames) {
        String firstBusiness = null;
        for (String cn : classNames) {
            if (cn == null) continue;
            if (isMechanismFrame(cn)) continue;
            if (isPlatformFrame(cn)) {
                // 平台帧紧跟在机制帧之后出现，说明是框架的默认执行器在起作用，
                // 它本身就是最有信息量的答案 —— 直接返回。
                return cn;
            }
            if (firstBusiness == null) firstBusiness = cn;
        }
        return firstBusiness;
    }

    private static boolean startsWithAny(String s, Set<String> prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p)) return true;
        }
        return false;
    }
}
