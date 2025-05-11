package com.wgt.module

import android.content.Context

interface ModuleInitializer {
    fun init(appContext: Context)
}