package com.wgt.lifecycle.presenter;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;
import androidx.lifecycle.OnLifecycleEvent;

import com.wgt.lifecycle.presenter.interf.view.IBaseView;

import java.lang.ref.WeakReference;

/**
 * Time: 2024/4/11
 * Author: wgt
 * Description:
 */
public class BasePresenter<T extends IBaseView> implements LifecycleOwner {
    private final LifecycleRegistry mLifecycleRegistry = new LifecycleRegistry(this);
    WeakReference<T> baseView;

    public void attachView(T view) {
        baseView = new WeakReference<>(view);
    }

    /**
     * 解绑
     */
    public void detachView() {
        if (baseView != null) {
            baseView.clear();
            baseView = null;
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    void onCreateX(LifecycleOwner owner) {

    }


    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    void onStartX(LifecycleOwner owner) {
    }


    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    void onStop(LifecycleOwner owner) {
    }


    @OnLifecycleEvent(Lifecycle.Event.ON_RESUME)
    void onResume(LifecycleOwner owner) {
    }


    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    void onPause(LifecycleOwner owner) {
    }


    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    void onDestory(LifecycleOwner owner) {
    }


    @OnLifecycleEvent(Lifecycle.Event.ON_ANY)
    void onAny(LifecycleOwner owner) {
    }

    @NonNull
    @Override
    public Lifecycle getLifecycle() {
        return mLifecycleRegistry;
    }
}
