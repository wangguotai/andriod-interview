package com.wgt.base

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import com.wgt.base.behavior.EnableViewBinding
import com.wgt.lifecycle.presenter.BasePresenter
import com.wgt.lifecycle.presenter.interf.view.IBaseView

abstract class BaseActivity<V : ViewDataBinding, T : IBaseView, P : BasePresenter<T>> :
    AppCompatActivity(), EnableViewBinding<V>, IBaseView {
    protected lateinit var presenter: P

    override val binding: V by lazy {
        DataBindingUtil.inflate(layoutInflater, getLayoutId(), null, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        presenter = createPresenter()
        presenter.attachView(this as T)
        registerSDK()
        init()
    }


    open fun init() {}
    abstract fun createPresenter(): P
    fun registerSDK() {}
    fun unRegisterSDK() {}

    override fun onDestroy() {
        super.onDestroy()
        presenter.detachView()
        unRegisterSDK()
    }

}