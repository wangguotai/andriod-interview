package com.mi.order

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.mi.common.router.RecordPathManager

class OrderMainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_main)
    }

    fun jumpApp(view: View) {}
    fun jumpLogin(view: View) {
        // 实现组件间的通信
        // 方法1. 类加载
//        val targetClass = Class.forName("com.mi.login.LoginMainActivity")
//        val intent = Intent(this, targetClass)
//        startActivity(intent)
        // 方法2. 全局Map
        RecordPathManager.getTargetActivity("login", "LoginMainActivity")?.let {
            startActivity(Intent(this, it))
        }
    }
}