package com.wgt.lifecycle.presenter

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.wgt.lifecycle.presenter.interf.view.IBaseView
import java.lang.ref.WeakReference

/**
 * Time: 2024/4/11
 * Author: wgt
 * Description:
 */
open class BasePresenter<T : IBaseView> : LifecycleObserver {
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
    protected open fun onCreate(owner: LifecycleOwner?) {
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    protected fun onStartX(owner: LifecycleOwner?) {
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    protected fun onStop(owner: LifecycleOwner?) {
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    protected fun onResume(owner: LifecycleOwner?) {
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    protected fun onPause(owner: LifecycleOwner?) {
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    protected open fun onDestory(owner: LifecycleOwner?) {
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_ANY)
    protected fun onAny(owner: LifecycleOwner?) {
    }
}
