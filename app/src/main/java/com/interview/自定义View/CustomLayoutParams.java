package com.interview.自定义View;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.ViewGroup;

import com.example.myapplication.R;

/**
 * Time: 2024/2/16
 * Author: wgt
 * Description: 使用自定义属性构建自定义LayoutParams
 *  1. 在attrs中构建属性，并在xml中使用
 *  2. 继承MarginLayoutParams构造函数中读取
 *  3. 重写ViewGroup中需要的方法
 */
public class CustomLayoutParams extends ViewGroup.MarginLayoutParams {
    // 下面的自定义属性可用于onMeasure和onLayout阶段中解决特定需求
    public int simpleAttr;
    public int gravity;

    public CustomLayoutParams(Context c, AttributeSet attrs) {
        super(c, attrs);
        // 解析布局属性
        TypedArray typedArray = c.obtainStyledAttributes(attrs, R.styleable.CustomViewGroup_Layout);
        simpleAttr = typedArray.getInteger(R.styleable.CustomViewGroup_Layout_layout_simple_attr_1, 0);
        gravity = typedArray.getInteger(R.styleable.CustomViewGroup_Layout_android_layout_gravity, -1);
        typedArray.recycle(); // 释放资源
    }

    public CustomLayoutParams(int width, int height) {
        super(width, height);
    }

    public CustomLayoutParams(ViewGroup.MarginLayoutParams source) {
        super(source);
    }

    public CustomLayoutParams(ViewGroup.LayoutParams source) {
        super(source);
    }
}
