package com.interview.sdk.runtime.thread;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 三方 SDK 线程创建的运行时归因中心。
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  定位：它回答静态扫描**答不了**的那个问题
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  静态扫描（build-logic/sdk-plugin）能告诉你「**在哪儿**可能建线程」；
 *  这里回答「**实际**有多少、谁在持续建、当前还活着多少」。
 *
 *  两者的分工与顺序不能反：
 *    · 静态先跑 —— 零成本、能进 CI、给出可疑清单
 *    · 运行时后跑 —— 真机采样，对可疑点做定量确认
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  ⚠️ 归因口径：为什么要有「未命名 / 归不到」这两个桶
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  归因必须 **MECE**（不重不漏），否则各项占比加起来不是 100%，
 *  报告读者会以为「还有别的没列出来」。
 *
 *  但现实里有**永久归不到类**的部分：
 *   · JVM/系统线程（main、Signal Catcher、Binder:*、FinalizerDaemon…）
 *     它们的创建者堆栈不可得（开机时建的），或属平台不算包名
 *   · 纯 native 线程：没有 Java 堆栈
 *
 *  所以设计成四个桶，且**未归入的落到「未归因」桶并显式列出**，
 *  而不是悄悄丢掉 —— 丢掉会让「三方占 60%」这种结论失去分母的可信度。
 */
public final class ThreadAttributionRegistry {

    /** 单例。⚠️ 刻意用静态单例而非依赖注入：Hook 的入口是被 Agent/hook 库回调的，拿不到上下文。 */
    private static final ThreadAttributionRegistry INSTANCE = new ThreadAttributionRegistry();

    public static ThreadAttributionRegistry get() {
        return INSTANCE;
    }

    /** 是否启用。默认**关** —— 抓堆栈有开销，不能默认影响线上性能。 */
    private volatile boolean enabled = false;

    /**
     * 抓创建者堆栈的采样上限。
     *
     * ⚠️ 为什么必须有上限：`new Throwable().getStackTrace()` 是同步的、要走 JVM
     *    的 `StackTraceElement` 构造，在启动期大量建线程时（某些 SDK 一次建几十个）
     *    开销会叠加。超过上限后**只计数不抓栈** —— 数量统计仍然精确，
     *    只是失去后 50 个的归因。这个取舍应显式告知使用者（见 {@link #render()}）。
     */
    private volatile int stackSampleLimit = 50;

    private final AtomicInteger totalCreated = new AtomicInteger();
    private final AtomicInteger javaCreated = new AtomicInteger();
    private final AtomicInteger nativeCreated = new AtomicInteger();
    private final AtomicInteger capturedStacks = new AtomicInteger();
    private final AtomicInteger overLimit = new AtomicInteger();

    /** 归因键（责任方类名）→ 创建次数 */
    private final Map<String, AtomicInteger> byBlamed = new ConcurrentHashMap<>();

    /** 归因键 → 线程名样本（最多几个，用于交叉验证「是不是这个 SDK 建的」） */
    private final Map<String, List<String>> nameSamples = new ConcurrentHashMap<>();

    /** 最新一次全量存活线程快照，供报告用 */
    private volatile List<ThreadOrigin> lastSnapshot = Collections.emptyList();

    private final AtomicLong baseUptimeMs = new AtomicLong();

    /**
     * ⚠️ 刻意留成**包级可见**（非 private），供单元测试创建隔离实例。
     *
     * <p>测试不能用单例：单例状态跨用例累积，会让「超限计数」这类断言互相污染，
     * 而这种污染表现为偶发失败，极难定位。生产代码仍只走 {@link #get()}。
     */
    ThreadAttributionRegistry() {}

    public boolean isEnabled() {
        return enabled;
    }

    /** 开启归因。⚠️ 建议只在 debug / 灰度 / 性能排查包里开。 */
    public void enable() {
        enable(50);
    }

