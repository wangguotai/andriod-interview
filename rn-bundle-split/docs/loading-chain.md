# 加载链路：base 和业务包是怎么进到 RN 运行时的

> 这是 [rn-bundle-primer.md](./rn-bundle-primer.md) 的续篇，回答一个具体问题：
> **从点开 App 图标，到两个 bundle 真的跑起来，中间发生了什么？**
>
> 全程贴着这个项目的真实代码走，每一跳都标了源文件与行号。
> 链路跨了 **Java → C++ → JS** 三层，别看跳数多，主干其实只有两条线。

---

## 0. 先看全景（记住这两条线就行）

```
【A 线：建运行时 + 灌 bundle】
   Activity.onCreate
      └→ loadApp("BusinessApp")
         └→ startReactApplication(...)
            └→ createReactContextInBackground()
               └→ ...（后台线程）...
                  └→ catalystInstance.runJSBundle()
                     └→ ★ mJSBundleLoader.loadScript(this)      ← 我们插进来的 loader
                        ├→ loadScriptFromAssets("assets://index.android.bundle",    false) → 投进 JS 队列 ①
                        └→ loadScriptFromAssets("assets://business.android.bundle", false) → 投进 JS 队列 ②

【B 线：把界面挂上去】
   ...（同一进程稍后）...
   setupReactContext()
      └→ attachRootViewToInstance()
         └→ reactRoot.runApplication()
            └→ AppRegistry.runApplication("BusinessApp")  → 投进 JS 队列 ③

                    ↓ 同一条 JS 队列，先入先出

   队列执行顺序：① base  →  ② business  →  ③ 挂界面
                                                     ↑
                            因为 ①② 排队在前，③ 执行时组件已注册好
```

**一句话**：base 和业务包，本质是**在同一个 `loadScript` 调用里被投进同一条 JS 队列的两段脚本**。
挂界面排在它们后面 —— 这就是"为什么能立刻跑起来"，也是"为什么懒加载在这个架构下不行"。

---

## 1. A 线：从 Activity 到「我们插的 loader」

### 1.1 起点：Activity 启动

`BusinessActivity` 继承 `ReactActivity`。`ReactActivity.onCreate` 只做一件事 ——
把活交给 delegate：

```java
// ReactActivity.java:45-48
protected void onCreate(Bundle savedInstanceState) {
  ...
  mDelegate.onCreate(savedInstanceState);
}
```

delegate 里接着调 `loadApp`，把"要渲染哪个组件"传下去：

```java
// ReactActivityDelegate.java:136-138
protected void loadApp(String appKey) {
  mReactDelegate.loadApp(appKey);
  getPlainActivity().setContentView(mReactDelegate.getReactRootView());
}
```

> 这里的 `appKey`，就是 `BusinessActivity.getMainComponentName()` 返回的 `"BusinessApp"`。

### 1.2 关键一跳：`getReactInstanceManager()` 是**惰性**的

```java
// ReactDelegate.java（非 bridgeless 分支）
mReactRootView.startReactApplication(
    getReactNativeHost().getReactInstanceManager(), appKey, mLaunchOptions);
```

`getReactInstanceManager()` **第一次被调用时才创建**：

```java
// ReactNativeHost.java:48-54
public ReactInstanceManager getReactInstanceManager() {
  if (mReactInstanceManager == null) {
    mReactInstanceManager = createReactInstanceManager();   // ★ 调到我们的 override
  }
  return mReactInstanceManager;
}
```

**这里就是我们插进链路的入口。** `MainApplication` 覆写了
`createReactInstanceManager()`，在里面把默认 loader 换成了 `MultiBundleLoader`：

```kotlin
// MainApplication.kt:53-62
override fun createReactInstanceManager(): ReactInstanceManager {
  val builder = baseReactInstanceManagerBuilder
    .setJSBundleLoader(MultiBundleLoader(this@MainApplication, BUSINESS_ASSET_NAME))
    .setJSExceptionHandler { e -> Log.e(TAG, "JS 异常: ${e.message}", e) }
  return builder.build()
}
```

