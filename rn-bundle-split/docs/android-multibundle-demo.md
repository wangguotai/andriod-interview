# Android 原生多 bundle 加载 demo

这份文档对应 todo 里的第 6 项：**在真机上证明「base bundle + 业务 bundle 加载进同一个 JS 运行时」**。

前面几步已经在 Node 侧把「拆分」本身验证过了（业务包里不含 base 内容、依赖引用完整）。
但 Node 里没有原生桥，L3 只能停在「缺原生模块」处。这一步补的是**真机证据**：
两个 bundle 真的先后进了同一个 Hermes 运行时，业务组件真的跑起来了。

---

## 1. 结论先行（真机实测）

设备：Xiaomi M2012K11AC (alioth, arm64)，Android，debug 构建。

| 现象 | logcat 证据 |
| --- | --- |
| base bundle 执行成功 | `I/ReactNativeJS: Running "RnBundleSplit"` |
| 业务 bundle 在**同一进程**执行成功 | `I/ReactNativeJS: Running "BusinessApp"`，pid 与上面相同 |
| 业务界面真的渲染出来 | 截图 OCR：`业务包（Business Bundle）` / `level: gold` / `score = 30` / `统计：命中 7 / 未命中 3` |
| 交互也是活的（不只是静态首屏） | 点一下界面上的「命中 +1」按钮：`score = 30 → 33`、`命中 7 → 8`、`B · 30 → B · 33` |

「`Running "BusinessApp"`」这一行是核心证据。它意味着：

1. 业务 bundle 里的 `AppRegistry.registerComponent('BusinessApp', ...)` 执行了；
2. 而 `AppRegistry` 本身住在 base bundle 里 —— 业务代码能拿到它，说明
   **业务包对 `react` / `react-native` 的 `require` 是落在 base 的模块表上的**；
3. RN 原生侧随后能按名字启动这个组件，说明注册结果对宿主可见（跨 bundle 生效）。

而且它不是一个「静态首屏」的假象：界面上的分数是**业务包自己的** `src/logic.js` 算出来的
（`summarizeResult` / `formatScore`），点击「命中 +1」会走业务包的 `useState` 重新计算并重渲染
（`score = 30 → 33`、`命中 7 → 8`）。也就是说，**React 的运行时来自 base，业务逻辑来自业务包，
两者在同一次渲染里协同工作**。

---

## 2. 它是怎么工作的

### 2.1 加载顺序

[`MultiBundleLoader.kt`](../host-app/android/app/src/main/java/com/rnbundlesplit/MultiBundleLoader.kt)
重写了 `JSBundleLoader.loadScript()`，在一个 loader 里顺序加载两个 asset：

```kotlin
delegate.loadScriptFromAssets(context.assets, "assets://index.android.bundle", false)
delegate.loadScriptFromAssets(context.assets, "assets://business.android.bundle", false)
```

`ReactInstanceManager` 只创建一次 JS 运行时，`loadScript` 也只被调用一次
（`catalystInstance.runJSBundle()`），所以两个 bundle 天然落在同一个运行时里。

### 2.2 为什么能「追加」而不互相踩踏

两层原因，缺一不可：

- **原生侧**：`Instance::loadScriptFromString` 只是往 JS 线程队列投递一次
  `evaluateJavaScript`，**没有任何状态重置**。第二次求值就是往已有运行时里再灌一段脚本。
