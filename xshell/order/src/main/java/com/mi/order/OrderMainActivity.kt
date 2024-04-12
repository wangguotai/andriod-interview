package com.mi.order

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.mi.common.router.RecordPathManager
import com.mi.mrouter_api.manager.ParameterManager
import com.mi.router_annotation.MRouter
import com.mi.router_annotation.Parameter

@MRouter(path = "/order/OrderMainActivity")
class OrderMainActivity : AppCompatActivity() {

    @JvmField
    @Parameter
    var age: Int = 0

    @JvmField
    @Parameter
    var name: String = "11"


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_order_main)
        Log.d(OrderMainActivity::class.java.simpleName, "$name, $age")
        // ARouter.inject的过程
        ParameterManager.getInstance().loadParameter(this)
        findViewById<Button>(R.id.btn_LifecycleActivity).setOnClickListener {
            startActivity(Intent(this@OrderMainActivity, LifecycleDemoActivity::class.java))
        }
    }


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