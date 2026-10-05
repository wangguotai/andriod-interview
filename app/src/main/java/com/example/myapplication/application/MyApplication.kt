package com.example.myapplication.application

import android.app.Application
import android.content.Context
import com.example.myapplication.hotfix.HotFix
import com.interview.net.NetClient
//import org.koin.core.context.startKoin
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException


class MyApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
//        // 执行热修复。插入补丁dex （前提条件类没有被加载和使用过）
//        val filesDir = filesDir // 应用的私有存储空间
//        val cachesDir = cacheDir // 应用的私有缓存空间
//        val externalFilesDir = getExternalFilesDir(null) // 应用的外部存储空间
//        println("$filesDir, $cachesDir, $externalFilesDir")



//        HotFix.installPatch(this, File("${getExternalFilesDir(null)!!.absolutePath}/patch.dex"))
    }

    override fun onCreate() {
        super.onCreate()
//        startKoin {  }

        // 网络质量感知必须在这里启动，而不是懒加载：
        // 网络切换回调要在**第一次请求之前**就开始收集，否则冷启动后首个请求
        // 读到的永远是初始值（NONE），自适应超时等策略会以错误的前提做决策。
        // 本调用只注册回调 + 读一次当前状态，不发起任何网络 IO、不阻塞启动。
        NetClient.init(this)
    }

}