    public void enable(int stackSampleLimit) {
        this.stackSampleLimit = Math.max(0, stackSampleLimit);
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    public void reset() {
        totalCreated.set(0);
        javaCreated.set(0);
        nativeCreated.set(0);
        capturedStacks.set(0);
        overLimit.set(0);
        byBlamed.clear();
        nameSamples.clear();
        lastSnapshot = Collections.emptyList();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  记录入口
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 记录一次「由 Java 线程发起」的线程创建。
     *
     * <p>调用点：native `pthread_create` hook 的回调（GBPLT hook `pthread_create`），
     * 或 `Thread.UncaughtExceptionHandler` 式的 Java 层包装。**必须在调用者线程上调用**，
     * 因为要抓的正是调用者的堆栈。
     *
     * <p>⚠️ 真实实现里这段逻辑在 C++ 侧（见 app/src/main/cpp/thread_hook.cpp）：
     * hook 后判断当前是否已 attach 到 JVM，是则回调 Java 侧抓堆栈。
     * **为什么堆栈在 Java 侧抓而不是在 native 侧拼**：回调本身就在调用者线程上执行，
     * Java 侧一句 `new Throwable().getStackTrace()` 就够；在 native 侧用 JNI 拼
     * StackTraceElement 要逐帧一次 JNI 往返，代码量约 10 倍。能不跨语言就别跨语言。
     *
     * @param newThreadName 新线程的名字（可由 hook 从 `pthread_getname_np` 拿到，或传默认名）
     * @param daemon 是否 daemon
     */
    public void onThreadCreatedByJava(String newThreadName, boolean daemon) {
        if (!enabled) return;
        totalCreated.incrementAndGet();
        javaCreated.incrementAndGet();

        if (capturedStacks.get() >= stackSampleLimit) {
            overLimit.incrementAndGet();
            return;
        }
        capturedStacks.incrementAndGet();

        Throwable stack = new Throwable("thread-creation-site");
        record(newThreadName, daemon, ThreadOrigin.Creator.JAVA, stack);
    }

    /**
     * 记录一次「由纯 native 线程发起」的线程创建。
     *
     * <p>⚠️ 这条路拿不到 Java 堆栈，且**这不是工具缺陷**：纯 native 线程本来就没有
     * Java 栈可抓。它的价值在于给出一个不同的结论 ——
     * 「这个线程是某个 SDK 的 native 层建的」，而不是「不知道谁建的」。
     * 这类线程只能靠线程名（如 `OkHttp` 的 native 线程、ffmpeg 的 `video_dec`）与
     * native 侧 `backtrace()` 定位。
     */
    public void onThreadCreatedByNative(String newThreadName, boolean daemon) {
        if (!enabled) return;
        totalCreated.incrementAndGet();
        nativeCreated.incrementAndGet();
        record(newThreadName, daemon, ThreadOrigin.Creator.NATIVE, null);
    }

    private void record(
            String threadName,
            boolean daemon,
            ThreadOrigin.Creator creator,
            Throwable stack
    ) {
        String blamed = null;
        if (stack != null) {
            StackTraceElement[] frames = stack.getStackTrace();
            String[] classNames = new String[frames.length];
            for (int i = 0; i < frames.length; i++) classNames[i] = frames[i].getClassName();
            blamed = FrameFilter.blame(classNames);
        }
        String key = blamed != null ? blamed : "«未归因»（native 线程 / 平台内部）";

        byBlamed.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();

        List<String> samples = nameSamples.computeIfAbsent(
                key, k -> Collections.synchronizedList(new ArrayList<>()));
        if (samples.size() < 3 && !samples.contains(threadName)) {
            samples.add(threadName);
        }

        if (baseUptimeMs.get() == 0) baseUptimeMs.set(nowMs());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  存活快照（与创建计数互补）
    // ══════════════════════════════════════════════════════════════════════

    /**
     * 对当前**存活**的线程做一次快照。返回按责任方聚类的计数。
     *
     * <p>⚠️ 为什么需要它，明明已经有「创建次数」了：
     *    两者回答的是不同问题，缺一不可。
     *    · 创建次数 → 「谁在**频繁**建线程」（可能是短命线程，建了就退，危害小）
     *    · 存活快照 → 「谁**占住**了线程」（长期驻留，直接吃地址空间与调度开销）
     *    一个 SDK 可能建了 500 次线程但每次都很快退出（不严重），
     *    另一个建了 20 个线程但全部常驻（严重）。只看创建次数会把两者搞反。
     *
     * <p>⚠️ 这里的「责任方」是**近似**：`Thread.getAllStackTraces()` 给的是
     *    **线程自己的**堆栈，不是创建者的堆栈。所以本方法按
     *    「线程当前正在跑谁的代码」来归类 —— 对常驻工作线程足够准确，
     *    对「已经休眠在 `park()` 的池线程」会归到 `LockSupport`。
     *    要精确到创建者，必须用上面的 {@link #onThreadCreatedByJava} 那一路（Hook）。
     *    这个限制是真实的，报告里必须写出来。
     */
    public Map<String, Integer> snapshotAlive() {
        Map<String, Integer> byOwner = new LinkedHashMap<>();
        List<ThreadOrigin> snapshot = new ArrayList<>();
        long now = nowMs();

        Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
        for (Map.Entry<Thread, StackTraceElement[]> e : all.entrySet()) {
            Thread t = e.getKey();
            String blamed = "«未归因»";

            if (t == Thread.currentThread()) {
                blamed = "本工具自身";
            } else {
                StackTraceElement[] frames = e.getValue();
                String[] classNames = new String[frames.length];
                for (int i = 0; i < frames.length; i++) classNames[i] = frames[i].getClassName();
                String b = FrameFilter.blame(classNames);
                if (b != null) blamed = b;
            }

            byOwner.merge(blamed, 1, Integer::sum);
            snapshot.add(new ThreadOrigin(
                    t.getName(), t.isDaemon(), ThreadOrigin.Creator.JAVA, null, blamed, now));
        }
        lastSnapshot = snapshot;
        return byOwner;
    }

    public List<ThreadOrigin> lastSnapshot() {
        return lastSnapshot;
    }

    // ══════════════════════════════════════════════════════════════════════
    //  报告
    // ══════════════════════════════════════════════════════════════════════

    public int totalCreated() { return totalCreated.get(); }
    public int javaCreated() { return javaCreated.get(); }
    public int nativeCreated() { return nativeCreated.get(); }
    public int overLimitCount() { return overLimit.get(); }

    /** 渲染成人类可读报告。⚠️ 末尾必须带上局限说明 —— 见方法的实现。 */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("══════ 三方 SDK 线程归因报告 ══════\n");
        sb.append("采样开关: ").append(enabled ? "开" : "关（数据无意义）").append('\n');
        sb.append("线程创建总数: ").append(totalCreated.get()).append('\n');
        sb.append("  其中 Java 线程发起: ").append(javaCreated.get()).append('\n');
        sb.append("  其中纯 native 发起: ").append(nativeCreated.get())
                .append("（无 Java 堆栈可溯源，属事实而非工具缺陷）\n");

        if (overLimit.get() > 0) {
            sb.append("  ⚠️ 超出采样上限未抓栈: ").append(overLimit.get())
                    .append(" 次 —— 数量仍计入总数，但失去归因。\n");
        }

        sb.append("\n── 按责任方（谁建的）──\n");
        renderMap(sb, byBlamed);

        sb.append("\n── 当前存活线程（线程自己正在跑谁的代码，非创建者）──\n");
        if (lastSnapshot.isEmpty()) {
            sb.append("（未做快照，调用 snapshotAlive() 后可见）\n");
        } else {
            Map<String, Integer> alive = new LinkedHashMap<>();
            for (ThreadOrigin o : lastSnapshot) alive.merge(o.blamedClass, 1, Integer::sum);
            renderMap(sb, immutable(alive));
            sb.append("存活线程总数: ").append(lastSnapshot.size()).append('\n');
        }

        // ⚠️ 局限必须写在报告里，不能只写在文档里 —— 报告会被脱离上下文传播。
        sb.append("\n── 局限（读结论前必看）──\n");
        sb.append("· 「是按责任方」来自创建时的调用栈，**仅覆盖由 Java 线程发起的创建**。\n");
        sb.append("· 纯 native 线程（如多媒体/加解密 SDK）无法归因到 Java 责任方，已单列。\n");
        sb.append("· 存活快照按「线程当前执行位置」归类，休眠中的池线程会归到 park/LockSupport，\n");
        sb.append("  因此它与「按责任方」两个表**不应该逐行对应** —— 这是两种不同口径。\n");
        sb.append("· 加壳/加固 SDK：其线程由解密后的代码创建，静态扫描看不到，但**本手段仍能抓到**\n");
        sb.append("  （Hook 点在 libc，与被 hook 代码是否加壳无关）—— 这正是运行时归因不可替代之处。\n");
        return sb.toString();
    }

    private void renderMap(StringBuilder sb, Map<String, AtomicInteger> map) {
        if (map.isEmpty()) {
            sb.append("（无数据）\n");
            return;
        }
        List<Map.Entry<String, AtomicInteger>> list = new ArrayList<>(map.entrySet());
        list.sort(Comparator.comparingInt((Map.Entry<String, AtomicInteger> e) -> e.getValue().get()).reversed());
        int total = 0;
        for (Map.Entry<String, AtomicInteger> e : list) total += e.getValue().get();

        for (Map.Entry<String, AtomicInteger> e : list) {
            int n = e.getValue().get();
            sb.append(String.format("  %6d  %5.1f%%  %s", n, total == 0 ? 0f : n * 100f / total, e.getKey()));
            List<String> samples = nameSamples.get(e.getKey());
            if (samples != null && !samples.isEmpty()) {
                sb.append("   样本线程名: ").append(samples);
            }
            sb.append('\n');
        }
    }

    private static Map<String, AtomicInteger> immutable(Map<String, Integer> src) {
        Map<String, AtomicInteger> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : src.entrySet()) {
            out.put(e.getKey(), new AtomicInteger(e.getValue()));
        }
        return out;
    }

    /** 用于日志/Dump 的完整堆栈字符串（含未过滤的原始帧）。 */
    public static String dumpStack(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static long nowMs() {
        // ⚠️ 刻意不用 System.currentTimeMillis()：它受用户改系统时间/NTP 校时影响，
        //    会出现「后建的线程时间戳更早」。uptime 单调递增，才适合做时序。
        //    不用 android.os.SystemClock 是为了让本类能在纯 JVM 单元测试里跑。
        return System.nanoTime() / 1_000_000L;
    }
}
