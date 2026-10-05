package com.interview.net.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.interview.net.NetMetrics

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: **阶段耗时堆叠条** —— 把每条请求的 DNS/建连/TLS/首包/传输 画成一条横向堆叠条。
 *
 * 这是「度量层」最该有的那张图：一条 3 秒的请求到底花在哪一段，一眼可见。
 * 纯 `TextView` 打数字做不到这件事 —— 数字要你在脑子里做减法。
 *
 * ─── 三条画图纪律（否则图会说谎）───
 *
 * 1. **未发生的阶段不画**：连接复用时 dns/connect/tls 是 -1（不是 0ms），
 *    必须跳过而不是画一个 0 宽度的段 —— 否则「复用很快」会被误读成「这三段是 0」。
 * 2. **统一横轴**：所有条按窗口内**同一个最大值**缩放，条长才可比。
 *    若每条按自身 total 归一化，每条都填满整宽，就完全看不出「谁更慢」。
 *    代价是快请求会变短 —— 但那正是事实。
 * 3. **模拟样本可辨**：来自弱网模拟器的条加斜线底纹，绝不与真实流量混淆。
 */
class StageStackedBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val sp = resources.displayMetrics.scaledDensity

    private val barBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEFF2.toInt() }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF495057.toInt(); textSize = 10f * sp
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF212529.toInt(); textSize = 10f * sp; textAlign = Paint.Align.RIGHT
    }
    private val okPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2F9E44.toInt() }
    private val errPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE03131.toInt() }
    private val hatchPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000; strokeWidth = 1.2f; style = Paint.Style.STROKE
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF868E96.toInt(); textSize = 12f * sp; textAlign = Paint.Align.CENTER
    }

    private var records: List<NetMetrics.Record> = emptyList()
    private val rect = RectF()

    /** 与 [DashboardModels.StageKind] 对应的固定配色（顺序即堆叠顺序）。 */
    private val stageColors = intArrayOf(
        0xFF4C6EF5.toInt(), // DNS
        0xFF12B886.toInt(), // 建连
        0xFFF59F00.toInt(), // TLS
        0xFFE8590C.toInt(), // 首包
        0xFFADB5BD.toInt(), // 传输余量
    )

    /** 展示的最大条数（新→旧取前 N）。 */
    var maxRows: Int = 8

    fun setRecords(list: List<NetMetrics.Record>) {
        records = list.take(maxRows)
        requestLayout()
        invalidate()
    }

    private val rowHeightPx: Float get() = 18f * sp
    private val rowGapPx: Float get() = 8f * sp
    private val labelWidthPx: Float get() = 34f * sp
    private val valueWidthPx: Float get() = 44f * sp

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = records.size.coerceAtLeast(1)
        val desired = (paddingTop + paddingBottom + rows * (rowHeightPx + rowGapPx)).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth.coerceAtLeast(200), widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (records.isEmpty()) {
            canvas.drawText(
                "暂无样本 —— 点 3.单次API / 4.并发 发几次请求，或用下方弱网模拟器注入",
                width / 2f, height / 2f, hintPaint,
            )
            return
        }

        val left = paddingLeft + labelWidthPx
        val right = width - paddingRight - valueWidthPx
        val trackWidth = (right - left).coerceAtLeast(1f)

        // 统一横轴：取窗口内最大 total，保证条长可比（见类注释纪律 2）。
        val maxTotal = records.maxOfOrNull { it.stage.totalMillis.coerceAtLeast(0) }?.coerceAtLeast(1) ?: 1

        records.forEachIndexed { i, r ->
            val top = paddingTop + i * (rowHeightPx + rowGapPx)
            val cy = top + rowHeightPx / 2f

            // 左侧：状态点 + 协议缩略（h2/h3/缓存）
            canvas.drawCircle(paddingLeft + 4f * sp, cy, 3f * sp, if (r.ok) okPaint else errPaint)
            val tag = when {
                r.fromCache -> "CACHE"
                r.protocol.contains("3") || r.protocol.contains("QUIC") -> "h3"
                else -> "h2"
            }
            canvas.drawText(tag, paddingLeft + 10f * sp, cy + 3.5f * sp, labelPaint)

            // 轨道底
            rect.set(left, top, right, top + rowHeightPx)
            canvas.drawRoundRect(rect, 3f * sp, 3f * sp, barBgPaint)

            // 分段堆叠
            var x = left
            val slices = DashboardModels.stageSlices(r.stage)
            slices.forEachIndexed { si, slice ->
                if (slice.millis < 0) return@forEachIndexed // 未发生：跳过（纪律 1）
                val w = trackWidth * (slice.millis.toFloat() / maxTotal)
                if (w <= 0f) return@forEachIndexed
                segPaint.color = stageColors[si]
                rect.set(x, top, x + w, top + rowHeightPx)
                canvas.drawRect(rect, segPaint)
                x += w
            }

            // 模拟样本：斜线底纹（纪律 3）
            if (r.simulated) {
                rect.set(left, top, right, top + rowHeightPx)
                canvas.save()
                canvas.clipRect(rect)
                var hx = left - rowHeightPx
                while (hx < right) {
                    canvas.drawLine(hx, top + rowHeightPx, hx + rowHeightPx, top, hatchPaint)
                    hx += 6f * sp
                }
                canvas.restore()
            }

            // 右侧：总耗时
            val total = if (r.ok) "${r.stage.totalMillis}ms" else "ERR"
            valuePaint.color = if (r.ok) 0xFF212529.toInt() else 0xFFE03131.toInt()
            canvas.drawText(total, (width - paddingRight).toFloat(), cy + 3.5f * sp, valuePaint)
        }
    }
}
