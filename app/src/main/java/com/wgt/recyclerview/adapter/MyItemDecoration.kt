package com.wgt.recyclerview.adapter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.FontMetrics
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ItemDecoration
import com.wgt.recyclerview.util.ActivityHelper
import kotlin.properties.Delegates

class MyItemDecoration(context: Context) : ItemDecoration() {

    private var groupHeaderHeight by Delegates.notNull<Int>()
    private val headPaint by lazy { Paint() }
    private val textPaint by lazy { Paint() }
    private val textRect by lazy { Rect() }
    private val textFontMetrics = FontMetrics()

    init {
        groupHeaderHeight = ActivityHelper.dpToPx(100f).toInt()
        headPaint.color = Color.RED
        textPaint.textSize = 50f
        textPaint.color = Color.GREEN
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDrawOver(c, parent, state)
        if (parent.adapter is SimpleAdapter) {
            val adapter: SimpleAdapter = parent.adapter as SimpleAdapter
            val firstVisiblePos =
                (parent.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            // 第一个可见位置的view
            val view = parent.findViewHolderForLayoutPosition(firstVisiblePos)!!.itemView

            val left = parent.paddingLeft.toFloat()
            val right = parent.width - parent.paddingRight.toFloat()
            val top = parent.paddingTop.toFloat()
            var bottom = top + groupHeaderHeight.toFloat()

            val groupText = "group_${firstVisiblePos / 5}"
            val textWidth = textPaint.measureText(groupText)

            val textLeft = (left + right - textWidth) / 2
            // 获取该字体的 fontMetrics
            textPaint.getFontMetrics(textFontMetrics)

            val textBaseLine =
                top + groupHeaderHeight / 2 - (textFontMetrics.descent + textFontMetrics.ascent) / 2
            if (adapter.isGroupHeader(firstVisiblePos + 1)) {
                bottom = Math.min(groupHeaderHeight, view.bottom - parent.paddingTop).toFloat()
                c.drawRect(left, top, right, top + bottom, headPaint)
                c.drawText(
                    groupText,
                    textLeft,
                    textBaseLine - groupHeaderHeight / 2 + bottom / 2,
                    textPaint
                )
            } else {
                c.drawRect(left, top, right, bottom, headPaint)
                c.drawText(groupText, textLeft, textBaseLine, textPaint)
            }
        }
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDraw(c, parent, state)
        // 2. 绘制头部的view  onDraw不会遮挡 item， onDrawOver会遮挡，这是由 canvas绘制的顺序决定的 一个在前一个在最后
        if (parent.adapter is SimpleAdapter) {
            val childCount = parent.childCount
            val left = parent.paddingLeft.toFloat()
            val right = (parent.width - parent.paddingRight).toFloat()
            for (i in 0 until childCount) {
                val view = parent.getChildAt(i)
                val position = parent.getChildLayoutPosition(view)
                val isGroupHeader = (parent.adapter as SimpleAdapter).isGroupHeader(position)
                if (view.top - groupHeaderHeight >= parent.paddingTop) {
                    if (isGroupHeader) {
                        c.drawRect(
                            left,
                            view.top - groupHeaderHeight.toFloat(),
                            right,
                            view.top.toFloat(),
                            headPaint
                        )
                        val groupName = "Group_${position / 5}"
                        textPaint.getTextBounds(groupName, 0, groupName.length, textRect)
//                    textPaint.textAlign = Paint.Align.CENTER
                        val textWidth = textPaint.measureText(groupName)
                        c.drawText(
                            groupName,
                            (right + left - textWidth) / 2,
                            view.top - groupHeaderHeight / 2 + textRect.height() / 2f, textPaint
                        )
                    } else {
                        // 分割线
                        c.drawRect(left, view.top - 2f, right, view.top.toFloat(), headPaint)
                    }
                }
            }
        }
    }

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        super.getItemOffsets(outRect, view, parent, state)

        if (parent.adapter is SimpleAdapter) {

            // 1. 怎么判断是头部
            val position = parent.getChildLayoutPosition(view)
            if ((parent.adapter as SimpleAdapter).isGroupHeader(position)) {
                // 如果是头部，预留更大的地方
                outRect.set(0, groupHeaderHeight, 0, 0)
            } else {
                outRect.set(0, 2, 0, 0)
            }
        }

    }
}