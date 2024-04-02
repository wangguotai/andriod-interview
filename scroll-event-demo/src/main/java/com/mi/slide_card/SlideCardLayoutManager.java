package com.mi.slide_card;

import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import com.mi.slide_card.config.CardConfig;

public class SlideCardLayoutManager extends RecyclerView.LayoutManager {
    @Override
    public RecyclerView.LayoutParams generateDefaultLayoutParams() {
        return new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /**
     * 一个RecyclerView中 布局四个堆叠的卡片
     * 布局4个
     *
     * @param recycler Recycler to use for fetching potentially cached views for a
     *                 position
     * @param state    Transient state of RecyclerView
     */
    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
//        super.onLayoutChildren(recycler, state);
        // 1. 依照布局时的回收流程，调用
        detachAndScrapAttachedViews(recycler);

        // 放置在最底部的卡片
        int bottomPosition;
        int itemCount = getItemCount();
        if (itemCount < CardConfig.MAX_SHOW_COUNT) {
            bottomPosition = 0;
        } else {
            bottomPosition = getItemCount() - CardConfig.MAX_SHOW_COUNT;
        }
        // 2. 复用 ViewHolder
        for (int i = bottomPosition; i < itemCount; i++) {
            View view = recycler.getViewForPosition(i);
            addView(view);
            // 测量child
            measureChildWithMargins(view, 0, 0);
            int parentWidth = getWidth();
            int parentHeight = getHeight();
            // 布局child
            int horizontalSpan = (parentWidth - getDecoratedMeasuredWidth(view)) / 2;
            int verticalSpan = (parentHeight - getDecoratedMeasuredHeight(view)) / 2;
            layoutDecoratedWithMargins(view, horizontalSpan, verticalSpan, parentWidth - horizontalSpan, parentHeight - verticalSpan);

        }


    }
}

