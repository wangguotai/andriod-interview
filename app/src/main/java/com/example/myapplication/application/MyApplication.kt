package com.example.myapplication.application

import android.app.Application
import android.content.Context
import com.example.myapplication.hotfix.HotFix
import com.interview.net.NetClient
import com.interview.内存.MemoryMonitor
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

        // 内存监控（JVM 堆 + Native + 图形内存）：
        //   ① 注册 ComponentCallbacks2 → 拿到**系统**对整机内存压力的判断（唯一权威口径）
        //   ② 起 2s 采样器（环形 256 点 ≈ 8.5 分钟窗口）
        //   ③ 事件走 StabilityReporter（与 ANR/Crash **同一条出口**，不新建管道）
        // ⚠️ 刻意**不在这里**装两样东西（会改变被测系统的行为，必须显式开启）：
        //   · native 分配归因探针（memtrace/bytehook）—— 挂在全局 malloc 热路径上
        //   · 堆直方图 dumpHprofData —— stop-the-world 0.5~2s
        //   两者的入口都在「内存监控 Lab」页，且"开着"这件事在总览里可见。
        MemoryMonitor.install(this)

        // 上报驱动（周期 flush + 进后台 flush）：
        // ⚠️ 库侧（StabilityMonitor.ReportDriver）**默认关**，这是刻意的纪律
        //    （自动周期上报会真实消耗唤醒 + IO，与 SIGQUIT 通道、native 归因探针同一判断）。
        //    但**本 App 显式打开它** —— 否则采出来的数据永远到不了出口，
        //    整个链路是断的（这正是之前审计出的缺口：全仓没有任何东西驱动 flush）。
        //    intervalMs 用 30s 的生产默认值；实验页可以临时改小观察。
        // ⚠️ 出口是**模拟提交**（不联网，只在 App 内可查）—— 见 ReportDriver 注释。
        StabilityMonitor.ReportDriver.start()

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