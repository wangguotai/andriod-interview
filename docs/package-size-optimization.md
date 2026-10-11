# 包体积优化：开关与实测

> 本文是**操作手册 + 实测记录**，不是理论清单。
> 每一项都标注了「为什么这么做」和「踩过什么坑」，数字全部来自本仓库实跑。

---

## 一、实测结果（先看结论）

对比条件：同一工作区，`./gradlew :app:assembleRelease` 分别不开/开 `opt.shrink`。
基线是我重新构建的（见 §五 的坑 1：仓库里那份 9.15 MB 的旧 APK 缺 native 库，不可用作基线）。

| 指标 | 无开关 | `-Popt.shrink` | 变化 |
|---|---|---|---|
| **APK 总大小** | **13.19 MB** | **6.05 MB** | **−54.1%** |
| dex（解压） | 21.77 MB | 2.72 MB | −87.5% |
| res（解压） | 1.91 MB | 1.30 MB | −32% |
| lib/.so | 3.49 MB | 3.49 MB | 不变 |

AAB 交付（`-Popt.shrink :app:bundleRelease`）：

| 项 | 大小 | 说明 |
|---|---|---|
| AAB 总计 | 7.27 MB | |
| `base/` 模块 | **3.57 MB** | 单台 arm64 设备实际下载的量 |
| `BUNDLE-METADATA/` | 3.70 MB | 调试符号 `.sym`，**不下发给设备** |

> 为什么 AAB 的 `base/`（3.57 MB）比 APK（6.05 MB）小：
> APK 里 `.so` 为 mmap 而**不压缩**存储（3.49 MB），AAB 里压缩存储（1.70 MB），
> 加上资源/语言分片，差额就有约 2.5 MB。

**dex 是唯一的体积大头**（占基线解压后 80%），所以 `opt.shrink` 的收益几乎全来自 R8。

---

## 二、四个开关

全部默认 `false`，即**不改动现有构建行为**。定义在 [gradle.properties](../gradle.properties)，
解析在 [app/build.gradle.kts](../app/build.gradle.kts)。

```bash
# 一键全开（shrink + audit；不含 webp）
./gradlew :app:assembleRelease -Popt.all

# 只开 R8
./gradlew :app:assembleRelease -Popt.shrink

# R8 + 审计报告
./gradlew :app:assembleRelease -Popt.shrink -Popt.audit

# 出 AAB（交付层，单机下载量最小）
./gradlew :app:bundleRelease -Popt.shrink

# 图片来源转换（见 §四，默认 dry-run）
tools/opt/webp-res.sh
```

| 开关 | 作用 | 风险 |
|---|---|---|
| `opt.shrink` | R8 代码压缩 + 资源压缩 | 漏 keep = **运行时崩/静默丢数据**，编译期不报错 |
| `opt.webp` | 图片转 WebP（报告） | 需 `cwebp`；`opt.webp.apply` 才落盘改源文件 |
| `opt.audit` | 汇总 R8 删除清单/混淆映射到 `build/reports/opt/` | 无（只读） |
| `opt.all` | = shrink + audit | 同 shrink |

### ⚠️ `-P` 不带值必须视为 true

`-Popt.shrink`（无 `=值`）时 `findProperty` 返回**空串**。
若直接 `toBooleanStrictOrNull()`，会得到 `false` —— 表现为「打开了开关但产物毫无变化」，
极易误判成「R8 没效果」。解析函数已专门处理这一点（含 `"1"/"yes"/"on"`）。

---

## 三、R8 keep 规则

见 [app/proguard-rules.pro](../app/proguard-rules.pro)。四类必要项，每类都写了理由：

1. **Retrofit 运行时动态代理** —— 方法名→HTTP 路径在运行时解析
2. **Gson 反射读写字段** —— 字段名被混淆 = 解析出 null 且**不报错**
3. **JNI 符号** —— 类名+方法名拼成符号，混淆后 `UnsatisfiedLinkError`，编译期无感
4. **ThreadMonitor ASM 改写落点** —— `UnifiedThread` 等名字必须稳定

### 唯一能在这台机器上验证 keep 是否正确的方法

编译**不**报错不代表 keep 正确 —— 四类问题都是运行时才炸。静态验证手段：

```bash
# 1. 关键类名是否保留（JNI / ASM 落点）
unzip -p app/build/outputs/apk/release/app-release-unsigned.apk 'classes*.dex' \
  | strings | grep -c 'com/interview/net/nativebridge/NetLabNative'

# 2. 是否被 R8 整类删除（usage.txt = 真实删除清单）
grep 'com.interview.retrofit.Contributor' app/build/outputs/mapping/release/usage.txt
grep 'com.interview.retrofit.Contributor' app/build/outputs/mapping/release/mapping.txt

# 3. 危险区间：usage.txt 有该行（只删了空 <clinit>）+ mapping.txt 映射到自身
#    = 类保留，属正常。两边都搜不到才是「整类被删」。
```

**必须补的**：真机冒烟。以上只能证明「类还在」，不能证明「反射路径真的能跑通」。
建议回归点：网络接口（GitHub contributors，验证 Gson）、每个 Lab 的 native 入口。

---

## 四、图片：WebP（对本仓库 app 收益为 0）

`tools/opt/webp-res.sh`，默认 dry-run，`--apply` 才落盘。

**实测结论：`app` 的图片已经全是 WebP，转换收益为 0。**
全仓库仅剩 `xshell/order` 的 9 张 JPG —— 那是独立 APK，与 `app` 无关。

安全性依据（不是凭感觉）：Android 资源按**资源名**解析，与扩展名无关 ——
`@drawable/foo` 与 `R.drawable.foo` 都不含后缀。所以换编码**不需要改写任何引用**。

