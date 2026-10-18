# RN 加载白屏排查手册

> 白屏是**症状**，不是原因。加载链路有十几个检查点，值钱的能力是**快速判断卡在哪一环**，
> 而不是挨个试。
>
> 本文的故障特征全部来自**真机实测**（小米 M2012K11AC / arm64 / Android），
> 不是从文档抄的。每个案例都标了「我实际注入的故障 → 实际观察到的现象」。

---

## 0. 白屏 ≠ 崩溃：先分成两大类

这是**第一个岔路口**，决定后面怎么查。一条命令就能分：

```bash
pid=$(adb shell pidof com.rnbundlesplit | tr -d '\r')
echo "pid=[$pid]"      # 有值=进程活着；空=进程已死
```

| 现象 | 含义 | 查哪边 |
|---|---|---|
| **进程没了** | 原生层抛异常崩了（可能带弹窗，也可能静默） | 看 `FATAL EXCEPTION` 崩溃栈 → 第 2 节 |
| **进程活着但白屏** | JS 层挂了，原生兜住了 | 看 `ReactNativeJS` / 自定义 tag 的 JS 异常 → 第 3 节 |

> ⚠️ 我实测发现一个**容易误判的点**：`I/ReactNativeJS: Running "BusinessApp"`
> 这行日志**会在报错之前就打印出来**（第 3.3 案例里就是这样）。
> 所以**不能**用"有没有 Running 日志"来判断成功 —— 它只说明"
> 原生侧发起了挂载请求"，不代表渲染成功。**要看有没有紧跟其后的 `E/ReactNativeJS`。**

---

## 1. 先建"正常长什么样"的基线（最重要的一步）

没有基线就没法判断异常。**先在一个已知正常的包上跑一遍，把成功日志存下来**：

```bash
# 清日志 → 冷启动 → 抓关键行
adb logcat -c
adb shell am force-stop com.rnbundlesplit
adb shell am start -n com.rnbundlesplit/.BusinessActivity
sleep 8
adb logcat -d | grep -E "ReactNativeJS|RnBundleSplit" | tee /tmp/rn-ok.log
```

**正常时应看到（实测）**：

```
I/ReactNativeJS(11751): Running "BusinessApp"
```

**没有**任何 `E/` 开头的 `RnBundleSplit` 或 `ReactNativeJS` 行。

界面上应该出现业务内容 → 用 dump 确认（比截图更准，能拿到结构化文本）：

```bash
adb shell uiautomator dump /sdcard/ok.xml && adb pull /sdcard/ok.xml /tmp/ok.xml
python3 -c "
import re; x=open('/tmp/ok.xml').read()
t=[m.group(1) for m in re.finditer(r'text=\"([^\"]*)\"',x) if m.group(1).strip()]
print(t[:4])"
# 正常: ['业务包 (Business Bundle)', '这个界面来自独立的业务 bundle...', 'B · 30', 'level: gold']
```

**白屏时**：只有状态栏文字，界面节点数极少：

```
界面上的文字: (无文字 → 疑似白屏)    总节点数: 8
```

---

## 2. 进程已死：原生层崩溃

### 2.1 抓崩溃栈

```bash
adb logcat -d | grep -A 40 "FATAL EXCEPTION"
```

### 2.2 实测案例：asset 名字对不上 ★ 本项目最常见

**我注入了什么**：把 APK 里的 `assets/business.android.bundle` 改名成 `business_renamed.bundle`
（模拟 `MultiBundleLoader.BUSINESS_ASSET_NAME` 与实际文件名不一致）。

**实际观察到的**（进程直接死，连白屏都看不到）：

```
FATAL EXCEPTION: create_react_context
Process: com.rnbundlesplit, PID: 10964
java.lang.RuntimeException: Unable to load script. Make sure you're either running Metro
  (run 'npx react-native start') or that your bundle 'business.android.bundle' is
  packaged correctly for release.
  at com.facebook.react.bridge.CatalystInstanceImpl.jniLoadScriptFromAssets(Native Method)
  at com.facebook.react.bridge.CatalystInstanceImpl.loadScriptFromAssets(CatalystInstanceImpl.java:239)
  at com.rnbundlesplit.MultiBundleLoader.loadScript(MultiBundleLoader.kt:43)   ← ★ 直接指到我们的代码
  at com.facebook.react.bridge.CatalystInstanceImpl.runJSBundle(CatalystInstanceImpl.java:268)
  at com.facebook.react.ReactInstanceManager.createReactContext(ReactInstanceManager.java:1464)
```

