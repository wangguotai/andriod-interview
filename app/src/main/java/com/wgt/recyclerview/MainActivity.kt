package com.wgt.recyclerview

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.get
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.myapplication.R
import com.wgt.recyclerview.adapter.MyItemDecoration
import com.wgt.recyclerview.adapter.SimpleAdapter
import com.wgt.recyclerview.util.ActivityHelper

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main2)
        (window.decorView.findViewById<FrameLayout>(android.R.id.content)[0] as ViewGroup).apply {
            removeAllViews()
            addView(RecyclerView(this@MainActivity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                layoutManager = LinearLayoutManager(this@MainActivity).apply {
                    // 关闭预取
                    isItemPrefetchEnabled = false
                }
                val list = ActivityHelper.getData(10) {
                    "Item $it"
                }
                adapter = SimpleAdapter(list)
                itemAnimator = null
                addItemDecoration(MyItemDecoration(this@MainActivity))
            })
        }
    }
}