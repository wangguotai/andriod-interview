package com.interview.thread.lint

import com.android.tools.lint.client.api.UElementHandler
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.android.tools.lint.detector.api.TextFormat
import com.intellij.psi.PsiClass
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UastCallKind

/**
 * Time: 2026/9/28
 * Author: wgt
 * Description: 线程滥用 Lint 规则 —— 卡在 CI 上的「规范层」执行者
 *
 * ─── 为什么需要它 ───
 *
 * 线程治理方案里，规范层（统一池收口）若无强制手段，就只是文档里的愿望。
 * code review 会漏、会疲劳、会被人情放过；Lint 不会。
 *
 * 本规则拦两类「绕过收口」的写法：
 *   1. `new Thread(...)` / `Thread(...)` / `new HandlerThread(...)` 及其子类
 *   2. `Executors.newXxx(...)` 各类线程池工厂
 *
 * 目标是让所有线程创建都必须走 `com.interview.thread.ThreadPools`。
 *
 * ─── 为什么用 UAST 而不是纯 PSI ───
 *
 * 同一份规则必须同时覆盖 Java 与 Kotlin：
 *   Kotlin:  `Thread { ... }`      ← 尾部 lambda 语法，构造调用长得像函数调用
 *   Java:    `new Thread(...)`
 * UAST 把两者都归一成 [UCallExpression]（`kind == CONSTRUCTOR_CALL`），
 * 所以一套逻辑即可覆盖两种语言。纯 PSI 需要按语言分别处理。
 *
 * ─── 覆盖边界（诚实说明）───
 *
 * · 只作用于**本项目源码**。三方 SDK 是二进制依赖，Lint 看不到其源码 ——
 *   那类要靠 ASM 插桩（第 3 层收敛）或 Native Hook（第 4 层）兜底。
 * · 反射创建线程（`Class.forName("java.lang.Thread")`）拦不住。
 * · `Executors.defaultThreadFactory()` 未纳入：它返回工厂而非池，
 *   单独使用不构成池泛滥，误伤大于收益。
 */
class ThreadMisuseDetector : Detector(), SourceCodeScanner {

    /**
     * 用 getApplicableUastTypes + UElementHandler 而非
     * getApplicableConstructorTypes：后者在 Kotlin 的某些构造调用形态下
     * 触发不稳定，直接处理 [UCallExpression] 最可预期。
     */
    override fun getApplicableUastTypes(): List<Class<out UElement>> =
        listOf(UCallExpression::class.java)

    override fun createUastHandler(context: JavaContext): UElementHandler =
        object : UElementHandler() {
            override fun visitCallExpression(node: UCallExpression) {
                when (node.kind) {
                    UastCallKind.CONSTRUCTOR_CALL -> checkConstructor(context, node)
                    UastCallKind.METHOD_CALL -> checkMethodCall(context, node)
                    else -> Unit
                }
            }
        }

    // ─────────────────────────────────────────────
    // 1) 构造调用：Thread / HandlerThread 及其子类
    // ─────────────────────────────────────────────

    private fun checkConstructor(context: JavaContext, node: UCallExpression) {
        val containing = node.resolve()?.containingClass ?: return
        val fqn = containing.qualifiedName

        val issue = when {
            // ⚠️ 顺序不能反：HandlerThread **继承自** Thread，
            // 若先判 Thread 家族会被误分类成 NewThreadUsage。
            // 先判更具体的子类，再判家族。
            fqn == HANDLER_THREAD -> NEW_HANDLER_THREAD
            isThreadFamily(containing) -> NEW_THREAD
            else -> return
        }

        // 自建 Thread 子类的类声明处（`class MyThread : Thread()`）会命中
        // 父类构造的 super 调用，这里不重复报 —— 报在 super 调用点的位置
        // 和用户实际写出 new 的地方一致，无需特殊处理。
        context.report(
            issue,
            node,
            context.getCallLocation(node, includeReceiver = false, includeArguments = false),
            issue.getBriefDescription(TextFormat.TEXT) + "（应改用 ThreadPools）",
        )
    }

    /**
     * 判断是否属于 Thread 家族。
     *
     * 必须走父类链：`okhttp3.Dispatcher` 之类的三方库不在此列，但项目自建的
     * `class PitThread : Thread(...)` 属于 —— 它同样绕过统一池。
     *
     * 深度上限防御循环继承这类畸形字节码。
     */
    private fun isThreadFamily(cls: PsiClass): Boolean {
        var cur: PsiClass? = cls
        var depth = 0
        while (cur != null && depth++ < MAX_HIERARCHY_DEPTH) {
            if (cur.qualifiedName == JAVA_LANG_THREAD) return true
            cur = cur.superClass
        }
        return false
    }

