package com.interview.自定义View.group

import android.content.Context
import android.util.AttributeSet
import android.view.View
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
    var mHorizontalSpacing = 100 // 稍后使用自定义属性提供赋值
    var mVerticalSpacing = 40
    val allLines by lazy {
        mutableListOf<MutableList<View>>()
    }
    val lineHeight by lazy {
        mutableListOf<Int>()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 先度量孩子
        val childCount = childCount

        // 父布局给的允许的宽高
        val selfWidth = MeasureSpec.getSize(widthMeasureSpec)
        val selfHeight = MeasureSpec.getSize(widthMeasureSpec)

        // 流式布局的宽高
        var parentNeededWidth = 0
        var parentNeededHeight = 0


        // 保存一行中的所有的view
        val lineView = mutableListOf<View>()
        // 记录这行已经使用了多宽的size
        var lineWidthUsed = 0
        var lineHeightUsed = 0
        // 便利children
        for (i in 0 until childCount) {
            val childView = getChildAt(i)
            // 获取一个layoutParams
            val layoutParams = childView.layoutParams
            // 将一个layoutParams转换成measureSpec
            val childWidthMeasureSpec =
                getChildMeasureSpec(
                    widthMeasureSpec,
                    paddingLeft + paddingRight,
                    layoutParams.width
                )
            val childHeightMeasureSpec = getChildMeasureSpec(
                heightMeasureSpec,
                paddingTop + paddingBottom,
                layoutParams.height
            )
            childView.measure(widthMeasureSpec, heightMeasureSpec)

            // 获取子view的度量宽高
            val childMeasuredWidth = childView.measuredWidth
            val childMeasuredHeight = childView.measuredHeight

            // 换行逻辑
            if(childMeasuredWidth + lineWidthUsed + mHorizontalSpacing > selfWidth) {
                // 当需要换行时将当前行的宽高进行记录
                parentNeededHeight += lineHeightUsed
                parentNeededWidth = maxOf(parentNeededWidth, lineWidthUsed)
                // 清理上一行的数据
                lineView.clear()
                lineWidthUsed = 0
                lineHeightUsed = 0
            }
            // view是分行layout的，所以要记录每一行有哪些view,所以要记录每一行有哪些view，这样可以方便layout布局
            lineView.add(childView)
            // 每行都会有自己的宽高
            lineWidthUsed += childMeasuredWidth + mHorizontalSpacing
            lineHeightUsed = maxOf(childMeasuredHeight + mHorizontalSpacing, lineHeightUsed)
        }
        // 在度量自己，保存
        // 根据子View的度量结果，来重新度量自己ViewGroup
        // 作为一个ViewGroup，它自己也是一个View，它的大小也需要根据它的父亲给他提供的宽高来度量
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)

        val realWidth = if (widthMode == MeasureSpec.EXACTLY) selfWidth else parentNeededWidth
        val realHeight = if (heightMode == MeasureSpec.EXACTLY) selfHeight else parentNeededHeight
        setMeasuredDimension(realWidth, realHeight)
    }

    // 布局
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val curL = paddingLeft
        val curT = paddingTop
        for (i in 0 until childCount) {
            val view = getChildAt(i)

        }
    }
}