**怎么读这个栈**（包含好消息和坏消息）：

- 🎯 **好消息**：因为是我们自己的 loader 调的，栈里直接有 `MultiBundleLoader.loadScript(:43)`
  —— **精确定位到"哪个 bundle、哪一行"**，这比 RN 默认 loader 报错友好得多。
- ⚠️ **坏消息 / 关键线索**：报错文案说"Make sure you're running **Metro**..."
  —— 这句提示**在这种场景下是误导性的**。我们是离线 assets 加载，跟 Metro 无关。
  **不要被它带偏**去查 Metro，直接顺着 `MultiBundleLoader.kt:43` 查文件名即可。
- 报错里**带着文件名** `'business.android.bundle'` → 说明是"这个 asset 找不到"。

**根因与修法**：`BUSINESS_ASSET_NAME` 与 assets 实际文件名不一致。
对齐二者（[MainApplication.kt](../host-app/android/app/src/main/java/com/rnbundlesplit/MainApplication.kt) 里的常量 vs `app/src/main/assets/`）。

### 2.3 排查 checklist（原生层）

| 检查 | 命令 / 位置 | 说明 |
|---|---|---|
| asset 真的打进 APK 了吗 | `unzip -l app-debug.apk \| grep assets/` | 实测两个都在（index 888954B / business 4883B） |
| 文件名对得上吗 | 对比 `MainApplication.BUSINESS_ASSET_NAME` 与 `ls app/src/main/assets/` | **最容易错**，见 2.2 |
| 是不是被 zip 压缩了 | `unzip -v app-debug.apk \| grep bundle` | **实测不是问题**：`AASSET_MODE_STREAMING` 会透明解压 |
| 走的是 Metro 还是 assets | 看 `recreateReactContextInBackgroundInner` 分支 | dev support 开着会去连 Metro，绕过我们的 loader |
| 崩溃栈里有没有我们的 loader | `grep MultiBundleLoader` 崩溃栈 | 有的话直接看它给的行号 |

---

## 3. 进程活着但白屏：JS 层挂了

这类**最像"白屏"** —— 界面卡在空白/启动图，进程还在。
共同特征：异常被 `JSExceptionHandler` 捕获，**没崩**。

### 3.1 关键：先确认异常有没有被打印出来

本项目在 `MainApplication` 里挂了 handler：

```kotlin
.setJSExceptionHandler { e -> Log.e(TAG, "JS 异常: ${e.message}", e) }
```

所以**所有 JS 异常都会以 `E/RnBundleSplit` 打印**（否则默认 handler 可能不吭声）：

```bash
adb logcat -d | grep -E "E/RnBundleSplit|E/ReactNativeJS" 
```

> 💡 如果你的项目**没挂** handler，dev support 关闭时 JS 异常可能**完全静默** ——
> 那就是"真·白屏无线索"。**第一条整改建议就是挂上 `setJSExceptionHandler`。**

### 3.2 实测案例：业务包有语法错误 → 白屏 + 进程活着

**我注入了什么**：业务包最后一行 `__r(938847);` 改成 `this is not valid js syntax!!!;`

**实际观察到的**（进程活着 pid=11140，界面无文字）：

```
I/HermesVM(11140): Compiling JS failed: 6:6:';' expected          ← ★ 直接给出位置
E/RnBundleSplit(11140): JS 异常: Compiling JS failed: 6:6:';' expected
E/RnBundleSplit(11140): com.facebook.jni.CppException: Compiling JS failed: 6:6:';' expected
  at com.facebook.react.bridge.queue.MessageQueueThreadHandler.dispatchMessage(...)
E/ReactNativeJNI(11140): Attempting to call JS function on a bad application bundle: AppRegistry.runApplication()
E/RnBundleSplit(11140): JS 异常: Attempting to call JS function on a bad application bundle: AppRegistry.runApplication()
W/unknown:ReactNative(11140): CatalystInstanceImpl.destroy() end
```

