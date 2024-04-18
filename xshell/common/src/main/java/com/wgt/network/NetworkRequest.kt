package com.wgt.network

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request

class NetworkRequest {
    companion object {
        private const val TAG = "NetworkRequest"
        private var instance: NetworkRequest? = NetworkRequest()
        fun getInstance(): NetworkRequest {
            return instance!!
        }
    }

    private val client by lazy { OkHttpClient() }
    fun get() {
        try {
            val request = Request.Builder()
                .url("https://v12.mi.com/in")
                .build()
            // 执行同步请求
            val call = client.newCall(request)
            val response = call.execute()

            // 获得响应
            val body = response.body
            println(body)
            Log.d(TAG, body.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }
}