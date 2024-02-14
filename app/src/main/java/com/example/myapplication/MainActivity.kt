package com.example.myapplication

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannedString
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.example.myapplication.hotfix.patch.Demo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_main)
        findViewById<TextView>(R.id.textView).apply {
            text = SpannableString("SpannedString").apply {
                setSpan(ForegroundColorSpan(Color.GREEN), 0, 5, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        println(classLoader.toString())
        Demo.test()
    }
}