**怎么读**：

1. `Compiling JS failed: 6:6:';' expected` —— **Hermes 编译失败，还给了行列号**。
   注意这个 `6:6` 是**业务包内部**的行列（业务包很短，容易定位）。
2. `Attempting to call JS function on a bad application bundle` —— 这句话是**结论**：
   bundle 编译挂了，运行时被标记为 bad，后面所有 JS 调用都被拒绝。
3. `CatalystInstanceImpl.destroy() end` —— 运行时被销毁了，所以界面永远等不到内容。

**根因**：bundle 语法/编译错误。**排查顺序**：
先确认是哪个 bundle 挂了（看 `6:6` 这种行号落在 base 还是 business 的区间），
再回源文件修。

### 3.3 实测案例：组件名对不上 ★ 本项目最相关

**我注入了什么**：业务包里 `AppRegistry.registerComponent('BusinessApp', ...)` 的
`'BusinessApp'` 改成 `'BusinessApp_TYPO'`（模拟 `MainApplication.BUSINESS_COMPONENT_NAME`
与 `business-app/index.js` 里的名字不一致）。

**实际观察到的**（进程活着 pid=11555，白屏无文字）：

```
I/ReactNativeJS(11555): Running "BusinessApp"                            ← ★ 先打印了这行！
E/ReactNativeJS(11555): Invariant Violation: "BusinessApp" has not been registered. This can happen if:
E/ReactNativeJS(11555): * Metro (the local dev server) is run from the wrong folder. ...
E/ReactNativeJS(11555): * A module failed to load due to an error and `AppRegistry.registerComponent` wasn't called.
E/RnBundleSplit(11555): JS 异常: Invariant Violation: "BusinessApp" has not been registered. ...
E/RnBundleSplit(11555): anonymous@353:289
E/RnBundleSplit(11555): runApplication@138:1564                    ← ★ JS 调用栈！
E/RnBundleSplit(11555): value@39:3932
```

**怎么读**（这个案例信息量最大）：

1. ⚠️ **`Running "BusinessApp"` 先出现，然后才报错** ——
   这正是第 0 节说的陷阱：那行日志只代表"原生发起了挂载请求"，
   **不代表注册成功**。所以**看到 Running 不要以为好了**，要看后面有没有 `E/`。
2. `has not been registered` 是**结论**：`AppRegistry` 的 `runnables` 表里没这个名字。
3. **报错文案里那两条提示同样是误导的**：它说"Metro 目录不对"或"registerComponent 没被调用"。
   在分包场景下**这两条都不适用** —— 真实原因通常是
   **"注册的名字"和"要挂载的名字"不是同一个**。
4. 调用栈 `runApplication@138:1564` **直接指向 AppRegistry 的挂载路径** ——
   证明"注册没发生 / 名字不对"，而不是"RN 没起来"。

**根因与修法**：三处名字必须一致：

| 位置 | 值 |
|---|---|
| `business-app/index.js` 的 `BUSINESS_APP_NAME` | `'BusinessApp'` |
| `MainApplication.BUSINESS_COMPONENT_NAME` | `'BusinessApp'` |
| `BusinessActivity.getMainComponentName()` | 返回上面那个 |

任一处不一致 → 白屏 + 上面的 Invariant Violation。

### 3.4 其他 JS 层白屏原因（按概率）

| 原因 | 特征日志 | 根因 |
|---|---|---|
| **依赖 id 对不上** | `Requiring unknown module "123456"` | mapping 与 bundle 不同步；业务包里引用了 base 没有的 id |
| **mapping 过期** | 不报错但行为不对/白屏 | base 侧改了源码但没重生成 mapping；或者业务包是用旧 mapping 打的 |
| **组件渲染时抛异常** | JS 异常栈里有**组件名**（如 `App`） | 业务代码自身 bug，或依赖的 base 模块版本不匹配 |
| **异步时序问题** | `has not been registered`（同上） | 业务包加载晚于 `runApplication`（懒加载改造后最常见的坑） |

---

## 4. 判断"卡在哪一环"：分阶段验证法

不要一上来就抓全量日志。**按加载链路分段验证**，快速缩小范围：

