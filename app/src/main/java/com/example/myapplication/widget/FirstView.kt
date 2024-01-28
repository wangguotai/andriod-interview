package com.example.myapplication.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.myapplication.R

class FirstView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    // 目标圆角矩形
    private val rect = RectF()

    var roundSize: Float = 50f
        set(value) {
            // 自定义setter的逻辑
            println("Setting property value to: $value")
            field = value
        }
    var cornerRadius = 20f
    var roundPosition: PointF = PointF(0f, 0f)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 设置圆角矩形的坐标大小
        rect.set(roundPosition.x, roundPosition.y, width - roundSize, height - roundSize)
        path.reset()
        // 绘制圆角矩形 顺时针
        path.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)
        canvas.drawPath(path, paint)
    }
}