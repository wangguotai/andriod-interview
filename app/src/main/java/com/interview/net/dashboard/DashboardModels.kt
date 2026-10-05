package com.interview.net.dashboard

import com.interview.net.NetMetrics

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 网络仪表盘的**纯数据契约** —— 把「原始记录/快照」翻译成图表能直接吃的形状。
 *
 * ─── 为什么把翻译抽成纯函数 ───
 *
 * 自定义 View 里如果直接读 [NetMetrics] 并顺手算分位/分组，会有两个后果：
 *   1. 画不出来的时候（数据脏、空窗口、分位 off-by-one）只能靠肉眼调，
 *      没有可断言的中间层；
 *   2. 同一份「分位怎么算」的逻辑会在几个 View 里各写一遍，迟早漂移
 *      （本仓库在 [NetMetrics.percentile] 上已经踩过 0-based 的坑）。
 *
 * 所以这里只做一件事：**纯粹的、无 Android 依赖的**变换。它可以被 JVM 单测钉住，
 * View 只负责把结果画出来。这也符合本仓库「判定/解释/变换外提、渲染留 UI」的一贯拆法。
 */

/** 阶段耗时的一段（用于堆叠条）。命名与 [NetMetrics.Stage] 字段一一对应。 */
enum class StageKind(val label: String) {
    DNS("DNS"),
    CONNECT("建连"),
    TLS("TLS"),
    TTFT("首包"),   // time-to-first-byte = 服务端等待
    TRANSFER("传输"), // total - (dns+connect+tls+ttft) 的余量，可能为 0
}

/** 一个阶段在一条堆叠条里的取值（毫秒；<0 表示该阶段未发生）。 */
data class StageSlice(val kind: StageKind, val millis: Long)

object DashboardModels {

    /**
     * 把一次记录的 [NetMetrics.Stage] 拆成有序的堆叠段。
     *
     * ⚠️ 三处必须诚实的处理（否则堆叠条会骗人）：
     *   1. **复用连接**：dns/connect/tls 全是 -1 —— 它们**没发生**，不是「0ms」。
     *      这里保留 -1，由 View 决定「跳过不画」，绝不能当成 0 挤进堆叠。
     *   2. **QUIC**：connect 段**内含融合的 TLS**（Rust 路径），tls 为 -1。
     *      直接相加不会重复计（因为 tls 是 -1），但**不能**说「QUIC 的连接就是纯 TCP」。
     *   3. **传输余量**：total 未必等于四段之和（含请求体上传、响应体下载、
     *      以及各阶段的调度间隙）。余量用 [StageKind.TRANSFER] 显式呈现，
     *      负数截断为 0 并在 isOverflow 标出 —— 不把对不上的部分悄悄吞掉。
     */
    fun stageSlices(stage: NetMetrics.Stage): List<StageSlice> {
        val known = listOf(
            stage.dnsMillis,
            stage.connectMillis,
            stage.tlsMillis,
            stage.firstByteMillis,
        ).filter { it >= 0 }.sum()
        val transfer = if (stage.totalMillis >= 0) (stage.totalMillis - known).coerceAtLeast(0) else -1
        return listOf(
            StageSlice(StageKind.DNS, stage.dnsMillis),
            StageSlice(StageKind.CONNECT, stage.connectMillis),
            StageSlice(StageKind.TLS, stage.tlsMillis),
            StageSlice(StageKind.TTFT, stage.firstByteMillis),
            StageSlice(StageKind.TRANSFER, transfer),
        )
    }

    /**
     * 「已知四段之和」是否明显小于 total —— 用来提示「还有时间花在别处」。
     *
     * 阈值取 1ms：低于它属于测量噪声（各段各自取整），报出来只会制造噪音。
     */
    fun hasUnaccountedTime(stage: NetMetrics.Stage, thresholdMillis: Long = 1): Boolean {
        if (stage.totalMillis < 0) return false
        val known = listOf(
            stage.dnsMillis, stage.connectMillis, stage.tlsMillis, stage.firstByteMillis,
        ).filter { it >= 0 }.sum()
        return stage.totalMillis - known > thresholdMillis
    }

