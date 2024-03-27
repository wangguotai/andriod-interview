package com.wgt.draw_text

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import com.wgt.draw_text.widget.SimpleColorChangeTextView

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<SimpleColorChangeTextView>(R.id.color_change_textview).apply {
            postDelayed(
                {
                    onStartLeft(this@apply, "mPercent")

                }, 1000
            )
//            repeat(100){
//                postDelayed({
//                    this.mPercent +=0.01f
//                },100)
//            }
        }
        // 通过属性动画改变
    }

    fun onStartLeft(view: View, propertyName: String) {
        ObjectAnimator.ofFloat(view, propertyName, 0f, 0.5f, 1f).setDuration(2500).start()
    }
}