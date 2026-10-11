package com.interview.sdk.plugin.io

import com.interview.sdk.plugin.ThreadCreationSite
import com.interview.sdk.plugin.ThreadKind
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import java.io.File

/**
 * 一个依赖产物的「全部静态事实」——单次遍历产出，四个审计维度共用。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  ⚠️ 为什么要做「单次遍历」而不是每个维度各扫一遍
 * ══════════════════════════════════════════════════════════════════════
 * app 的依赖闭包有上百个 aar，每个 aar 内部还有 `classes.jar` 的嵌套解压。
 * 四个维度各扫一遍 = 4 倍 IO + 4 倍解压，构建时间会不可接受。
 * 更关键的是**结论一致性**：同一份字节码被两次解析，可能因异常吞掉
 * 而得到不同的类集合，进而让「重复类检测」和「体积归属」互相打架。
 * 单次遍历让所有维度共享同一份快照，天然自洽。
 *
 * ⚠️ 为什么用 ASM Tree API 而不是 Visitor API
 * 本扫描要看**指令上下文**，Visitor 是流式的，拿不到「前一条指令」：
 *   · `new ThreadPoolExecutor(0, MAX, ..., queue)` —— 要回看栈上构造的参数，
 *     才知道 core/max 与队列类型（判断是否无界队列）
 *   · `new Thread(runnable, "name")` —— 要看调用的是哪个重载，才知道有没有命名
 * Tree API 把这些指令变成可索引的列表，回看是 O(1)。
 * 代价是内存占用高些（每个类建一棵指令树），但审计是离线任务，可接受。
 */
internal data class ArtifactFacts(
    /** 该产物定义的全部类（JVM 内部名） */
    val classes: MutableSet<String> = HashSet(),
    /**
     * 字符串常量 → 出现位置（`类#方法`）。
     * ⚠️ 用 Map 而不是 Set：报告必须能回答「在哪用的」，
     *    只给「用了」等于让人自己再去翻一遍字节码，工具就白做了。
     *    同一个字符串可能出现在多处，只记第一处（足够定位）。
     */
    val stringConstants: MutableMap<String, String> = HashMap(),
    /** 成员引用 `owner#name` —— API 隐含权限反查的输入 */
    val memberRefs: MutableSet<String> = HashSet(),
    /** 本产物内继承 java/lang/Thread 的类 —— 用于识别 `new XxxThread()` */
    val threadSubclasses: MutableSet<String> = HashSet(),
    /** 线程 / 执行器创建点 */
    val threadSites: MutableList<ThreadCreationSite> = ArrayList(),
    /** 定义类的字节总量（代码维度的体积归属依据） */
    var codeBytes: Long = 0,
)

/**
 * 扫描一个产物（jar / aar / 目录）产出 [ArtifactFacts]。
 *
 * ⚠️ 两遍扫描是**刻意**的（先收集类层级，再分析指令）：
 *    判断 `new MyWorkerThread()` 是不是「建线程」，需要先知道 MyWorkerThread
 *    是否继承 Thread。而类定义与使用处可能在不同 class 文件里，
 *    一遍扫完就拿不到这个信息。两遍的代价是 CPU 而非 IO（字节已读入），可接受。
 */
internal object ArtifactScanner {

    fun scan(source: File, owner: String): ArtifactFacts {
        val facts = ArtifactFacts()

        // ─── 第一遍：类清单 + 继承 Thread 的子类 + 代码字节量 ───
        BytecodeWalker.walkClasses(source) { name, reader, byteSize ->
            facts.classes.add(name)
            facts.codeBytes += byteSize.toLong()
            if (reader.superName == "java/lang/Thread") facts.threadSubclasses.add(name)
        }

        // ─── 第二遍：指令级分析 ───
        BytecodeWalker.walkClasses(source) { _, reader, _ ->
            val node = ClassNode()
            reader.accept(node, ClassReader.SKIP_FRAMES)
            analyzeClass(node, owner, facts)
        }
        return facts
    }

