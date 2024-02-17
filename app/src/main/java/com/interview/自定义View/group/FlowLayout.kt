package com.interview.自定义View.group

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup

/**
 * Time: 2024/2/17
 * Author: wgt
 * Description:
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : ViewGroup(context, attrs, defStyleAttr) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 先度量孩子
        val childCount = childCount
        for (i in 0 until childCount) {
            getChildAt(i).measure(widthMeasureSpec, heightMeasureSpec)
        }
        var width = 0
        var height = 0
        // 在度量自己，保存
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        TODO("Not yet implemented")
    }
}