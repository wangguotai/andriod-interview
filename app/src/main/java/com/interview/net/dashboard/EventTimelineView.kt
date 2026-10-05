package com.interview.net.dashboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Time: 2026/10/5
 * Author: wgt
 * Description: **网络状况事件时间线** —— 把「什么时候切了网 / 档位什么时候变了」画成一条轴。
 *
 * ─── 为什么值得单独一张图 ───
 *
 * 弱网问题里最容易被忽略的是**切换瞬间**：WiFi 掉到蜂窝的那一两秒，DNS 缓存失效、
 * 连接池作废、正在飞的请求失败。这不是稳态 RTT 能反映的，只有把「事件」标在
 * 时间轴上，才能和同一时刻的延迟曲线对上 —— 「看，P99 那个尖峰正好在切网那一刻」。
 *
 * 数据来自 [com.interview.net.NetworkQuality.addListener]，事件时刻由监听方当场打点。
 */
class EventTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val sp = resources.displayMetrics.scaledDensity

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFADB5BD.toInt(); strokeWidth = 1.5f
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8590C.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF343A40.toInt(); textSize = 10f * sp
    }
    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF868E96.toInt(); textSize = 9f * sp
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF868E96.toInt(); textSize = 12f * sp; textAlign = Paint.Align.CENTER
    }

    private var events: List<DashboardModels.NetEvent> = emptyList()

    fun setEvents(list: List<DashboardModels.NetEvent>) {
        events = list.takeLast(MAX_EVENTS)
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = events.size.coerceAtLeast(1)
        val desired = (paddingTop + paddingBottom + rows * (16f * sp + 6f * sp) + 20f * sp).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(desired, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val axisX = paddingLeft + 10f * sp
        val top = paddingTop + 6f * sp

        if (events.isEmpty()) {
            canvas.drawText("暂无事件 —— 切一次网（WiFi↔蜂窝）或点上方档位按钮", width / 2f, height / 2f, hintPaint)
            return
        }

        val bottom = top + events.size * (16f * sp + 6f * sp)
        canvas.drawLine(axisX, top, axisX, bottom, axisPaint)

        events.forEachIndexed { i, e ->
            val cy = top + i * (16f * sp + 6f * sp) + 8f * sp
            canvas.drawCircle(axisX, cy, 4f * sp, dotPaint)
            canvas.drawCircle(axisX, cy, 6.5f * sp, Paint(dotPaint).apply {
                style = Paint.Style.STROKE; strokeWidth = 1f; alpha = 90
            })
            canvas.drawText(e.title, axisX + 12f * sp, cy + 3.5f * sp, textPaint)
            canvas.drawText(e.detail, axisX + 12f * sp, cy + 15f * sp, timePaint)
        }
    }

    companion object {
        private const val MAX_EVENTS = 6
    }
}
