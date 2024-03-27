package com.mi.scroll_event_demo.touchEvent.conflict

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.viewpager.widget.ViewPager
import kotlin.math.abs
import kotlin.properties.Delegates

class MyViewPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewPager(context, attrs) {
    private var mLastX by Delegates.notNull<Float>()
    private var mLastY by Delegates.notNull<Float>()
//    /**
//     * On intercept touch event 内部拦截法
//     *
//     * @param ev
//     * @return
//     */
//    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
////        return super.onInterceptTouchEvent(ev)
//        if(ev.action == MotionEvent.ACTION_DOWN){
//            super.onInterceptTouchEvent(ev)
//            return false
//        }
//        return true
//    }
//
    /**
     * On intercept touch event 外部拦截法 由parent决定
     *
     * @param ev
     * @return
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                mLastX = x
                mLastY = y
            }

            MotionEvent.ACTION_MOVE -> {
                if (abs(x - mLastX) > abs(y - mLastY)) {
                    return true
                }
            }
        }

        return super.onInterceptTouchEvent(ev)
    }
}