package com.wgt.recyclerview.adapter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.ItemDecoration
import com.wgt.recyclerview.util.ActivityHelper
import kotlin.properties.Delegates

class MyItemDecoration(context: Context) : ItemDecoration() {

    private var groupHeaderHeight by Delegates.notNull<Int>()

    init {
        groupHeaderHeight = ActivityHelper.dpToPx(100f).toInt()
    }

    override fun onDrawOver(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDrawOver(c, parent, state)
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        super.onDraw(c, parent, state)
        // 2. 绘制头部的view  onDraw不会遮挡 item， onDrawOver会遮挡，这是由 canvas绘制的顺序决定的 一个在前一个在最后
//        if (parent.adapter is SimpleAdapter) {
//            val childCount = parent.childCount
//            for (i in 0 until childCount)
//        }
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