package com.wgt.modularity

import android.app.Application
import android.content.Context
import com.wgt.anr.MainLooperWatcher

class XshellApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        MainLooperWatcher().mainThreaderWatcher()
    }

    override fun onCreate() {
        super.onCreate()
    }
}