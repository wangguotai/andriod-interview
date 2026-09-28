package com.interview.thread.lint

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue
import org.junit.Test

/**
 * Lint 规则的边界测试。
 *
 * 这里最重要的不是「能不能报出来」，而是 **不该报的地方不能报** ——
 * 一个误报率高的规则会被团队整体关掉，比没有规则更糟。
 */
class ThreadMisuseDetectorTest : LintDetectorTest() {

    override fun getDetector(): Detector = ThreadMisuseDetector()

    override fun getIssues(): List<Issue> = ThreadMisuseDetector.ISSUES

    // ─────────────────────────────────────────────
    // 正例：必须报出来
    // ─────────────────────────────────────────────

    @Test
    fun testJavaNewThread() {
        lint()
            .files(
                java(
                    """
                    package test.pkg;
                    public class Bad {
                        public void go() {
                            new Thread(new Runnable() {
                                public void run() { }
                            }).start();
                        }
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectErrorCount(1)
            .expectContains("NewThreadUsage")
    }

    @Test
    fun testKotlinThreadLambda() {
        // Kotlin 的 `Thread { }` 尾部 lambda 是最常见的写法，
        // 也是最容易被 PSI 方案漏掉的形态
        lint()
            .files(
                kotlin(
                    """
                    package test.pkg
                    class Bad {
                        fun go() {
                            Thread { println("x") }.start()
                        }
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectErrorCount(1)
            .expectContains("NewThreadUsage")
    }

    @Test
    fun testKotlinThreadSubclass() {
        lint()
            .files(
                kotlin(
                    """
                    package test.pkg
                    class MyThread(private val r: Runnable) : Thread(r)
                    """.trimIndent(),
                ),
            )
            .run()
            .expectErrorCount(1)
            .expectContains("NewThreadUsage")
    }

    @Test
    fun testExecutorsNewFixedThreadPool() {
        lint()
            .files(
                java(
                    """
                    package test.pkg;
                    import java.util.concurrent.Executors;
                    public class Bad {
                        public void go() {
                            Executors.newFixedThreadPool(4);
                        }
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectErrorCount(1)
            .expectContains("ExecutorsThreadPool")
    }

    @Test
    fun testExecutorsNewCachedThreadPool() {
        // 最危险的工厂：maximumPoolSize 是 Integer.MAX_VALUE 且用 SynchronousQueue
        lint()
            .files(
                kotlin(
                    """
                    package test.pkg
                    import java.util.concurrent.Executors
                    class Bad {
                        fun go() = Executors.newCachedThreadPool()
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectErrorCount(1)
            .expectContains("ExecutorsThreadPool")
    }

    @Test
    fun testHandlerThread() {
        lint()
            .files(
                kotlin(
                    """
                    package test.pkg
                    import android.os.HandlerThread
                    class Bad {
                        fun go() = HandlerThread("worker").start()
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectErrorCount(1)
            .expectContains("HandlerThreadUsage")
    }

    // ─────────────────────────────────────────────
    // 反例：绝对不能报（误伤检查）
    // ─────────────────────────────────────────────

    @Test
    fun testDefaultThreadFactoryClean() {
        // 收口层本身用 ThreadPoolExecutor + ThreadFactory，必须放行。
        // 注意 NamedThreadFactory 内部确实 new Thread —— 那是**唯一合法**的落点。
        lint()
            .files(
                kotlin(
                    """
                    package test.pkg
                    import java.util.concurrent.Executors
                    import java.util.concurrent.ThreadFactory
                    class Good {
                        fun go() {
                            // Executors.defaultThreadFactory() 不构成池泛滥，不拦
                            val f: ThreadFactory = Executors.defaultThreadFactory()
                            println(f)
                        }
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectClean()
    }

    @Test
    fun testJavaThreadStaticMethodsClean() {
        // Thread.sleep / currentThread 是常用 API，误拦会很痛
        lint()
            .files(
                java(
                    """
                    package test.pkg;
                    public class Good {
                        public void go() throws Exception {
                            Thread.sleep(100);
                            Thread t = Thread.currentThread();
                            t.interrupt();
                        }
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectClean()
    }

    @Test
    fun testKotlinThreadStaticMethodsClean() {
        lint()
            .files(
                kotlin(
                    """
                    package test.pkg
                    class Good {
                        fun go() {
                            Thread.sleep(100)
                            val t = Thread.currentThread()
                            println(t.name)
                        }
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectClean()
    }

    @Test
    fun testSameNameFactoryClean() {
        // 只认 java.util.concurrent.Executors，不能按方法名瞎报
        lint()
            .files(
                kotlin(
                    """
                    package test.pkg
                    object MyExecutors {
                        fun newFixedThreadPool(n: Int) = n
                    }
                    class Good {
                        fun go() = MyExecutors.newFixedThreadPool(4)
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectClean()
    }

    // ─────────────────────────────────────────────
    // 抑制机制：必须有效，否则团队只能整体关规则
    // ─────────────────────────────────────────────

    @Test
    fun testSuppressAnnotation() {
        lint()
            .files(
                kotlin(
                    """
                    package test.pkg
                    class Intentional {
                        @Suppress("NewThreadUsage")
                        fun go() {
                            Thread { println("x") }.start()
                        }
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectClean()
    }
}
