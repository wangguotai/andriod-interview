package com.example.myapplication.application

import android.app.Application
import android.content.Context
import com.example.myapplication.hotfix.HotFix
//import org.koin.core.context.startKoin
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException


class MyApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        // 执行热修复。插入补丁dex （前提条件类没有被加载和使用过）
        val filesDir = filesDir // 应用的私有存储空间
        val cachesDir = cacheDir // 应用的私有缓存空间
        val externalFilesDir = getExternalFilesDir(null) // 应用的外部存储空间
        println("$filesDir, $cachesDir, $externalFilesDir")



//        HotFix.installPatch(this, File("${getExternalFilesDir(null)!!.absolutePath}/patch.dex"))
    }

    override fun onCreate() {
        super.onCreate()
//        startKoin {  }
    }

}