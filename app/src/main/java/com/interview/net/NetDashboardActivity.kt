package com.interview.net

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.interview.net.dashboard.DashboardModels
import com.interview.net.dashboard.EventTimelineView
import com.interview.net.dashboard.LatencySeriesView
import com.interview.net.dashboard.RatioBarView
import com.interview.net.dashboard.SimulatedNetwork
import com.interview.net.dashboard.StageStackedBarView
import com.interview.thread.ThreadPools
import okhttp3.Request

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: **网络度量仪表盘** —— 把「感知层 / 度量层」从日志文本变成图形。
 *
 * ─── 与 [NetLabActivity] 的分工 ───
 *
 * [NetLabActivity] 是**证据链**：一堆按钮，每个产出可复制的文本证据（逐条记录、
 * 路由判定、pin 对拍）。它回答「这件事到底有没有生效」。
 * 本页是**仪表盘**：一张持续刷新的画面，回答「现在的网络状况长什么样、
 * 慢在哪一段、长尾抬了多高、什么时候切了网」。两者互补，不是替代。
 *
 * ─── 两条数据线的纪律（本页最重要的设计）───
 *
 *   ① **真实**：来自 [NetMetrics]（由 [NetEventListener] 从 OkHttp 网络栈采集）。
 *      真实请求由「真实请求 ×10」按钮触发，走完整 OkHttp 栈（含 DNS/连接池/拦截器）。
 *   ② **模拟**：来自 [SimulatedNetwork]（确定性模型），落库时标记 `simulated=true`。
 *      它**不产生任何真实流量**、**不回喂** [NetworkQuality]（见 [NetMetrics.record]）。
 *
 * 图表上：真实=实线/纯色，模拟=虚线/斜纹。勾选「只看真实样本」可把模拟整个剔掉。
 * 这条纪律是刻意的：一个把「人造的漂亮曲线」当真实链路展示的仪表盘，不如没有。
 *
 * ─── 线程纪律 ───
 *
 * 所有真实请求提交到 [ThreadPools.network]（不在 UI 线程发网络）。
 * [NetMetrics] 的变更回调发生在写入线程（OkHttp 回调线程），故本页统一
 * post 回主线程再刷 UI —— 度量层刻意不依赖 Android，切线程是 UI 的责任。
 */
class NetDashboardActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var honesty: TextView
    private lateinit var stageView: StageStackedBarView
    private lateinit var seriesView: LatencySeriesView
    private lateinit var ratioView: RatioBarView
    private lateinit var timelineView: EventTimelineView
    private lateinit var onlyReal: CheckBox

    private val main = Handler(Looper.getMainLooper())

    /** 事件时间线（仅主线程访问）。切网回调会 thread-hop 到这里。 */
    private val events = ArrayList<DashboardModels.NetEvent>()

    /** 上一次已知的真实档位，用于折叠相邻同级事件。 */
    private var lastLevel: NetworkLevel? = null

    /** 每次快照分配一个序号，便于时间线排序与去重。 */
    private var eventSeq = 0L

    private val metricsListener: () -> Unit = { main.post { refresh() } }

    private val qualityListener: (NetworkQuality.Snapshot) -> Unit = { snap ->
        val at = System.currentTimeMillis()
        main.post {
            DashboardModels.levelEvent(lastLevel, ++eventSeq, snap, at)?.let { ev ->
                events += ev
                while (events.size > 20) events.removeAt(0)
                lastLevel = snap.level
            }
            refresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_net_dashboard)

        status = findViewById(R.id.tv_status)
        honesty = findViewById(R.id.tv_honesty)
        stageView = findViewById(R.id.v_stage)
        seriesView = findViewById(R.id.v_series)
        ratioView = findViewById(R.id.v_ratios)
        timelineView = findViewById(R.id.v_timeline)
        onlyReal = findViewById(R.id.cb_real_only)

        findViewById<Button>(R.id.btn_real_burst).setOnClickListener { runRealBurst() }
        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            NetMetrics.reset()
            events.clear()
            lastLevel = null
            refresh()
        }
        findViewById<Button>(R.id.btn_inject).setOnClickListener { injectSimulated() }
        findViewById<Button>(R.id.btn_inject_swap).setOnClickListener { injectSwitchScenario() }
        onlyReal.setOnCheckedChangeListener { _, _ -> refresh() }

        // 默认选「弱网」：真机多半是 GOOD 档，默认给个能看到长尾的档位。
        findViewById<RadioGroup>(R.id.rg_preset).check(R.id.rb_weak)

        // 记录当前档位作为首个事件（否则切网前的事件线是空的，看不出"切"的对比）。
        lastLevel = NetworkQuality.currentQuality().level
        events += DashboardModels.NetEvent(
            seq = ++eventSeq,
            title = "初始档位：${lastLevel}",
            detail = NetworkQuality.currentQuality().detail,
            kind = DashboardModels.NetEvent.Kind.LEVEL,
            atMillis = System.currentTimeMillis(),
        )
        refresh()
    }

    override fun onStart() {
        super.onStart()
        NetMetrics.addListener(metricsListener)
        NetworkQuality.addListener(qualityListener)
    }

    override fun onStop() {
        NetMetrics.removeListener(metricsListener)
        NetworkQuality.removeListener(qualityListener)
        super.onStop()
    }

    // ─────────────────────────────────────────
    // 真实流量
    // ─────────────────────────────────────────

    /** 提交 10 次真实请求。走共享 OkHttp 栈，度量由 [NetEventListener] 采集。 */
    private fun runRealBurst() {
        val n = 10
        repeat(n) {
            val ok = ThreadPools.network.execute(CALLER) {
                runCatching {
                    val req = Request.Builder()
                        .url("https://api.github.com/zen")
                        .header("User-Agent", CALLER)
                        .build()
                    NetClient.shared.newCall(req).execute().use { it.code }
                }.onFailure { Log.w(TAG, "真实请求失败：${it.message}") }
            }
            if (!ok) Log.w(TAG, "net 泳道拒绝第 $it 次（背压）")
        }
    }

    // ─────────────────────────────────────────
    // 模拟注入
    // ─────────────────────────────────────────

    private fun selectedPreset(): SimulatedNetwork.Preset = when (
        findViewById<RadioGroup>(R.id.rg_preset).checkedRadioButtonId
    ) {
        R.id.rb_good -> SimulatedNetwork.Preset.GOOD
        R.id.rb_slow -> SimulatedNetwork.Preset.SLOW
        R.id.rb_lossy -> SimulatedNetwork.Preset.LOSSY
        else -> SimulatedNetwork.Preset.WEAK
    }

    /** 注入一批当前档位的模拟样本。**显式标记 simulated=true**。 */
    private fun injectSimulated() {
        val preset = selectedPreset()
        val batch = SimulatedNetwork.generate(preset, count = 30)
        batch.forEach { recordSimulated(preset, it) }
        // 注入后立刻刷新（NetMetrics 的监听也会触发一次，这里保证 UI 及时）。
        refresh()
    }

    /**
     * 构造一个「从良好切到当前档位」的场景，用来演示**切网瞬间**的效果：
     * 前 12 条按 GOOD、后 12 条按所选档位，中间打两个事件点。
     *
     * 为什么值得专门做一个按钮：稳态 RTT 看不出切网，只有序列上出现
     * 「台阶 + 尾部尖峰」才说明问题；这正是弱网治理里最难复现的一幕。
     */
    private fun injectSwitchScenario() {
        val target = selectedPreset()
        SimulatedNetwork.generate(SimulatedNetwork.Preset.GOOD, count = 12, seed = 7)
            .forEach { recordSimulated(SimulatedNetwork.Preset.GOOD, it) }

        events += DashboardModels.NetEvent(
            seq = ++eventSeq,
            title = "模拟：切网（良好 → ${target.displayName}）",
            detail = "此后的样本按「${target.displayName}」模型生成（模拟，非真实链路）",
            kind = DashboardModels.NetEvent.Kind.LEVEL,
            atMillis = System.currentTimeMillis(),
        )
        while (events.size > 20) events.removeAt(0)

        SimulatedNetwork.generate(target, count = 12, seed = 99)
            .forEach { recordSimulated(target, it) }

        refresh()
    }

    private fun recordSimulated(preset: SimulatedNetwork.Preset, s: SimulatedNetwork.SimRequest) {
        NetMetrics.record(
            NetMetrics.Record(
                host = "sim/${preset.name.lowercase()}",
                method = "GET",
                code = s.code,
                ok = s.ok,
                protocol = "MOCK",
                reusedConnection = s.reusedConnection,
                fromCache = s.fromCache,
                responseBytes = if (s.ok) 512 else 0,
                stage = s.stage,
                errorMessage = s.errorMessage,
                simulated = true,
            )
        )
    }

    // ─────────────────────────────────────────
    // 刷新
    // ─────────────────────────────────────────

    private fun refresh() {
        // 取足够长的一段给图表；WINDOW=200，这里 60 足够看清形状又不糊。
        val all = NetMetrics.recent(60)
        val shown = if (onlyReal.isChecked) all.filter { !it.simulated } else all
        val realCount = all.count { !it.simulated }
        val simCount = all.count { it.simulated }

        val q = NetworkQuality.currentQuality()
        status.text = buildString {
            append("档位=${q.level} score=${q.score} transport=${q.transport} metered=${q.metered}\n")
            append("smoothRTT=${if (q.smoothRttMillis < 0) "无样本" else "${q.smoothRttMillis}ms"} ")
            append("成功率=${if (q.successRate < 0) "无样本" else "%.0f%%".format(q.successRate * 100)}\n")
            append(q.detail)
        }

        honesty.text = "数据构成：真实 $realCount 条 / 模拟 $simCount 条" +
            "（窗口 ${all.size}）" +
            if (onlyReal.isChecked) " —— 当前**已隐藏**模拟样本" else " —— 图表中模拟为虚线/斜纹"

        // 阶段堆叠：新→旧取前 8（真实的更该被优先看到，但保持时间序不拆散）
        stageView.setRecords(shown.take(8))

        // 时序：逐段着色（真实=实线蓝，含模拟端点=虚线紫）
        val series = DashboardModels.series(shown)
        // ⚠️ 分位必须对**同一批** shown 计算：否则勾选「只看真实」时曲线是真实的、
        // P99 却仍含模拟数据，出现最难查的口径不一致。
        seriesView.setData(series, NetMetrics.summarize(shown))

        ratioView.setRates(DashboardModels.rates(shown))
        timelineView.setEvents(events)
    }

    companion object {
        private const val TAG = "NetDashboard"
        private const val CALLER = "interview.netdashboard"
    }
}