- **JS 侧**：业务包是 `modulesOnly` 产物，**通篇不含任何 prelude** ——
  实测业务包里连 `__c(` 都搜不到。清表只发生在 prelude 执行时（`clear()` → `modules = new Map()`），
  所以业务包**根本没有机会清空 base 的模块表**。业务包的 `__d(...)` 于是追加进 base 建好的
  同一张全局模块表（`define` 第一行就是 `if (modules.has(n)) return;`，id 重复则**先到先得**，
  base 注册的不会被业务包覆盖）。

  > 补充：业务包尾部确实还有一段 runtime 垫片（`split` 命令追加的），它发现 `g.__r` / `g.__d`
  > 已存在就早返回。但那是**兜底**，不是本机制成立的原因 —— 真正起作用的是「不含 prelude」。
  > 详细分析见 [implementation.md 第 7.2 节](./implementation.md#72-业务包独立加载其实不行已验证)。

这也是业务包里那些 `__d` 只定义「业务自己的 4 个模块」、却把 `react` / `react-native`
的 id 留在 `dependencyMap` 里的原因 —— 引用要保留，注册由 base 提供。

用 mapping 反查一下业务包的依赖引用，能直接看到这条链路：

```
572174  base: node_modules/@babel/runtime/helpers/interopRequireDefault.js
632901  base: node_modules/react/index.js
172571  base: node_modules/react-native/index.js
554779  base: node_modules/react/jsx-runtime.js
975589  base: node_modules/@babel/runtime/helpers/slicedToArray.js
1021812 业务包自定义（base 无此 id）
 591196 业务包自定义（base 无此 id）
 868085 业务包自定义（base 无此 id）
 938847 业务包自定义（base 无此 id）
```

### 2.3 两个界面、一个运行时

demo 没有引入导航库，而是走了最朴素的办法：**两个 launcher Activity 共用同一个 `ReactNativeHost`**。

- `MainActivity` → 组件 `"RnBundleSplit"`（base bundle 里注册的）
- `BusinessActivity` → 组件 `"BusinessApp"`（业务 bundle 里注册的）

两者都从 `MainApplication` 拿同一个 `reactNativeHost`，所以运行时只建一次。

> 为什么不用 `loadSynchronously = true` 来「强行保证顺序」：该分支走
> `Instance::loadBundleSync`，会在**调用线程**上直接执行 JS。从主线程调它 =
> 在 UI 线程上跑整个 bundle。用 `false` 时两次求值都进同一条 JS 队列、先入先出，
> 顺序本来就是确定的。RN 自带的 split bundle 加载器也传 `false`。

---

## 3. 怎么复现这个 demo

### 3.1 一键准备产物

```bash
bash scripts/prepare-android-demo.sh
```

脚本做三件事：

1. `base` 命令：在 `host-app` 产出 base bundle + `mapping.json`（489 个模块）；
2. `split` 命令：业务工程按 mapping 剥离 base 内容，产出只含业务代码的包（4 个模块，~5KB）；
3. `verify` 命令：跑 L1/L2/L3 校验，然后把两个 bundle 摆进
   `host-app/android/app/src/main/assets/`
   （分别是 `index.android.bundle` 与 `business.android.bundle`）。

### 3.2 构建与安装

```bash
cd host-app/android
./gradlew :app:assembleDebug -PreactNativeArchitectures=arm64-v8a
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

构建前确认 `JAVA_HOME` 指向 JDK 17（RN 0.75 不接受 JDK 18+）。
本机路径：`/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home`。

### 3.3 跑起来

装好后桌面会出现**两个图标**：

- `RnBundleSplit` → 渲染 base bundle 的组件
- `BusinessApp (业务包)` → 渲染业务 bundle 的组件

也可以直接命令行拉起，然后看日志：

```bash
adb logcat -c
adb shell am start -n com.rnbundlesplit/.MainActivity    # 期望: Running "RnBundleSplit"
adb shell am start -n com.rnbundlesplit/.BusinessActivity # 期望: Running "BusinessApp"
adb logcat -d | grep ReactNativeJS
```

---

## 4. 几个刻意的取舍（以及代价）

| 取舍 | 原因 | 代价 |
| --- | --- | --- |
| debug 变体 + `getUseDeveloperSupport() = false` | 若为 true，`recreateReactContextInBackgroundInner()` 会优先去连 Metro，绕开我们装的 `MultiBundleLoader`。关掉后 debug 也固定走 assets，可离线复现。 | 没有 RedBox / Dev Menu，JS 报错只能看 logcat（已挂 `JSExceptionHandler` 打到 `RnBundleSplit` tag）。 |
| 用**纯 JS** bundle，不用 `.hbc` | Hermes 的 `evaluateJavaScript` 本来就接受 JS 源码（dev 模式就是这么干的）。纯 JS 可拼接、不受 HBC 字节码版本约束。 | 首屏要多一次解析；未享受 Hermes 预编译收益。`.hbc` 路径见 todo 第 8 项。 |
| 加载器里**预先**加载业务包，不做懒加载 | `ReactActivity.onCreate → loadApp()` 会在业务包尚未注册时就去 `runApplication("BusinessApp")`，直接报 "Application has not been registered"。 | 业务包体积虽小但仍在启动时加载；按需加载是后续扩展点。 |
| 复用 base 工程的 `applicationId`，业务包不用 Gradle 生成 | 两边必须共用同一张 hashed id 表，Gradle 自己 bundle 出来的 id 一定对不上。 | 业务 bundle 只能由本工具的 `split` 命令产出，不能走 `bundleReleaseJsAndAssets`。 |

### 关于 `.bundle` 被 zip 压缩这件事

构建产物里两个 bundle 都是 **deflate 压缩**存的（APK 里 868KB → 289KB），
一开始担心 `AAsset_getBuffer()` 拿不到连续内存。真机验证下来**没有问题**：
`AAssetManager_open(..., AASSET_MODE_STREAMING)` 会透明解压，`JSLoader.cpp` 的
`AssetManagerString` 正常读到了内容。也就是说 RN 这条路径不要求 `noCompress`。

---

## 5. 已知限制 / 后续

- **仅桥接模式（旧架构）**：`newArchEnabled=false`。bridgeless 下 `ReactHost.loadBundle`
  的路径不同，本 loader 的写法不适用。
- **仅 debug 演示**：`release` 同样能用（`getUseDeveloperSupport()` 本就是 false），
  但没为 release 单独跑过；且 `enableProguardInReleaseBuilds=false`。
- **mapping 目前按路径索引，不校验内容哈希**：若 base 侧某个共享模块的源码变了而 mapping
  没重生成，业务包里被剥离的那份会静默用 base 的旧实现。计划补 `sourceHashes` 字段并在
  不一致时告警（尚未实现）。
- **`.hbc` 字节码 + 开发模式**：见 todo 第 8 项。
- 之前 Node 侧出现过的 `Invariant Violation: __fbBatchedBridgeConfig is not set`
  属于「node 里没有原生桥」的预期报错，真机上不会出现 —— 已在真机确认。
