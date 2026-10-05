package com.interview.net

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 网络质量**打分策略** —— 纯逻辑，零 Android 依赖。
 *
 * ─── 为什么从 NetworkQuality 里拆出来 ───
 *
 * 打分是「弱网优化」里最需要被反复调参、也最需要被验证的部分：
 * 「RTT 到多少算弱网」「成功率低于多少降级」这些阈值一旦写错，
 * 要么全量误降级（强网用户被当弱网），要么该降不降（弱网直接失败）。
 *
 * 但它原本嵌在 NetworkQuality 里 —— 那是个 Android object，一被引用就会
 * 触发 ConnectivityManager 回调注册、通知监听等副作用，**根本没法在 JVM 上单测**。
 * 拆成纯函数后：`./gradlew :app:testDebugUnitTest` 秒级验证全部分支，
 * 不需要设备、不需要网络。
 *
 * 这也是本仓库「把策略与平台隔离」的一贯做法（对照 rust/imagepipeline
 * 把算法与 JNI 分离的纪律）。
 */
enum class NetworkLevel(val score: Int) {
    /** 无网或探测判定不可达 */
    OFFLINE(0),
    /** 高延迟/高丢包/假连接：触发全量降级 */
    WEAK(1),
    /** 临界：触发部分降级（图片降清晰度、关闭预加载） */
    MARGINAL(2),
    /** 强网：全量拉取、允许预加载 */
    GOOD(3),
}

object NetworkScoring {

    /**
     * 阈值为何取这些数（诚实标注：以下均为**经验值**，非本仓库实测标定）：
     *   - 100ms：蜂窝/WiFi「好」区间的上界。
     *   - 300ms：交互式界面可接受上限，超过用户能明确感到「卡」。
     *     量级参考「100ms 即时反馈 / 1s 保持注意力」的经典分界，取 300ms
     *     作为「需要骨架屏/降级按钮」的触发线。
     *   - 成功率 60%：低于此说明链路已不可用（10 次里 4 次失败），
     *     继续按正常拉取只会放大失败与重试。
     *
     * ⚠️ 上线前应结合 [NetMetrics] 的分位分布回调这些值，别当硬结论。
     */
    const val RTT_GOOD_MILLIS = 100L
    const val RTT_WEAK_MILLIS = 300L
    const val SUCCESS_RATE_WEAK = 0.6
    const val MIN_SAMPLES_FOR_TRUST = 3

    /**
     * 打分。规则顺序**有优先级，不能调换**：
     *
     *   1. 系统判无网             → OFFLINE
     *   2. 有网但近期请求全失败   → WEAK（抓「假连接」：WiFi 满格却不达）
     *   3. 样本足够：看 RTT + 成功率
     *   4. 样本不足：以系统类型为准（强网默认乐观，蜂窝保守一档）
     *
     * @param hasNetwork  系统层是否报告「有网」
     * @param transport   WIFI / CELLULAR / ETHERNET / OTHER / NONE
     * @param smoothRttMillis 平滑 RTT，-1 表示无样本
     * @param successRate 成功率 0.0~1.0，-1 表示无样本
     * @param sampleCount 样本数
     */
    fun evaluate(
        hasNetwork: Boolean,
        transport: String,
        smoothRttMillis: Long,
        successRate: Double,
        sampleCount: Int,
    ): Pair<NetworkLevel, Int> {
        if (!hasNetwork) {
            return NetworkLevel.OFFLINE to NetworkLevel.OFFLINE.score
        }

        // 规则 2：假连接检测。系统说联网了，但真实请求全挂。
        // 这是「感知必须结合真实请求」的最有力证据 —— 单看 XiFi/G 图标会误判。
        if (sampleCount >= MIN_SAMPLES_FOR_TRUST && successRate in 0.0..0.0001) {
            return NetworkLevel.WEAK to NetworkLevel.WEAK.score
        }

        if (sampleCount < MIN_SAMPLES_FOR_TRUST || smoothRttMillis < 0) {
            // 规则 4：样本不足。WiFi/以太网默认 GOOD；蜂窝保守为 MARGINAL ——
            // 弱信号区首次请求失败率显著更高，而样本不足时无法区分，宁可保守一档。
            return when (transport) {
                "WIFI", "ETHERNET" -> NetworkLevel.GOOD to NetworkLevel.GOOD.score
                else -> NetworkLevel.MARGINAL to NetworkLevel.MARGINAL.score
            }
        }

        // 规则 3：RTT + 成功率综合，取更差的一档。
        val rttWeak = smoothRttMillis >= RTT_WEAK_MILLIS
        val rttMarginal = smoothRttMillis >= RTT_GOOD_MILLIS
        val failing = successRate < SUCCESS_RATE_WEAK

        return when {
            rttWeak || failing -> NetworkLevel.WEAK to NetworkLevel.WEAK.score
            rttMarginal -> NetworkLevel.MARGINAL to NetworkLevel.MARGINAL.score
            else -> NetworkLevel.GOOD to NetworkLevel.GOOD.score
        }
    }
}
