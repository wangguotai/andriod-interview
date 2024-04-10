package com.mi.login

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.mi.mrouter_api.manager.RouterManager

class LoginMainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_login)
    }

    fun jumpOrder(view: View) {
        RouterManager.build("/order/OrderMainActivity")
            .withInt("age", 3)
            .navigation(this)
    }
}