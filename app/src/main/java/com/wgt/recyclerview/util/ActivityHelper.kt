package com.wgt.recyclerview.util

import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue


object ActivityHelper {
    fun <T> getData(generator: (index: Int) -> T): List<T> {
        val list = ArrayList<T>(100)
        repeat(100) {
            list.add(generator(it))
        }
        return list
    }

    fun <T> getData(capacity: Int, generator: (index: Int) -> T): MutableList<T> =
        MutableList(capacity) { generator(it) }

    // Service for Adapter

    fun generatorItemDrawable(): Drawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            setStroke(4, Color.BLUE)
            cornerRadius = dpToPx(4f)
        }

    // Common Utils
    fun dpToPx(dp: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP,
        dp,
        Resources.getSystem().displayMetrics
    )
}