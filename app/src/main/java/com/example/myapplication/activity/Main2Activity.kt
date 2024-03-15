package com.example.myapplication.activity

import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager.widget.ViewPager
import androidx.viewpager.widget.ViewPager.OnPageChangeListener
import com.example.myapplication.R
import com.example.myapplication.widget.viewPager.MyViewPagerAdapter
import com.example.myapplication.widget.viewPager.PageTransform
import com.interview.自定义View.group.FlowLayout
import java.lang.ref.WeakReference
import java.util.Timer
import java.util.TimerTask


class Main2Activity : AppCompatActivity() {
    class InnerHandler(private val activity: WeakReference<Main2Activity>) : Handler() {
        override fun handleMessage(msg: Message) {
            super.handleMessage(msg)
            when (msg.what) {
                1 -> {
                    activity.get()?.run {
                        index = (index + 1) % items.size
                        viewPager!!.currentItem = index
                    }
                }
            }
        }
    }

    private val handler = InnerHandler(WeakReference(this))
    private var viewPager: ViewPager? = null
    private var radioGroup: RadioGroup? = null
    private val items: MutableList<Int> by lazy {
        mutableListOf()
    }

    private var index = 0
    private var preIndex = 0
    private var timer = Timer()
    private var isContinue = true
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_2)

        viewPager = findViewById(R.id.view_pager)
        radioGroup = findViewById(R.id.radio_group)
        repeat(4) {
            items.add(it)
        }
        viewPager?.run {
            pageMargin = FlowLayout.dp2px(30)
            offscreenPageLimit = 3
            adapter = MyViewPagerAdapter(items, this@Main2Activity)
            addOnPageChangeListener(object : OnPageChangeListener {
                override fun onPageScrolled(
                    position: Int,
                    positionOffset: Float,
                    positionOffsetPixels: Int
                ) {
                }

                override fun onPageSelected(position: Int) {
                    index = position
                    setCurrentDot(index % items.size)
                }

                override fun onPageScrollStateChanged(state: Int) {
                }

            })
            setPageTransformer(true, PageTransform())
        }
        initRadioButton(items.size)
        timer.schedule(object : TimerTask() {
            override fun run() {
                if (isContinue) {
                    handler.sendEmptyMessage(1)
                }
            }

        }, 2000, 2000)
    }

    private fun setCurrentDot(i: Int) {
        if (radioGroup!!.getChildAt(i) != null) {
            //当前按钮不可改变
            radioGroup!!.getChildAt(i).isEnabled = false
        }
        if (radioGroup!!.getChildAt(preIndex) != null) {
            //上个按钮可以改变
            radioGroup!!.getChildAt(preIndex).isEnabled = true
            //当前位置变为上一个，继续下次轮播
            preIndex = i
        }
    }

    private fun initRadioButton(length: Int) {
        for (i in 0 until length) {
            val imageView = ImageView(this)
            imageView.setImageResource(R.drawable.rg_selector)
            imageView.setPadding(20, 0, 0, 0)
            radioGroup!!.addView(
                imageView, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            radioGroup!!.getChildAt(0).isEnabled = false
        }
    }

}