    // ─────────────────────────────────────────────
    // 2) 方法调用：Executors.newXxx
    // ─────────────────────────────────────────────

    private fun checkMethodCall(context: JavaContext, node: UCallExpression) {
        val name = node.methodName ?: return
        if (name !in POOL_FACTORY_METHODS) return

        val containing = node.resolve()?.containingClass?.qualifiedName ?: return
        if (containing != EXECUTORS) return

        context.report(
            THREAD_POOL_FACTORY,
            node,
            context.getCallLocation(node, includeReceiver = true, includeArguments = false),
            "Executors.$name(...) 自建线程池会绕过统一治理（应改用 ThreadPools）",
        )
    }

    companion object {
        private const val JAVA_LANG_THREAD = "java.lang.Thread"
        private const val HANDLER_THREAD = "android.os.HandlerThread"
        private const val EXECUTORS = "java.util.concurrent.Executors"
        private const val MAX_HIERARCHY_DEPTH = 64

        private val POOL_FACTORY_METHODS = setOf(
            "newFixedThreadPool",
            "newCachedThreadPool",
            "newSingleThreadExecutor",
            "newScheduledThreadPool",
            "newSingleThreadScheduledExecutor",
            "newWorkStealingPool",
        )

        private val NEW_THREAD = Issue.create(
            id = "NewThreadUsage",
            briefDescription = "直接 new Thread 绕过了统一线程池",
            explanation = """
                `new Thread(...)` 创建的线程不在统一治理范围内，会导致：

                · 线程名不可溯源（线上只能看到 `Thread-12`，不知道谁创建的）
                · 无配额约束，单个模块可无限创建
                · 无法统一设置优先级与 daemon，后台常驻会耗电
                · 无法统一监控与取消

                请改用 `com.interview.thread.ThreadPools` 中按下游资源划分的泳道
                （`network` / `disk` / `dbWrite` / `background`），
                它带语义化命名、有界队列背压、调用方配额与可观测指标。

                确实需要自己管理线程时（如收口层实现本身），
                在该文件上用 `@Suppress("NewThreadUsage")` 并写明理由。
            """.trimIndent(),
            category = Category.PERFORMANCE,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(
                ThreadMisuseDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )

        private val NEW_HANDLER_THREAD = Issue.create(
            id = "HandlerThreadUsage",
            briefDescription = "直接创建 HandlerThread 未纳入统一治理",
            explanation = """
                `android.os.HandlerThread` 是常驻线程，自建同样绕过统一治理：
                线程名不可溯源、无配额、后台常驻会持续唤醒 CPU 导致耗电，
                严重时被系统判定为后台滥用而杀进程。

                若确需串行执行环境，请用 `ThreadPools.single`；
                后台任务请优先考虑 `WorkManager`（受 Doze 管控、可合并调度）。
            """.trimIndent(),
            category = Category.PERFORMANCE,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(
                ThreadMisuseDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )

        private val THREAD_POOL_FACTORY = Issue.create(
            id = "ExecutorsThreadPool",
            briefDescription = "Executors 自建线程池绕过了统一治理",
            explanation = """
                `Executors.newXxx(...)` 创建的是独立线程池，问题与 `new Thread` 同源，
                且更隐蔽：

                · `newCachedThreadPool` 的 `maximumPoolSize` 是 `Integer.MAX_VALUE`
                  且用 `SynchronousQueue`，突发流量下**线程数无上限**，
                  是最典型的线程失控来源
                · 池之间互不隔离，一个 SDK 的慢任务不影响不了别的池
                · 命名、配额、监控全部缺失

                请改用 `com.interview.thread.ThreadPools` 的泳道。
                若确需独立池，应显式提供 `ThreadFactory`（语义化命名 + daemon + 优先级）
                并使用**有界队列**，然后 `@Suppress` 本检查并写明理由。
            """.trimIndent(),
            category = Category.PERFORMANCE,
            priority = 8,
            severity = Severity.ERROR,
            implementation = Implementation(
                ThreadMisuseDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )

        val ISSUES: List<Issue> = listOf(NEW_THREAD, NEW_HANDLER_THREAD, THREAD_POOL_FACTORY)
    }
}
