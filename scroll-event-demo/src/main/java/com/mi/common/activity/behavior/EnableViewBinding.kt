package com.mi.common.activity.behavior

import androidx.databinding.ViewDataBinding

interface EnableViewBinding<T : ViewDataBinding> {
    val binding: T
    fun getLayoutId(): Int
}