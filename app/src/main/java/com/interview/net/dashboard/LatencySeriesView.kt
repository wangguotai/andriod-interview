package com.interview.net.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.interview.net.NetMetrics

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: **实时时间序列 + P50/P90/P99 参考线** —— 让「长尾」变成看得见的一条抬起来的线。
 *
 * ─── 这张图想证明的一句话 ───
 *
 * 均值/成功率看起来正常，不代表没问题：P50 贴着地面、P99 却冲到 3s，
 * 是弱网用户的真实体验。把 P50/P90/P99 三条参考线叠在同一个纵轴上，
 * 「尾部抬升」就从一个抽象说法变成一条可以指着的曲线。
 *
 * ─── 横轴为什么用序号而不是时间 ───
 *
 * 见 [DashboardModels.SeriesPoint]：demo 的请求是突发式的，墙钟轴会挤成几团。
 * 用序号呈现「最近 N 次请求的形状」才是度量想看的。
 *
 * ─── 真实/模拟怎么区分（这条必须没有歧义）───
 *
 * 曲线**逐段**着色：真实样本之间的段是**实线蓝**，只要任一端点是模拟样本，
 * 该段就是**虚线紫**。这样「同一条曲线里哪些是真实、哪些是模拟」一眼可分，
 * 且**绝不**把模拟数据画成实线蓝冒充真实流量（早前一版用「主实线+叠加虚线」
 * 会把模拟样本也画进主实线，属于会骗人的画法，已弃用）。
 *
 * 纵轴对整条曲线取同一个 max 刻度：跨段、跨真实/模拟都能在同一把尺子上比较。
 */
class LatencySeriesView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val sp = resources.displayMetrics.scaledDensity

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFCED4DA.toInt(); strokeWidth = 1f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFEDEFF2.toInt(); strokeWidth = 1f
    }
    private val realPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1C7ED6.toInt(); strokeWidth = 2f * sp; style = Paint.Style.STROKE
    }
    private val simPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF7048E8.toInt(); strokeWidth = 2f * sp; style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(6f * sp, 4f * sp), 0f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val p50Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2F9E44.toInt(); strokeWidth = 1.2f
        pathEffect = DashPathEffect(floatArrayOf(8f * sp, 6f * sp), 0f)
    }
    private val p90Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF59F00.toInt(); strokeWidth = 1.2f
        pathEffect = DashPathEffect(floatArrayOf(8f * sp, 6f * sp), 0f)
    }
    private val p99Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE03131.toInt(); strokeWidth = 1.2f
        pathEffect = DashPathEffect(floatArrayOf(8f * sp, 6f * sp), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF868E96.toInt(); textSize = 10f * sp
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF868E96.toInt(); textSize = 12f * sp; textAlign = Paint.Align.CENTER
    }

    private var points: List<DashboardModels.SeriesPoint> = emptyList()
    private var summary: NetMetrics.Summary? = null

    fun setData(series: List<DashboardModels.SeriesPoint>, summary: NetMetrics.Summary?) {
        this.points = series
        this.summary = summary
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (140 * sp).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft + 38f * sp
        val right = width - paddingRight - 4f * sp
        val top = paddingTop + 6f * sp
        val bottom = height - paddingBottom - 14f * sp

        if (points.size < 2) {
            canvas.drawText("样本不足（<2）—— 发几次请求后再看趋势", width / 2f, height / 2f, hintPaint)
            return
        }

        // ── 纵轴刻度：整条曲线共享同一个 max，跨真实/模拟可比 ──
        val allTotals = points.map { it.totalMillis }.filter { it >= 0 }
        val maxY = (allTotals.maxOrNull() ?: 1L).coerceAtLeast(1L)

        // 三条水平网格 + 左轴数值
        val gridCount = 3
        for (g in 0..gridCount) {
            val y = bottom - (bottom - top) * g / gridCount
            canvas.drawLine(left, y, right, y, gridPaint)
            val v = maxY * g / gridCount
            canvas.drawText("${v}ms", paddingLeft.toFloat(), y + 3.5f * sp, textPaint)
        }
        canvas.drawLine(left, top, left, bottom, axisPaint)
        canvas.drawLine(left, bottom, right, bottom, axisPaint)

        // ── 曲线：逐段着色（真实=实线蓝 / 含模拟端点=虚线紫）──
        val n = points.size
        val dx = if (n > 1) (right - left) / (n - 1) else 0f
        fun xAt(i: Int) = left + dx * i
        fun yAt(millis: Long) = bottom - (bottom - top) * (millis.coerceAtLeast(0).toFloat() / maxY)

        for (i in 1 until n) {
            val a = points[i - 1]
            val b = points[i]
            val paint = if (a.simulated || b.simulated) simPaint else realPaint
            canvas.drawLine(xAt(i - 1), yAt(a.totalMillis), xAt(i), yAt(b.totalMillis), paint)
        }
        // 单点（n==1）时画个点，否则什么都看不到
        if (n == 1) {
            dotPaint.color = if (points[0].simulated) simPaint.color else realPaint.color
            canvas.drawCircle(xAt(0), yAt(points[0].totalMillis), 3f * sp, dotPaint)
        }

        // ── 分位参考线 ──
        summary?.let { s ->
            drawPercentile(canvas, left, right, top, bottom, maxY, s.p50TotalMillis, p50Paint, "P50=${s.p50TotalMillis}")
            drawPercentile(canvas, left, right, top, bottom, maxY, s.p90TotalMillis, p90Paint, "P90=${s.p90TotalMillis}")
            drawPercentile(canvas, left, right, top, bottom, maxY, s.p99TotalMillis, p99Paint, "P99=${s.p99TotalMillis}")
        }

        // 右下角图例
        canvas.drawText("— 真实   ┄ 模拟/含模拟   P50/P90/P99", right.toFloat(), height - 2f * sp, Paint(textPaint).apply {
            textAlign = Paint.Align.RIGHT
        })
    }

    private fun drawPercentile(
        canvas: Canvas,
        left: Float, right: Float, top: Float, bottom: Float, maxY: Long,
        value: Long, paint: Paint, label: String,
    ) {
        if (value < 0) return
        val y = bottom - (bottom - top) * (value.toFloat() / maxY)
        canvas.drawLine(left, y, right, y, paint)
        val tp = Paint(textPaint).apply { color = paint.color; textAlign = Paint.Align.RIGHT }
        canvas.drawText(
            label,
            (width - paddingRight).toFloat(),
            (y - 2f * sp).coerceAtLeast(top + 9f * sp),
            tp,
        )
    }
}