```
① bundle 有没有进 APK？
   unzip -l app.apk | grep assets/                          ← 有就过
        ↓
② loader 有没有被调到？
   grep "CatalystInstanceImpl.runJSBundle" 或看 MultiBundleLoader 有没有异常
        ↓
③ asset 读得到吗？（文件名对不对）
   崩溃栈里有 "Unable to load script" + 文件名 → 就是这环
        ↓
④ JS 编译得过吗？
   grep "Compiling JS failed" / "HermesVM"                  ← 有就是语法问题
        ↓
⑤ 模块注册成功吗？
   grep "Requiring unknown module"                          ← 有就是 id/mapping 问题
        ↓
⑥ 组件注册了吗？
   grep "has not been registered"                           ← 有就是名字/时序问题
        ↓
⑦ 渲染时抛异常了吗？
   grep "E/RnBundleSplit" 看完整 JS 栈
```

**实测三案例分别卡在**：② 之前不成立（asset 找不到，第 ③ 环）、④（编译失败）、⑥（名字不对）。

---

## 5. 工具与命令备查

### 5.1 一条命令抓全部关键信息

```bash
adb logcat -c && adb shell am force-stop com.rnbundlesplit && \
adb shell am start -n com.rnbundlesplit/.BusinessActivity && sleep 8 && \
echo "--- pid: $(adb shell pidof com.rnbundlesplit | tr -d '\r')" && \
adb logcat -d | grep -iE "ReactNativeJS|RnBundleSplit|Unable to load|not been registered|unknown module|Compiling JS failed|FATAL EXCEPTION"
```

### 5.2 判断白屏 vs 有内容（比截图可靠）

```bash
adb shell uiautomator dump /sdcard/d.xml && adb pull /sdcard/d.xml /tmp/d.xml
python3 -c "
import re; x=open('/tmp/d.xml').read()
t=[m.group(1) for m in re.finditer(r'text=\"([^\"]*)\"',x) if m.group(1).strip()]
print('文字:', t if t else '(白屏)'); print('节点数:', x.count('<node'))"
```

### 5.3 快速注入故障复现（不用等 3 分钟构建）

改了 JS 想验证效果时，**不必重新 Gradle 构建**（要 ~3 分钟）。
直接替换 APK 里的 bundle 再签名重装，约 3 秒：

```bash
# 用改坏的 bundle 替换；第三个参数可选，默认用 app-debug.apk
bash scripts/repack-bundle.sh business.android.bundle /tmp/broken.bundle

# 还原
bash scripts/repack-bundle.sh business.android.bundle business-app/output/business.android.release.bundle
```

脚本做的事：复制 APK → `zip` 替换 assets 条目 → `zipalign` → `apksigner` → `adb install -r -d`。
**注意它不动构建产物**（在临时目录操作），所以随时可以还原。

---

## 6. 本项目特有的坑（其他 RN 项目不一定有）

| 坑 | 为什么 | 怎么快速确认 |
|---|---|---|
| **dev support 关闭 → 没有 RedBox** | `getUseDeveloperSupport() = false`，错误只进 logcat | 白屏时**必须**看 logcat；没挂 `JSExceptionHandler` 就完全无线索 |
| **两个 bundle 都有可能是"坏的"** | 任一编译失败都会导致 `bad application bundle` | 看 Hermes 报的行列号落在哪个 bundle 的区间 |
| **asset 名 / 组件名有 3 处要一致** | 原生常量与 JS 常量分离，容易改一处 | 见 3.3 那张表 |
| **报错文案里的 Metro 提示是误导** | RN 的 `Unable to load script` 默认假设你在连 Metro | 离线 assets 场景**忽略这句**，直接看自己 loader 的行号 |
| **`Running "X"` 日志不代表成功** | 它打印在 `runApplication` 开头，早于 Invariant 检查 | 必须看有没有紧跟的 `E/ReactNativeJS` |

---

## 7. 如果只能记三句话

1. **先分岔**：`pidof` 有值 = JS 层白屏（查 `E/` 日志）；为空 = 原生崩溃（查 `FATAL EXCEPTION`）。
2. **`Running "X"` 不是成功标志** —— 它可能刚打印完就 `has not been registered`。
3. **报错里提到 Metro 的那句，在离线 assets 场景是误导** —— 顺着自己的 loader 行号查。
