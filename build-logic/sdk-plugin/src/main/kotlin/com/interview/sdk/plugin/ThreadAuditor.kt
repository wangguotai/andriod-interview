package com.interview.sdk.plugin

import com.interview.sdk.plugin.io.ArtifactFacts

/**
 * 线程滥用审计（静态画像）。
 *
 * ══════════════════════════════════════════════════════════════════════
 *  ⚠️ 必须先把「静态画像」的能力边界说清楚，否则报告会被误解
 * ══════════════════════════════════════════════════════════════════════
 *
 *  静态扫描统计的是**字节码里的创建点（code site）**，不是运行时线程数。
 *  两者可能差好几个数量级：
 *
 *    · 一个创建点在循环里被调 1000 次 → 静态 1，运行时 1000
 *    · 一个创建点在冷分支里从不执行   → 静态 1，运行时 0
 *    · 线程池复用线程                 → 静态 1，运行时线程数 < core+max
 *
 *  所以静态画像能回答的问题是：
 *    ✅ 「**在哪儿**建的、什么形态、有没有命名、队列有没有界」
 *    ❌ 「**实际有多少**线程、是谁在持续创建」
 *
 *  后一个问题只有运行时能答 —— 见 `sdk-governance-runtime` 模块
 *  （Hook `pthread_create` + 创建者堆栈聚类）与本仓库既有的
 *  `com.interview.thread.NativeThreadHook`。
 *
 *  两者是互补关系：静态**先发现可疑点**（成本低、覆盖全、能进 CI），
 *  运行时**再确认实际影响**（成本高、需真机、但结论权威）。
 *  顺序不能反 —— 没有静态线索就直接上真机采样，等于大海捞针。
 */
internal class ThreadAuditor(private val firstPartyPrefixes: List<String>) {

    fun audit(deps: List<DependencyInfo>, artifacts: Map<String, ArtifactFacts>): ThreadReport {
        val profiles = artifacts.mapNotNull { (owner, facts) ->
            if (facts.threadSites.isEmpty()) return@mapNotNull null
            ThreadProfile(
                owner = owner,
                isThirdParty = owner != com.interview.sdk.plugin.permission.PermissionAuditor.FIRST_PARTY &&
                        firstPartyPrefixes.none { owner.startsWith(it) } &&
                        !owner.startsWith("project:"),
                sites = facts.threadSites,
            )
        }.sortedByDescending { it.total }

        return ThreadReport(profiles)
    }
}
