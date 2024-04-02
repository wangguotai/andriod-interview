package com.mi.slide_card

import android.os.Bundle
import com.mi.common.activity.BaseActivity
import com.mi.scroll_event_demo.R
import com.mi.scroll_event_demo.databinding.ActivityMainBinding

class MainActivity : BaseActivity<ActivityMainBinding>() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun getLayoutId() = R.layout.activity_main
}