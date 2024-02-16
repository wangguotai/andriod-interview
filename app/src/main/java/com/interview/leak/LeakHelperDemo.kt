package com.interview.LeakHelper

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.LinkedList
import java.util.concurrent.locks.LockSupport

/**
 * Time: 2024/2/15
 * Author: wgt
 * Description: 基于ReferenceQueue的原理构建一个LeakHelper的简易Demo模型 用于理解解决面试问题
 */
class LeakHelperDemo {
    val activity = Activity(ActivityLifeCycleOwnerImpl)

    class Activity(private val lifeCycleManager: ActivityLifeCycleOwner) {
        val tag = javaClass.simpleName
        fun onDestroy() {
            lifeCycleManager.updateLifecycleState(LifecycleState.DESTROYED, WeakReference(this))
        }
    }

    enum class LifecycleState {
        CREATED,
        DESTROYED,
    }

    interface ActivityLifeCycleOwner {
        fun register(listener: LeakLifecycleListener)
        fun updateLifecycleState(state: LifecycleState, referent: WeakReference<Activity>)
    }

    interface LeakLifecycleListener {
        fun onStateChanged()
        fun onDestroy(referent: WeakReference<Activity>)
    }

    object ActivityLifeCycleOwnerImpl : ActivityLifeCycleOwner {
        private val listeners = mutableListOf<LeakLifecycleListener>()
        override fun register(listener: LeakLifecycleListener) {
            listeners.add(listener)
        }

        override fun updateLifecycleState(
            state: LifecycleState,
            referent: WeakReference<Activity>
        ) {
            if (state == LifecycleState.DESTROYED) {
                for (listener in listeners) {
                    listener.onDestroy(referent)
                }
            }
        }

    }

    interface GCObserver {
        fun onGC()
    }

    // 监控GC是否发生
    object GCMonitorObserveOwner {
        private var rf = WeakReference(GCOwner())
        private val observerListener = mutableListOf<GCObserver>()

        class GCOwner : Object() {
            override fun finalize() {
                super.finalize()
                println("GCCheck app gc occur")
                for (observer in observerListener) {
                    observer.onGC()
                }
                rf = WeakReference(GCOwner())
            }
        }

        fun register(observer: GCObserver) {
            observerListener.add(observer)
        }
    }

    class LeakHelper : LeakLifecycleListener, GCObserver {
        private val mReferenceQueue = ReferenceQueue<Activity>()
        private val mDestroyedActivityTagList = LinkedList<String>()
        private val weakRefList = mutableListOf<WatchedActivityReference>()
        // 开启一个单独的线程处理内存泄漏的监控
        private val mWatchedThread = Thread {
            try {
                while (true) {
                    LockSupport.park()
                    // 防止GC干扰
                    synchronized(mReferenceQueue) {
                        var ref: WatchedActivityReference?
                        while (mReferenceQueue.poll().also {
                                ref = it as WatchedActivityReference?
                            } != null) {
                            ref?.activityName?.let {
                                mDestroyedActivityTagList.remove(it)
                                println("$it has been properly recycled.")
                            }
                        }
                        mDestroyedActivityTagList.forEach {
                            println("Leaking founded in $it")
                        }
                    }
                }
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
        /**
         * 将LeakHelper作为事件接受器绑定到 Activity的生命周期管理和GC事件发生的监听中
         */
        fun install() {
            GCMonitorObserveOwner.register(this) // 注册GC回收发生
            ActivityLifeCycleOwnerImpl.register(this)  // 注册生命周期
        }


        override fun onStateChanged() {
        }

        // 当Activity触发onDestroy后，将其放入虚引用队列
        override fun onDestroy(referent: WeakReference<Activity>) {
            mWatchedThread.start()
            // 创建若引用并将其与ReferenceQueue关联
            bindQueue(referent)
        }

        // 将触发onDestroy的Activity与虚引用绑定,并简单通过名称进行记录
        private fun bindQueue(referent: WeakReference<Activity>) {
            referent.get()?.let {
                mDestroyedActivityTagList.add(it.tag)
                weakRefList.add(WatchedActivityReference(it, mReferenceQueue))
            }
        }

        override fun onGC() {
            LockSupport.unpark(mWatchedThread)
        }
        class WatchedActivityReference : WeakReference<Activity> {
            lateinit var activityName: String

            constructor(referent: Activity, queue: ReferenceQueue<Activity>) : super(
                referent,
                queue
            ) {
                activityName = referent.javaClass.simpleName
            }
        }
    }
}

fun main() {
    var demo: LeakHelperDemo? = LeakHelperDemo()
    val leakHelper = LeakHelperDemo.LeakHelper() // 改成单例的实现即可
    leakHelper.install()
    demo!!.activity.onDestroy()
    demo = null
    System.gc()
    println("After GC")
//    Thread.sleep(4000)
//    System.gc()
    println("再次GC")
    println("Finished main")
}