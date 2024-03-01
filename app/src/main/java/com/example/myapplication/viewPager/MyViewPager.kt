package com.example.myapplication.viewPager

import android.content.Context
import android.util.AttributeSet
import androidx.viewpager.widget.ViewPager

class MyViewPager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewPager(context, attrs) {
    val TAG = "MyViewPager"
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var maxHeight = 0
        val paddingHorizontal = paddingStart + paddingEnd
        val paddingVertical = paddingTop + paddingBottom
        for (index in 0 until childCount) {
            getChildAt(index).apply {
                val childMeasureWithSpec = getChildMeasureSpec(
                    widthMeasureSpec, paddingHorizontal, layoutParams.width
                )
                val childMeasureHeightSpec = getChildMeasureSpec(
                    heightMeasureSpec, paddingVertical, layoutParams.height
                )
                measure(childMeasureWithSpec, childMeasureHeightSpec)
                if (measuredHeight > maxHeight) {
                    maxHeight = measuredHeight
                }
            }
        }
        val newHeightMeasureSpec =
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.getMode(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, newHeightMeasureSpec)
    }
}