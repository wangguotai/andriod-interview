package com.interview.retrofit

import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

private val retrofit =
    Retrofit.Builder().baseUrl(GITHUB_API)
        .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
        .addConverterFactory(GsonConverterFactory.create())
        .build()

val gitHub: GitHub = retrofit.create(GitHub::class.java)