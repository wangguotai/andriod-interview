package com.mi.slide_card.widget;

import android.graphics.Canvas;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.Adapter;

import java.util.List;

public class SlideCallback<A extends Adapter<?>, D> extends ItemTouchHelper.SimpleCallback {
    private RecyclerView mRv;
    private A mAdapter;

    private List<D> mData;

    /**
     * Creates a Callback for the given drag and swipe allowance. These values serve as
     * defaults
     * and if you want to customize behavior per ViewHolder, you can override
     * {@link #getSwipeDirs(RecyclerView, ViewHolder)}
     * and / or {@link #getDragDirs(RecyclerView, ViewHolder)}.
     */

    public SlideCallback(RecyclerView rv, A adapter, List<D> dataList) {
        super(0, 15);
        this.mRv = rv;
        this.mAdapter = adapter;
        this.mData = dataList;
    }

    // 处理拖拽
    @Override
    public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
        return false;
    }

    // 处理滑动
    @Override
    public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        D removedData = mData.remove(viewHolder.getLayoutPosition());
        mData.add(removedData);
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
        double maxDistance = recyclerView.getWidth() * 0.5f;
        double distance = Math.sqrt(dX * dX + dY * dY);
        double fraction = Math.min(distance / maxDistance, 1);

        // 显示的个数为 4个
        int itemCount = recyclerView.getChildCount();
        for (int i = 0; i < itemCount; i++) {
            View view = recyclerView.getChildAt(i);
            int level = itemCount - i - 1;

        }
    }
}