而 `build()` 会把我们给的 loader 原样存下来（注意那个三选一的分支）：

```java
// ReactInstanceManagerBuilder.java:351-354
(mJSBundleLoader == null && mJSBundleAssetUrl != null)
    ? JSBundleLoader.createAssetLoader(mApplication, mJSBundleAssetUrl, false)
    : mJSBundleLoader,          // ★ 我们传了 loader，走这个分支
```

> ⚠️ 这也是为什么 `setJSBundleLoader` 必须在 `setBundleAssetName` **之后**调 ——
> 两者会互相清空对方（`ReactInstanceManagerBuilder.java:99-101` 和 `125-127`）。

### 1.3 进入后台线程建运行时

```java
// ReactRootView.java:478 —— startReactApplication 内部
mReactInstanceManager.createReactContextInBackground();
```

这条线最后走到：

```java
// ReactInstanceManager.java:501-505
private void recreateReactContextInBackgroundFromBundleLoader() {
  ...
  recreateReactContextInBackground(mJavaScriptExecutorFactory, mBundleLoader);  // ★ 带着 loader
}
```

> 注意：能走这条"直接用 bundle loader"的分支，是因为我们把
> `getUseDeveloperSupport()` 设成了 `false`。否则它会优先去连 Metro（第 457-497 行那个分支），
> **把我们的 loader 绕过去** —— 这是当初必须关掉 dev support 的原因。

然后起一个后台线程真正干活：

```java
// ReactInstanceManager.java:1123-1126
private void runCreateReactContextOnNewThread(final ReactContextInitParams initParams) {
  ...
  mCreateReactContextThread = new Thread(null, () -> {
    ...
    reactApplicationContext = createReactContext(
        initParams.getJsExecutorFactory().create(),
        initParams.getJsBundleLoader());   // ★ loader 一路传到这儿
  }, "create_react_context");
```

### 1.4 ★ 最小的一跳，也是最重要的一跳

`createReactContext()` 干完一堆初始化（native modules、UI manager…），
**最后一步**是灌 bundle：

```java
// ReactInstanceManager.java:1462-1464
ReactMarker.logMarker(ReactMarkerConstants.PRE_RUN_JS_BUNDLE_START);
Systrace.beginSection(TRACE_TAG_REACT_JAVA_BRIDGE, "runJSBundle");
catalystInstance.runJSBundle();
```

而 `runJSBundle()` 里就一句关键调用：

```java
// CatalystInstanceImpl.java:264-268
public void runJSBundle() {
  FLog.d(ReactConstants.TAG, "CatalystInstanceImpl.runJSBundle()");
  Assertions.assertCondition(!mJSBundleHasLoaded, "JS bundle was already loaded!");
  mJSBundleLoader.loadScript(CatalystInstanceImpl.this);   // ★★★ 调的是我们的 loader
  ...
}
```

**看到 `mJSBundleLoader` 了吗** —— 就是我们注入的那个。
它被调用**一次**，而且**只被调用一次**（上面那句 `assertCondition(!mJSBundleHasLoaded)` 会拦重复调用）。

> 这就是整个方案成立的前提：**RN 只给"一次机会"加载 bundle，
> 但这一次机会里的 `loadScript()` 是我们自己实现的** ——
> 所以我们能在那一次里塞两段。

### 1.5 我们的 loader：一次调用，两段脚本

```kotlin
// MultiBundleLoader.kt:38-47
override fun loadScript(delegate: JSBundleLoaderDelegate): String {
  // ① base：完整 bundle（自带 prelude + 489 个 __d + __r(入口)）
  delegate.loadScriptFromAssets(context.assets, BASE_ASSET_URL, false)
  // ② business：只含 4 个 __d + __r(业务入口)
  delegate.loadScriptFromAssets(context.assets, "assets://$businessAssetName", false)
  return BASE_ASSET_URL
}
```

