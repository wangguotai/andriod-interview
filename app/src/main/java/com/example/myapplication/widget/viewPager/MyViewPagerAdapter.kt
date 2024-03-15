package com.example.myapplication.widget.viewPager

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.viewpager.widget.PagerAdapter
import com.example.myapplication.R

class MyViewPagerAdapter(val mItems: List<Int>, val mContext: Context) : PagerAdapter() {

    override fun getCount(): Int {
        return mItems.size
    }

    override fun isViewFromObject(view: View, `object`: Any): Boolean {
        return `object` == view
    }

    override fun instantiateItem(container: ViewGroup, position: Int): Any {
        val realPos = position % mItems.size
//        val view = LayoutInflater.from(mContext).inflate(R.layout.linear_item, null)
        val view = LayoutInflater.from(mContext).inflate(R.layout.linear_item, container, false)
        view.findViewById<TextView>(R.id.tv).run {
            val provideText = "$position - data ${mItems[realPos]}"
            text = provideText
        }
        container.addView(view)
        return view
    }

    override fun destroyItem(container: ViewGroup, position: Int, `object`: Any) {
        container.removeView(`object` as View)
    }
}