    private fun analyzeClass(node: ClassNode, owner: String, facts: ArtifactFacts) {
        node.methods?.forEach { method ->
            val insns = method.instructions ?: return@forEach
            val where = "${node.name}#${method.name}"
            for (i in 0 until insns.size()) {
                val insn = insns[i]

                // ── 字符串常量：权限名、线程名等（记录出现位置）──
                if (insn is LdcInsnNode && insn.cst is String) {
                    val s = insn.cst as String
                    if (s.isNotEmpty() && s.length < 256) facts.stringConstants.putIfAbsent(s, where)
                }

                // ── 成员引用：API 隐含权限反查 + 线程池 API 识别 ──
                if (insn is MethodInsnNode) {
                    facts.memberRefs.add("${insn.owner}#${insn.name}")
                } else if (insn is FieldInsnNode) {
                    facts.memberRefs.add("${insn.owner}#${insn.name}")
                }

                // ── 线程 / 执行器创建点 ──
                detectThreadSite(node, method, insn, owner, facts)
            }
        }
    }

    private fun detectThreadSite(
        node: ClassNode,
        method: MethodNode,
        insn: AbstractInsnNode,
        owner: String,
        facts: ArtifactFacts,
    ) {
        fun add(kind: ThreadKind, detail: String) {
            facts.threadSites.add(
                ThreadCreationSite(
                    owner = owner,
                    className = node.name,
                    method = "${method.name}${method.desc}",
                    kind = kind,
                    detail = detail,
                )
            )
        }

        when (insn) {
            // ── 线程池工厂：Executors.newXxx ──
            is MethodInsnNode -> when {
                insn.owner == "java/util/concurrent/Executors" && insn.name.startsWith("new") -> {
                    val kind = when (insn.name) {
                        "newSingleThreadExecutor", "newSingleThreadScheduledExecutor" -> ThreadKind.EXECUTOR_FACTORY
                        "newScheduledThreadPool" -> ThreadKind.SCHEDULED_EXECUTOR
                        else -> ThreadKind.EXECUTOR_FACTORY
                    }
                    add(kind, "factory=Executors.${insn.name}")
                }
                // ── HandlerThread ──
                insn.name == "<init>" && insn.owner == "android/os/HandlerThread" ->
                    add(ThreadKind.HANDLER_THREAD, "class=HandlerThread")
                // ── Timer ──
                insn.name == "<init>" && insn.owner == "java/util/Timer" ->
                    add(ThreadKind.TIMER, "class=Timer")
                // ── AsyncTask（已废弃但存量 SDK 大量使用）──
                insn.owner == "android/os/AsyncTask" ->
                    add(ThreadKind.ASYNC_TASK, "class=AsyncTask")
                // ── 协程专用单线程调度器（本质也是独占线程）──
                insn.owner == "kotlinx/coroutines/ExecutorsKt" || insn.name == "newSingleThreadContext" ->
                    add(ThreadKind.COROUTINE_NEW_SINGLE_THREAD, "factory=newSingleThreadContext")
                // ── ThreadPoolExecutor 构造：抓 core/max/队列 ──
                insn.name == "<init>" &&
                        (insn.owner == "java/util/concurrent/ThreadPoolExecutor" ||
                                insn.owner == "java/util/concurrent/ScheduledThreadPoolExecutor") -> {
                    val detail = describePool(insn, method)
                    add(ThreadKind.THREAD_POOL_EXECUTOR, detail)
                }
                // ── new Thread(...) / new XxxThread(...) ──
                insn.name == "<init>" && (
                        insn.owner == "java/lang/Thread" ||
                                facts.threadSubclasses.contains(insn.owner)
                        ) -> {
                    val kind = if (insn.owner == "java/lang/Thread") ThreadKind.NEW_THREAD
                    else ThreadKind.NEW_THREAD_SUBCLASS
                    val named = insn.desc.contains("Ljava/lang/String;")
                    add(kind, "class=${insn.owner.substringAfterLast('/')}" + if (named) " name=explicit" else "")
                }
            }
        }
    }

