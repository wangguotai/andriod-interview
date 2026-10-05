package com.interview.net.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: **比率条** —— 成功率 / 连接复用率 / 缓存命中率 的实时读数。
 *
 * ─── 为什么把这三个放一起 ───
 *
 * 它们都是「0..1 的比例」，但含义完全不同，放一起是为了对照着读：
 *   · **成功率**掉 → 链路或服务端有问题（弱网/5xx）；
 *   · **复用率**掉 → 连接池在被反复作废：切网、超时、服务端 close 都会让复用率归零，
 *     而复用率一旦掉，DNS/TCP/TLS 的成本就重新出现（这解释了延迟曲线的抬头）；
 *   · **缓存命中率** → 与网络无关时延迟下降的唯一合法来源，别把缓存命中当成"网变快了"。
 *
 * ⚠️ 无样本时显示「无样本」而不是 0% —— 0% 与「没数据」是两回事，
 * 混为一谈会让监控在冷启动时假装"全挂了"。
 */
class RatioBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val sp = resources.displayMetrics.scaledDensity

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEDEFF2.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF343A40.toInt(); textSize = 10f * sp
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF212529.toInt(); textSize = 10f * sp; textAlign = Paint.Align.RIGHT
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF868E96.toInt(); textSize = 12f * sp; textAlign = Paint.Align.CENTER
    }

    private var rates: DashboardModels.Rates = DashboardModels.Rates.EMPTY
    private val rect = RectF()

    /** (显示名, 取值, 颜色) —— 顺序即绘制顺序。 */
    private fun rows(): List<Triple<String, Double, Int>> = listOf(
        Triple("成功率", rates.successRate, 0xFF2F9E44.toInt()),
        Triple("连接复用", rates.reuseRate, 0xFF1C7ED6.toInt()),
        Triple("缓存命中", rates.cacheRate, 0xFF7048E8.toInt()),
    )

    fun setRates(r: DashboardModels.Rates) {
        rates = r
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desired = (3 * (16f * sp + 8f * sp) + paddingTop + paddingBottom).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rates.sampleCount == 0) {
            canvas.drawText("暂无样本", width / 2f, height / 2f, hintPaint)
            return
        }

        val labelW = 48f * sp
        val valueW = 52f * sp
        val left = paddingLeft + labelW
        val right = width - paddingRight - valueW
        val trackW = (right - left).coerceAtLeast(1f)

        rows().forEachIndexed { i, (name, v, color) ->
            val top = paddingTop + i * (16f * sp + 8f * sp)
            val cy = top + 8f * sp

            canvas.drawText(name, paddingLeft.toFloat(), cy + 3.5f * sp, textPaint)

            rect.set(left, top, right, top + 12f * sp)
            canvas.drawRoundRect(rect, 3f * sp, 3f * sp, trackPaint)

            if (v >= 0) {
                val w = (trackW * v).toFloat()
                if (w > 0f) {
                    rect.set(left, top, left + w, top + 12f * sp)
                    canvas.drawRoundRect(rect, 3f * sp, 3f * sp, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = color
                    })
                }
            }

            canvas.drawText(
                DashboardModels.pct(v),
                (width - paddingRight).toFloat(), cy + 3.5f * sp, valuePaint,
            )
        }
    }
}
