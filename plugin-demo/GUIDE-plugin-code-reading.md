# 插件化 Lab · 代码阅读指南

> 目标：**30~45 分钟**把宿主侧装载器读懂，并且知道每一处「为什么这么写」。
> 按依赖顺序排，**不要跳着读** —— 后面每个类都建立在前一个的假设上。
>
> 配套：[`NOTES-plugin-lab.md`](NOTES-plugin-lab.md)（取舍与实测）、
> [`USAGE-plugin-lab.md`](USAGE-plugin-lab.md)（怎么跑）。

---

## 0. 先建立三个心智模型

读代码前先记住这三句，后面所有细节都挂在这三根钉子上。

1. **插件是一个"资源组合"，不是一个类。**
   `dex 的 ClassLoader + apk 的 AssetManager + 一个入口实例`
   三者必须**一起建、一起弃**。所以有 `LoadedPlugin` 这个"捆"。

2. **"独立打包"是可证伪的，验的方式是 identity。**
   不是"能不能打开插件页"，而是：
   插件类的 loader 是不是**另一个**？宿主 `Class.forName` 是不是**失败**？

3. **资源隔离靠的是"名字"，不是"查不查得到"。**
   同一个 ID 在两张表里都存在，只是指向不同资源（见 NOTES 第三节）。

---

## 1. 阅读顺序

```
① contract/IPluginPage.kt          ← 唯一的共享契约（60 行，先看它）
② PluginManifest.kt                ← 元数据解析 + sha256 校验
③ PluginClassLoader.kt             ← 委派规则（为什么必须委派）
④ PluginResources.kt               ← 资源隔离的全部设计取舍（最长，也最值得读）
⑤ PluginAssets.kt                  ← 两个命名空间的读法
⑥ PluginLoader.kt                  ← 三条来源 → 一条装载路径
⑦ LoadedPlugin.kt                  ← 生命周期、控制器形态、Activity 形态
⑧ PluginExperiments.kt             ← 五个可证伪实验
⑨ PluginLabActivity.kt             ← 把这些串成页面（与上面所有文件同目录）
```

### ① `contract/IPluginPage.kt` —— 唯一的共享契约

三个方法：`onCreate(pluginContext, container, networkPayload)` / `onDestroy()` / `describe()`。

关键点：**这份源码被编进两个 dex**（plugin-demo 的 build.gradle.kts 里有一行 `srcDir`
把 `app/src/main/java/com/interview/plugin/contract` 加进来）。所以读 ③ 时要带着
"两个 dex 里各有一份同名接口"这个前提。

注意 `networkPayload` 是**传进来**的：插件自己没有网络能力也没有权限。
这让"网络接口"这条链路与"插件零依赖"不冲突。

### ② `PluginManifest.kt` —— 元数据是契约文件

解析 `plugin-package.xml`，做 sha256 校验。看两个地方：

- 为什么校验写在这里而不是下载处 —— 因为**离线来源也应该受同一份约束**；
- 校验失败时**不**回退到上次的缓存 —— 那会让"服务端发错包"变成静默行为。

### ③ `PluginClassLoader.kt` —— 委派规则是这里唯一的考点

前缀列表（`com.interview.plugin.contract.` / `kotlin.` / `android.` / `androidx.` …）
决定哪些类交给宿主 parent。

问自己一个问题：如果**不**委派 `com.interview.plugin.contract.`，会发生什么？
答案（也是为什么这段代码存在）：

> 插件 dex 里那份 `IPluginPage` 会被用它自己的 ClassLoader 加载 ⇒
> 宿主有**两个不同的 `IPluginPage` Class 对象** ⇒ 转换必然 `ClassCastException`。

`describe()` 里打印的两行"本类的加载器 / 共享接口的加载器，两者是否同一加载器"
就是这条命题的运行时证据。

### ④ `PluginResources.kt` —— 全篇最值得读的文件

读的时候按这个顺序看，每一步都对应一个曾经的错误：

1. 文件头 §一：讲清 `0x7f` 为什么导致"同一 ID、两张表"；
2. §二：API 31 前后的三处差异（每个都能用 android.jar 复核）；
3. `probeIsolation()` 的长注释：**最初写错了什么、真机跑出来是什么、
   为什么"名字"才是判据**；
4. `newEmptyAssetManager()` / `addAssetPath()` / `newResources()`：
   三个反射点，以及**为什么刻意不做 hidden-api 绕过**。

### ⑤ `PluginAssets.kt` —— 两个命名空间

`readText()`（assets）与 `readPackageRootFd()`（包根）是**两个不同的 API**。
`selfCheck()` 里放了一条**注定失败**的对照（拿 `open` 去读包根文件）——
那是为了把"命名空间"变成每次运行都会重演一次的事实。

### ⑥ `PluginLoader.kt` —— 三条来源汇聚到一条路径

看 `Source` 枚举和 `load()` 的 `when`：三条来源产出的都是
`(File, PluginManifest?, String?)`，然后统一走 `loadFromFile()`。

- `stageFromAssets()`：为什么必须**拷贝**（`DexClassLoader` 要真实路径去 mmap）；
- `fetchFromNetwork()`：为什么**元数据在前**、为什么只有 https；
- `locateInPrivateDir()`：为什么把"所有候选路径 + 存在与否"都打出来。

### ⑦ `LoadedPlugin.kt` —— 生命周期与两种入口形态

- `release()` 的诚实边界：断开引用 ≠ ART 内部表立刻清空（同 dex 路径有缓存）；
- 控制器形态（`instantiatePage()`）：推荐路径 —— 插件类实现 `IPluginPage`，
  宿主把 `pluginContext` + 容器 `ViewGroup` 交给它；
- Activity 形态（`attachActivity()`）：复用宿主自己的 `PluginLabActivity` 作占坑 stub，
  + 反射 `Activity.attach`，以及"为什么拿不到自己的 token"。

### ⑧ `PluginExperiments.kt` —— 五个可证伪实验

每个实验的注释里都写了**反面会观察到什么**。重点看：
D（宿主加载不到插件类，且区分 `ClassNotFoundException` 与 `NoClassDefFoundError`）
和 E（同 ID 在两张表里解析出不同名字）。

### ⑨ `PluginLabActivity.kt` —— 收口

把来源选择、策略选择、五个实验、日志展示串成一个页面。
看它是怎么把"过程"（`PluginLoadLog`）与"结果"（`LoadedPlugin`）分开的 ——
日志独立成类，是为了不把 `resources` 变成可空字段（见 `LoadedPlugin.kt` 文件头）。

---

## 2. 想动手改的话，从这几处开始

| 想验证什么 | 改哪里 |
|---|---|
| "库真的没编进宿主" | 加一条 `PluginLoadInstrumentedTest` 断言（照着实验 D 写） |
| 换个插件包不用重装宿主 | `tools/plugin-package/deploy.sh` → 页面选「私有目录」 |
| 资源隔离失效会长什么样 | 页面切到 `SHARED_HOST_RESOURCES` 策略，看隔离对照变红 |
| 委派规则少一项会怎样 | 从 `PluginClassLoader` 的前缀列表里删掉 `contract.`，观察 `ClassCastException` |

改完记得跑：

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.interview.plugin.PluginLoadInstrumentedTest
```
