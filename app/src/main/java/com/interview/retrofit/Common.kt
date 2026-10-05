package com.interview.retrofit

import com.interview.net.NetClient
import retrofit2.Retrofit
import retrofit2.adapter.rxjava3.RxJava3CallAdapterFactory
import retrofit2.converter.gson.GsonConverterFactory

/**
 * Time: 2026/10/5（本轮接入网络层收口）
 * Author: wgt
 * Description: Retrofit 单例。
 *
 * ─── 本轮变更：从「默认 client」改为「收口 client」───
 *
 * 原来这里不设 client，Retrofit 会**自己 new 一个 OkHttpClient**。
 * 后果是：网络层的所有优化（自定义 DNS、分阶段度量、自适应重试）
 * 对这个接口全部失效 —— 它是全 App 一个隐形的旁路。
 *
 * 现在显式传 [NetClient.shared]：Retrofit 只是 OkHttp 之上的
 * 「接口→请求」翻译层，**网络能力应完全来自 client**，不该自己持有。
 * 这样 GitHub 接口与图片下载共享同一份 DNS 缓存、连接治理与度量口径。
 *
 * 档位选择：shared 是 API 档（RTT 受限的小 JSON），正好匹配 Retrofit 的典型用途。
 */
private val retrofit =
    Retrofit.Builder().baseUrl(GITHUB_API)
        .client(NetClient.shared)
        .addCallAdapterFactory(RxJava3CallAdapterFactory.create())
        .addConverterFactory(GsonConverterFactory.create())
        .build()

val gitHub: GitHub = retrofit.create(GitHub::class.java)