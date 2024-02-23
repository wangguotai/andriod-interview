package com.interview.自定义View;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;

import com.example.myapplication.R;

/**
 * Time: 2024/2/16
 * Author: wgt
 * Description:
 */
public class CustomViewGroup extends ViewGroup {
    public CustomViewGroup(Context context) {
        this(context, null);
    }
    // xml反射会调用该构造函数
    public CustomViewGroup(Context context, AttributeSet attrs) {
        this(context, attrs, R.style.CustomViewGroupStyle_light);
    }

    public CustomViewGroup(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, R.style.CustomViewGroupStyle);
    }

    public CustomViewGroup(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes); // 用于自定义属性
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {

    }
    // 调整默认的layoutParams
    @Override
    protected LayoutParams generateDefaultLayoutParams() {
        return new CustomLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    }
}
