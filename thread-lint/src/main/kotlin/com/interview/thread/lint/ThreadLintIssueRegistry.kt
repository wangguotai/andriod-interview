package com.interview.thread.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/**
 * Lint 规则注册表。
 *
 * ⚠️ 两个必须做对、否则「规则装了但不生效」的坑：
 *
 * 1. **必须有 META-INF/services 文件**，否则 Lint 发现不了这个 Registry，
 *    构建会静默通过、不报任何错 —— 最难查的一种失败：
 *      thread-lint/src/main/resources/META-INF/services/com.android.tools.lint.client.api.IssueRegistry
 *      内容：com.interview.thread.lint.ThreadLintIssueRegistry
 *
 * 2. **minApi / api 要声明**，Lint 会据此判断注册表是否兼容当前版本。
 *    返回 [CURRENT_API] 表示「跟随编译时的 lint-api 版本」，最简单也最不易出错。
 */
class ThreadLintIssueRegistry : IssueRegistry() {

    override val issues: List<Issue> = ThreadMisuseDetector.ISSUES

    /** 编译期 lint-api 的 API 号；返回它即「不限制」 */
    override val api: Int = CURRENT_API

    override val minApi: Int = 8

    /**
     * lint-api 31.x 起要求声明 vendor，缺失会有警告。
     * 指向规则文档，便于团队知道怎么修。
     */
    override val vendor: Vendor = Vendor(
        vendorName = "interview-thread-governance",
        identifier = "com.interview.thread.lint",
        feedbackUrl = "https://example.com/issues",
    )
}
