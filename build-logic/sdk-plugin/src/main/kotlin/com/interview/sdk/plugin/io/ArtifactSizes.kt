package com.interview.sdk.plugin.io

import java.io.File
import java.util.zip.ZipFile

/** 产物里「资源」与「native」两类的解压字节数。代码字节由 [ArtifactScanner] 给出。 */
internal data class ResourceSizes(val resBytes: Long, val nativeBytes: Long, val assetBytes: Long)

internal object ArtifactSizes {

    /**
     * 统计 AAR / JAR 内资源与 native 的体积。
     *
     * ⚠️ 口径是**解压后（file_size）**而不是压缩后（compress_size），原因：
     *    我们关心的是「这块东西本身有多大、值不值得优化」，
     *    而不是「它在某个特定打包方式下占多少」。压缩率是打包器的变量，
     *    拿它做决策会得出「dex 很小」的错误结论（dex 在 APK 里压缩得很好，
     *    但它恰恰是最大的解压后类别 —— 见 docs/package-size-optimization.md）。
     *
     * AAR 的目录约定（AGP 定义）：
     *   · `res/`    编译后的资源
     *   · `assets/` 原样打包的资产（字体、模型文件常在这里，且从不被压缩/裁剪）
     *   · `jni/<abi>/` 下的 .so   native 库
     *   · `classes.jar`    字节码（单独统计）
     */
    fun measure(file: File): ResourceSizes {
        if (!file.isFile) return ResourceSizes(0, 0, 0)
        return runCatching {
            ZipFile(file).use { zip ->
                var res = 0L
                var native = 0L
                var assets = 0L
                zip.entries().asSequence().forEach { e ->
                    if (e.isDirectory) return@forEach
                    val n = e.name
                    when {
                        n.startsWith("res/") -> res += e.size
                        n.startsWith("jni/") && n.endsWith(".so") -> native += e.size
                        n.startsWith("assets/") -> assets += e.size
                        // 有些 AAR 用 libs/ 放 native（老约定），一并统计
                        n.startsWith("libs/") && n.endsWith(".so") -> native += e.size
                    }
                }
                ResourceSizes(res, native, assets)
            }
        }.getOrDefault(ResourceSizes(0, 0, 0))
    }
}
