package com.example.myapplication.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.myapplication.R

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"

    //    private val viewModel by viewModel<MainTabViewModel> {  parametersOf(TAG)}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.layout_main)




        println(classLoader.toString())
    }
}
