package com.interview.net.dashboard

import com.interview.net.NetMetrics
import kotlin.math.roundToLong

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 弱网模拟器的**确定性阶段模型** —— 把「网络有多差」翻译成一次请求的
 * 阶段耗时与成败，且**可复现**。
 *
 * ─── 这是个什么东西，不是什么（先讲清边界，别误读）───
 *
 * ⚠️ 它**不是**真实链路测量，也**不改**系统网络。它是：
 *   · 无 Android 依赖的纯函数模型：给定「档位 + 带种子的随机数」→ 一批阶段耗时；
 *   · 落库时用 [NetMetrics.Record.simulated] = true **显式标记**，
 *     仪表盘上以虚线/浅色区分，且**绝不与真实流量混为一谈**；
 *   · 存在的理由：真实设备上无法按需造出「RTT 400ms + 丢包 20%」的弱网，
 *     而没有它，页面在没网/没请求时就是一片空白，演示不可复现。
 *
 * 一句话：**真实流量用来"看真的"，注入用来"看得见"**，两者在数据上分得清清楚楚。
 *
 * ─── 为什么用固定种子而不是 Random() ───
 *
 * 演示要可复现：同一档位点两次应得到**同一条**曲线形状，否则每次讲解都得
 * 重新描述「这次为什么长这样」。固定种子让「弱网长尾」是一个可指认的对象。
 *
 * ─── 与 [com.interview.net.NetworkQuality] 的关系 ───
 *
 * 本模型**不参与**真实的网络判定，只产出一束人造 [NetMetrics.Record]。
 * 唯一的软性关联在 [presetFrom]：它按真实档位挑一个「更差的」模拟档位候选，
 * 但这只是默认值，用户可显式覆盖。策略层永远只认真实信号。
 */
object SimulatedNetwork {

    /**
     * 模拟档位。语义对齐真实 [com.interview.net.NetworkLevel]，但**多两档**：
     *
     * 真实感知层只有 OFFLINE/WEAK/MARGINAL/GOOD 四档，而演示需要展示
     * 「弱到什么程度」的连续谱 —— 把 WEAK 拆成 NORMAL/SLOW/WEAK/LOSSY 四档，
     * 才能看出「RTT 从 80ms 涨到 480ms 时分位数怎么塌」。
     * 这不是要改评估模型，纯粹是演示用的更细粒度的自变量。
     */
    enum class Preset(
        val displayName: String,
        /** 各阶段基准耗时（ms），按 DNS/建连/TLS/首包/传输 顺序。 */
        val baseMillis: LongArray,
        /** 抖动比例 0..1：实际耗时 = base × (1 + jitter×U(-1,1))。 */
        val jitter: Double,
        /** 失败概率 0..1。 */
        val failureRate: Double,
    ) {
        GOOD("良好 WiFi", longArrayOf(4, 12, 18, 30, 20), jitter = 0.15, failureRate = 0.0),
        SLOW("慢速", longArrayOf(30, 60, 90, 120, 40), jitter = 0.25, failureRate = 0.02),
        WEAK("弱网", longArrayOf(80, 160, 220, 320, 90), jitter = 0.45, failureRate = 0.10),
        LOSSY("高丢包", longArrayOf(150, 300, 400, 900, 250), jitter = 0.70, failureRate = 0.28),
    }

    /**
     * 一次模拟请求的完整结果。字段刻意与 [NetMetrics.Record] 需要的量对齐。
     */
    data class SimRequest(
        val stage: NetMetrics.Stage,
        val ok: Boolean,
        val code: Int,
        val reusedConnection: Boolean,
        val fromCache: Boolean,
        val errorMessage: String?,
    )

    /**
     * 生成一批模拟请求（旧→新）。
     *
     * @param preset    模拟档位
     * @param count     请求条数
     * @param seed      随机种子（固定则形状可复现）
     * @param reuseRate 连接复用概率（对齐真实 EventListener 的 reused 语义）
     */
    fun generate(
        preset: Preset,
        count: Int,
        seed: Long = 42L,
        reuseRate: Double = 0.6,
    ): List<SimRequest> {
        val rnd = java.util.Random(seed)
        return (0 until count).map { i ->
            // 前两次强制不复用：真实世界里冷启动前几发必然建连，
            // 一个「一上来就 100% 复用」的假数据一眼就假。
            val reused = i >= 2 && rnd.nextDouble() < reuseRate
            val failed = rnd.nextDouble() < preset.failureRate
            val cached = !failed && rnd.nextDouble() < 0.08

            val stage = if (cached) {
                NetMetrics.Stage(-1, -1, -1, -1, jittered(preset, 4, rnd))
            } else if (reused) {
                // 复用：dns/tcp/tls 三段**未发生**，按真实语义置 -1（不是 0）。
                NetMetrics.Stage(
                    dnsMillis = -1,
                    connectMillis = -1,
                    tlsMillis = -1,
                    firstByteMillis = jittered(preset, preset.baseMillis[3], rnd),
                    totalMillis = -1, // 下面统一算
                )
            } else {
                NetMetrics.Stage(
                    dnsMillis = jittered(preset, preset.baseMillis[0], rnd),
                    connectMillis = jittered(preset, preset.baseMillis[1], rnd),
                    tlsMillis = jittered(preset, preset.baseMillis[2], rnd),
                    firstByteMillis = jittered(preset, preset.baseMillis[3], rnd),
                    totalMillis = -1,
                )
            }

            val transfer = jittered(preset, preset.baseMillis[4], rnd)
            // 失败时（超时/重置）总耗时通常显著拉长 —— 这正是弱网尾部。
            val total = stage.partsSum() + transfer + if (failed) jittered(preset, preset.baseMillis[3], rnd) else 0

            val finalStage = stage.copy(
                totalMillis = total,
                firstByteMillis = if (failed) -1 else stage.firstByteMillis,
            )

            val code = when {
                cached -> 200
                failed -> if (preset == Preset.LOSSY) 0 else -1
                else -> 200
            }
            SimRequest(
                stage = finalStage,
                ok = !failed,
                code = code,
                reusedConnection = reused,
                fromCache = cached,
                errorMessage = if (failed) {
                    if (preset == Preset.LOSSY) "SocketTimeoutException: 读超时（模拟丢包）"
                    else "IOException: 连接被重置（模拟弱网）"
                } else null,
            )
        }
    }

    /**
     * 真实档位 → 建议的模拟档位（仅作默认值，用户可改）。
     *
     * 映射刻意偏「能看到差异」：真机多半是 GOOD 档，若跟着映射成 GOOD，
     * 演示就看不到弱网长尾了 —— 故真实 GOOD 默认给 SLOW，让你再手动加档。
     */
    fun presetFrom(level: com.interview.net.NetworkLevel): Preset = when (level) {
        com.interview.net.NetworkLevel.OFFLINE -> Preset.LOSSY
        com.interview.net.NetworkLevel.WEAK -> Preset.LOSSY
        com.interview.net.NetworkLevel.MARGINAL -> Preset.WEAK
        com.interview.net.NetworkLevel.GOOD -> Preset.SLOW
    }

    private fun NetMetrics.Stage.partsSum(): Long =
        listOf(dnsMillis, connectMillis, tlsMillis).filter { it >= 0 }.sum()

    /** base × (1 + jitter×U(-1,1))，下限 0。 */
    private fun jittered(preset: Preset, base: Long, rnd: java.util.Random): Long {
        val factor = 1.0 + preset.jitter * (rnd.nextDouble() * 2 - 1)
        return (base * factor).roundToLong().coerceAtLeast(0)
    }
}
