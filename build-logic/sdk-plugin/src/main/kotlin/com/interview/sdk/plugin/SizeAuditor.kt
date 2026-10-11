package com.interview.sdk.plugin

import com.interview.sdk.plugin.io.ArtifactFacts
import com.interview.sdk.plugin.io.ResourceSizes

/**
 * 体积归属：回答「这 50 MB 里，每个依赖各占多少」。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  ⚠️ 口径必须说清，否则数字会被误用
 * ══════════════════════════════════════════════════════════════════════
 *
 *  本工具统计的是**解压后字节数**（AAR/JAR 内 entry 的 file_size）。
 *  它与 APK 里的实际占用**不是一回事**：
 *    · dex 在 APK 中会被重新压缩与合并（多个 AAR 的 class 进同一个 dex）
 *    · .so 在 APK 中通常**不压缩**（为了 mmap），但 AAB 分发时会压缩
 *    · R8 会删掉大量「归因到某依赖」的代码，使该依赖的实际贡献远小于归属值
 *
 *  所以：**只用它做相对排名与占比，不要把它当 APK 增量**。
 *  精确的 APK 增量只有一条路：分别构建包含/不包含该依赖的包做差分
 *  （tools/opt/unused-report.sh 的 APK 构成部分做的是另一件事：
 *   按文件类别看最终产物，两者互补）。
 *
 *  为什么仍然要做归属统计：**它回答的是「优化谁」而不是「能省多少」**。
 *  在几十个依赖里找到最大的那 3 个，比精确算出每个省多少字节更早产生价值。
 */
internal class SizeAuditor {

    fun audit(
        deps: List<DependencyInfo>,
        artifacts: Map<String, ArtifactFacts>,
        sizes: Map<String, ResourceSizes>,
        firstPartyFacts: ArtifactFacts,
        firstPartyBytes: Long,
    ): SizeReport {
        val contributions = deps.mapNotNull { dep ->
            val facts = artifacts[dep.id] ?: return@mapNotNull null
            val sz = sizes[dep.id] ?: ResourceSizes(0, 0, 0)
            SizeContribution(
                id = dep.id,
                isThirdParty = dep.isThirdParty,
                codeBytes = facts.codeBytes,
                // ⚠️ assets 计入资源列：它在 APK 里同样受 shrinkResources 管理
                //    （但注意 assets 不会被资源压缩删除，只能靠代码层面不去请求）
                resBytes = sz.resBytes + sz.assetBytes,
                nativeBytes = sz.nativeBytes,
                classCount = facts.classes.size,
            )
        }
        return SizeReport(
            firstParty = SizeContribution(
                id = com.interview.sdk.plugin.permission.PermissionAuditor.FIRST_PARTY,
                isThirdParty = false,
                codeBytes = firstPartyBytes,
                resBytes = 0,
                nativeBytes = 0,
                classCount = firstPartyFacts.classes.size,
            ),
            dependencies = contributions.sortedByDescending { it.totalBytes },
        )
    }
}
