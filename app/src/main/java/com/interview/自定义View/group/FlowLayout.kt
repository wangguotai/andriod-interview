package com.interview.自定义View.group

import android.content.Context
import android.content.res.Resources
import android.util.AttributeSet
import android.util.TypedValue
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
    var mHorizontalSpacing = dp2px(16) // 稍后使用自定义属性提供赋值
    var mVerticalSpacing = dp2px(8)
    val allLines by lazy {
        mutableListOf<MutableList<View>>()
    }
    val lineHeights by lazy {
        mutableListOf<Int>()
    }

    /**
     * 1. 先度量孩子
     *     - 根据LayoutParams的测量模式和父布局measureSpc + padding，获取child的measureSpec
     *     - 将measureSpec传入child的measure方法，具体的度量在onMeasure中
     *     - 父布局通过child的测量结果，更新自身的宽高
     *
     *
     * @param widthMeasureSpec
     * @param heightMeasureSpec
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        clearMeasureParams();
        // 先度量孩子
        val childCount = childCount

        // 父布局给的允许的宽高
        val selfWidth = MeasureSpec.getSize(widthMeasureSpec)
        val selfHeight = MeasureSpec.getSize(widthMeasureSpec)

        // measure过程中，子View要求的父ViewGroup的宽
        var parentNeededWidth = 0
        // measure过程中，子View要求的父ViewGroup的高
        var parentNeededHeight = 0

        // 保存一行中的所有的view
        var lineView = mutableListOf<View>()
        // 记录这行已经使用了多宽的size
        var lineWidthUsed = 0
        // 记录一行中的行高
        var lineHeight = 0
        // 遍历children
        for (i in 0 until childCount) {
            val childView = getChildAt(i)
            // 获取一个layoutParams
            val layoutParams = childView.layoutParams
            if (childView.visibility != GONE) {
                // 将一个layoutParams转换成measureSpec
                val childWidthMeasureSpec = getChildMeasureSpec(
                    widthMeasureSpec,
                    paddingLeft + paddingRight,
                    layoutParams.width
                )
                val childHeightMeasureSpec = getChildMeasureSpec(
                    heightMeasureSpec,
                    paddingTop + paddingBottom,
                    layoutParams.height
                )
                childView.measure(childWidthMeasureSpec, childHeightMeasureSpec)
                // 获取子view的度量宽高
                val childMeasuredWidth = childView.measuredWidth
                val childMeasuredHeight = childView.measuredHeight

                // 换行逻辑
                if (childMeasuredWidth + lineWidthUsed + mHorizontalSpacing > selfWidth) {
                    // 当需要换行时将当前行的宽高进行记录
                    parentNeededHeight += lineHeight + mVerticalSpacing
                    parentNeededWidth = maxOf(parentNeededWidth, lineWidthUsed)
                    // 记录当前的行内容及行高，
                    allLines.add(lineView)
                    lineHeights.add(lineHeight)
                    // 清理上一行的数据
                    lineView = mutableListOf()
                    lineWidthUsed = 0
                    lineHeight = 0
                }
                // view是分行layout的，所以要记录每一行有哪些view,所以要记录每一行有哪些view，这样可以方便layout布局
                lineView.add(childView)
                // 每行都会有自己的宽高
                lineWidthUsed += childMeasuredWidth + mHorizontalSpacing
                lineHeight = maxOf(childMeasuredHeight, lineHeight)
                // 处理最后一行
                if (i == childCount - 1) {
                    allLines.add(lineView)
                    lineHeights.add(lineHeight)
                    parentNeededHeight += lineHeight + mVerticalSpacing
                    parentNeededWidth = maxOf(parentNeededWidth, lineWidthUsed + mHorizontalSpacing)
                }
            }
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

    private fun clearMeasureParams() {
        lineHeights.clear();
        allLines.clear();
    }

    // 布局
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val paddingLeftValue = paddingLeft
        val paddingTopValue = paddingTop
        var curL = paddingLeftValue
        var curT = paddingTopValue
        var lineIndex = 0
        for (lineViews in allLines) {
            for (view in lineViews) {
                // 布局每一个子View的位置
                val left = curL
                val top = curT
                val right = view.measuredWidth + left
                val bottom = view.measuredHeight + top;
                view.layout(left, top, right, bottom)
                curL = right + mHorizontalSpacing
            }
            curT += lineHeights[lineIndex++] + mVerticalSpacing
            curL = paddingLeftValue
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
    }

    companion object {
        fun dp2px(dp: Int): Int {
            return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                dp.toFloat(),
                Resources.getSystem().displayMetrics
            ).toInt()
        }
    }
}