package com.wgt.lifecycle.presenter

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.OnLifecycleEvent
import com.wgt.lifecycle.presenter.interf.view.IBaseView
import java.lang.ref.WeakReference

/**
 * Time: 2024/4/11
 * Author: wgt
 * Description:
 */
class BasePresenter<T : IBaseView?> : LifecycleOwner {
    private val mLifecycleRegistry = LifecycleRegistry(this)
    var baseView: WeakReference<T>? = null
    fun attachView(view: T) {
        baseView = WeakReference(view)
    }

    /**
     * 解绑
     */
    fun detachView() {
        if (baseView != null) {
            baseView!!.clear()
            baseView = null
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun onCreateX(owner: LifecycleOwner?) {
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    fun onStartX(owner: LifecycleOwner?) {
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    fun onStop(owner: LifecycleOwner?) {
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    fun onResume(owner: LifecycleOwner?) {
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun onPause(owner: LifecycleOwner?) {
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun onDestory(owner: LifecycleOwner?) {
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_ANY)
    fun onAny(owner: LifecycleOwner?) {
    }

    override val lifecycle: Lifecycle = mLifecycleRegistry
}
