package com.interview.sdk.runtime.thread;

import java.util.Objects;

/**
 * 一次线程创建事件的归因结果。
 *
 * <p>⚠️ 这个类是整个运行时归因的**交付物**：它的字段设计决定了报告能回答什么问题。
 * 特别地，{@link #creatorAttribution} 的取值刻意只有三种，而不是「有/无堆栈」两种 ——
 * 因为「拿不到 Java 堆栈」有两种截然不同的含义，混在一起会让人得出错误结论
 * （详见该字段的注释）。
 */
public final class ThreadOrigin {

    /** 新线程的名字。⚠️ 可能是默认名（Thread-7 / pool-1-thread-2），这本身就是重要信息。 */
    public final String threadName;

    /** 是否 daemon。daemon 线程随进程退出，危害小于非 daemon 常驻线程。 */
    public final boolean daemon;

    /**
     * 创建者归因。
     *
     * <p>三态而不是两态，这是本类的核心设计：
     * <ul>
     *   <li>{@link #JAVA} —— 创建者是已 attach 的 Java 线程。拿到了完整 Java 堆栈，
     *       {@link #creatorStack} 就是「谁建的」。</li>
     *   <li>{@link #NATIVE} —— 创建者是纯 native 线程。**它本来就没有 Java 堆栈**，
     *       拿不到不是工具能力问题，而是事实如此。这类线程只能靠线程名与 native
     *       调用栈定位。</li>
     *   <li>{@link #ALREADY_SHUTTING_DOWN} —— 创建者线程正在退出，堆栈不可靠。</li>
     * </ul>
     *
     * <p>如果把后两种都歸成「未知」，就会把「某个 SDK 的 native 层在建线程」
     * 这种**很有价值的结论**给淹没掉。
     */
    public enum Creator { JAVA, NATIVE, ALREADY_SHUTTING_DOWN }

    public final Creator creatorAttribution;

    /**
     * 创建者的 Java 堆栈（已过滤框架帧）。
     * 仅当 {@link #creatorAttribution} 为 {@link Creator#JAVA} 时非空。
     */
    public final Throwable creatorStack;

    /**
     * 归因到的「责任方」：堆栈里第一个不属于框架的类名 —— 也就是最接近
     * 「业务代码/某个 SDK 主动建线程」的那一层。
     *
     * <p>这是给人看的结论字段。要做 MECE 的归因则用
     * {@link ThreadAttributionRegistry#inferOwnerPackage()}。
     */
    public final String blamedClass;

    /** 创建时间（SystemClock.uptimeMillis，跨进程可对齐；不用 wall clock） */
    public final long atUptimeMs;

    public ThreadOrigin(
            String threadName,
            boolean daemon,
            Creator creatorAttribution,
            Throwable creatorStack,
            String blamedClass,
            long atUptimeMs
    ) {
        this.threadName = threadName;
        this.daemon = daemon;
        this.creatorAttribution = creatorAttribution;
        this.creatorStack = creatorStack;
        this.blamedClass = blamedClass;
        this.atUptimeMs = atUptimeMs;
    }

    /** 首个业务帧（跳过 Thread 机制本身）。返回 null 表示堆栈里全是框架帧。 */
    public String firstBusinessFrame() {
        if (creatorStack == null) return null;
        for (StackTraceElement e : creatorStack.getStackTrace()) {
            String cn = e.getClassName();
            if (FrameFilter.isMechanismFrame(cn) || FrameFilter.isPlatformFrame(cn)) continue;
            return cn + "#" + e.getMethodName() + ":" + e.getLineNumber();
        }
        return null;
    }

    @Override
    public String toString() {
        return "ThreadOrigin{" + threadName + ", " + creatorAttribution
                + ", blamed=" + blamedClass + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ThreadOrigin)) return false;
        ThreadOrigin that = (ThreadOrigin) o;
        return daemon == that.daemon
                && atUptimeMs == that.atUptimeMs
                && Objects.equals(threadName, that.threadName)
                && creatorAttribution == that.creatorAttribution;
    }

    @Override
    public int hashCode() {
        return Objects.hash(threadName, daemon, creatorAttribution, atUptimeMs);
    }
}
