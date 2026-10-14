package com.rnbundlesplit

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.fabricEnabled
import com.facebook.react.defaults.DefaultReactActivityDelegate

/**
 * 渲染**业务包**里的组件。
 *
 * 关键点：这个 Activity 用的是和 [MainActivity] 同一个 [ReactNativeHost] /
 * 同一个 `ReactInstanceManager` / 同一个 JS 运行时。`"BusinessApp"` 这个组件名
 * 是由业务 bundle 的入口（business-app/index.js 里的
 * `AppRegistry.registerComponent('BusinessApp', ...)`）注册进 `AppRegistry` 的，
 * 而 `AppRegistry` 本身来自 base bundle。
 *
 * 所以这个界面能起来，就同时证明了：
 *   - 业务 bundle 成功追加进 base 的全局模块表（否则它连 `react-native` 都 require 不到）；
 *   - 业务代码注册的组件能被 RN 原生侧正常启动（AppRegistry 跨 bundle 生效）。
 */
class BusinessActivity : ReactActivity() {

  /**
   * 对应 business-app/index.js 里 `BUSINESS_APP_NAME`，两边必须一致。
   */
  override fun getMainComponentName(): String = MainApplication.BUSINESS_COMPONENT_NAME

  override fun createReactActivityDelegate(): ReactActivityDelegate =
      DefaultReactActivityDelegate(this, mainComponentName, fabricEnabled)
}
