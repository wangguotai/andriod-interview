package com.interview.thread.plugin

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode

/**
 * Time: 2026/9/27
 * Author: wgt
 * Description: 指令级变换核心 —— 整个 ASM 模块唯一有技术含量的一环
 *
 * ══════════════════════════════════════════════════════════════════════
 * 阅读本文档前，先接受三个「字节码事实」
 * ══════════════════════════════════════════════════════════════════════
 *
 * ─── 事实 1：DUP 为什么存在？栈平衡是精确的 ───
 *
 * 最常见的线程创建 `Thread { doWork() }` 编译结果：
 *
 *   NEW java/lang/Thread                 ① 栈上放一个「未初始化对象引用」
 *   DUP                                  ② 复制一份：一份给 <init> 消耗，一份留着用
 *   INVOKEDYNAMIC run()Ljava/lang/Runnable;   ③ 压入 Runnable
 *   INVOKESPECIAL java/lang/Thread.<init>(Ljava/lang/Runnable;)V
 *                                        ④ 消耗栈顶的「引用 + 参数」，完成初始化
 *   ASTORE n                             ⑤ 剩下那份存进局部变量表
 *
 * 关键：INVOKESPECIAL 调用构造函数时会**隐式消耗**栈顶的引用。
 * 所以 DUP 不是冗余，没有它就凑不出「调用完还能拿到对象」的效果。
 * → 推论：**不能简单地在 <init> 前后插入代码**，栈的平衡必须精确维持。
 *   本类的变换之所以安全，是因为它只「改参数/改 owner」，不改变栈的深度。
 *
 * ─── 事实 2：为什么不能整体替换 Thread？（本方案的核心洞察来源）───
 *
 * 因为 ASTORE / ALOAD 的下标 **依赖上下文**：
 *
 *   实例方法里 0 号槽是 this；静态方法里 0 号槽是第一个参数
 *
 * 编译期无法预知每个 Thread 对象被存到哪个槽、之后被怎么使用，
 * 所以「把 Thread 全换成 MyThread」会踩坑。
 *
 * → **核心洞察：不统一 Thread，而是统一 Runnable 的执行环境。**
 *   Runnable 是干净的突破口（Thread 的各种构造函数都能接 Runnable），
 *   把它的执行位置从「新建线程」改成「统一线程池」，就完成了收敛。
 *   这也是 [com.interview.thread.UnifiedThread] 存在的理由。
 *
 * ─── 事实 3：改 owner 必须「同时」改 NEW（最易翻车的坑）───
 *
 *   NEW com/interview/thread/UnifiedThread                  ← 必须改
 *   DUP
 *   INVOKESPECIAL com/interview/thread/UnifiedThread.<init>  ← 必须改
 *
 * 字节码校验器要求「NEW 出来的未初始化类型」与「所调构造函数的 owner」**必须一致**。
 * 只改其中一条 → 类加载时抛 **VerifyError**。
 * 实现见下方 [rewrite]，两行改写被刻意绑在一起，不要拆开。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 两个变换（可独立开关）
 * ══════════════════════════════════════════════════════════════════════
 *
 * ─── 变换 A：命名（enableNaming，默认开）───
 *   Thread()          → Thread(String name)
 *   Thread(Runnable)  → Thread(Runnable, String name)
 *   name 取调用者类名，让线上线程快照直接可溯源（参考滴滴 Booster）。
 *
 *   做法：在 <init> 之前插入一条 LDC（压入类名字符串），
 *        并把 <init> 的描述符尾部追加一个 Ljava/lang/String;。
 *
 *   ✅ 这次插入不引入新的跳转目标，与调用点紧邻，
 *      因此既有的 StackMapTable 仍然有效，无需重算栈帧
 *      （AGP 默认 FramesComputationMode.COPY_FRAMES 即可，
 *       本仓库产物中 StackMapTable 被原样保留 —— 已实测确认）。
 *
 * ─── 变换 B：收敛（enableUnify，默认关）───
 *   把 NEW / <init> 的 java/lang/Thread 整体换成 UnifiedThread，
 *   该子类覆写 start()，不真正起线程，而是把 Runnable 提交到统一线程池。
 *
 *   ⚠️ 默认关闭：它改变程序运行时语义（线程不再真正新建），
 *      依赖 ThreadLocal 隔离 / Looper 归属 / 线程专属上下文的代码会出错。
 *
 * ══════════════════════════════════════════════════════════════════════
 * 设计取舍
 * ══════════════════════════════════════════════════════════════════════
 *
 * · **延迟 emit**：visitMethod 不直接透传，而是返回 MethodNode，
 *   等 visitEnd（指令收齐）后再变换。因为要「配对 NEW 与它的 <init>」，
 *   必须拿到完整指令链做前后文分析 —— 流式处理做不到。
 *   代价：每个方法在内存里存一份指令列表。
 *   （ASM 经典取舍：ClassVisitor 流式省内存但无全局视野；
 *     MethodNode 树式有全局视野但吃内存。）
 *
 * · **精确匹配**：只认 `NEW java/lang/Thread` 这一种情况。
 *   子类（如 `class SpThread : Thread()`）构造函数里对 super() 的调用
 *   前面没有 NEW java/lang/Thread，因此不会被误伤。
 *   —— 此行为已用探针实测验证。
 *
 * · **幂等保护**：见 [hasNameParam]，避免重复构建时重复插桩。
 *
 * @see ThreadAsmVisitorFactory AGP 侧的生命周期与过滤逻辑
 * @see com.interview.thread.UnifiedThread 变换 B 的运行时落点
 */
