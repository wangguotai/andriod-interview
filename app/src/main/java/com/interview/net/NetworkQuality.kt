package com.interview.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: 第 1 步「感知层」—— 网络质量分档与探测。
 *
 * ─── 为什么必须先有这一层 ───
 *
 * 文章里所有「弱网专项」手段（降级、动态超时、多通道兜底）都有一个共同前提：
 * **系统得先知道现在是弱网**。没有质量感知，那些策略只能写死成常量，
 * 结果是强网用户被按弱网对待（体验变差），或弱网用户被按强网对待（直接失败）。
 *
 * 感知分两个层次，缺一不可：
 *   1. **静态类型**：ConnectivityManager 告诉我们 WiFi / 4G / 无网。
 *      它快、免费、事件驱动，但**不可信** —— 「WiFi 满格但不通」是最常见的
 *      假连接（酒店门户、断网的路由器）。
 *   2. **动态质量**：由 [NetMetrics] 的真实请求结果回喂的滚动 RTT / 成功率。
 *      它慢半拍，但反映的是**真实可达性**。
 *
 * 本类只负责**采集**（两个层次的原始数据 + 窗口维护）；真正的判定规则
 * 抽在纯逻辑 [NetworkScoring] 里，可在 JVM 上单测全部分支。
 * 这个拆分见 [NetworkScoring] 的类注释。
 *
 * ─── 依赖纪律 ───
 *
 * ⚠️ 本类不做任何 IO，构造时不发起网络探测。 [start] 只注册回调 + 读一次状态，
 * 不阻塞启动。[currentQuality] 随时可读，纯内存计算。
 * context 只应传 applicationContext（本类会比 Activity 活得久）。
 */
object NetworkQuality {

    private const val TAG = "NetworkQuality"

    data class Snapshot(
        val level: NetworkLevel,
        val score: Int,
        /** 传输类型：WIFI / CELLULAR / ETHERNET / NONE / OTHER */
        val transport: String,
        val metered: Boolean,
        /** 最近窗口的平滑 RTT（ms）；无样本时为 -1 */
        val smoothRttMillis: Long,
        /** 最近窗口成功率 0.0~1.0；无样本时为 -1 */
        val successRate: Double,
        val sampleCount: Int,
        val detail: String,
    )

    // ─────────────────────────────────────────
    // 静态类型（事件驱动）
    // ─────────────────────────────────────────

    private lateinit var appContext: Context
    private val started = AtomicInteger(0)
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    @Volatile
    private var transport: String = "NONE"

    @Volatile
    private var metered: Boolean = false

    @Volatile
    private var hasNetwork: Boolean = false

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refreshFromSystem()
            notifyListeners()
        }

        override fun onLost(network: Network) {
            refreshFromSystem()
            notifyListeners()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            caps: NetworkCapabilities,
        ) {
            applyCapabilities(caps)
            notifyListeners()
        }
    }

    /**
     * 幂等启动。**必须在 Application.onCreate 调用**（而不是懒加载）：
     * 网络切换事件要在第一次请求之前就开始收集，否则 App 冷启动后的
     * 首个请求拿到的永远是初始值 NONE。
     */
    fun start(context: Context) {
        if (!started.compareAndSet(0, 1)) return
        appContext = context.applicationContext
        registerSystemCallback()
        refreshFromSystem()
    }

    private fun registerSystemCallback() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            Log.w(TAG, "ConnectivityManager 不可用，退化为仅靠动态质量分档")
            return
        }
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, connectivityCallback)
        }.onFailure { Log.w(TAG, "注册网络回调失败（缺 ACCESS_NETWORK_STATE？）", it) }
    }

    private fun refreshFromSystem() {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val active = cm.activeNetwork ?: run {
            hasNetwork = false
            transport = "NONE"
            metered = false
            return
        }
        val caps = cm.getNetworkCapabilities(active) ?: return
        applyCapabilities(caps)
    }

    private fun applyCapabilities(caps: NetworkCapabilities) {
        hasNetwork = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
    }

    // ─────────────────────────────────────────
    // 动态质量（请求结果回喂）
    // ─────────────────────────────────────────

    private data class Sample(val rttMillis: Long, val ok: Boolean)

    private val samples = ConcurrentLinkedQueue<Sample>()

    /**
     * 由 [NetMetrics] 在每次请求结束时回喂。业务代码不要直接调。
     *
     * 只保留最近 [WINDOW] 个样本：网络质量是**时变**的，
     * 用全历史均值会让「刚从地铁出来进 WiFi」的用户被旧数据拖着。
     */
    internal fun recordRequest(rttMillis: Long, ok: Boolean) {
        if (rttMillis < 0) return
        samples.add(Sample(rttMillis, ok))
        while (samples.size > WINDOW) samples.poll()
    }

    /** 网络切换/长时间无样本时清空历史（旧链路的 RTT 不再代表当前链路）。 */
    internal fun resetSamples() {
        samples.clear()
    }

    /** 当前质量快照。纯读，可在主线程调用。 */
    fun currentQuality(): Snapshot {
        val history = samples.toList()
        val count = history.size
        val smoothRtt: Long
        val successRate: Double
        if (count == 0) {
            smoothRtt = -1
            successRate = -1.0
        } else {
            // 平滑 RTT 用**中位数**而非均值，抗单次长尾：
            // DNS 慢一次的样本不该把整体判成弱网。样本量小，排序成本可忽略。
            val sorted = history.map { it.rttMillis }.sorted()
            smoothRtt = sorted[sorted.size / 2]
            successRate = history.count { it.ok }.toDouble() / count
        }

        val (level, score) = NetworkScoring.evaluate(
            hasNetwork = hasNetwork,
            transport = transport,
            smoothRttMillis = smoothRtt,
            successRate = successRate,
            sampleCount = count,
        )
        return Snapshot(
            level = level,
            score = score,
            transport = transport,
            metered = metered,
            smoothRttMillis = smoothRtt,
            successRate = successRate,
            sampleCount = count,
            detail = describe(level, smoothRtt, successRate, count),
        )
    }

    private fun describe(level: NetworkLevel, smoothRtt: Long, successRate: Double, count: Int): String =
        buildString {
            append("transport=$transport metered=$metered samples=$count ")
            append("rtt=${if (smoothRtt < 0) "n/a" else "${smoothRtt}ms"} ")
            append("ok=${if (successRate < 0) "n/a" else "%.0f%%".format(successRate * 100)}")
            if (!hasNetwork) append(" | 系统判无网")
            if (hasNetwork && count < NetworkScoring.MIN_SAMPLES_FOR_TRUST) {
                append(" | 样本不足，以系统类型为准")
            }
            if (level == NetworkLevel.WEAK && successRate in 0.0..0.0001) {
                append(" | ⚠ 疑似假连接（有网但请求不可达）")
            }
        }

    // ─────────────────────────────────────────
    // 订阅
    // ─────────────────────────────────────────

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        // 网络切换后旧 RTT 样本失效，先清再用新状态通知。
        resetSamples()
        val snapshot = currentQuality()
        Log.i(TAG, "网络状态变化 -> ${snapshot.level} (${snapshot.detail})")
        listeners.forEach { runCatching { it(snapshot) } }
    }

    private const val WINDOW = 16
}
