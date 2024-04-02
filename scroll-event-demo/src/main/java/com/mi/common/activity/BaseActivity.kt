package com.mi.common.activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import com.mi.common.activity.behavior.EnableViewBinding

abstract class BaseActivity<T : ViewDataBinding> : AppCompatActivity(), EnableViewBinding<T> {
    override val binding: T by lazy {
        DataBindingUtil.inflate(layoutInflater, getLayoutId(), null, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
    }
}