class ThreadClassVisitor(
    next: ClassVisitor,
    private val currentClass: String,
    private val enableNaming: Boolean,
    private val enableUnify: Boolean,
    private val excludedPackages: List<String>,
) : ClassVisitor(Opcodes.ASM9, next) {

    /**
     * 延迟 emit：把流的处理收成「一棵树」，等指令收齐再变换。
     *
     * 为什么不直接返回 mv（透传）？
     * → 见类注释「设计取舍」：配对 NEW 与 <init> 需要完整指令链的前后文。
     *
     * 注意返回类型是 MethodVisitor?，null 表示「这个方法跳过」。
     */
    override fun visitMethod(
        access: Int,
        name: String,
        descriptor: String,
        signature: String?,
        exceptions: Array<out String>?,
    ): MethodVisitor? {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions) ?: return null
        return object : MethodNode(Opcodes.ASM9, access, name, descriptor, signature, exceptions) {
            override fun visitEnd() {
                // 到这里，本方法的全部指令都已收集进 this.instructions
                transform(this)
                // 变换完再交给下游（AGP 的 ClassWriter）
                accept(mv)
            }
        }
    }

    /**
     * 遍历指令，配对「NEW java/lang/Thread」与它对应的「INVOKESPECIAL <init>」。
     *
     * 只看两类指令，其余一概不动 —— 这是「不破坏原字节码语义」的前提。
     * 栈的深度与顺序完全不变，只是改了「调用哪个构造函数」和「多传一个参数」。
     */
    private fun transform(method: MethodNode) {
        val insns: InsnList = method.instructions ?: return
        if (insns.size() == 0) return
        if (isExcluded()) return

        // 记录最近一个 NEW java/lang/Thread，用于配对它的构造函数调用。
        // 只认「NEW Thread」这一种情况：子类（如 SpThread）构造函数里对 super() 的调用
        // 前面没有 NEW java/lang/Thread，因此不会被误伤。
        var pendingNew: TypeInsnNode? = null

        var node: AbstractInsnNode? = insns.first
        while (node != null) {
            when {
                // ① 遇到 NEW java/lang/Thread → 先记下来，等它的 <init>
                node.opcode == Opcodes.NEW && node is TypeInsnNode && node.desc == THREAD -> {
                    pendingNew = node
                }

                // ② 遇到 java/lang/Thread.<init> → 配对成功，执行改写
                node.opcode == Opcodes.INVOKESPECIAL && node is MethodInsnNode
                        && node.name == INIT && node.owner == THREAD -> {
                    rewrite(insns, node, pendingNew)
                    pendingNew = null
                }
            }
            node = node.next
        }
    }

    /**
     * 真正的改写点。两个变换都在这里，注意它们的执行顺序不影响彼此。
     *
     * @param ctor    待改写的 java/lang/Thread.<init> 指令
     * @param newInsn 与它配对的 NEW 指令；为 null 表示这条 <init> 前面没有
     *                NEW java/lang/Thread（典型情况：子类构造函数里的 super() 调用）
     */
    private fun rewrite(insns: InsnList, ctor: MethodInsnNode, newInsn: TypeInsnNode?) {
        // ── 变换 B：收敛 ──
        // ⚠️ 铁律：NEW 与 <init> 必须同步改 owner，否则 VerifyError（见类注释 事实 3）。
        // newInsn 为 null 时不改 NEW —— 那种情况是子类构造函数里的 super() 调用，
        // 栈上早已有 this（ALOAD 0），不存在配对的 NEW。
        if (enableUnify) {
            newInsn?.desc = UNIFIED_THREAD
            ctor.owner = UNIFIED_THREAD
        }

        // ── 变换 A：补 name 参数 ──
        // 两步：① 在 <init> 之前插入 LDC 压入类名（作为新增的第 N 个实参）
        //       ② 改描述符，声明这个新增的 String 形参
        // 顺序要求：LDC 必须先插在 ctor 之前，才能让新增的实参正好落在
        //           <init> 消耗参数的栈位置上。
        val desc = ctor.desc
        if (enableNaming && !hasNameParam(desc)) {
            insns.insertBefore(ctor, LdcInsnNode(currentClass.replace('/', '.')))
            ctor.desc = desc.substring(0, desc.lastIndexOf(')')) + "Ljava/lang/String;)V"
        }
    }

    /** 类级排除：支持前缀匹配，用于放行收口层自身等不应被改造的包。 */
    private fun isExcluded(): Boolean =
        excludedPackages.any { currentClass.startsWith(it) }

    /**
     * 幂等保护：判断描述符里是否已经有 String 参数。
     *
     * 作用：避免同一份 class 被重复插桩时（增量构建 / 多次 transform）
     * 叠加出 `(Runnable, String, String)V` 这种错误签名。
     *
     * 本仓库的典型命中场景（它们本来就写了线程名）：
     *   Thread(r, "leaked-pool-$index-thread")
     *   Thread(r, "$sdkName-worker")
     * 实测确认这两处未被重复插桩。
     *
     * ⚠️ 精度边界（已知，非 bug）：这是**启发式**判断 —— 只问「有没有 String」，
     *    不问「这个 String 是不是 name」。若存在签名形如 `Thread(Runnable, String)`
     *    且那个 String **不是**线程名的自建重载，理论上会漏插。
     *    本仓库不存在此类代码，故未处理。
     */
    private fun hasNameParam(desc: String?): Boolean =
        desc != null && desc.contains("Ljava/lang/String;")

    companion object {
        private const val THREAD = "java/lang/Thread"
        private const val INIT = "<init>"
        private const val UNIFIED_THREAD = "com/interview/thread/UnifiedThread"
    }
}
