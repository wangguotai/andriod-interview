package com.interview.plugin.contract

import android.content.Context
import android.view.ViewGroup

/**
 * Time: 2026/10/13
 * Author: wgt
 * Description: 宿主 ↔ 插件的**唯一共享面**（Shared API）。
 *
 * ─── 这个接口为什么存在，以及它为什么必须住在宿主的源码树里 ───
 *
 * 插件与宿主是**两个 ClassLoader**。跨 ClassLoader 传对象的前提是：
 * **接口/父类必须由同一个 ClassLoader 定义**，否则会得到那个最经典的
 * `ClassCastException: X cannot be cast to X`（同一个类名、同一份 dex 字节，
 * 但两个不同的 Class 对象 ⇒ 在 ART 看来是两个不相干的类型）。
 *
 * 所以这里有一条硬约束，写下来防止以后被"顺手"破坏：
 *
 *   1. 本文件的源码**只有一份**（当前路径），`:plugin-demo` 通过
 *      `sourceSets.main.java.srcDir(...)` 把**同一份源码**编进自己的 dex ——
 *      它不依赖 `:app`，也不产生任何 project 依赖（见 plugin-demo/build.gradle.kts）。
 *   2. 运行期，插件的 ClassLoader 对 `com.interview.*` 一律**先委派给宿主**
 *      （见 PluginClassLoader），于是插件的 `IPluginPage` 解析到宿主 dex 里那一份
 *      ⇒ 类型一致，转换成功。
 *   3. **反例（务必不要这么做）**：把 IPluginPage 放进插件独有的包名
 *      （如 `com.interview.plugindemo.IPluginPage`）。那时插件加载自己 dex 里那份，
 *      而宿主的 dex 里根本没有它 ⇒ 宿主只能对插件返回的对象全程反射。
 *      能用，但丢掉编译期类型检查，也丢掉本文件的全部意义。
 *
 * ─── 接口为什么故意"窄" ───
 *
 * 插件体系里最容易失控的不是加载，而是**共享面膨胀**：今天加一个 OkHttp 类型的
 * 参数，明天加一个 Activity 子类。共享面上的每个类型都是未来插件的**最低系统
 * 版本**。所以这里只用 Android SDK 公开类型（Context / ViewGroup / String），
 * 一个自定义类型都不进签名。
 */
interface IPluginPage {

    /**
     * 宿主准备好容器后调用。实现方负责把界面挂到 [container] 上。
     *
     * @param pluginContext **插件自己的 Context**（由宿主构造，其 `resources`
     *        指向插件 APK）。插件必须用它去 `getString` / `LayoutInflater.from`，
     *        否则 `R.layout.*` 这类 ID 在宿主的资源表里找不到，直接 Resources$NotFoundException。
     *        这个参数是"资源隔离"在接口层面的体现 —— 插件**拿不到**宿主 Resources
     *        （`pluginContext` 不是 Activity，也不暴露宿主 Context）。
     * @param container 宿主给出的容器，插件只往这里 addView。
     * @param networkPayload 宿主从**网络接口**取回的原始报文（原文透传，不加工）。
     *        之所以由宿主传入而不是插件自己联网，是为了让"插件零依赖"成立：
     *        插件里没有 OkHttp，也没有 INTERNET 权限 —— 网络归宿主，插件只管渲染。
     */
    fun onCreate(pluginContext: Context, container: ViewGroup, networkPayload: String?)

    /** 宿主容器销毁时调用。插件在此释放自己持有的东西（Bitmap / 监听器 / 线程）。 */
    fun onDestroy()

    /**
     * 插件自证身份：返回**只有插件才答得出来**的事实，供宿主原样展示。
     * 这是本演示的可证伪点 —— 宿主不预置任何一条答案。
     */
    fun describe(): String
}
