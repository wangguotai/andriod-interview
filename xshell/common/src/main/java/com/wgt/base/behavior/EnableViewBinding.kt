package com.wgt.base.behavior

import androidx.databinding.ViewDataBinding

interface EnableViewBinding<T : ViewDataBinding> {
    val binding: T
    fun getLayoutId(): Int
}