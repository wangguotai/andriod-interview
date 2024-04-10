package com.mi.mrouter_api.manager

import android.content.Context
import android.os.Bundle

/**
 * 跳转时 ，用于参数的传递
 */
class BundleManager {
    // Intent传输  携带的值，保存到这里
    var bundle = Bundle()
        private set

    // 对外界提供，可以携带参数的方法
    fun withString(key: String, value: String?): BundleManager {
        bundle.putString(key, value)
        return this // 链式调用效果 模仿开源框架
    }

    fun withBoolean(key: String, value: Boolean): BundleManager {
        bundle.putBoolean(key, value)
        return this
    }

    fun withInt(key: String, value: Int): BundleManager {
        bundle.putInt(key, value)
        return this
    }

    // 根据单一原则，此处只完成参数管理，应当委托给路由管理器
    fun navigation(context: Context): Any? {
        return RouterManager.navigation(context, this)
    }
}
