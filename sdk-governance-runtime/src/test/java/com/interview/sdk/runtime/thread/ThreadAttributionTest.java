package com.interview.sdk.runtime.thread;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 归因核心逻辑的单元测试。
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  为什么这些测试值得写（而不是「测了个 getter」）
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  {@link FrameFilter#blame} 是整条归因链路的**唯一决策点**，
 *  而它的输入（真实堆栈）在真机上极难复现与构造。如果不在 JVM 单测里
 *  用固定堆栈把它的行为钉住，任何一次「顺手调整过滤顺序」都会静默改变
 *  所有归因结论 —— 且这种改变**不会报错**，只会让报告里的责任方悄悄换人。
 *
 *  另外这些测试全在纯 JVM 上跑（不依赖 Robolectric/真机），
 *  因为 {@link ThreadAttributionRegistry} 刻意避开了 android.os.SystemClock，
 *  这条设计约束正是为了让归因逻辑可被快速回归。
 */
public class ThreadAttributionTest {

    // ══════════════════════════════════════════════════════════════════════
    //  FrameFilter：机制帧必须被跳过
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 最典型的场景：线程池建线程。责任方必须是 SDK 的业务类，
     * **不能**是 ThreadPoolExecutor.addWorker —— 那只是机制。
     */
    @Test
    public void blame_skipsThreadPoolMechanism_findsSdkClass() {
        String[] frames = {
                "java.lang.Thread",                                  // 机制
                "java.util.concurrent.ThreadPoolExecutor$Worker",     // 机制
                "java.util.concurrent.ThreadPoolExecutor",            // 机制
                "com.trackingsdk.internal.Dispatcher",                // ← 真正责任方
                "com.example.app.MainActivity",
        };
        assertEquals("com.trackingsdk.internal.Dispatcher", FrameFilter.blame(frames));
    }

    /**
     * ⚠️ 回归测试：这条用例保护一个**极易写反**的判定。
     *
     * 平台帧（androidx.*）出现在机制帧之后时，它本身就是答案 ——
     * 说明是 AndroidX 的默认执行器在起作用，而不是某个业务类。
     * 若把平台帧和机制帧一起跳过，会把责任方错误地算到更外层的业务调用者身上，
     * 于是「AndroidX 自建线程」被误报成「业务代码建线程」，误导优化方向。
     */
    @Test
    public void blame_platformFrameAfterMechanism_isTheAnswer() {
        String[] frames = {
                "java.lang.Thread",
                "java.util.concurrent.ThreadPoolExecutor",
                "androidx.arch.core.executor.DefaultTaskExecutor",   // ← 就是它
                "com.example.app.MainActivity",
        };
        assertEquals("androidx.arch.core.executor.DefaultTaskExecutor", FrameFilter.blame(frames));
    }

    /** 工具自身的帧必须被跳过，否则所有归因都会指向「本工具自身」。 */
    @Test
    public void blame_skipsOwnInstrumentationFrames() {
        String[] frames = {
                "java.lang.Thread",
                "com.interview.sdk.runtime.thread.ThreadAttributionRegistry",
                "com.interview.sdk.runtime.thread.ThreadAttributionRegistry$1",
                "com.vendor.sdk.Worker",
        };
        assertEquals("com.vendor.sdk.Worker", FrameFilter.blame(frames));
    }

    /** 纯 native 场景：没有可归因的 Java 帧 → 必须返回 null，而不是随便返回一帧。 */
    @Test
    public void blame_allMechanismFrames_returnsNull() {
        String[] frames = {
                "java.lang.Thread",
                "java.util.concurrent.ThreadPoolExecutor",
        };
        assertNull(FrameFilter.blame(frames));
    }

    /** Kotlin 的 `thread {}` 与协程调度器也属机制，不能成为责任方。 */
    @Test
    public void blame_skipsKotlinAndCoroutineMechanism() {
        String[] frames = {
                "kotlin.concurrent.ThreadsKt",
                "kotlinx.coroutines.scheduling.CoroutineScheduler",
                "com.pushsdk.HeartbeatTask",
        };
        assertEquals("com.pushsdk.HeartbeatTask", FrameFilter.blame(frames));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ThreadOrigin：firstBusinessFrame 的过滤
    // ══════════════════════════════════════════════════════════════════════

    /**
     * ⚠️ 这条用例曾经写错、且错得很典型，值得留档：
     *
     * 第一版在测试类里直接 `new Throwable()`，断言首个业务帧以
     * `com.interview.sdk.runtime.thread.` 开头 —— 然后失败。
     * 原因是过滤器会跳过**工具自身包的帧**，而测试类正好在那个包下，
     * 于是首个业务帧指向了 JUnit 的 runner。
     *
     * 这个失败暴露的其实是**测试的设计缺陷**，不是实现的缺陷：
     * 要验证「业务帧不被过滤」，抛栈点就必须位于一个**中立/业务**包里。
     * 所以改用 {@code com.example.biz.FrameThrower}。
     * （保留这段注释，是因为它是「测试类被放在被测类的包下」这种常见习惯
     *   与「按包名过滤」这种实现之间必然的冲突，下次还会有人踩。）
     */
    @Test
    public void firstBusinessFrame_skipsPlatformAndMechanism() {
        Throwable t = com.example.biz.FrameThrower.throwHere();
        String frame = new ThreadOrigin(
                "x", false, ThreadOrigin.Creator.JAVA, t, null, 0L).firstBusinessFrame();
        assertNotNull("真实堆栈里必然有业务帧", frame);
        // ⚠️ 只断言到 `类#方法`，不断言行号：行号会在无关的编辑（加注释）后失效，
        //    产生「改了注释就测试失败」的假信号。归因的正确性依赖的是类与方法，不是行号。
        assertTrue("首个业务帧应是抛栈点所在业务类: " + frame,
                frame.startsWith("com.example.biz.FrameThrower#throwHere"));
    }

    /** 工具自身的帧必须被过滤掉，否则归因会指向自己。 */
    @Test
    public void firstBusinessFrame_skipsOwnFrames() {
        Throwable t = new Throwable();
        String frame = new ThreadOrigin(
                "x", false, ThreadOrigin.Creator.JAVA, t, null, 0L).firstBusinessFrame();
        assertTrue("工具自身包应被过滤: " + frame,
                frame == null || !frame.startsWith("com.interview.sdk.runtime.thread."));
    }

    @Test
    public void firstBusinessFrame_nullStack_returnsNull() {
        ThreadOrigin o = new ThreadOrigin("n", false, ThreadOrigin.Creator.NATIVE, null, null, 0L);
        assertNull("纯 native 归因没有堆栈，必须返回 null 而不是抛 NPE", o.firstBusinessFrame());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Registry：开关语义与采样上限
    // ══════════════════════════════════════════════════════════════════════

    /** 默认必须是关的 —— 抓堆栈有开销，不能默认影响线上。 */
    @Test
    public void registry_disabledByDefault_recordsNothing() {
        ThreadAttributionRegistry r = new ThreadAttributionRegistry();
        r.onThreadCreatedByJava("t", false);
        assertEquals("未开启时不应记录任何数据", 0, r.totalCreated());
    }

    /**
     * ⚠️ 采样上限的语义必须精确：**总数仍然精确，只是超出部分不抓栈**。
     *    曾经容易写错的是「超限后连计数都不加」——那会让总量偏小，
     *    而「到底建了多少线程」恰恰是本工具最基础的输出，不能有偏差。
     */
    @Test
    public void registry_overSampleLimit_stillCountsTotal() {
        ThreadAttributionRegistry r = new ThreadAttributionRegistry();
        r.enable(2);
        for (int i = 0; i < 10; i++) {
            r.onThreadCreatedByJava("worker-" + i, false);
        }
        assertEquals(10, r.totalCreated());
        assertEquals(8, r.overLimitCount());
        assertTrue("报告必须提示有未归因的部分", r.render().contains("超出采样上限"));
    }

    /**
     * native 创建的线程要**单独计数**，且不能污染 Java 归因。
     * 这是「两种拿不到堆栈的含义不同」在计数层的体现。
     */
    @Test
    public void registry_nativeCreation_countedSeparately() {
        ThreadAttributionRegistry r = new ThreadAttributionRegistry();
        r.enable();
        r.onThreadCreatedByNative("ffmpeg-dec", false);
        assertEquals(1, r.totalCreated());
        assertEquals(0, r.javaCreated());
        assertEquals(1, r.nativeCreated());
        assertTrue(r.render().contains("纯 native 发起: 1"));
    }

    /** 报告必须始终带有局限说明 —— 防止被脱离上下文传播时丢失免责。 */
    @Test
    public void render_alwaysContainsCaveats() {
        ThreadAttributionRegistry r = new ThreadAttributionRegistry();
        r.enable();
        String out = r.render();
        assertTrue(out.contains("局限"));
        assertTrue("必须说明加壳 SDK 也能被本手段覆盖", out.contains("加壳"));
    }

    /** 真实建线程 + 存活快照：验证快照能返回非空且包含自己。 */
    @Test
    public void snapshotAlive_returnsLiveThreads() throws Exception {
        ThreadAttributionRegistry r = new ThreadAttributionRegistry();
        r.enable();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }
        }, "sdk-audit-test-worker");
        t.setDaemon(true);
        t.start();
        try {
            java.util.Map<String, Integer> alive = r.snapshotAlive();
            assertTrue("存活快照不应为空", !alive.isEmpty());
            assertTrue("快照应包含刚起的线程",
                    r.lastSnapshot().stream().anyMatch(o -> "sdk-audit-test-worker".equals(o.threadName)));
        } finally {
            t.interrupt();
        }
    }
}