两次调用的第三个参数都是 `false`（异步）—— 这一点后面 3.1 会讲清为什么很关键。

---

## 2. 跨语言：Java → C++

### 2.1 Java 层只是转发

```java
// CatalystInstanceImpl.java:236-241
public void loadScriptFromAssets(
    AssetManager assetManager, String assetURL, boolean loadSynchronously) {
  mSourceURL = assetURL;
  jniLoadScriptFromAssets(assetManager, assetURL, loadSynchronously);   // ★ native 方法
}
```

### 2.2 C++ 层：剥前缀 + 判类型 + 读文件

```cpp
// jni/react/jni/CatalystInstanceImpl.cpp:248-271
void CatalystInstanceImpl::jniLoadScriptFromAssets(
    jni::alias_ref<JAssetManager::javaobject> assetManager,
    const std::string& assetURL,
    bool loadSynchronously) {
  const int kAssetsLength = 9;                    // strlen("assets://")
  auto sourceURL = assetURL.substr(kAssetsLength);  // ★ 把 "assets://" 剥掉

  auto manager = extractAssetManager(assetManager);
  auto script = loadScriptFromAssets(manager, sourceURL);   // ★ 读 asset 内容

  if (JniJSModulesUnbundle::isUnbundle(manager, sourceURL)) {
    // ... RAM bundle 分支（我们不是这条）
  } else if (isIndexedRAMBundle(&script)) {
    instance_->loadRAMBundleFromString(std::move(script), sourceURL);
  } else {
    instance_->loadScriptFromString(                       // ★ 我们走这条
        std::move(script), sourceURL, loadSynchronously);
  }
}
```

两个细节值得记：

1. **`assets://` 前缀正好 9 个字符**，C++ 侧硬剥掉它再去找 asset。
   （这也解释了为什么 `setBundleAssetName` 内部是拼 `"assets://" + name`。）
2. **判类型是看"头部字节"，不是看扩展名**。我们的文件叫 `.bundle`，
   但内容是纯 JS 文本 → 既不是 RAM bundle 也不是 HBC → 走最普通的
   `loadScriptFromString`。

读 asset 的地方（顺便回答一个曾经的担心）：

```cpp
// jni/react/jni/JSLoader.cpp:66-72
auto asset = AAssetManager_open(manager, assetName.c_str(), AASSET_MODE_STREAMING);
if (asset) {
  auto script = std::make_unique<AssetManagerString>(asset);
  ...
```

> `AASSET_MODE_STREAMING` 会**透明解压** —— 所以即使 APK 里 bundle 被 zip 压缩了，
> 这里照样读得到。真机验证过没问题。

### 2.3 进入"JS 队列"

```cpp
// cxxreact/Instance.cpp:160-167
void Instance::loadScriptFromString(
    std::unique_ptr<const JSBigString> string,
    std::string sourceURL,
    bool loadSynchronously) {
  if (loadSynchronously) {
    loadBundleSync(nullptr, std::move(string), std::move(sourceURL));   // 同步：调用线程直接跑
  } else {
    loadBundle(nullptr, std::move(string), std::move(sourceURL));       // ★ 我们走这条
  }
}
```

选异步的这条，最后走到：

```cpp
// cxxreact/NativeToJsBridge.cpp:129-150
void NativeToJsBridge::loadBundle(...) {
  runOnExecutorQueue(              // ★ 投进 JS 线程的消息队列！
      [this, ...](JSExecutor* executor) mutable {
        ...
        executor->loadBundle(std::move(*startupScript), std::move(startupScriptSourceURL));
      });
}
```

`runOnExecutorQueue` 就是那个"队列"：

```cpp
// cxxreact/NativeToJsBridge.cpp:290-310
void NativeToJsBridge::runOnExecutorQueue(std::function<void(JSExecutor*)>&& task) noexcept {
  ...
  m_executorMessageQueueThread->runOnQueue(          // ★ 同一条队列，FIFO
      [this, isDestroyed, task = std::move(task)] { ... task(m_executor.get()); });
}
```

