package com.wgt.modularity

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.xshell.BuildConfig
import com.example.xshell.R
import com.mi.mrouter_api.manager.RouterManager
import com.mi.order.OrderMainActivity
import com.mi.router_annotation.MRouter
import com.wgt.network.NetworkRequest

@MRouter(path = "/xshell/MainActivity")
class MainActivity : AppCompatActivity() {
    companion object {
        val TAG = MainActivity::class.java.simpleName
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (BuildConfig.isRelease) {
            Log.d(TAG, "onCreate 当前是：集成化 线上环境，以app壳为主导运行的方式")
            Toast.makeText(
                this,
                "当前是：集成化 线上环境，以app壳为主导运行的方式",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Log.d(TAG, "onCreate: 当前是：组件化 测试环境，所有的子模块都可以独立运行")
            Toast.makeText(
                this,
                "当前是：组件化 测试环境，所有的子模块都可以独立运行",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // app ---> 登录
    fun jumpLogin(view: View?) {
        NetworkRequest.getInstance().get()
//        val intent = Intent(this, LoginMainActivity::class.java)
//        intent.putExtra("name", "Derry")
//        startActivity(intent)
    }

    // app ---> Order订单
    fun jumpOrder(view: View?) {
//        RouterManager.build("/order/OrderMainActivity")
//            .withInt("age", 8)
//            .navigation(this)
        val intent = Intent(this, OrderMainActivity::class.java)
        intent.putExtra("name", "Derry")
        startActivity(intent)
//        val startClass = `OrderMainActivity$$MRouter`.findTargetClass("/order/OrderMainActivity")
    }

    fun jumpLifecycleDemo(view: View) {
        RouterManager.build("/order/LifecycleDemoActivity")
            .navigation(this)
    }
}