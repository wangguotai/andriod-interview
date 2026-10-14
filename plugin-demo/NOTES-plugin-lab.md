# 插件化 Lab · 设计取舍与实测数据

> 本文只写**能复核**的东西。每个结论都标注「凭什么」，以及「边界在哪」。
> 凡是没验证的，明确写"未验证"，不写成"应当可以"。

---

## 一、这个 Lab 在证什么

一句话：**插件可以是一个独立的 apk，宿主不编译它、不 import 它，
只在运行期用类加载把它请进来。**

拆成四条**可分别证伪**的命题：

| 命题 | 反面（如果命题是假的，会观察到什么） |
|---|---|
| 插件的类是**另一个** ClassLoader 加载的 | 若被编进宿主，`loadClass` 同样成功 —— 但 loader identity 会是宿主那个 |
| 宿主 ClassLoader **加载不到**插件类 | 若命题为假，宿主 `Class.forName` 会成功 |
| 插件的资源只有**插件的** Resources 认得 | 若共用宿主 Resources，插件布局会静默渲染成宿主的另一个布局 |
| 插件能读自己 apk 的 assets 与包根文件 | 若 `addAssetPath` 没挂上，读取会失败（且常常被静默 catch） |

四条对应 `PluginLoadInstrumentedTest` 里的断言，也对应页面上的实验按钮。

---

## 二、五个关键取舍（每个都否定了一个"更省事"的方案）

### 2.1 为什么不用 `:plugin-demo:assembleDebug` 的默认产物

library 模块的默认产物是 **AAR**：里面是 `classes.jar`，**不是 dex**，
也没有 `resources.arsc`。而"独立 dex + 独立资源表"恰好是要证的东西 ——
AAR 只能当**原料**。

于是 `tools/plugin-package/package.sh` 只借 AGP 做「编译」这一半，
组装那一半自己做（`d8` + `aapt2` + `zip`）。多写几十行 shell，换来三件 AGP 不给的事：

1. 真正的 `classes.dex` ⇒ "宿主 apk 里没有这些类"可以用 `dexdump` **外部核对**；
2. `resources.arsc` + `plugin-res-ids.txt` ⇒ 隔离对照有 ID 来源；
3. `plugin-package.xml` ⇒ 网络链路的契约文件。

### 2.2 为什么不用"共享 R 类"的方案

有一种做法是搞一个 contract 模块，宿主和插件都依赖它，共用一套 R 常量。
**不需要**：aapt2 给插件分配的 R 常量就内联在插件自己的字节码里，
永远与插件自己的 `Resources` 匹配。搞共享 R 反而引入一个"两边必须同步编译"的耦合。

真正需要共享的只有**接口**（`IPluginPage`），而且它的共享方式很讲究 —— 见 2.5。

### 2.3 为什么不用 `context.assets` + `addAssetPath`

那会把插件 apk 追加到**宿主的** AssetManager 上 —— 从那一刻起两张资源表**合并**了，
宿主从此能解析插件的 ID（也可能覆盖同名条目）。**隔离被摧毁**。

这条路径在本 Lab 里保留了，但明确标注为"仅作对照"（`Strategy.SHARED_HOST_RESOURCES`），
因为它能证明一件事：**隔离是被我们"选"出来的，不是碰巧得到的。**

### 2.4 为什么网络链路只认 HTTPS

插件包会被当代码加载。明文 http 下这条链路可被替换 ——
攻击者可以**同时**改 apk 与元数据，于是 sha256 校验形同虚设（它只能防传输损坏，
防不了中间人）。所以代码里直接 `require(url.startsWith("https://"))`。

代价是本地调试需要真实 HTTPS 服务，不能用 `adb reverse` + cleartext。

### 2.5 为什么 `IPluginPage` 必须委派给宿主

同一个接口源码被编进两个 dex。如果插件 dex 里那份**也**被使用，
宿主就会有**两个不同的 `IPluginPage` Class 对象** ⇒ 跨加载器转换必然
`ClassCastException`。

解法是 `PluginClassLoader` 的委派规则：以 `com.interview.plugin.contract.`、
`kotlin.`、`android.`、`androidx.` 等为前缀的类**一律交给宿主 parent**。
于是插件侧解析到的 `IPluginPage` 就是宿主那个 `Class` 对象，转换自然成立。

