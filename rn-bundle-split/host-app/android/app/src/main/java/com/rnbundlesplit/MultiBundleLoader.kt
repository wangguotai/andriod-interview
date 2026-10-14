package com.rnbundlesplit

import android.content.Context
import com.facebook.react.bridge.JSBundleLoader
import com.facebook.react.bridge.JSBundleLoaderDelegate

/**
 * 一个 [JSBundleLoader]，它在一个 JS 运行时里**顺序**加载两个 bundle：
 *
 *   1. base bundle（assets://index.android.bundle）—— RN 运行时 + 全部基础依赖，
 *      并执行 `__r(InitializeCore); __r(entry);` 完成环境初始化。
 *   2. business bundle（assets://business.android.bundle）—— 只有业务模块的
 *      `__d(...)` 定义 + `__r(业务入口)`，直接追加进 base 已建好的全局模块表。
 *
 * ── 为什么可以「追加」而不是「重启」 ─────────────────────────────────
 * 桥接模式（旧架构）下 JS 运行时只创建一次，`JSBundleLoader.loadScript` 会被
 * `ReactInstanceManager` 在同一个 `CatalystInstance` 上调用一次
 * （ReactInstanceManager.java:1464 `catalystInstance.runJSBundle()`），
 * 而底层 `Instance::loadScriptFromString`（cxxreact/Instance.cpp:160）
 * 只是往 JS 线程队列里投递一次 `evaluateJavaScript` —— **没有任何状态重置**。
 * 因此第二个 bundle 的 `__d` 会注册进同一个 `modules` Map
 * （metro-runtime/src/polyfills/require.js 的 `define` 以 `modules.has(id)` 去重）。
 *
 * ── 为什么 loadSynchronously = false ────────────────────────────────
 * `loadSynchronously = true` 会走 `Instance::loadBundleSync`，它在**调用线程**
 * 上直接执行 JS（NativeToJsBridge.cpp:153 → 直接调 executor->loadBundle，
 * 不像异步分支那样 runOnExecutorQueue）。从主线程调用它等于在 UI 线程跑整个 bundle。
 * 用 false 时两次 evaluate 都投递到同一条 JS 线程队列，先入先出，
 * 顺序天然得到保证 —— RN 自己的 split bundle 加载器
 * （JSBundleLoader.createCachedSplitBundleFromNetworkLoader → loadSplitBundleFromFile）
 * 也用 false。
 */
class MultiBundleLoader(
    private val context: Context,
    private val businessAssetName: String,
) : JSBundleLoader() {

  override fun loadScript(delegate: JSBundleLoaderDelegate): String {
    // 1) base：完整 bundle，自带 prelude + polyfills + __r(InitializeCore) + __r(entry)
    delegate.loadScriptFromAssets(context.assets, BASE_ASSET_URL, false)

    // 2) business：只有 __d 定义与业务入口，追加到上面那张全局模块表
    delegate.loadScriptFromAssets(context.assets, "assets://$businessAssetName", false)

    // 返回值仅用于 sourceURL/日志与 sourcemap 符号化，取 base 即可
    return BASE_ASSET_URL
  }

  companion object {
    const val BASE_ASSET_URL = "assets://index.android.bundle"
  }
}
