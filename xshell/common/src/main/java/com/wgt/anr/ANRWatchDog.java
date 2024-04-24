package com.wgt.anr;

import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;


public class ANRWatchDog extends Thread {

    private static final String TAG = "ANR";
    static ANRWatchDog sWatchdog;
    private int timeout = 5000;
    private boolean ignoreDebugger = true;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private ANRChecker anrChecker = new ANRChecker();
    private ANRListener anrListener;

    private ANRWatchDog() {
        super("ANR-WatchDog-Thread");
    }

    public static ANRWatchDog getInstance() {
        if (sWatchdog == null) {
            sWatchdog = new ANRWatchDog();
        }
        return sWatchdog;
    }

    public void addANRListener(ANRListener listener) {
        this.anrListener = listener;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND); // 设置为后台线程
        while (true) {
            while (!isInterrupted()) {
                synchronized (this) {
                    anrChecker.schedule();
                    long waitTime = timeout;
                    long start = SystemClock.uptimeMillis();
                    while (waitTime > 0) {
                        try {
                            wait(waitTime);
                        } catch (InterruptedException e) {
                            Log.w(TAG, e.toString());
                        }
                        waitTime = timeout - (SystemClock.uptimeMillis() - start);
                    }
                    if (!anrChecker.isBlocked()) {
                        continue;
                    }
                }
                if (!ignoreDebugger && Debug.isDebuggerConnected()) {
                    continue;
                }
                String stackTraceInfo = getStackTraceInfo();
                if (anrListener != null) {
                    anrListener.onAnrHappened(stackTraceInfo);
                }
            }
            anrListener = null;
        }
    }

    private String getStackTraceInfo() {
        StringBuilder stringBuilder = new StringBuilder();
        for (StackTraceElement stackTraceElement : Looper.getMainLooper().getThread().getStackTrace()) {
            stringBuilder
                    .append(stackTraceElement.toString())
                    .append("\r\n");
        }
        return stringBuilder.toString();
    }

    public interface ANRListener {
        void onAnrHappened(String stackTraceInfo);
    }

    private class ANRChecker implements Runnable {

        private boolean mCompleted;
        private long mStartTime;
        private long executeTime = SystemClock.uptimeMillis();

        @Override
        public void run() {
            synchronized (ANRWatchDog.this) {
                mCompleted = true;
                executeTime = SystemClock.uptimeMillis();
            }
        }

        void schedule() {
            mCompleted = false;
            mStartTime = SystemClock.uptimeMillis();
            mainHandler.postAtFrontOfQueue(this);
        }

        boolean isBlocked() {
            return !mCompleted || executeTime - mStartTime >= 5000;
        }
    }
}