**我们的两次 `loadScriptFromAssets`，就是往这条队列里 push 了两个任务。**
base 先 push，business 后 push → 执行顺序天然就是 base → business。

---

## 3. 落到 JS：才真正执行

### 3.1 为什么"异步 + 同一条队列"就够了

先看两个分支的**可验证差异**（读源码就能确认）：

| | `loadSynchronously = true` | `loadSynchronously = false` |
|---|---|---|
| 走哪个函数 | `loadBundleSync` | `loadBundle` |
| 有没有 `runOnExecutorQueue` | **没有** | **有** |
| 结果 | 在**调用线程**上直接 `m_executor->loadBundle(...)` | 把任务 push 进 **JS 线程队列** |

同步分支的源码（注意它**没有**排队这一步）：

```cpp
// cxxreact/NativeToJsBridge.cpp:153-167
void NativeToJsBridge::loadBundleSync(...) {
  ...
  m_executor->loadBundle(std::move(startupScript), ...);   // ★ 直接执行，没进队列
  ...
}
```

**为什么要在意这个**：`loadScript()` 是被 `createReactContext()` 在最后一行调的
（`ReactInstanceManager.java:1464`），而 `createReactContext()` 跑在一个叫
`create_react_context` 的**后台线程**上（`ReactInstanceManager.java:1139-1195`）。
也就是说同步分支会让 JS 在**那个后台线程**上执行。

而这会引出两个问题：

1. **线程安全**：JS 引擎（runtime）归 JS 线程独占。在别的线程上调
   `evaluateJavaScript` 等于跨线程访问同一个非线程安全的运行时。
2. **顺序失控**：RN 其他所有 JS 调用（包括后面 B 线的 `runApplication`）
   都走 `runOnExecutorQueue` 那条队列。同步分支**绕过队列**，
   于是它和后续调用之间**不再有先后保证**。

> 说明：第 1 点是基于「Hermes 运行时非线程安全」这一前提的推断（源码里没写注释）；
> 第 2 点是可以直接从源码确认的 —— `loadBundleSync` 确实没有 `runOnExecutorQueue`。

**所以选异步不是"图别的"，图的是：让两段 bundle 和后续所有 JS 调用排在同一条队伍里，
由单线程队列来保证顺序，而不是靠"谁先执行到"来碰运气。**

（RN 自己处理 split bundle 时也传 `false`：`JSBundleLoader.java:79-85`
的 `createCachedSplitBundleFromNetworkLoader` → `loadSplitBundleFromFile`
→ `jniLoadScriptFromFile(..., false)`。）

### 3.2 最终执行点

```cpp
// jsiexecutor/jsireact/JSIExecutor.cpp:149-161
void JSIExecutor::loadBundle(
    std::unique_ptr<const JSBigString> script, std::string sourceURL) {
  ...
  runtime_->evaluateJavaScript(          // ★ 160 行：真正的 JS 引擎求值
      std::make_unique<BigStringBuffer>(std::move(script)), sourceURL);
  flush();
  ...
}
```

到这里，一段 JS 文本才真正被 Hermes 执行。

**但注意**：这里求值的是**一段**脚本。我们连续投了两个任务，
所以这一段会执行两遍 —— 第一遍是 base，第二遍是 business：

```
第 1 次 evaluateJavaScript：base 的 8706 字符 prelude + 489 个 __d + __r(入口)
                            → 建全局表、登记 489 个模块、渲染出 "RnBundleSplit"

第 2 次 evaluateJavaScript：business 的 4 个 __d + __r(938847)
                            → 往同一张表塞 4 个模块
                            → __r(938847) 执行 → AppRegistry.registerComponent("BusinessApp")
```

