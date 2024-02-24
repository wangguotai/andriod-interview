package com.example.myapplication

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannedString
import android.text.style.ForegroundColorSpan
import android.widget.TextView
import androidx.activity.ComponentActivity
import org.koin.core.parameter.parametersOf
import org.koin.android.viewmodel.ext.android.viewModel
import com.interview.自定义View.CustomViewGroup

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
    private val viewModel by viewModel<MainTabViewModel> {  parametersOf(TAG)}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.layout_main)
        findViewById<TextView>(R.id.textView).apply {
            text = SpannableString("SpannedString").apply {
                setSpan(
                    ForegroundColorSpan(Color.GREEN),
                    0,
                    5,
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }

        findViewById<CustomViewGroup>(R.id.main_custom_view_id).apply {

            setTheme(R.style.CustomViewGroupStyle)
        }



        println(classLoader.toString())
    }
}
