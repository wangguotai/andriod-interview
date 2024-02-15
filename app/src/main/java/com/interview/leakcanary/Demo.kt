package com.interview.leakcanary

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.LinkedList
import java.util.concurrent.locks.LockSupport

/**
 * Time: 2024/2/15
 * Author: wgt
 * Description:
 */
class Demo {
    val activity = Activity(ActivityLifeCycleOwnerImpl)

    class Activity(val lifeCycleManager: ActivityLifeCycleOwner) {
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
        //        val queue = LinkedList<WeakReference<Activity>>()
//        val queue = LinkedList<WeakReference<Activity>>()
//        fun onDestroy() {
//            for (a in queue) {
//                if (a.get() == null){
//                    queue.remove()
//                }
//            }
//        }
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

    class LeakCanary : LeakLifecycleListener, GCObserver {
        private val mReferenceQueue = ReferenceQueue<Activity>()
        private val mDestroyedActivityTagList = LinkedList<String>()
        private val weakRefList = mutableListOf<WatchedActivityReference>()
        private val mWatchedThread = Thread {
            try {
                while (true) {
                    LockSupport.park()
                    // 防止GC干扰
//                        synchronized(referenceQueue) {
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
//                        }
                }
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
//            WatcherThread(mReferenceQueue, mDestroyedActivityTagList) // 检测是否发生泄漏的线程

        // 将LeakCanary与LifecycleManager绑定接收触发onDestroy的对象
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

//        class WatcherThread(
////            val referenceQueue: ReferenceQueue<Activity>,
////            val destroyedActivityList: MutableList<String>
//        ) : Thread() {
//            override fun run() {
//                try {
//                    while (true) {
//                        LockSupport.park()
//                        // 防止GC干扰
//                        Thread.sleep(10000)
////                        synchronized(referenceQueue) {
//                        var ref: WatchedActivityReference?
//                        while (referenceQueue.poll().also {
//                                ref = it as WatchedActivityReference?
//                            } != null) {
//                            ref?.activityName?.let {
//                                destroyedActivityList.remove(it)
//                                println("$it has been properly recycled.")
//                            }
//                        }
//                        destroyedActivityList.forEach {
//                            println("Leaking founded in $it")
//                        }
////                        }
//                    }
//                } catch (e: InterruptedException) {
//                    e.printStackTrace()
//                }
//            }
//
//        }

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
    var demo: Demo? = Demo()
    val leakCanary = Demo.LeakCanary() // 改成单例的实现即可
    leakCanary.install()
    demo!!.activity.onDestroy()
//    demo = null
    System.gc()
    println("After GC")
//    Thread.sleep(4000)
//    System.gc()
    println("再次GC")
    println("Finished main")
}