脚本会跳过：`*.9.png`（WebP 不支持 9-patch 拉伸标记）、已存在同名 `.webp` 的（资源名冲突，直接报错而非猜）。

---

## 五、踩过的坑（读到这里能省你几小时）

### 坑 1：仓库里那份 APK 是陈旧产物，会骗人

`app-release-unsigned.apk` 初始为 9.15 MB，**缺 native 库**（只有 `libthreadhook.so`，
且残留 4 个 ABI，与 `abiFilters = arm64-v8a` 矛盾）。
用它当基线会得出完全错误的结论。**做任何体积对比前，先重新构建一次。**

### 坑 2：`-keep interface com.x.**` 匹配不到 class

第一版 proguard 写的是 `-keep interface com.interview.retrofit.**`。
结果 `Contributor`（**data class**）匹配不上，被 R8 整类删除，
`login`/`contributions` 字段从 dex 消失 —— Gson 反序列化会静默失败（接口 200、界面空白）。
改用 `-keep class com.interview.retrofit.**`（`class` 同时覆盖 class/interface/enum）后修复，
已在 dex 层复验字段存在。

### 坑 3：`usage.txt` 出现类名 ≠ 整类被删

R8 会把空方法列进删除清单。判据是**同时看 mapping.txt**：
映射到自身 = 类保留。养成习惯：**dex 是 ground truth**，报告文件是线索。

### 坑 4：既存 bug —— ASM 插件对 `super()` 的处理

**这不是本次改动引入的，是开 R8 后暴露出来的**，且插件注释与代码矛盾：

[ThreadClassVisitor.kt:153-154](../build-logic/thread-plugin/src/main/kotlin/com/interview/thread/plugin/ThreadClassVisitor.kt#L153)
注释说「子类构造函数里对 super() 的调用前面没有 NEW，因此不会被误伤」，
但 [`rewrite()`](../build-logic/thread-plugin/src/main/kotlin/com/interview/thread/plugin/ThreadClassVisitor.kt#L183) 里（[L189-190](../build-logic/thread-plugin/src/main/kotlin/com/interview/thread/plugin/ThreadClassVisitor.kt#L189)）：

```kotlin
if (enableUnify) {
    newInsn?.desc = UNIFIED_THREAD   // ← 按注释正确判空
    ctor.owner = UNIFIED_THREAD      // ← ✗ super() 时 newInsn==null，但仍改了 owner
}
```

触发点：[HandlerLeakActivity.kt:108](../app/src/main/java/com/interview/leak/HandlerLeakActivity.kt#L108)
的 `private inner class LeakChecker(...) : Thread()`。
`super()` 的 owner 被改成 `UnifiedThread`，而类父类仍是 `java/lang/Thread`
⇒ 调用非父类构造器，D8/R8 报 `Constructor mismatch`：

```
Invalid stack map table ... expected constructor from ...LeakChecker or its superclass,
but was com.interview.thread.UnifiedThread.<init>(java.lang.String)
```

后果链：类内 `LeakChecker().start()` 仍指向 `java/lang/Thread`（未改），
但 `LeakChecker.<init>` 非法 → 该 Activity 的线程泄漏演示**极可能已损坏**。
D8 曾放行（只标记警告）并产出损坏字节码；新版本 R8 可能直接假设该方法不可达。

**修复方向（未实施，属独立改动）**：把 `ctor.owner = UNIFIED_THREAD` 也放进判空，
即 `newInsn?.let { it.desc = UNIFIED_THREAD; ctor.owner = UNIFIED_THREAD }`；
或对「类自身继承 Thread」的情形提供真正的父类桥接。

### 坑 5：模块都是独立 application

`vmp-lab` / `ipc-lab` / `scroll-event-demo` / `xshell` **各自是 application 模块，出独立 APK**，
不在 `app` 的包内。所以「把它们改成 dynamic feature」对 `app` 体积**零收益** ——
这个方向在本仓库是伪命题，不要去做。

---

## 六、当前状态

- [x] `opt.shrink` / `opt.webp` / `opt.audit` / `opt.all` 开关，默认全关
- [x] R8 keep 规则（四类，已在 dex 层复验）
- [x] APK −54.1%、AAB 单机 3.57 MB
- [x] `tools/opt/webp-res.sh`（dry-run 已验证；结论是 app 无需转换）
- [x] `tools/opt/unused-report.sh`（已实测：APK 构成 / usage.txt / 依赖清点）
- [x] `-Popt.audit` 在构建后自动汇总 `removed-code.txt`（49112 行）+ `mapping.txt`
      → `app/build/reports/opt/`
- [ ] **真机冒烟**（R8 唯一可信的验证手段，未做）
- [ ] 坑 4 的 ASM 修复（独立改动，建议单独提交）
- [ ] `adapter-rxjava3` / `logging-interceptor` 依赖清理（见 `unused-report.sh` 输出；
      前者是「引用存在但功能为死」，需人工判断）

### 依赖清理的注意事项

`tools/opt/unused-report.sh` 会列出源码+资源里零命中的依赖。**实测已修正两处误判**：

| 依赖 | 初判 | 实际 |
|---|---|---|
| `constraintlayout` | 疑似可删 | **在用**（`activity_live_data_demo.xml`）—— 只搜 Kotlin 会漏 |
| `adapter-rxjava3` | 疑似可删 | 被 `Common.kt` import，但 `GitHub` 接口无任何 Rx 返回类型 = 功能为死 |

脚本已改为**同时搜索 `app/src/main/java` 与 `app/src/main/res`**。
