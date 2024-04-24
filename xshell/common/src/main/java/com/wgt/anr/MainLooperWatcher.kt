package com.wgt.anr

import android.os.Debug
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Printer
import android.util.StringBuilderPrinter

class MainLooperWatcher : Printer {
    private val TAG = "MainLooperWatcher"
    private var mLastMills: Long = 0
    private var mLastMillis: Long = 0
    private var mLastSeconds: Long = 0
    private var mTimesPerSeconds: Long = 0

    fun mainThreaderWatcher() {
        Looper.getMainLooper().setMessageLogging(this)
    }

    override fun println(msg: String?) {
        if (msg.isNullOrEmpty() || msg.contains("Choreographer")
            || msg.contains("ActivityThread\$H")
        ) {
            return
        }

        if (msg.startsWith(">>>>>> Dispatching")) {
            mLastMillis = SystemClock.elapsedRealtime()
            mTimesPerSeconds++
            if (mTimesPerSeconds > 100) {
                report(msg, 0, mTimesPerSeconds)
            }
            if (mLastMillis - mLastSeconds > 1000) {
                mLastSeconds = mLastMillis
                mTimesPerSeconds = 0
            }
        } else {// <<<<< Finished
            val now = SystemClock.elapsedRealtime()
            val usedMillis = now - mLastMillis
            if (usedMillis < 16L) return

            if (usedMillis > 48) {
                Log.w(TAG, "$usedMillis ms used for $msg, $mLastSeconds")
            } else {
                Log.i(TAG, "$usedMillis ms used for $msg, $mLastSeconds")
            }

            if (usedMillis > 500L) {
                report(msg, usedMillis, mTimesPerSeconds)
                mTimesPerSeconds = 0
            }
        }
    }

    private fun report(msg: String, usedMillis: Long, callsInSecond: Long) {
        val sb = StringBuilder()
        Looper.getMainLooper().dump(StringBuilderPrinter(sb), "")
        Log.e(TAG, "$usedMillis ms used for $msg, $mLastSeconds, $sb")

        val exp =
            "[Block Runnable] ${usedMillis}ms used for ${msg}, $callsInSecond calls in one seconds"
        Log.e(TAG, exp)
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return

//        if (BuildConfig.DEBUG && usedMillis > 4000) {
//            throw RuntimeException(exp)
//        } else {
        // TOAST
    }
}
