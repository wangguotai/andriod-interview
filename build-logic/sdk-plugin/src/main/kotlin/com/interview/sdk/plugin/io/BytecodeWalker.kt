package com.interview.sdk.plugin.io

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.util.zip.ZipFile

/**
 * 依赖产物的字节码遍历基础设施。
 *
 * 需要同时支持三种形态，这是 Android 生态的现实：
 *   · `.jar`  —— 纯 Java 库
 *   · `.aar`  —— Android 库，字节码在内部的 `classes.jar`
 *   · 目录    —— project 依赖的已编译 `classes/` 输出
 *
 * 分两层：
 *   1. [walkClasses]   —— 一个可调用函数，遍历并回调
 *   2. [scanClassResources] —— 面向「类名集合 / 重复类」这类元信息需求
 *
 * ⚠️ 用 `ClassReader.SKIP_DEBUG` 而不是 SKIP_FRAMES：
 *    本工具需要行号来输出可点击的证据位置（`Foo.kt:123`），
 *    调试信息不能跳。代价是解析稍慢，但审计是离线任务，可接受。
 */
internal object BytecodeWalker {

    /** 解析一个 class 字节数组时使用的标志。 */
    private const val PARSING = ClassReader.SKIP_FRAMES

    /**
     * 遍历一个产物里的所有 class。
     *
     * @param source jar / aar / 目录
     * @param visit  每个 class 调用一次：类名 + 已解析的 reader + 该 class 的原始字节数。
     *               **不要在回调里持有 reader**，它内部引用的是临时字节数组。
     *
     * ⚠️ 为什么把「字节数」作为回调参数而不是让调用方从 reader 上取：
     *    ASM 的 `ClassReader.classFileLength` / `.b` 是**非公开字段**
     *    （文档明确说「为二进制兼容保留」），拿它算体积会在 ASM 升级时静默出错 ——
     *    而体积统计出错不会抛异常，只会给出错误的数字，属最坏的一类 bug。
     *    在读取处顺手把 `bytes.size` 传出去，才是唯一稳定的来源。
     */
    fun walkClasses(source: File, visit: (className: String, reader: ClassReader, byteSize: Int) -> Unit) {
        when {
            source.isDirectory -> walkDirectory(source, visit)
            source.name.endsWith(".aar", ignoreCase = true) -> walkAar(source, visit)
            source.name.endsWith(".jar", ignoreCase = true) -> walkJar(source, visit)
            // .apk / .dex / .so 等暂不处理：本工具的输入是「依赖」，不是最终产物。
            // 最终产物的分析交给 APK 侧脚本（tools/opt/unused-report.sh），职责分开。
        }
    }

    private fun walkDirectory(dir: File, visit: (String, ClassReader, Int) -> Unit) {
        dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".class") }
            .forEach { f ->
                runCatching {
                    val bytes = f.readBytes()
                    visit(ClassReader(bytes).className, ClassReader(bytes), bytes.size)
                } // 单个损坏的 class 不能中断整个审计 —— 依赖里的老 class 版本参差不齐
            }
    }

    private fun walkJar(jar: File, visit: (String, ClassReader, Int) -> Unit) {
        runCatching {
            ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.endsWith(".class") }
                    .forEach { entry ->
                        runCatching {
                            val bytes = zip.getInputStream(entry).readBytes()
                            visit(ClassReader(bytes).className, ClassReader(bytes), bytes.size)
                        }
                    }
            }
        }
    }

    private fun walkAar(aar: File, visit: (String, ClassReader, Int) -> Unit) {
        runCatching {
            ZipFile(aar).use { zip ->
                val classesJar = zip.getEntry("classes.jar") ?: return@use
                // AAR 里的 classes.jar 是嵌套 zip，必须先落到临时文件再读
                // （ZipInputStream 不支持随机访问，直接解析 entry 流会失败）
                val tmp = File.createTempFile("sdk-audit-", ".jar")
                try {
                    zip.getInputStream(classesJar).use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    walkJar(tmp, visit)
                } finally {
                    tmp.delete()
                }
            }
        }
    }

    /** 列出一个产物里定义的全部类名（JVM 内部名，斜杠分隔）。用于「重复类」检测。 */
    fun scanClassResources(source: File): Set<String> {
        val out = HashSet<String>()
        walkClasses(source) { name, _, _ -> out.add(name) }
        return out
    }

    /** 供 visitor 透传用的空终端 visitor。 */
    val NO_OP: ClassVisitor = object : ClassVisitor(Opcodes.ASM9) {}
}
