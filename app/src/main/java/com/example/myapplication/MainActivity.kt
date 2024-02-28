package com.example.myapplication

import android.os.Bundle
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    private val TAG = "MainActivity"
//    private val viewModel by viewModel<MainTabViewModel> {  parametersOf(TAG)}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.layout_main)




        println(classLoader.toString())
    }
}