> 第 2 次里 `__r` 是谁提供的？base 第 1 次执行时挂到全局的。
> 而 business 里没有 prelude（`modulesOnly`），所以**不会重建表、也不会清表** ——
> 它只是"接着往同一张表里塞东西"。（详见 primer 第 5 章。）

---

## 4. B 线：界面是怎么挂上去的

bundle 跑完 ≠ 界面出来。还需要**告诉 RN"把哪个组件挂到哪个 root view 上"**。

这步发生在 `setupReactContext`（在 native modules 队列线程上执行）：

```java
// ReactInstanceManager.java:1240-1252（简化）
private void setupReactContext(final ReactApplicationContext reactContext) {
  synchronized (mAttachedReactRoots) {
    synchronized (mReactContextLock) {
      mCurrentReactContext = Assertions.assertNotNull(reactContext);   // ① 记下当前 context
    }
    ...
    for (ReactRoot reactRoot : mAttachedReactRoots) {
      attachRootViewToInstance(reactRoot);                             // ② 每个 root 挂上去
    }
  }
}
```

`attachRootViewToInstance` 里最终调：

```java
// ReactInstanceManager.java:1294-1302（非 Fabric 分支）
rootTag = uiManager.addRootView(...);
reactRoot.setRootViewTag(rootTag);
reactRoot.runApplication();              // ★
```

然后：

```java
// ReactRootView.java:655-683
public void runApplication() {
  ...
  catalystInstance.getJSModule(AppRegistry.class).runApplication(jsAppModuleName, appParams);
```

**关键**：`getJSModule(...).runApplication(...)` 是一个 **Native → JS 的调用**，
它最终也走 `runOnExecutorQueue`：

```cpp
// cxxreact/NativeToJsBridge.cpp:169-186
void NativeToJsBridge::callFunction(...) {
  runOnExecutorQueue([this, module, method, arguments, ...](JSExecutor* executor) {
    ...
  });
}
```

---

## 5. 拼起来：那条队列上的真实顺序

因为 ① ② 和 ③ **用的是同一条队列**，顺序就完全确定了：

```
JS 队列（单线程，FIFO）
┌────────────────────────────────────────────────────────────┐
│ ① loadBundle(base)      ← 在 createReactContext 阶段 push   │
│ ② loadBundle(business)  ← 同上，紧跟着 push                 │
│ ③ runApplication("BusinessApp") ← 在 setupReactContext 阶段 push │
└────────────────────────────────────────────────────────────┘
        ↓ 依次执行
① base 跑：建表 + 489 模块 + 渲染 "RnBundleSplit"
② business 跑：4 模块 + registerComponent("BusinessApp")   ← 组件在这注册好了
③ runApplication("BusinessApp")：查 runnables 表 → 找到 → 渲染 ✓
```

### 为什么③能找到组件？看 AppRegistry 的两半

**注册**（在 ② 里执行，业务包最后一行 `__r(938847)` 触发的）：

```js
// ReactNative/AppRegistry.js:121-127（简化）
registerComponent(appKey, componentProvider, section) {
  runnables[appKey] = (appParameters, displayMode) => { renderApplication(...); };
}
```

**启动**（在 ③ 里执行）：

```js
// ReactNative/AppRegistry.js:201-222（简化）
runApplication(appKey, appParameters, displayMode) {
  const msg = `Running "${appKey}"${logParams}`;     // ★ 208 行：你看到的那行日志
  infoLog(msg);
  invariant(                                          // ★ 215 行
    runnables[appKey],
    `"${appKey}" has not been registered. This can happen if: ...`
  );
  runnables[appKey](appParameters, coerceDisplayMode(displayMode));   // 真正挂载
}
```

**所以真机上那两行日志的含义，现在完全对得上了**：

```
I/ReactNativeJS: Running "RnBundleSplit"    ← ① base 跑完，它的 __r(入口) 触发 runApplication
I/ReactNativeJS: Running "BusinessApp"      ← ③ 目标组件挂载
```

