# 插件化 Lab · 使用说明

> 目标：**不重装宿主，换一个插件包**，并且每一条加载链路都能被证伪 ——
> 而不是"看起来能跑"。
>
> 配套：[`NOTES-plugin-lab.md`](NOTES-plugin-lab.md)（设计取舍与实测数据）、
> [`GUIDE-plugin-code-reading.md`](GUIDE-plugin-code-reading.md)（代码阅读顺序）。

---

## 0. 一次性前置：把插件包打出来

插件模块 `:plugin-demo` 是普通 library，**它自己不会产出 apk**
（library 的默认产物是 AAR）。要把它变成"可独立分发的插件 apk"，
必须走组装脚本 —— 这一步是刻意的，理由见 NOTES 的「为什么不用 AGP 的默认产物」。

```bash
tools/plugin-package/package.sh
```

脚本做六件事（每次都会把过程打出来，不让人靠猜）：

| 步骤 | 做什么 | 为什么必须自己来 |
|---|---|---|
| 0 | `./gradlew :plugin-demo:assembleDebug` | 只借 AGP 编译，不借它打包 |
| 1 | 解开 AAR 拿 `classes.jar` / `res/` / `assets/` | AAR 是**原料**，不是产物 |
| 2 | `d8` → `classes.dex` | AAR 里是 jar，没有 dex；"独立 dex"要靠这一步 |
| 3 | `aapt2 compile` + `link` → `resources.arsc` | 没有 arsc 就没有"插件自己的资源表" |
| 3b | 导出 `plugin-res-ids.txt` | 让宿主**不引用插件 R** 也能对同一个 ID 做隔离对照 |
| 4-5 | 组装 assets / 包根探针 / 二进制 manifest → zip | 成品必须是一个**合法 apk**（见下） |
| 6 | `jarsigner` + 写 `plugin-package.xml`（sha256/size/入口类） | 网络链路的契约文件 |

产物：

```
build/plugin-package/plugin-demo-debug.apk     ← 可独立分发的插件（默认还会内嵌进宿主 assets）
build/plugin-package/plugin-package.xml        ← 元数据（含 sha256）
app/src/main/assets/plugin-demo-debug.apk      ← 离线兜底的内嵌副本
```

---

## 1. 在真机上跑

宿主里打开 **「插件化 Lab」**（首页清单里，紧跟在 VMP 条目之后）。

页面上有三条来源可选，对应三条**互不相同**的链路：

### ① assets 内嵌（零外部依赖）

点「加载内嵌插件」。宿主会把 assets 里的 apk 拷到私有目录再加载 ——
**拷贝不是因为懒**：`DexClassLoader` 要真实文件路径去 mmap，而 assets 里的 apk
是宿主 apk 内部的一个条目，没有独立路径。这一步顺带把"内嵌"与"私有目录"两条来源
收敛到同一条装载路径上。

### ② 私有目录（可替换，最适合手动实验）

```bash
tools/plugin-package/deploy.sh
```

它把 apk + xml 推到 `/sdcard/Android/data/<pkg>/files/`（即 `getExternalFilesDir`）——
这个位置是"shell 能写、app 能读写"的交集，所以宿主代码里**不用为调试路径开特例**。
推完在页面上点「私有目录插件」即可。

> ⚠️ `/sdcard` 根目录不要用。分区存储下 shell 与 app 各看一半，路径和权限会随
> 机型/版本变形（外部存储权限、MANAGE_EXTERNAL_STORAGE、sdcard_rw 组……）。

### ③ 网络接口（真实 HTTPS）

插件包 + 元数据放到任意 **HTTPS** 静态服务，在页面填入 `plugin-package.xml` 的 URL。

```bash
# 例：用任意静态托管把这两个文件放上去
#   plugin-package.xml
#   plugin-demo-debug.apk   （默认与 xml 同目录，同名）
```

链路顺序是**元数据在前**，而且这不是随便定的：

1. 元数据里有 `size` 与 `sha256` ⇒ 下载 apk 时就知道"应该多大、应该是哪一份"；
2. 元数据里有 `minSdk` ⇒ **下载之前**就能拒绝不适合本机的包，
   省掉一次无用的大文件传输（移动网络下这是真实成本）；
3. 下完**按 sha256 核验字节**，不一致就删掉临时文件并报错。

链路**只接受 `https://`**：插件包会被当代码加载，明文下这条链路可被替换，
sha256 校验也就失去意义（攻击者能同时改 apk 与元数据）。

---

## 2. 页面上那几个"实验"按钮在验什么