    /**
     * 时间序列点：每个点的 total 与 ttfb，横轴按「记录序号」而非墙钟时间。
     *
     * ─── 为什么不用墙钟时间 ───
     *
     * 本 demo 的请求是突发式的（点一下发一批，中间可能隔几分钟），
     * 墙钟轴上会挤成几团、中间的空白毫无信息量。用**序号**当横轴，
     * 呈现的是「最近 200 次请求」的形状，才是度量真正想看的。
     * 真实生产监控才需要墙钟轴（这里没有时间字段，也不假装有）。
     */
    data class SeriesPoint(
        val index: Int,
        val totalMillis: Long,
        val ttfbMillis: Long,
        val ok: Boolean,
        val simulated: Boolean,
    )

    /**
     * 由最近记录生成时序（旧→新，便于从左到右画）。
     * 入参按 [NetMetrics.recent] 的「新→旧」传入，这里会反转。
     */
    fun series(recordsNewestFirst: List<NetMetrics.Record>): List<SeriesPoint> =
        recordsNewestFirst.asReversed().mapIndexed { i, r ->
            SeriesPoint(
                index = i,
                totalMillis = r.stage.totalMillis,
                ttfbMillis = r.stage.firstByteMillis,
                ok = r.ok,
                simulated = r.simulated,
            )
        }

    /** 网络状况事件（时间线上的一颗点）。 */
    data class NetEvent(
        val seq: Long,
        val title: String,
        val detail: String,
        val kind: Kind,
        /** 事件发生的墙钟时间（由**采集方**在回调当场打点，不由本模型编造）。 */
        val atMillis: Long,
    ) {
        enum class Kind { CONNECTIVITY, LEVEL }
    }

    /**
     * 由一次快照构造事件；与 [prev] 同级时返回 null（相邻同级必须折叠）。
     *
     * 为什么把「是否产生事件」也做成纯函数：实测切网瞬间系统会连来几条回调，
     * 不去重的话时间线会被同一条刷屏 —— 这条规则需要可单测，而不是埋在 View 里。
     */
    fun levelEvent(
        prev: com.interview.net.NetworkLevel?,
        seq: Long,
        s: com.interview.net.NetworkQuality.Snapshot,
        atMillis: Long,
    ): NetEvent? {
        // 相邻同级折叠掉：切网瞬间系统会连来几条回调，不去重会刷屏。
        if (prev != null && prev == s.level) return null
        return NetEvent(
            seq = seq,
            title = "档位 → ${s.level}",
            detail = "score=${s.score} transport=${s.transport} metered=${s.metered} " +
                "rtt=${if (s.smoothRttMillis < 0) "n/a" else "${s.smoothRttMillis}ms"}",
            kind = NetEvent.Kind.LEVEL,
            atMillis = atMillis,
        )
    }

    // ─────────────────────────────────────────
    // 比率（成功率/复用率/缓存命中）
    // ─────────────────────────────────────────

    data class Rates(
        val successRate: Double,
        val reuseRate: Double,
        val cacheRate: Double,
        val sampleCount: Int,
    ) {
        companion object {
            /** 无样本时全为 -1（而不是 0）——0% 与「没数据」是两回事。 */
            val EMPTY = Rates(-1.0, -1.0, -1.0, 0)
        }
    }

    fun rates(records: List<NetMetrics.Record>): Rates {
        if (records.isEmpty()) return Rates.EMPTY
        val n = records.size
        return Rates(
            successRate = records.count { it.ok }.toDouble() / n,
            reuseRate = records.count { it.reusedConnection }.toDouble() / n,
            cacheRate = records.count { it.fromCache }.toDouble() / n,
            sampleCount = n,
        )
    }

    /**
     * 半圆/条形进度里用到的百分比文本。负值显示为「无样本」——
     * 这是本仓库反复强调的一条：**缺数据 ≠ 0**。
     */
    fun pct(v: Double): String = if (v < 0) "无样本" else "%.0f%%".format(v * 100)
}
