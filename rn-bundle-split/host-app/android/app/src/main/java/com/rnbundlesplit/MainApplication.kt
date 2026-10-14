package com.rnbundlesplit

import android.app.Application
import android.util.Log
import com.facebook.react.PackageList
import com.facebook.react.ReactApplication
import com.facebook.react.ReactHost
import com.facebook.react.ReactInstanceManager
import com.facebook.react.ReactNativeHost
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.JSExceptionHandler
import com.facebook.react.defaults.DefaultNewArchitectureEntryPoint.load
import com.facebook.react.defaults.DefaultReactHost.getDefaultReactHost
import com.facebook.react.defaults.DefaultReactNativeHost
import com.facebook.soloader.SoLoader

class MainApplication : Application(), ReactApplication {

  override val reactNativeHost: ReactNativeHost =
      object : DefaultReactNativeHost(this) {
        override fun getPackages(): List<ReactPackage> =
            PackageList(this).packages.apply {
              // Packages that cannot be autolinked yet can be added manually here, for example:
              // add(MyReactNativePackage())
            }

        override fun getJSMainModuleName(): String = "index"

        /**
         * 关闭 dev support，让 debug 构建也**固定走 assets 里的 bundle**，
         * 而不是去连 Metro。
         *
         * 若这里返回 true，`ReactInstanceManager.recreateReactContextInBackgroundInner()`
         * 会在 `mUseDeveloperSupport && mJSMainModulePath != null` 成立时优先请求 dev server
         * （packager 在跑 → 下载并改用缓存包），把我们在 [createReactInstanceManager]
         * 里装的 [MultiBundleLoader] 绕过去。demo 要的是「无 Metro 也能起」，
         * 所以这里给它 false；release 构建本来也是 false。
         */
        override fun getUseDeveloperSupport(): Boolean = false

        override val isNewArchEnabled: Boolean = BuildConfig.IS_NEW_ARCHITECTURE_ENABLED
        override val isHermesEnabled: Boolean = BuildConfig.IS_HERMES_ENABLED

        /**
         * 只改一件事：把 bundle loader 换成「base + business 顺序加载」的那个。
         *
         * 这里复用父类的 `getBaseReactInstanceManagerBuilder()`，它已经组装好了
         * application / packages / JS 引擎 / dev support 等全部默认项，
         * 我们再 `setJSBundleLoader` 覆盖 bundle 来源即可
         * （`setJSBundleLoader` 会同时清掉 `setBundleAssetName` 设过的 assetUrl，
         * 见 ReactInstanceManagerBuilder.java:125）。
         */
        override fun createReactInstanceManager(): ReactInstanceManager {
          val builder = baseReactInstanceManagerBuilder
            .setJSBundleLoader(MultiBundleLoader(this@MainApplication, BUSINESS_ASSET_NAME))
            .setJSExceptionHandler { e ->
              // 不设的话默认 handler 会把异常直接抛到 JS 线程上（DefaultJSExceptionHandler），
              // 崩溃信息虽会进 logcat 但不带我们的 tag，排查 demo 时不方便。
              Log.e(TAG, "JS 异常: ${e.message}", e)
            }
          return builder.build()
        }
      }

  override val reactHost: ReactHost
    get() = getDefaultReactHost(applicationContext, reactNativeHost)

  override fun onCreate() {
    super.onCreate()
    SoLoader.init(this, false)
    if (BuildConfig.IS_NEW_ARCHITECTURE_ENABLED) {
      // If you opted-in for the New Architecture, we load the native entry point for this app.
      load()
    }
  }

  companion object {
    const val TAG = "RnBundleSplit"

    /** 业务 bundle 打进 APK 的 assets 名字（business-app 产物复制过来时用这个文件名）。 */
    const val BUSINESS_ASSET_NAME = "business.android.bundle"

    /**
     * 业务包注册的组件名，必须与 business-app/index.js 里的 `BUSINESS_APP_NAME` 一致。
     * BusinessActivity 用它来启动界面。
     */
    const val BUSINESS_COMPONENT_NAME = "BusinessApp"
  }
}
