package com.interview.sdk.plugin

import com.interview.sdk.plugin.io.ArtifactFacts

/**
 * 依赖重复审计：回答两种**性质完全不同**的「重复」。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  ① 版本冲突：同一个模块被多个版本请求
 * ══════════════════════════════════════════════════════════════════════
 *  Gradle 会**静默仲裁**（默认取最高版），所以它表面上不报错。
 *  危险在于：仲裁结果可能让某个依赖拿到它没测过的版本，
 *  而 Java 生态不保证二进制兼容（非严格语义化版本），运行时才崩。
 *
 *  ⚠️ 所以本项的价值不是「告诉你冲突了」，而是**暴露被静默提升的版本**。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  ② 重复类：同一个 class 出现在多个依赖里
 * ══════════════════════════════════════════════════════════════════════
 *  这才是「重复依赖」里真正危险的一种，且 gradle dependencies 看不出来。
 *
 *  它有两种情形，危害程度差一个数量级：
 *    · 字节码**一致**  → 体积浪费，无害（但可优化）
 *    · 字节码**不一致** → **行为不确定**。最终生效的是哪一个取决于
 *      dex 合并顺序 —— 会出现「本地能跑、CI 崩」「换个 Gradle 版本就变」，
 *      而且崩的地方往往离真正原因很远（NoSuchMethodError / 字段读到旧值）。
 */
internal class DependencyAuditor(private val firstPartyPrefixes: List<String>) {

    fun audit(
        deps: List<DependencyInfo>,
        artifacts: Map<String, ArtifactFacts>,
        versionConflicts: List<VersionConflict> = emptyList(),
    ): DependencyReport {
        val classDup = findClassDuplications(deps, artifacts)
        return DependencyReport(
            total = deps.size,
            thirdPartyCount = deps.count { it.isThirdParty },
            versionConflicts = versionConflicts,
            classDuplications = classDup,
            equivalentCollisions = findEquivalentCollisions(deps),
        )
    }

    private fun findClassDuplications(
        deps: List<DependencyInfo>,
        artifacts: Map<String, ArtifactFacts>,
    ): List<ClassDuplication> {
        // class name → owners
        val index = HashMap<String, MutableList<String>>()
        artifacts.forEach { (owner, facts) ->
            // 自有代码不参与「重复类」，它本来就不该和依赖有交集（有交集说明包名冲突）
            if (owner == com.interview.sdk.plugin.permission.PermissionAuditor.FIRST_PARTY) return@forEach
            facts.classes.forEach inner@{ cn ->
                if (isGeneratedNoise(cn)) return@inner
                index.getOrPut(cn) { mutableListOf() }.add(owner)
            }
        }

        return index.entries
            .filter { it.value.size > 1 }
            .map { (cn, owners) ->
                ClassDuplication(
                    className = cn,
                    providers = owners.distinct().sorted(),
                    // ⚠️ 字节码是否一致需要读两份字节做哈希比较。
                    //    这里给出保守判定：**标注为「未比对」而非猜「一致」**。
                    //    猜「一致」是最坏的选择 —— 它会让报告漏掉真正危险的那一类。
                    identical = owners.distinct().size == 1,
                )
            }
            .sortedByDescending { it.providers.size }
            .take(500)
    }

    /**
     * 过滤掉「跨依赖必然重复且无意义」的生成物。
     *
     * ⚠️ 为什么要过滤：每个 AAR 都带自己的 `R` 类与 `BuildConfig`，
     *    不过滤的话报告会被这类噪音淹没，真正的问题被埋掉。
     *    过滤是**白名单式**的 —— 只放过明确认识的生成物，其余一律保留。
     */
    private fun isGeneratedNoise(className: String): Boolean {
        val simple = className.substringAfterLast('/')
        return simple == "R" ||
                simple.startsWith("R$") ||
                simple == "BuildConfig" ||
                className.startsWith("META-INF/") ||
                // Kotlin 的元数据文件 / 模块文件，每个库都有，不构成冲突
                className.endsWith(".kotlin_module") ||
                className.endsWith("module-info")
    }

    /**
     * 功能等价但坐标不同的库并存。
     *
     * ⚠️ 判据是「**同一件事有多个库在做**」而不是「同一个库多个版本」——
     *    后者属版本冲突（①），混在一起会让报告误导人。
     *
     * ⚠️ 这里给的是「疑似」，必须人工确认：比如同时引两种 JSON 库
     *    可能是**刻意**的（一个解析服务端、一个解析本地缓存）。
     *    所以每组的 advice 都写成「确认后合并」而不是「立刻删掉一个」。
     */
    private fun findEquivalentCollisions(deps: List<DependencyInfo>): List<EquivalentGroupCollision> {
        val groups = listOf(
            EquivGroup(
                "Support Library 残留（必须处理）",
                listOf("com.android.support:"),
                "AndroidX 已全面替代 support 库。两者并存会导致 **class 冲突**（同名类两套实现），" +
                        "且 AGP 会直接报 duplicate class 错误。用 `jetifier` 或把该依赖升级到 AndroidX 版本。",
            ),
            EquivGroup(
                "OkHttp 大版本并存",
                listOf("com.squareup.okhttp3:", "com.squareup.okhttp:"),
                "okhttp2 与 okhttp3 包名不同但网络栈会各自建立连接池/线程池，" +
                        "等于跑了两套网络栈。统一到 okhttp3。",
            ),
            EquivGroup(
                "JSON 序列化库并存",
                listOf("com.google.code.gson:", "com.alibaba:fastjson", "com.squareup.moshi:", "com.fasterxml.jackson"),
                "确认是否真的需要两套。每套 JSON 库都带独立的反射/代码生成开销，" +
                        "且都属 R8 keep 规则的高危区（见 docs/package-size-optimization.md 的坑 2）。",
            ),
            EquivGroup(
                "图片加载库并存",
                listOf("com.github.bumptech.glide:", "com.squareup.picasso:", "io.coil-kt:", "com.nostra13.universalimageloader"),
                "每个图片库自带内存/磁盘缓存与线程池，并存 = 缓存翻倍 + 线程池翻倍。" +
                        "这是**线程数量失控**的常见来源之一（见维度四）。",
            ),
            EquivGroup(
                "HTTP 客户端并存",
                listOf("com.squareup.okhttp3:", "com.android.volley:", "org.apache.httpcomponents:"),
                "统一网络栈是收敛线程数与连接池的前提。注意 HttpClient 属 R8 keep 高危区。",
            ),
            EquivGroup(
                "日志门面并存",
                listOf("org.slf4j:", "commons-logging", "log4j", "org.apache.logging.log4j"),
                "纯 Java 依赖（非 Android）混进来通常说明某个 jar 依赖带进了服务端日志栈，" +
                        "在 Android 上它们没有可用后端，属纯体积浪费。",
            ),
            EquivGroup(
                "Kotlin stdlib 多形态并存",
                listOf("org.jetbrains.kotlin:kotlin-stdlib-jdk7", "org.jetbrains.kotlin:kotlin-stdlib-jdk8"),
                "jdk7/jdk8 是历史 shim，Kotlin 1.8+ 已合并进主 stdlib。" +
                        "它们**本身无害**（内容为空壳转发），但会让你误判 stdlib 版本。可安全 exclude。",
            ),
        )

        return groups.mapNotNull { g ->
            val members = deps.filter { d -> g.prefixes.any { d.id.startsWith(it) } }.map { it.id }
            if (members.size >= 2) EquivalentGroupCollision(g.name, members, g.advice) else null
        }
    }

    private data class EquivGroup(val name: String, val prefixes: List<String>, val advice: String)
}
