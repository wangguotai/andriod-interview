# rn-bundle-split 实现说明

> 这份文档讲**怎么实现的**：拆分的原理、关键取舍的理由、以及每处判断的源码依据。
> 真机运行结果与复现步骤见 [`android-multibundle-demo.md`](./android-multibundle-demo.md)。

---

## 0. 要解决的问题与不变量

RN 默认把整个 App 打成**一个** bundle。要做「基础包 + 业务包」，绕不开三件事：

1. **两个工程的 module id 必须算得一模一样** —— base 工程和业务工程是两个独立进程、
   两套 node_modules、两张依赖图，但业务包必须能引用 base 里注册的模块。
2. **业务包里不能重复放下 react/react-native** —— 否则拆了个寂寞（base 本体才是体积大头）。
3. **运行时只有一个** —— 两个 bundle 要能塞进同一张全局模块表且不互相踩踏。

整个实现围绕下面这条不变量展开：

> **module id 是「文件身份」的纯函数，与打包顺序、工程、机器路径都无关。**
> 只要两个工程用同一个函数，就无需共享任何运行时状态即可算出同一张 id 表。

---

## 1. 地基：跨工程一致的 module id

### 算法

```
id = parseInt(sha1(key).slice(0, 8), 16) % 2^20
```

实现：[`lib/moduleIdFactory.js:28-31`](../packages/rn-bundle-split/lib/moduleIdFactory.js#L28)。

**为什么不能用 metro 默认的自增 id**：默认 `createModuleIdFactory` 按「模块被访问的顺序」
发号。两个工程的依赖图不同，访问顺序必然不同，同一个文件会拿到不同 id，映射无从谈起。

**为什么是 `2^20`**：metro 的 `unpackModuleId` 用 `moduleId >>> 16` 取 segmentId、
`moduleId & 0xffff` 取 localId。低 16 位属于「段内局部 id」，所以把 id 限制在 20 位以内
（低 16 位 + 4 位裕量），既远离有符号 32 位溢出，也不与 segment 打包约定打架。

### key 的编址规则（这是最容易被忽略的设计点）

光有哈希不够 —— 如果 key 就是「工程内相对路径」，那么两个工程里同名的 `index.js`、
`App.tsx`、`app.json` 会算出**同一个 id**，业务工程的入口会被误判成「base 里已经有了」
而被整个过滤掉（入口消失，运行时直接找不到模块）。

所以 [`lib/util.js:63-72`](../packages/rn-bundle-split/lib/util.js#L63) 把 key 分了两类：

| 模块位置 | key | 效果 |
| --- | --- | --- |
| `node_modules/**`（react / react-native / 三方库） | 相对路径本身 | **跨工程共享**：两工程算出同一 id，靠 base 提供注册 |
| 其余（工程自有源码） | `${appId}/相对路径` | **按工程隔离**：同工程内稳定，跨工程天然不同 |

`appId` 默认从 `package.json` 的 `name` 推导（[`util.js:78-90`](../packages/rn-bundle-split/lib/util.js#L78)），
避免人工维护。代价是**同名工程会冲突**，此时需要 `--app-id` 显式指定。

### 代价：哈希取模会碰撞，所以必须报错而不是静默

489 个模块时碰撞概率约 `n²/(2·2^20) ≈ 0.11%` —— 量级可观，不能忽略。因此：

- 产出 mapping 时显式检测碰撞，有碰撞直接**抛错并提示换 `--id-salt`**（[`build.js:216-225`](../packages/rn-bundle-split/lib/build.js#L216)）；
- 读 mapping 时二次校验 id 唯一性（[`mapping.js:138-143`](../packages/rn-bundle-split/lib/mapping.js#L138)）。

宁可构建失败，也不出一个 id 悄悄指错的 bundle。

---

## 2. base 包：怎么产出

走的是**和 RN CLI 完全同一条路径** —— `metro/src/Server` 的 `build()`（[`build.js:89-131`](../packages/rn-bundle-split/lib/build.js#L89)），
只额外覆写 `serializer.createModuleIdFactory`。这样 transformer / resolver / minify 行为
与标准打包一致，不会走出一个「只有本工具认识」的怪 bundle。

一个必要的复刻：RN CLI 会通过 `getOverrideConfig` 强制注入 `InitializeCore` 与 core polyfills
（见 `@react-native/community-cli-plugin/dist/utils/loadMetroConfig.js`）。我们自己调
`loadConfig` 时必须做同样的事，否则 base 里缺初始化代码，业务包再跑也起不来
（[`metro.js:96-140`](../packages/rn-bundle-split/lib/metro.js#L96)）。

产出的 base 是**完整可执行**的：

```
[prelude + polyfills]  [__d ... × 489]  [__r(InitializeCore); __r(entry);]
```

对应的 mapping（[`mapping.js:19-70`](../packages/rn-bundle-split/lib/mapping.js#L19)）记了：
`schemaVersion / platform / buildType / appId / dev / minify / hermes / globalPrefix`,
以及最关键的 `base.modules = { "<key>": <id>, ... }`（489 条），外加 `bundleSha1` 等指纹。
**mapping 一旦生成，就是业务工程唯一需要知道的东西** —— 业务工程不需要 base 源码、
不需要 base bundle、不需要 node_modules 版本对齐。

---

## 3. 业务包：剥离算法

### 3.1 必须 `modulesOnly`，绝不能让业务包带 prelude

base 与业务包共用同一张全局模块表（metro-runtime 的 `modules` Map，由 prelude 的
`var modules = clear()` 创建，被 `global.__d` / `global.__r` 闭包持有）。

若业务包也带 runtime prelude，`__c()` 会执行 `modules = new Map()`，
把 base 注册的几百个模块**整个清空** —— 业务代码一 `require('react-native')` 就崩。

所以业务包构建时 `modulesOnly: true`（[`split.js:87-98`](../packages/rn-bundle-split/lib/split.js#L87)）：
只输出 `__d` 定义，不输出 prelude，也不输出 `__r(entry)`（入口启动由我们最后手工追加）。

### 3.2 剥离只删「定义」，保留「引用」

这是整个方案最容易做错的地方。业务工程接入时通过 preset 覆写 serializer 两处
（[`preset.js:56-109`](../packages/rn-bundle-split/lib/preset.js#L56)）：

- `createModuleIdFactory`：先查 base mapping 表，查不到再用同一套哈希算（业务新增模块）；
- `processModuleFilter`：把**已在 base 里**的模块从产物中滤掉。

关键在于 `processModuleFilter` 返回 `false` 时，**只剔掉模块定义本身**，
模块 `dependencyMap` 里指向 base 的 id **必须保留**：

```js
// 业务包里剩下的东西
__d(function(...){...}, 868085, [572174, 975589, 632901, 172571, 1021812, 554779, 591196])
                              ^^^^^^ 业务模块   ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ 这些 id 在 base 里
```

因为编译产物靠 `r(下标)` 取依赖，把 depMap 里的 base id 也删掉的话，
运行时 `r(下标)` 会拿到 `undefined` 并崩溃。

**验证过这条链路**（用生成的 mapping 反查业务包的依赖引用）：

| 被引用 id | 归属 |
| --- | --- |
| 572174 | base: `@babel/runtime/helpers/interopRequireDefault.js` |
| 632901 | base: `react/index.js` |
| 172571 | base: `react-native/index.js` |
| 554779 | base: `react/jsx-runtime.js` |
| 975589 | base: `@babel/runtime/helpers/slicedToArray.js` |
| 591196 / 868085 / 938847 / 1021812 | 业务包自己定义 |

业务包 4.8 KB / base 868 KB = **0.5%**，489 个 base 模块定义残留 **0** 个。

### 3.3 靠 AST 做外科手术，不做代码生成

`@babel/parser` 把 bundle 解析成 AST 后**只取 `range`（区间）**，
用 splice 精确摘除 `__d` 语句、改写 `dependencyMap`（[`inspect.js:9-12`](../packages/rn-bundle-split/lib/inspect.js#L9)、
[`strip.js:129-137`](../packages/rn-bundle-split/lib/strip.js#L129)）。

为什么不自己写字符扫描器：minified bundle 里字符串、模板串、正则字面量中都可能出现
`__d(function`、`)`、`}` 这些片段，手写括号配对必然踩坑。

为什么不用 AST 重新生成代码：**重生成会改变全部原有字节、破坏 sourcemap 的行映射**。
只取区间做 splice，可以做到「除了被摘掉的那几段，其余字节原样不动」。

### 3.4 兜底裁剪 + 自检

preset 正常生效时 `removed` 应该是 0。如果工程的 `customSerializer` 把 preset 覆盖掉了，
`stripBaseModules` 会把 base 定义再摘一遍并**告警**（[`split.js:138-155`](../packages/rn-bundle-split/lib/split.js#L138)）。
裁剪后立刻重新解析做自检：还有 base 定义残留 → 直接抛错（[`strip.js:153-162`](../packages/rn-bundle-split/lib/strip.js#L153)）。

---

## 4. 为什么两个 bundle 能进同一个运行时

### JS 侧：重复 `__d` 被静默去重，prelude 不会清表

- `define` 里第一件事就是 `if (modules.has(moduleId)) return;`
  —— **先到先得**，base 先注册的不会被业务包覆盖
  （`metro-runtime/src/polyfills/require.js:30-31`）。
- `modules` 的清空只发生在 prelude 执行时（`clear()` → `modules = new Map()`）。
  业务包是 `modulesOnly`、**不含 prelude**，所以**永远不会清表**。

### 原生侧：`loadScript` 只调一次，且没有状态重置

- `ReactInstanceManager` 在一次运行里只调 `catalystInstance.runJSBundle()` 一次
  （`ReactAndroid/.../ReactInstanceManager.java:1463-1464`）。
- `Instance::loadScriptFromString` 不清空任何状态，只是把一次 `evaluateJavaScript`
  投进 JS 线程队列（`ReactCommon/cxxreact/Instance.cpp:160-167`）。

所以「换一个 loader 在一个 loader 里加载两段脚本」是成立的 —— 第二段就是往已有运行时里再灌一段 JS。

### 顺序：靠同一条 JS 队列的 FIFO，不靠同步执行

两次加载都传 `loadSynchronously = false`：

- `false` → 走 `NativeToJsBridge::loadBundle`，内部 `runOnExecutorQueue(...)`
  投递到 `m_executorMessageQueueThread`（`NativeToJsBridge.cpp:129-131, 290-310`）。
  两次投递进**同一条队列**，先入先出 → base 的 prelude / `__r(InitializeCore)` 必然先执行。
- `true` → 走 `NativeToJsBridge::loadBundleSync`，**在调用线程上直接执行 JS**
  （`NativeToJsBridge.cpp:153-167`）。从主线程调它等于在 UI 线程上跑整个 bundle。

RN 自带的 split bundle 加载器（`JSBundleLoader.createCachedSplitBundleFromNetworkLoader`
→ `loadSplitBundleFromFile`）同样传 `false`，思路一致。

> 这里我一开始想用 `true` 来「强行保证顺序」，读源码后改掉了 —— 顺序本来就由队列保证，
> 用 `true` 反而引入「UI 线程跑 bundle」的隐患。

---

## 5. 原生 demo 的接线

### 一个 loader 加载两段

[`MultiBundleLoader.kt:38-47`](../host-app/android/app/src/main/java/com/rnbundlesplit/MultiBundleLoader.kt#L38)：

```kotlin
delegate.loadScriptFromAssets(context.assets, "assets://index.android.bundle", false)
delegate.loadScriptFromAssets(context.assets, "assets://$businessAssetName", false)
```

### 注入 loader 的两个坑

1. `setJSBundleLoader` 会**清掉** `mJSBundleAssetUrl`，反过来 `setBundleAssetName` 会清掉
   `mJSBundleLoader`（`ReactInstanceManagerBuilder.java:99-101, 125-127`）。
   所以 loader 必须在 asset 名之后设。
2. `build()` 有个断言：`mUseDeveloperSupport || mJSBundleAssetUrl != null || mJSBundleLoader != null`
   （`ReactInstanceManagerBuilder.java:332`）—— 三者至少有一个，否则起不来。

复用父类 `getBaseReactInstanceManagerBuilder()`（已组装好 application / packages / JS 引擎 /
dev support），只覆盖 bundle 来源（[`MainApplication.kt:53-62`](../host-app/android/app/src/main/java/com/rnbundlesplit/MainApplication.kt#L53)）。

### 为什么关掉 dev support

若 `getUseDeveloperSupport()` 返回 `true`，`recreateReactContextInBackgroundInner()`
会在 `mUseDeveloperSupport && mJSMainModulePath != null` 成立时**优先去连 Metro**
（packager 在跑就下载并改用缓存包），把我们装的 loader 绕过去。
关掉后 debug 也固定走 assets，可离线复现。**代价是没有 RedBox / Dev Menu**，
JS 报错只能看 logcat（已挂 `JSExceptionHandler` 打到 `RnBundleSplit` tag）。

### 两个界面、一个运行时

不引入导航库，而是**两个 launcher Activity 共用同一个 `ReactNativeHost`**
（`MainActivity` → `"RnBundleSplit"`，`BusinessActivity` → `"BusinessApp"`）。
界面能起来本身就证明了跨 bundle 链路是通的。

---

## 6. 可验证性：三层判据

「业务包里不含 base 内容」是**可复算**的，不靠嘴上说（[`lib/verify.js`](../packages/rn-bundle-split/lib/verify.js)）：

| 层 | 判据 | 结果 |
| --- | --- | --- |
| L1 定义层 | 业务包里不得存在 `id ∈ base` 的 `__d` | base 489 个定义残留 **0** 个 |
| L2 体积层 | 业务包必须显著小于 base（默认 < 50%） | 4.8 KB / 868 KB = **0.5%** |
| L3 拼接执行 | 把 base 定义段 + 业务包拼起来在 node 里真跑一遍 | 注册 4 个模块；入口能沿 `业务 → base → react-native` 解析 |

L3 是最有价值的一层：前两层只看静态文本，「拼接执行」才能真正暴露「id 对不上」
「依赖取到 undefined」这类问题。node 下没有原生模块，所以入口会停在
`__fbBatchedBridgeConfig is not set` —— 这属于**预期**，说明 JS 层模块链路已经通了。
真机上不会有这个错（已在设备确认）。

---

## 7. 已知边界（诚实清单）

### 7.1 只支持桥接模式（旧架构）

`newArchEnabled=false`。bridgeless 下走的是 `ReactHost.loadBundle`，路径不同，
本 loader 的写法不适用。

### 7.2 业务包独立加载其实**不行**（已验证）

`strip.js` 里的 runtime 垫片注释说它能让拆分产物「单独加载/单测」，但在 `split` 命令
这条路径上**实际是 no-op**：

- 垫片被追加在文件**末尾**（[`split.js:131-133`](../packages/rn-bundle-split/lib/split.js#L131)）；
- 而 `__d` 定义在文件**开头**。业务包单跑时，第一行 `__d(...)` 就已经报
  `ReferenceError: __d is not defined`，垫片根本没机会生效；
- 与 base 一起跑时，垫片发现 `g.__r` / `g.__d` 已存在 → 早返回，也是 no-op。

复现：

```bash
node -e "const vm=require('vm'),fs=require('fs');
const sb={console}; sb.global=sb; sb.globalThis=sb;
try{vm.runInNewContext(fs.readFileSync('business-app/output/business.android.release.bundle','utf8'),sb)}
catch(e){console.log(e.constructor.name, e.message)}"
# -> ReferenceError __d is not defined
```

要真的支持单独加载，垫片得插到文件最前面（`stripBaseModules` 的 `shim: true` 分支就是这么做的，
但 `split` 传了 `shim: false`）。

### 7.3 mapping 只按路径索引，不校验内容

如果 base 侧某个**共享模块的源码改了**而 mapping 没重新生成，业务包里被剥离的那份会
**静默使用 base 的旧实现**。计划补 `sourceHashes` 字段并在不一致时告警 —— 尚未实现。

### 7.4 其他

- monorepo 里不在 `node_modules` 的 workspace 包（相对路径形如 `../pkg/...`）不参与共享，
  会被两个工程各自重复打包。需要共享时软链/安装进 `node_modules`。
- `.hbc` 字节码路径：`hermesc` 编译的代码路径写了（`build.js:134-169`），但业务包
  **不能用 `.hbc`** —— HBC 是二进制字节码，**无法拼接**。要走 HBC 得改成 RAM bundle /
  segment 机制，未实现。
- 业务包是启动时**预加载**，懒加载未实现。
  （懒加载的障碍：`ReactActivity.onCreate → loadApp()` 会在业务包尚未注册时就去
  `runApplication("BusinessApp")`，直接报 "Application has not been registered"。）

---

## 8. 文件索引

```
packages/rn-bundle-split/
├── bin/rn-bundle-split.js        CLI 入口（base/init/split/verify/inspect/uninit）
└── lib/
    ├── util.js                   路径归一化、module key 编址规则、appId 推导
    ├── moduleIdFactory.js        id = sha1(key) % 2^20 + 碰撞检测
    ├── mapping.js                mapping 读写 / 校验 / schema
    ├── metro.js                 复用工程 config + 复刻 CLI 的 polyfill 注入
    ├── build.js                  base 命令（含 hermesc 调用）
    ├── split.js                  业务包命令（modulesOnly + 追加 shim/入口）
    ├── preset.js                 业务工程侧 serializer 覆写（查表 + 过滤）
    ├── strip.js                  兜底裁剪 + runtime 垫片 + 自检
    ├── inspect.js                @babel/parser 取 AST range
    ├── verify.js                 L1/L2/L3 判定
    ├── commands.js               init / uninit
    └── config-overlay.js         软链工具 + 包装 metro.config.js（可撤销）

host-app/
├── android/app/src/main/java/com/rnbundlesplit/
│   ├── MultiBundleLoader.kt     核心：一个 loader 顺序加载两个 bundle
│   ├── MainApplication.kt       注入 loader、关闭 dev support
│   ├── MainActivity.kt          base 组件入口
│   └── BusinessActivity.kt      业务组件入口
└── android/app/src/main/assets/ 两个 bundle（脚本生成，已 gitignore）

business-app/                    业务工程（接入方式 = 一个 config + preset 包装）
scripts/prepare-android-demo.sh  一键 base → split → verify → 摆 assets
```