> 顺带解释了那句经典报错 `"BusinessApp" has not been registered` 是**从哪来的** ——
> 就是上面这个 `invariant`。如果你把加载改成"懒加载"，③ 会在 ② 之前执行，
> `runnables["BusinessApp"]` 还不存在 → 立刻报这个错。
> **这就是"为什么必须预加载、懒加载不能简单改"的根因。**

---

## 6. 回顾：整条链路的分工

| 阶段 | 谁在做 | 干了什么 |
|---|---|---|
| 1 | `ReactActivity.onCreate` | 把组件名 `"BusinessApp"` 传进 delegate |
| 2 | `ReactNativeHost.getReactInstanceManager()` | **惰性创建** → 调到我们的 `createReactInstanceManager()` |
| 3 | `MainApplication` | 把 `MultiBundleLoader` 装进 builder |
| 4 | `createReactContextInBackground` 分支 | 因为 dev support 关闭 → 走 bundle loader 分支（不去连 Metro） |
| 5 | 后台线程 `create_react_context` | 初始化 native modules / UI manager |
| 6 | `catalystInstance.runJSBundle()` | **只调一次** `mJSBundleLoader.loadScript()` |
| 7 | ★ `MultiBundleLoader` | 在这"一次"里加载两段脚本 |
| 8 | JNI → C++ | 剥 `assets://`（9 字符）→ 读 asset → 判类型 → `loadScriptFromString` |
| 9 | `NativeToJsBridge::loadBundle` | 两段各自 **push 进同一条 JS 队列**（FIFO） |
| 10 | `JSIExecutor::loadBundle` | `evaluateJavaScript` → Hermes 真正执行 |
| 11 | `setupReactContext` → `runApplication` | 又一个 JS 调用，**排在那两段之后** |
| 12 | `AppRegistry.runApplication` | 查 `runnables` → 渲染 → 打出 `Running "BusinessApp"` |

---

## 7. 三个"为什么"（面试常问）

**Q：为什么一个 loader 能加载两个 bundle？**
A：因为 RN 只要求 `loadScript()` 被调一次，但**没规定里面只能加载一个**。
`JSBundleLoader` 是个抽象类，我们只是重写了它的方法体。

**Q：为什么顺序是确定的？**
A：两次加载都走 `runOnExecutorQueue` → 同一条 `MessageQueueThread` → FIFO。
base 先 push，就必然先执行。**不靠同步执行来保证顺序。**

**Q：为什么不能改用同步加载？**
A：`loadBundleSync` 没有 `runOnExecutorQueue`，它**绕过 JS 队列**在调用线程直接执行。
绕过之后就和后续的 JS 调用（比如 `runApplication`）失去了先后保证。
异步虽然"看起来慢一点"，但它保证了**所有 JS 调用排在同一条队伍里**，这才是正确的。

---

## 附：自查命令（想自己验证的话）

```bash
RNR=host-app/node_modules/react-native

# 1. runJSBundle 里那个"只调一次"的断言
grep -n "mJSBundleLoader.loadScript" \
  $RNR/ReactAndroid/src/main/java/com/facebook/react/bridge/CatalystInstanceImpl.java

# 2. assets:// 就是 9 个字符（C++ 侧硬剥）
grep -n "kAssetsLength" \
  $RNR/ReactAndroid/src/main/jni/react/jni/CatalystInstanceImpl.cpp

# 3. 异步分支 vs 同步分支（同步那条没有 runOnExecutorQueue）
sed -n '/void NativeToJsBridge::loadBundle(/,/^}/p' $RNR/ReactCommon/cxxreact/NativeToJsBridge.cpp
sed -n '/void NativeToJsBridge::loadBundleSync/,/^}/p'  $RNR/ReactCommon/cxxreact/NativeToJsBridge.cpp

# 4. "Running \"X\"" 日志与 "has not been registered" 报错同源
sed -n '201,225p' $RNR/Libraries/ReactNative/AppRegistry.js
```
