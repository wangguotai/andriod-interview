package com.interview.liveData

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class LiveDataViewModel : ViewModel() {
    private val _counter = MutableLiveData<Int>()
    val counter: LiveData<Int> = _counter // 对外暴露不可变LiveData

    init {
        _counter.value = 0
    }
    fun increment() {
        _counter.value = (_counter.value ?: 0) + 1
    }

    fun postIncrement(){
        Thread.getAllStackTraces()
        _counter.postValue(11)
    }
}