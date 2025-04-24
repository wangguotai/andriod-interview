package com.interview.liveData.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.interview.liveData.LiveDataViewModel
import com.example.myapplication.R

class LiveDataDemoActivity: AppCompatActivity() {
    private lateinit var viewModel: LiveDataViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_live_data_demo)
        viewModel = ViewModelProvider(this).get(LiveDataViewModel::class.java)

        // 观察LiveData变化
        viewModel.counter.observe(this) { count->
            Toast.makeText(this, "$count", Toast.LENGTH_LONG).show()
            findViewById<TextView>(R.id.live_data_demo_count).text = count.toString()
        }
        findViewById<TextView>(R.id.live_data_demo_count).setOnClickListener {
            viewModel.increment()
        }
    }
}