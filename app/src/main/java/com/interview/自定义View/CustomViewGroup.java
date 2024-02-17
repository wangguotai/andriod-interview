package com.interview.自定义View;

import android.content.Context;
import android.util.AttributeSet;
import android.view.ViewGroup;

/**
 * Time: 2024/2/16
 * Author: wgt
 * Description:
 */
public class CustomViewGroup extends ViewGroup {
    public CustomViewGroup(Context context) {
        super(context);
    }

    public CustomViewGroup(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomViewGroup(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public CustomViewGroup(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
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
