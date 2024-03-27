package com.wgt.draw_text.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.FontMetrics
import android.graphics.Rect
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class SimpleColorChangeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private var mText: String = "绘制文本"
    private val mPaint = Paint()
    private val reusePaint by lazy {
        Paint()
    }

    // 文本颜色渐变进度
    var mPercent = 0.0f
        get() = field
        set(value) {
            if (value != mPercent) {
                field = value
                invalidate()  // 重绘
            }
        }

    /**
     * top: 在给定字体大小下，baseline到字体的最高字形的距离
     * ascent: 单倍行距文本在基线上方的推荐距离。
     * descent: 单倍行距文本在基线下方的推荐距离。
     * bottom: 在给定字体大小下，baseline到字体的最低字形下方的距离
     *
     */
    private lateinit var mFontMetrics: FontMetrics
    private var baseLine = 100f
    private var mClipRectL: Rect = Rect(0, 0, 0, 0)
    private var mClipRectR: Rect = Rect(0, 0, 0, 0)


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save()
        mPaint.color = Color.BLACK
        drawCenterLineXY(canvas)
        drawCenterText(canvas)
        canvas.restore()
        // 二、文本颜色的渐变
        // 1. Canvas的概念
        // 上面的一层作为底层，下面绘制上层
        canvas.save()
        val textWidth = mPaint.measureText(mText)
        val left = (width / 2 - textWidth / 2)
        val right = (left + textWidth * mPercent).toInt()
        mPaint.color = Color.RED
        mClipRectL.set(left.toInt(), 0, right, height)
        canvas.clipRect(mClipRectL)
        canvas.drawText(mText, left, baseLine, mPaint)
        canvas.restore()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun drawCenterText(canvas: Canvas) {
        mPaint.textSize = 80f
        var xStart = width / 2f
//        val yStart = height / 2f
        //       一、 文本的宽高及绘制
        // 绘制文字    使用默认的 mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        //        canvas.drawText(mText, 0f, baseLine, mPaint)
        // 设置文字对齐
        //        mPaint.textAlign = Paint.Align.CENTER
        //        canvas.drawText(mText, xStart, baseLine, mPaint)
        // RIGHT
        //        mPaint.textAlign = Paint.Align.RIGHT
        //        canvas.drawText(mText, xStart, baseLine + mPaint.fontSpacing, mPaint)
        // 2. 绘制到屏幕中心
        // 文字高度的计算
        canvas.save()
        mFontMetrics = mPaint.fontMetrics
        // 想要的是文本居中，但位置是根据baseline确定的，因此需要下移 （descent-ascent)/2-descent的距离
        //        baseLine = height/2f + (fontMetrics.descent - fontMetrics.ascent)/2 - fontMetrics.descent
        baseLine = height / 2f - (mFontMetrics.ascent + mFontMetrics.descent) / 2
        mPaint.textAlign = Paint.Align.LEFT
        //        x轴居中 2. 计算距离
        val textWidth = mPaint.measureText(mText)
        xStart = width / 2 - textWidth / 2
        val left = width / 2 - textWidth / 2 + mPercent * textWidth
        mClipRectR.set(left.toInt(), 0, width, height)
        canvas.drawText(mText, xStart, baseLine, mPaint)
        canvas.restore()
    }

    private fun drawCenterLineXY(canvas: Canvas) {
        val paint = reusePaint
        paint.style = Paint.Style.FILL
        paint.color = Color.RED
        paint.strokeWidth = 3f
        canvas.drawLine(width / 2f, 0f, width / 2f, height.toFloat(), paint)
        canvas.drawLine(0f, height / 2f, width.toFloat(), height / 2f, paint)
        paint.reset()
    }
}