每个结论都是**可证伪**的，不是叙述。展开页面上的按钮即可看到实跑结果。

| 实验 | 命题 | 判定依据 |
|---|---|---|
| A | 动态代理跨 ClassLoader | 结论是**类型解析**，不是"能不能调" |
| B | 插件 Activity 有没有系统身份 | `getActivityToken()` 是 null 还是借宿主的 |
| C | 重新装载会不会复用上次结果 | ClassLoader / Class 的 **identity 每次都变** |
| D | 宿主 ClassLoader 能否加载插件类 | 必须 `ClassNotFoundException`（与 `NoClassDefFoundError` 区分） |
| E | 同一个资源 ID 在两张表里指向什么 | 比**名字**，不比"查不查得到" |

其中 **E 是整条命题的题眼**，单独说：

资源 ID 的 package 段 `0x7f` 是**应用包固定值**，宿主与插件都从 `0x7f030000` 起编号，
于是**同一个数字在两张表里都存在、只是指向不同资源**。本仓库实测：

```
0x7f030000   插件表 → layout/plugin_page      宿主表 → attr/SharedValue
0x7f010001   插件表 → color/plugin_panel      宿主表 → anim/abc_fade_out
0x7f040000   插件表 → string/plugin_activity_label
                                             宿主表 → bool/abc_action_bar_embed_tabs
0x7f040006   插件表 → string/plugin_title     宿主表 → （无，NotFoundException）
```

所以：

- "宿主查不到这个 ID" **不是**隔离的判据 —— 大多数 ID 它都查得到；
- 能判断归属的**只有解析出来的名字**；
- 后果是工程性的：宿主若用自己的 `Resources` 去 inflate 插件的 `R.layout.xxx`，
  它**不会报错**，而是安静地 inflate 成宿主的另一个布局 ——
  一个"点了没反应/界面变成别的样子"的 bug，且栈里没有任何异常。
  这就是"插件必须拿自己的 Resources"的**物理原因**。

---

## 3. 更硬的两条验收：不依赖 UI

页面按钮只是让你更快看到结果，**不能替代**下面两条外部核对。

```bash
# ① 插件包里有没有宿主不该有的东西 / 宿主里有没有插件的类
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -i plugindemo
#   → 应为空

dexdump -f app/build/outputs/apk/debug/app-debug.apk | grep Lcom/interview/plugindemo
#   → 应为空

# ② 成品插件包本身是不是一个**合法 apk**
"$ANDROID_HOME/build-tools/<版本>/aapt2" dump resources build/plugin-package/plugin-demo-debug.apk
#   → 应当正常打印资源表
```

第 ② 条曾经**失败过**，原因值得记住：脚本一度把**文本** `AndroidManifest.xml`
覆盖进成品，而 aapt2 link 早就产出了**二进制** manifest。后果不是"加载不了"
（我们的加载器不解析 manifest），而是成品不再是一个合法 apk ——
连 `aapt2 dump` 都会报 `failed to parse binary AndroidManifest.xml`。
**把自检手段自己弄坏**是最难发现的一类回归，所以这条留在了脚本里做断言。

### 真机金标准（10 条断言）

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.interview.plugin.PluginLoadInstrumentedTest
```

> ⚠️ 跑之前请**解锁手机**。本仓库那台 MIUI 设备在锁屏下会以
> `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` 拒绝 USB 安装 ——
> 这是 MIUI 的策略，不是代码问题，但会让整轮测试在装包阶段就失败。

---

## 4. 常见坑（都是本仓库踩过的）

| 现象 | 真实原因 |
|---|---|
| `aapt2 dump resources` 在成品上失败 | 成品里是文本 manifest，不是二进制 |
| 插件 assets 读不到 | `open()` 的路径**不带** `assets/` 前缀（前缀是隐式的）；写成 `assets/x` 会去找 `assets/assets/x` |
| 包根文件读不到 | `open()` 只查 assets 命名空间；包根要 `openNonAssetFd`，且该 zip 条目必须 **STORED**（未压缩） |
| `ClassCastException` on `IPluginPage` | 插件 dex 里也有一份同名接口 ⇒ 两个 `Class` 对象。必须让共享前缀**委派给宿主** |
| 第二次加载很快，于是以为"复用了" | ART 对同一 dex 路径有内部缓存。要看 **identity**，不是看耗时 |
| 手动 `adb push` 的包在测试里不见了 | `connectedAndroidTest` 会先重装宿主，重装会清掉 `getExternalFilesDir` |