这条是**实测**出来的（实验 A）：Kotlin 的 object expression 会因为
宿主包名的符号而 `NoClassDefFoundError`，所以委派前缀必须覆盖全。

---

## 三、资源隔离的物理原因（本 Lab 最重要的结论）

### 3.1 最初写错了，而错的那版恰好是最有价值的一课

第一版实现的判定是："宿主 `getResourceName(id)` 抛 NotFoundException ⇒ 隔离成立"。
真机跑出来**宿主解析成功了** —— 一度看起来像隔离失效。

真实原因：资源 ID 的 package 段 `0x7f` 是**应用包固定值**，
宿主与插件各自从 `0x7f030000` 起编号，于是**同一个 ID 在两张表里都存在**，
只是指向不同资源。本仓库实测（`aapt2 dump resources`，两边离线对照）：

| ID | 插件表 | 宿主表 |
|---|---|---|
| `0x7f030000` | `layout/plugin_page` | `attr/SharedValue` |
| `0x7f010001` | `color/plugin_panel` | `anim/abc_fade_out` |
| `0x7f040000` | `string/plugin_activity_label` | `bool/abc_action_bar_embed_tabs` |
| `0x7f040001` | `string/plugin_from_layout` | `bool/abc_config_actionMenuItemAllCaps` |
| `0x7f040005` | `string/plugin_subtitle` | （无）→ `NotFoundException` |
| `0x7f040006` | `string/plugin_title` | （无）→ `NotFoundException` |

### 3.2 结论

- "ID 相同"**不能**用来判断资源归属；
- "宿主查不到"**也不是**隔离的判据（大多数 ID 它都查得到）；
- 能判断归属的**只有解析出来的名字**。

### 3.3 工程后果（这才是它值得写进文档的原因）

宿主若用**自己的** `Resources` 去 inflate 插件的 `R.layout.xxx`：

- 它**不会**抛异常；
- 它会安静地 inflate 成宿主的**另一个布局**；
- 结果是一个"点了没反应 / 界面变成别的样子"的 bug，**栈里没有任何异常**。

这就是"插件必须拿自己的 Resources"的物理原因 —— 不是一个风格建议。

---

## 四、`AssetManager` 的两个命名空间（曾经写错，已改）

`addAssetPath` 挂上 apk 之后，命名空间里多了三层。**第三层曾被写错**：

| 层 | 正确读法 | 曾经的错误写法 |
|---|---|---|
| `res/` 编译资源 | `Resources.getLayout(...)` 等 | — |
| `assets/` 原始文件 | `open("x.txt")` —— **前缀隐式** | `open("assets/x.txt")`（会去找 `assets/assets/x.txt`） |
| apk **包根**文件 | `openNonAssetFd("x.txt")` —— 且 zip 条目须 **STORED** | `open("x.txt")`（只查 assets，必然失败） |

依据不是记忆，是源码：`android-34/android/content/res/AssetManager.java` 里
`open()` → `nativeOpenAsset`，`openNonAsset()` → `nativeOpenNonAsset`，
**是两个不同的 native 入口**。

`openNonAssetFd` 是公开 API，但内部走 mmap，所以条目**未压缩**才读得到 ——
忘了会得到 `This file can not be opened as a file descriptor; it is probably compressed`。
打包脚本用 `zip -0` 单独写 `plugin-root-probe.txt` 就是为了这一条。

---

## 五、API 31 前后（每个论断都能用 android.jar 复核）

| 项 | 事实 | 影响 |
|---|---|---|
| `Resources(AssetManager, DisplayMetrics, Configuration)` | **API 31 才有**公开构造器（android-28/30 的 android.jar 里一个 public 构造器都没有） | API < 31 只能反射隐藏构造器 —— **本仓库真机是 API 31，该分支未真机验证** |
| `AssetManager.addAssetPath(String)` | `@hide @Deprecated`，至今 greylist（`unsupported, reflection, allowed`） | 这是链路上唯一没有公开替代的反射点 |
| `AssetManager()` 无参构造器 | android.jar 里是 **package-private** | "造一个只含系统资源的空 AM"也绕不开反射 |
| `AssetManager.Builder` / `ApkAssets` / `ResourcesLoader` | 全是 hide | `ResourcesProvider` 只能 load、不能挂载 |

**结论：资源加载这条链路，在 Android 上不存在纯公开 API 的实现路径。**
这不是本演示偷懒 —— 这是为什么"插件化"始终是一个需要逐版本维护的工程，
而不是一个写一次就忘的库。

