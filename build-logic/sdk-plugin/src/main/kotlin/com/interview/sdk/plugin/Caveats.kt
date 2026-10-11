package com.interview.sdk.plugin

/**
 * 工具局限声明。**每一次运行都会原样写进报告末尾**。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  为什么要把它做成代码而不是文档里的一段话
 * ══════════════════════════════════════════════════════════════════════
 *
 *  报告会被**脱离上下文传播** —— 被贴进群里、被当成「权限可以删」的依据。
 *  一旦脱离上下文，读者不会去看「工具能干什么」，只会看结论列。
 *  所以局限必须与结论**同文并排**，且每次运行都带上，
 *  不能靠「文档里写了」来免责。
 *
 *  ⚠️ 另一个更实际的原因：这些局限本身是**可观测指标**。
 *    比如「自有代码输出目录找到 0 个」会让所有权限都被判成
 *    「仅 SDK 使用」或「可移除」—— 这是**系统性误判**，
 *    必须在报告里显式报警，而不是让读者从结论反推。
 */
internal object Caveats {

    fun forRun(
        firstPartyDirCount: Int,
        scannedArtifactCount: Int,
        declaredDependencyCount: Int,
    ): List<String> = buildList {
        if (firstPartyDirCount == 0) {
            add(
                "🔴 **严重**：未找到自有代码的编译输出（`tmp/kotlin-classes` / `intermediates/javac` 等）。" +
                        "这会让**所有权限**都无法归属到自有代码，全部落入「仅 SDK 使用」或「可移除」——" +
                        "**结论不可用**。请先执行一次 `./gradlew :<module>:compile<Variant>Kotlin` 再跑本任务。"
            )
        }
        if (scannedArtifactCount in 1..2) {
            add(
                "🟠 仅扫描到 $scannedArtifactCount 个产物（声明了 $declaredDependencyCount 个依赖）。" +
                        "多数依赖未解析到本地文件，报告覆盖不全。"
            )
        }

        add("**权限判定基于静态可达性**，看不见三类使用方式：反射调用、`ServiceLoader`/provider 动态加载、" +
                "只在特定机型/SDK 版本分支里执行的代码。因此 `DECLARED_UNUSED` 是「**未发现证据**」而非「**确定未使用**」。")
        add("**权限的「API 隐含」反查表是启发式且不完备的**：只覆盖常见平台 API，不含厂商 ROM 私有 API 与部分新 API。" +
                "表未命中**不代表**不需要权限 —— 本工具在这一点上只做加法（多报），不做减法（不敢断言无使用）。")
        add("**manifest 合并的最终结果才是权威**。本工具读的是各依赖 AAR 内的 manifest 与宿主 manifest，" +
                "未做完整的 manifest merger 模拟。最终校验请对 **APK 内的合并 manifest** 复查：" +
                "`aapt2 dump badging app-release.apk | grep uses-permission`。")
        add("**体积是「解压后字节」不是「APK 增量」**，且未扣除 R8 会删掉的部分。只用它做相对排名，不要当省下来的体积。")
        add("**线程是静态画像（创建点）不是运行时线程数**，两者可能差数量级。风险排序用静态，定量归因必须上运行时。" +
                "本工具**不对加壳/加固的 SDK 生效** —— 其字节码在运行时才解密，静态扫描看不见（这类只能靠运行时 Hook）。")
        add("**重复类检测中「字节码是否一致」当前不做逐字节比对**（保守标为不一致）。" +
                "需要精确结论时用：`unzip -p a.aar classes.jar > /tmp/a.jar` 后对两份 jar 做 `sha256sum` 比对。")
    }
}
