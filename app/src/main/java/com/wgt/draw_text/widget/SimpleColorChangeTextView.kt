package com.wgt.draw_text.widget

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class SimpleColorChangeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {
    private var mText: String = "绘制文本"
//    val mPaint = Paint()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 绘制文字    使用默认的 mTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        canvas.drawText(mText, 0f, 0f, paint)
    }
}