---

## 六、插件 dex 的真实构成（`dexdump` 实测）

插件 dex 只定义了 **6** 个类：

```
Lcom/interview/plugindemo/BuildConfig;
Lcom/interview/plugindemo/PluginEntry;
Lcom/interview/plugindemo/PluginEntry$Companion;
Lcom/interview/plugindemo/PluginEntry$$ExternalSyntheticLambda0;
Lcom/interview/plugindemo/ui/PluginPageActivity;
Lcom/interview/plugin/contract/IPluginPage;     ← 共享契约源码，编译进来了但运行期被委派
```

**`kotlin/*` 类：定义 0 个**（只有极少数被**引用**，如
`kotlin.jvm.internal.DefaultConstructorMarker`）。

- 早期打包脚本的输出写着"kotlin-stdlib 由 kotlin 插件自动带上" —— **这行是错的**，
  已改成区分「定义」与「引用」两行统计。
- 真实情况：插件对 stdlib 的依赖是**运行时借宿主的**，不是打包带来的。
  之所以稳：`DexClassLoader` 的 parent 就是宿主 ClassLoader，
  `kotlin/*` 在宿主 dex 里已加载过，引用自然解析成功。
- 由此得到一条**真实约束**：插件与宿主必须用**兼容版本**的 kotlin-stdlib，
  否则会在运行期报 `NoSuchMethodError` 而不是编译失败。脚本里那两行统计
  就是判断该约束是否被违背的最快手段。

---

## 七、签名对本演示影响哪一层

`jarsigner` 对**两条加载链路都不起作用**：

- 类：`DexClassLoader` 不校验签名，dex 能打开就加载；
- 资源：`AssetManager.addAssetPath` 同样不校验。

真正需要签名的是 **Activity 形态** —— 它最终要经由系统的 ActivityManager
与 uid 校验路径（导出检查、权限继承）。脚本用 debug keystore 签名，
就是为了让"签名到底影响哪一层"能被**对照**出来。

---

## 八、Activity 形态（Phase 2）：诚实边界

控制器形态（`IPluginPage` + `ViewGroup`）是**推荐**路径，已随 Phase 1 交付。

Activity 形态走的是"宿主占坑 + 反射 `Activity.attach`"：
占坑用的就是宿主自己的 `PluginLabActivity`（它已在宿主 manifest 里注册，
天然被系统认识），运行期把插件的 `Activity` 实例**塞进**这个壳里，
于是它借用宿主的系统身份，插件视图挂进宿主传入的容器 `ViewGroup`。

- `Activity.attach(...)` 是 `final void`、**不在 android.jar 里**、且**逐版本变参**
  （API 34 比 31 多一个 `initialCallerInfoInfoAccessToken` 参数），
  所以只能按参数类型逐个填 `null` 泛化调用。
- 即使成功，插件 Activity 也**拿不到自己的系统身份**：
  实验 B 会把 `getActivityToken()` 打出来 —— 结论是它要么是 null、
  要么是宿主的 token。因此这些能力**结构性做不到**（不是"没测"）：
  `startActivityForResult` 回调、参与任务栈/最近任务恢复、进程重建时
  `onSaveInstanceState` 恢复、多窗口/画中画位置由系统分配。

**未验证项**：该反射路径在 API 31 真机上是否真的能跑通，尚未取得运行结果。
若失败，会按实际报错记录，**不修饰成"可用"**。

> 这也是主流插件框架最终都选择"宿主里预注册一批 Stub 组件"的原因 ——
> 不是不会 hook，而是 hook 出来的组件拿不到系统身份，代价太大。

---

## 九、诚实边界汇总

| 项 | 状态 |
|---|---|
| API 31 真机上：类加载 / 资源隔离 / assets / 包根 / 宿主无插件类 | 已由真机日志确认（4 条断言首轮通过，其余为测试自身写错，已修） |
| API < 31 的 `Resources` 反射分支 | **未真机验证**，代码里有明确标注 |
| 网络接口链路（真实 HTTPS + sha256） | 代码就绪，**未取得端到端真机结果** |
| Activity 形态（反射 `attach`） | 代码就绪，**未取得真机结果** |
| "清除插件"的语义 | 是"下次走完整流程"，**不是**"内存归零"（ART 对同 dex 路径有内部缓存） |
