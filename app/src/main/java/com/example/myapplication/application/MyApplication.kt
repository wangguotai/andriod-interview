package com.example.myapplication.application

import android.app.Application
import android.content.Context
import com.example.myapplication.hotfix.HotFix
import com.interview.net.NetClient
import com.interview.稳定性监控.StabilityMonitor
//import org.koin.core.context.startKoin
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException


class MyApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        // 稳定性监控的**第一步必须在这里**：Application 自身的构造/attach 也可能崩
        // （ExitInfo 里的 REASON_INITIALIZATION_FAILURE），晚装一秒就漏一秒。
        // 此时不能碰任何依赖尚未初始化单例的资源 —— 本调用只用 filesDir。
        StabilityMonitor.installEarly(this)

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

        // 稳定性监控（顺序有讲究，见 StabilityMonitor 注释）：
        //   ① 会话标记（先读上次状态，再标 running）
        //   ② 补报上批崩溃遗嘱（同步，只读一个小文件）
        //   ③ 起 ANR 探针（哨兵 + Looper 旁听）
        //   ④ 起主线程采样器
        //   ⑤ ExitInfo 回捞 —— 丢到后台泳道，**绝不阻塞启动**
        //      （getHistoricalProcessExitReasons + 读 trace 可能上百毫秒，
        //        放在 onCreate 里等于直接吃冷启动的 ANR 预算）
        StabilityMonitor.installOnCreate(this)

        // 网络质量感知必须在这里启动，而不是懒加载：
        // 网络切换回调要在**第一次请求之前**就开始收集，否则冷启动后首个请求
        // 读到的永远是初始值（NONE），自适应超时等策略会以错误的前提做决策。
        // 本调用只注册回调 + 读一次当前状态，不发起任何网络 IO、不阻塞启动。
        NetClient.init(this)
    }

    /**
     * ⚠️ 诚实说明：`onTerminate` **只在模拟器/极端情况下被调用**，
     * 真机上应用退出没有可靠的钩子。所以「上次不干净」这个信号偏高（误报多），
     * 只能看趋势，不能拿单次结论定位问题（详见 CrashJournal.endSession）。
     */
    override fun onTerminate() {
        StabilityMonitor.onExit(this)
        super.onTerminate()
    }

}