    /**
     * 从 `ThreadPoolExecutor(int, int, long, TimeUnit, BlockingQueue)` 的调用点
     * 回看栈上的构造参数，提取 core/max 与队列是否有界。
     *
     * ⚠️ 只做**保守**判定：回看不到常量就标 unknown，**绝不猜**。
     *    猜错的后果很严重 —— 把有界队列误报成无界（虚报风险）尚可忍受，
     *    把无界误报成有界会让报告漏掉真正的线程数失控风险（漏报）。
     */
    private fun describePool(ctor: MethodInsnNode, method: MethodNode): String {
        val parts = mutableListOf<String>()
        val args = Type.getArgumentTypes(ctor.desc)

        // ── 队列类型：在方法体内找 BlockingQueue 的实例化 ──
        // 因为 ctor 的 queue 参数往往来自前几行的 NEW，且可能经过局部变量中转，
        // 精确的数据流分析成本过高，这里退化为「方法内出现了哪种队列」。
        var queueDesc = "queue=unknown"
        var sawUnbounded = false
        run {
            val insns = method.instructions
            for (i in 0 until insns.size()) {
                val n = insns[i]
                if (n is TypeInsnNode && n.getOpcode() == Opcodes.NEW) {
                    when (n.desc) {
                        "java/util/concurrent/LinkedBlockingQueue" -> {
                            // ⚠️ 关键坑：`new LinkedBlockingQueue()` 无参构造 =
                            //    capacity Integer.MAX_VALUE = **逻辑无界**。
                            //    这是最常见的「配了 maximumPoolSize 却永远不生效」的根因：
                            //    扩容条件是「队列满」，而无界队列永远不满 →
                            //    实际并发恒等于 corePoolSize，max 是死配置。
                            if (isNoArgCtor(insns, i, "java/util/concurrent/LinkedBlockingQueue")) {
                                queueDesc = "queue=unbounded"
                                sawUnbounded = true
                            } else {
                                queueDesc = "queue=bounded(LinkedBlockingQueue)"
                            }
                        }
                        "java/util/concurrent/ArrayBlockingQueue",
                        "java/util/concurrent/SynchronousQueue",
                        "java/util/concurrent/PriorityBlockingQueue" ->
                            queueDesc = "queue=bounded(${n.desc.substringAfterLast('/')})"
                        "java/util/concurrent/DelayQueue" -> queueDesc = "queue=unbounded(DelayQueue)"
                    }
                }
            }
        }
        if (!sawUnbounded) parts.add(queueDesc)

        // ── core / max：取方法内最近的整型常量做上界参考 ──
        val ints = collectIntConstants(method)
        if (ints.isNotEmpty()) {
            parts.add("ints=[${ints.joinToString(",")}]")
            // 提示语义：ThreadPoolExecutor 前两个 int 参数是 core/max，
            // 但要给出「位置对应」需要数据流分析，这里只给出候选值供人工判断。
        }
        parts.add("sig=${args.joinToString(",") { it.className.substringAfterLast('.') }}")
        return parts.joinToString(" ")
    }

    /** 判断 `NEW X` 之后是否调用的是无参 `<init>()V`。 */
    private fun isNoArgCtor(
        insns: InsnList,
        newIndex: Int,
        owner: String,
    ): Boolean {
        // NEW 之后紧邻的若干条指令里（跳过 DUP），找 <init>
        var i = newIndex + 1
        var guard = 0
        while (i < insns.size() && guard++ < 8) {
            val n = insns[i]
            if (n is MethodInsnNode && n.name == "<init>" && n.owner == owner) {
                return n.desc == "()V"
            }
            i++
        }
        return false
    }

    private fun collectIntConstants(method: MethodNode): List<Int> {
        val out = ArrayList<Int>()
        val insns = method.instructions
        for (i in 0 until insns.size()) {
            when (val n = insns[i]) {
                is IntInsnNode -> if (n.getOpcode() == Opcodes.BIPUSH || n.getOpcode() == Opcodes.SIPUSH) {
                    out.add(n.operand)
                }
                is LdcInsnNode -> if (n.cst is Int) out.add(n.cst as Int)
            }
        }
        // 只保留「像并发数」的小整数，避免把无关常量混进来
        return out.filter { it in 0..256 }.distinct().take(6)
    }
}
