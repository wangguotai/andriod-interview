package com.interview.sdk.plugin

import com.interview.sdk.plugin.io.ArtifactFacts
import com.interview.sdk.plugin.io.ArtifactScanner
import java.io.File

/**
 * 扫描自有代码的**已编译输出**（`build/tmp/kotlin-classes/<variant>` 与
 * `build/intermediates/javac/<variant>/classes`），产出与依赖同构的 [ArtifactFacts]。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  ⚠️ 为什么不扫源码（.kt / .java），而扫已编译的 .class
 * ══════════════════════════════════════════════════════════════════════
 *
 * 这是本工具最关键的一个正确性决策，值得展开：
 *
 * **扫源码会与 SDK 结论不同源，导致误判。**
 *
 * 对 SDK 我们只有字节码（jar/aar），所以只能扫字节码。如果自有代码改成扫源码，
 * 两边就是两套判定逻辑，会出现「同一个调用在自有代码里算用了、在 SDK 里算没用」
 * 这种自相矛盾的结论。而权限审计的结论恰恰是**跨这两者的比较**（谁在用）。
 * 比较的双方必须是同一把尺子。
 *
 * 具体到实现上的差异，扫源码至少会漏三类：
 *   1. **常量内联**：`Manifest.permission.CAMERA` 在编译期被内联成字符串字面量。
 *      源码里 grep 不到 "android.permission.CAMERA"，但字节码里是个 LDC —— 扫字节码才看得到。
 *   2. **注解处理器生成的代码**：DataBinding、Room、Hilt 生成的类不存在于源码树。
 *   3. **Kotlin 语法糖**：属性访问、DSL 会被编译成额外的方法调用，
 *      源码层面的「用没用某个 API」和字节码层面不是一一对应。
 *
 * 所以：**统一在字节码层比较**。源码只用来定位（报告里给出行号区间副本见下）。
 *
 * ⚠️ 行号映射的真实限制：
 *    编译输出里没有源码行号（那是调试信息，且本工具跳过了 DEBUG）。
 *    报告里给出的是 `类#方法` 级别的证据位置 —— 足以让人找到文件，
 *    但不给出精确行号。这是刻意避免「行号看起来精确、实际对不上」的假精确。
 */
internal object FirstPartyScanner {

    private val KNOWN_OUTPUT_DIRS = listOf(
        "tmp/kotlin-classes",
        "intermediates/javac",
        "intermediates/classes",
        "tmp/kapt3/classes",
        "tmp/kaptKotlin",
    )

    /**
     * 找到某个模块在指定 variant 下的自有代码编译输出目录。
     *
     * ⚠️ 需要**扫描多个候选目录并全部纳入**：一次构建可能同时有
     *    Kotlin（tmp/kotlin-classes）、Java（intermediates/javac）、
     *    kapt 生成代码（tmp/kapt3/classes）三处输出。
     *    只取第一个会漏掉 kapt 生成的代码 —— 而生成代码里恰恰常有权限相关调用。
     */
    fun findOutputDirs(moduleDir: File, variant: String): List<File> {
        val buildDir = File(moduleDir, "build")
        if (!buildDir.isDirectory) return emptyList()
        val out = ArrayList<File>()
        KNOWN_OUTPUT_DIRS.forEach { rel ->
            val base = File(buildDir, rel)
            if (!base.isDirectory) return@forEach
            // 目录结构因 AGP 版本而异，两种都试：
            //   <base>/<variant>/            （常见）
            //   <base>/<variantPrefix>/**    （variant 带 flavor 时）
            val direct = File(base, variant)
            if (direct.isDirectory) {
                out.add(direct)
            } else {
                base.listFiles()?.filter { it.isDirectory && it.name.contains(variant, ignoreCase = true) }
                    ?.forEach { out.add(it) }
            }
        }
        return out.distinct()
    }

    fun scan(dirs: List<File>): Pair<ArtifactFacts, Long> {
        val facts = ArtifactFacts()
        var bytes = 0L
        dirs.forEach { dir ->
            val f = ArtifactScanner.scan(dir, FIRST_PARTY_OWNER)
            facts.classes.addAll(f.classes)
            facts.stringConstants.putAll(f.stringConstants)
            facts.memberRefs.addAll(f.memberRefs)
            facts.threadSubclasses.addAll(f.threadSubclasses)
            facts.threadSites.addAll(f.threadSites)
            bytes += f.codeBytes
        }
        return facts to bytes
    }

    const val FIRST_PARTY_OWNER = "first-party"
}
