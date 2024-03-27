package com.mi.scroll_event_demo.touchEvent.conflict

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.ListView
import kotlin.math.abs
import kotlin.properties.Delegates

class MyListView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.listViewStyle,
    defStyleRes: Int = 0
) : ListView(context, attrs, defStyleAttr, defStyleRes) {
    private var mLastX by Delegates.notNull<Float>()
    private var mLastY by Delegates.notNull<Float>()

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = x - mLastX
                val deltaY = y - mLastY
                if (abs(deltaX) > abs(deltaY)) {
                    parent.requestDisallowInterceptTouchEvent(false)
                }
            }
        }
        mLastX = x
        mLastY = y
        return super.dispatchTouchEvent(event)
    }
}
