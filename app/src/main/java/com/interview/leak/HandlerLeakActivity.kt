package com.interview.leak

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.myapplication.R
import java.lang.ref.WeakReference

/**
 * Time: 2024/2/15
 * Author: wgt
 * Description: 基于 Handler 的非静态内部类内存泄漏场景
 *
 * 【泄漏原理】
 * 非静态内部类（含匿名内部类、Kotlin 的 inner class / 匿名 object）会隐式持有外部类实例的引用。
 * 下面 `mLeakyHandler` 是非静态内部类，它隐式持有 HandlerLeakActivity.this。
 * 而 Message 在 MessageQueue 中排队时，`msg.target` 指回这个 Handler。
 * 引用链：主线程 Looper -> MessageQueue -> Message -> Handler(内部类) -> Activity
 * 于是 Activity 被销毁（onDestroy）后，只要延时消息还没到期，Activity 就无法被 GC 回收 —— 内存泄漏。
 *
 * 【本 Demo 如何证明泄漏】
 * Activity 在 onDestroy 时把自身包成 WeakReference 交给一个后台检测线程，延时后触发 GC 并查看弱引用是否被清除。
 * 弱引用未被清除 => 对象仍被强引用着 => 泄漏。日志关键字：`[LEAK]`。
 *
 * 【对照实验】
 * mFixedHandler 是静态内部类 + 弱引用持有 Activity 的正确写法，它对应的延时消息不会导致泄漏。
 * 点击按钮可分别投递两类消息，比对 logcat 结果。
 */
class HandlerLeakActivity : AppCompatActivity() {

    private lateinit var mInfoText: TextView

    /**
     * 错误写法：非静态内部类。
     * 隐式持有 HandlerLeakActivity.this，随延时 Message 一起滞留在主线程 MessageQueue 中。
     */
    @SuppressLint("HandlerLeak")
    private val mLeakyHandler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            Log.d(TAG, "[LEAK] 泄漏 Handler 收到消息 what=${msg.what}（此时 Activity 可能已销毁）")
            mInfoText.text = "leaky handler 收到消息"
        }
    }

    /** 正确写法：静态内部类 + 弱引用，不持有外部 Activity。 */
    private class FixedHandler(activity: HandlerLeakActivity) : Handler(Looper.getMainLooper()) {
        private val mActivityRef = WeakReference(activity)

        override fun handleMessage(msg: Message) {
            val activity = mActivityRef.get()
            if (activity == null) {
                Log.d(TAG, "[SAFE] FixedHandler 收到消息，但 Activity 已被回收，安全跳过")
                return
            }
            Log.d(TAG, "[SAFE] FixedHandler 收到消息 what=${msg.what}（Activity 存活）")
            activity.mInfoText.text = "fixed handler 收到消息"
        }
    }

    private val mFixedHandler = FixedHandler(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_handler_leak)
        mInfoText = findViewById(R.id.tv_leak_result)

        // 投递一条 30 秒后才处理的延时消息，保证退出 Activity 时消息仍在队列中
        findViewById<Button>(R.id.btn_send_leaky).setOnClickListener {
            mLeakyHandler.sendEmptyMessageDelayed(MSG_DELAYED, DELAY_MILLIS)
            mInfoText.text = "已投递泄漏消息，${DELAY_MILLIS / 1000}s 后触发；\n现在请按返回键销毁 Activity 并观察 logcat"
            Log.d(TAG, "[LEAK] 已投递延时消息，延时=${DELAY_MILLIS}ms")
        }

        findViewById<Button>(R.id.btn_send_fixed).setOnClickListener {
            mFixedHandler.sendEmptyMessageDelayed(MSG_DELAYED, DELAY_MILLIS)
            mInfoText.text = "已投递安全消息，${DELAY_MILLIS / 1000}s 后触发（作为对照组）"
            Log.d(TAG, "[SAFE] 已投递延时消息，延时=${DELAY_MILLIS}ms")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "[LEAK] onDestroy：Activity 已销毁，但队列中的延时消息仍持有 Handler -> Activity")

        // 若无泄漏，延时消息到达前 Activity 本应被回收；此处主动校验弱引用是否还被清除。
        LeakChecker(WeakReference(this), TAG).start()

        // 说明：正常开发中应在此处 mLeakyHandler.removeCallbacksAndMessages(null) 来解除泄漏。
    }

    /**
     * 简易泄漏检测线程：等 GC 有机会跑几轮后，看弱引用是否被清除。
     * 弱引用仍存活 => 对象被强引用链牵住 => 泄漏。
     *
     * 关键点：必须用一个「控制组弱引用」（明确已置空、必被回收的对象）来证明 GC 真的执行了，
     * 否则无法区分「GC 没跑」与「真的被强引用牵住」，结论会失真。
     */
    private  class LeakChecker(
        private val activityRef: WeakReference<HandlerLeakActivity>,
        private val tag: String,
    ) : Thread() {
        override fun run() {
            // 控制组：创建一个对象、用弱引用持有、随即断开强引用。它必然可被回收。
            var control: Any? = Any()
            val controlRef = WeakReference(control)
            control = null

            repeat(GC_ROUNDS) { round ->
                try {
                    Thread.sleep(GC_INTERVAL_MILLIS)
                } catch (e: InterruptedException) {
                    return
                }
                // 制造内存压力并触发回收
                Runtime.getRuntime().gc()
                System.runFinalization()

                val gcHappened = controlRef.get() == null
                if (!gcHappened) {
                    Log.d(tag, "===== 第 ${round + 1} 轮：控制组对象未被回收，说明 GC 尚未执行，本轮结论不可信")
                    return@repeat
                }

                if (activityRef.get() == null) {
                    Log.d(tag, "===== 第 ${round + 1} 轮：GC 已执行，Activity 被回收 => 无内存泄漏")
                    return
                }
                Log.d(tag, "===== 第 ${round + 1} 轮：GC 已执行，但 Activity 仍存活 => 存在内存泄漏！")
            }

            if (controlRef.get() == null && activityRef.get() != null) {
                Log.e(tag, "===== 结论：GC 已确认执行，Activity 未被回收 => 确认内存泄漏。" +
                        "引用链 Looper -> MessageQueue -> Message.target -> 非静态内部类 Handler -> Activity")
            }
        }
    }

    companion object {
        private const val TAG = "HandlerLeak"
        private const val MSG_DELAYED = 0x9527

        /** 延时足够长，保证退出页面后消息仍在队列里。 */
        private const val DELAY_MILLIS = 30_000L
        private const val GC_ROUNDS = 8
        private const val GC_INTERVAL_MILLIS = 2_000L
    }
}
