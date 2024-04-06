package com.mi.slide_card

import android.os.Bundle
import android.widget.ImageView
import androidx.recyclerview.widget.ItemTouchHelper
import com.bumptech.glide.Glide
import com.mi.common.activity.BaseActivity
import com.mi.scroll_event_demo.R
import com.mi.scroll_event_demo.databinding.ActivityMainBinding
import com.mi.slide_card.adapter.UniversalAdapter
import com.mi.slide_card.adapter.ViewHolder
import com.mi.slide_card.dao.SlideCardBean
import com.mi.slide_card.widget.SlideCallback


class MainActivity : BaseActivity<ActivityMainBinding>() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mData = SlideCardBean.initDatas()
        val mAdapter = object : UniversalAdapter<SlideCardBean>(
            this@MainActivity,
            mData,
            R.layout.item_swipe_card
        ) {
            override fun convert(viewHolder: ViewHolder, slideCardBean: SlideCardBean) {
                viewHolder.setText(R.id.tvName, slideCardBean.name)
                viewHolder.setText(
                    R.id.tvPrecent,
                    "${slideCardBean.postition} \\ ${mData.size}"
                )
                Glide.with(this@MainActivity)
                    .load(slideCardBean.url)
                    .into(viewHolder.getView(R.id.iv) as ImageView)
            }

        }
        binding.rv.apply {
            layoutManager = SlideCardLayoutManager()
            adapter = mAdapter
        }
        val slideCallback = SlideCallback(binding.rv, mAdapter, mData)
        val itemTouchHelper = ItemTouchHelper(slideCallback)
        itemTouchHelper.attachToRecyclerView(binding.rv)

    }

    override fun getLayoutId() = R.layout.activity_main
}