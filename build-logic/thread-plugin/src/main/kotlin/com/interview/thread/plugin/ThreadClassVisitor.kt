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
 * Description: 指令级变换核心
 *
 * ─── 字节码原理 ───
 * 最常见的线程创建 `Thread { doWork() }` 编译结果：
 *
 *   NEW java/lang/Thread
 *   DUP
 *   INVOKEDYNAMIC run()Ljava/lang/Runnable;
 *   INVOKESPECIAL java/lang/Thread.<init>(Ljava/lang/Runnable;)V
 *   ASTORE n
 *
 * 三个关键事实：
 *  1. INVOKESPECIAL「隐式消耗栈顶的 Thread 引用」——这正是 DUP 存在的原因
 *  2. ASTORE / ALOAD 的下标依赖局部变量表（静态方法 0 是首个参数，实例方法 0 是 this），
 *     由编译上下文决定 —— 所以无法对 Thread 做「整体替换」
 *  3. 但 NEW / DUP / INVOKESPECIAL 的栈操作是确定的 → 可以安全地改 owner 与 NEW 的类型
 *
 * ⚠️ 关键坑：变换 owner 时必须**同时**改 NEW 的类型。
 * 校验器要求「NEW 出来的未初始化类型」与「所调构造函数的 owner」一致，
 * 只改 <init> 的 owner 会抛 VerifyError。
 *
 * ─── 变换 A：命名 ───
 *   Thread()          → Thread(String name)
 *   Thread(Runnable)  → Thread(Runnable, String name)
 *   name 取调用者类名，让线上线程快照直接可溯源（参考滴滴 Booster）
 *
 * ─── 变换 B：收敛 ───
 *   把 NEW/<init> 的 java/lang/Thread 整体换成 UnifiedThread，
 *   该子类覆写 start()，不真正起线程，而是把 Runnable 提交到统一线程池。
 *   对应核心洞察：「不统一 Thread，而是统一 Runnable 的执行环境」。
 */
class ThreadClassVisitor(
    next: ClassVisitor,
    private val currentClass: String,
    private val enableNaming: Boolean,
    private val enableUnify: Boolean,
    private val excludedPackages: List<String>,
) : ClassVisitor(Opcodes.ASM9, next) {

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
                transform(this)
                accept(mv)
            }
        }
    }

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
                node.opcode == Opcodes.NEW && node is TypeInsnNode && node.desc == THREAD -> {
                    pendingNew = node
                }

                node.opcode == Opcodes.INVOKESPECIAL && node is MethodInsnNode
                        && node.name == INIT && node.owner == THREAD -> {
                    rewrite(insns, node, pendingNew)
                    pendingNew = null
                }
            }
            node = node.next
        }
    }

    private fun rewrite(insns: InsnList, ctor: MethodInsnNode, newInsn: TypeInsnNode?) {
        // ── 变换 B：收敛。NEW 与 <init> 必须同步改 owner，否则 VerifyError ──
        if (enableUnify) {
            newInsn?.desc = UNIFIED_THREAD
            ctor.owner = UNIFIED_THREAD
        }

        // ── 变换 A：补 name 参数 ──
        val desc = ctor.desc
        if (enableNaming && !hasNameParam(desc)) {
            insns.insertBefore(ctor, LdcInsnNode(currentClass.replace('/', '.')))
            ctor.desc = desc.substring(0, desc.lastIndexOf(')')) + "Ljava/lang/String;)V"
        }
    }

    private fun isExcluded(): Boolean =
        excludedPackages.any { currentClass.startsWith(it) }

    private fun hasNameParam(desc: String?): Boolean =
        desc != null && desc.contains("Ljava/lang/String;")

    companion object {
        private const val THREAD = "java/lang/Thread"
        private const val INIT = "<init>"
        private const val UNIFIED_THREAD = "com/interview/thread/UnifiedThread"
    }
}
