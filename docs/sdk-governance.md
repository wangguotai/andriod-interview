# 三方 SDK 治理工具：设计、实现与实测

> 本文是**工具的设计说明 + 使用手册 + 面试答题稿**三合一。
> 所有数字来自本仓库实跑（`./gradlew :app:sdkAuditDebug`），
> 每个结论都标注了「凭什么」和「边界在哪」。
>
> 与既有产出的关系：
> - 线程维度的**原理**部分见 [`线程滥用与治理.md`](../app/src/main/java/com/interview/线程治理/线程滥用与治理.md)
> - 包体积的**工程开关**见 [`package-size-optimization.md`](package-size-optimization.md)
> - 本文补的是两者都缺的：**「哪个 SDK 干的」这个归因能力**，以及权限与依赖重复两个新维度。

---

## 〇、交付物一览

| 组件 | 位置 | 作用 |
|---|---|---|
| Gradle 插件 | [`build-logic/sdk-plugin`](../build-logic/sdk-plugin) | 编译期静态审计，四个维度一次遍历产出 |
| 运行时库 | [`sdk-governance-runtime`](../sdk-governance-runtime) | 线程创建的**运行时归因**（Hook 之后的记录与报告层） |
| 报告 | `app/build/reports/sdk-governance/` | `sdk-audit.md`（人读）+ `sdk-audit.json`（CI 卡口） |

```bash
# 静态审计（四维度）
./gradlew :app:sdkAuditDebug        # 或 sdkAuditRelease

# 运行时归因单测（13 个用例，纯 JVM，秒级）
./gradlew :sdk-governance-runtime:testDebugUnitTest
```

**实测结果**（本仓库 app / debug）：

| 维度 | 发现 |
|---|---|
| 权限 | 12 项；可移除 **2** 项（均为危险权限）；仅 SDK 内部使用 10 项 |
| 依赖 | 版本冲突 **53** 组；等价库并存 **4** 组；重复类 0 |
| 体积 | 依赖合计 **68.65 MB**（解压后）；最大单项 material3 7.94 MB |
| 线程 | 创建点 **115** 处（自有 62 / 三方 53）；高风险 45 处 |

---

## 一、权限：声明了哪些、真实有没有用、没用怎么删

### 1.1 判定的难点不在「声明」，在「使用」

声明很好拿（读 manifest）。难的是判断**有没有真用**，而这里有个致命的直觉错误：

> **绝大多数权限的使用，在字节码里没有任何权限字符串。**

`TelephonyManager.getDeviceId()` 需要 `READ_PHONE_STATE`，但调用处不会出现
`"android.permission.READ_PHONE_STATE"` —— 权限检查是系统的运行时行为。

所以只扫字符串（最常见的做法）会**大量漏报**。本工具的应对是**双证据链**：

| 证据类型 | 来源 | 例 |
|---|---|---|
| 显式 | 字节码里的 `LDC "android.permission.X"`、`Manifest.permission.X` 字段访问 | SDK 主动声明/检查 |
| **API→权限** | 反查表：调用了需要权限的平台 API | `java/net/Socket#<init>` → `INTERNET` |

实测中 `INTERNET` 就是靠第二类才被发现的 —— okhttp/okio 的调用点里**
完全没有** `INTERNET` 字样。**漏报它意味着有人会把它删掉，App 直接断网。**

### 1.2 结论分档（为什么不能只给「用/没用」两种）

| 结论 | 含义 | 处理 |
|---|---|---|
| 🔒 `USED_BY_FIRST_PARTY` | 自有代码在用 | 不可移除 |
| ⚠️ `USED_BY_SDK_ONLY` | 仅某个 SDK 内部用 | **不可直接删**，见下 |
| ✅ `DECLARED_UNUSED` | 全工程找不到使用证据 | 可移除（给出 `tools:node="remove"` 方案） |
| ❓ `REFLECTION_PRONE` | 声明方在反射高发名单 | 转人工，不给删除建议 |

**「仅 SDK 内部使用」为什么不能一删了之**：权限在 manifest 合并后是**宿主 App 的责任**。
删掉后 SDK 静默失效（返回 null / 抛 `SecurityException`），且多在灰度期才暴露。
正确方向是「让 SDK 不再需要」：移除该 SDK / 关功能开关 / 升级到不再申请的版本。

### 1.3 移除方案（不是「建议删除」，是可执行动作）

对 `DECLARED_UNUSED` 且来自 SDK 的权限，报告直接给出：

```xml
<manifest xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE"
        tools:node="remove" />
</manifest>
```

本仓库实测可移除：`MANAGE_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE`（均为危险权限，
且**源码中零使用证据**，已用 `grep -rn` 交叉验证过）。

### 1.4 本维度的诚实边界

报告末尾**每次运行都带**这些（写进代码而非只写文档 —— 报告会被脱离上下文传播）：

- 静态可达性看不见：**反射 / `ServiceLoader` / 只在特定机型分支执行的代码**。
  所以 `DECLARED_UNUSED` 是「**未发现证据**」而非「**确定未使用**」。
- API→权限反查表**启发式且不完备**，不含厂商 ROM 私有 API。
  表未命中**不代表**不需要权限；本工具只做加法（多报），不做减法。
- 最终权威是 **APK 内的合并 manifest**：
  `aapt2 dump badging app-release.apk | grep uses-permission`。

---

## 二、依赖重复与版本冲突

「重复依赖」是两件**性质完全不同**的事，混在一起谈会误导决策。

### 2.1 版本冲突：被**静默**提升的版本

Gradle 默认仲裁（取最高版）**不报错**。实测本仓库 **53 组**「请求版本 ≠ 选中版本」，例如：

| 模块 | 被请求 | 实际选中 | 请求方 |
|---|---|---|---|
| `com.squareup.okhttp3:okhttp` | 3.14.9 | **4.12.0** | retrofit 2.11.0 |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.6.4 | **1.7.1** | lifecycle、compose 等 5 处 |
| `androidx.recyclerview` | 1.0.0, 1.3.1-rc01 | **1.3.2** | material 1.11.0、viewpager2 |

**判据为什么是「requested ≠ selected」而不是「请求了多个版本」**：
后者会淹没在噪音里 —— 稍大的工程里几乎每个热门库都会被多版本请求，这是正常协作。
真正要人看一眼的是「**某个依赖拿到了它没声明的版本**」，因为 Java 生态不保证二进制兼容，
被提升后可能 `NoSuchMethodError`，且往往灰度才崩。

**解决方向**：
1. 优先 **BOM / `platform()`**（androidx、compose、firebase 都有）
2. 无 BOM 时用 `constraints` 或 `resolutionStrategy.force`
3. ⚠️ **不要盲目 force 到最高版** —— Gradle 本来就取最高版；要处理的是
   「最高版与其他依赖不兼容」，此时应 force 到**兼容版本**
4. 可加 `failOnVersionConflict()` 让提升显式失败，而不是静默

### 2.2 重复类：同一 class 被多个依赖打包

这才是真正危险的一种，且 `gradle dependencies` **看不出来**。
危害按字节码是否一致分两级：

- **一致** → 体积浪费，无害
- **不一致** → **行为不确定**。最终生效的取决于 dex 合并顺序，
  表现为「本地能跑 CI 崩」「换个 Gradle 版本行为就变」，且崩点离真因很远

检测方式：遍历所有依赖的类清单建 `类名 → 提供者` 索引。
**必须过滤生成物**（每个 AAR 都自带 `R` / `BuildConfig`，不过滤会被噪音淹没）。

⚠️ 实现取舍：本版本**不做逐字节比对**，保守标为「不一致」——
猜「一致」是最坏的选择，它会让报告漏掉真正危险的那一类。

### 2.3 等价库并存

判据是「**同一件事有多个库在做**」（而非同库多版本，那是 §2.1）。
实测本仓库 4 组：OkHttp 系、Glide 系、HTTP 客户端、`kotlin-stdlib-jdk7/jdk8`。

⚠️ 这里给的是「疑似」，**必须人工确认**（同时引两种 JSON 库可能是刻意的）。
所以措辞是「确认后合并」而非「立刻删一个」。

---

## 三、体积降低方向：还有哪些

R8 已在 [`package-size-optimization.md`](package-size-optimization.md) 里做到 −54.1%，
那之后的方向按**收益/成本**排序：

| 方向 | 手段 | 依据 / 注意 |
|---|---|---|
| **1. native 库** | 只留需要的 ABI；用 `abiFilters`；考虑 `.so` 压缩/重打包 | 本仓库实测 native 仅 0.3%（`bytehook` 219 KB），但对**音视频/AI SDK 常占 50%+**，是最高杠杆 |
| **2. 换实现** | 全量库 → 轻量替代（Glide → Coil；Gson → kotlinx.serialization） | 本仓库 `rxjava` 一个就 **5.79 MB / 1758 类**，而它只是「引用了但功能为死」—— 删了直接省 5.79 MB |
| **3. 去重复** | `exclude` 掉传递引入的重复库（见 §2.2） | 直接消除整份字节码 |
| **4. 资源** | `shrinkResources`；WebP；字体子集化；`assets` 裁剪 | `assets/` **不参与资源压缩**，只能靠代码层不请求 |
| **5. 交付层** | AAB + 按 ABI/语言/密度分片；动态特性 | 本仓库 AAB 单机下载 3.57 MB vs APK 6.05 MB |
| **6. 裁剪 SDK 功能** | 用 SDK 的「lite/按需集成」变体 | 很多 SDK 提供减配版本，收益常大于所有字节码优化之和 |
| **7. 别做的事** | 把独立 application 模块改 dynamic feature | 本仓库 `vmp-lab`/`ipc-shell` 等各自出独立 APK，对 `app` 体积**零收益**（见 package-size 文档坑 5） |

**工具提供的定位能力**：体积归属表把 68.65 MB 拆到每个依赖（代码/资源/native 三列），
直接回答「**优化谁**」。

⚠️ 口径说明：统计的是**解压后字节**，不是 APK 增量，且未扣除 R8 会删的部分。
**只用它做相对排名**。精确增量只有分别构建包含/不包含该依赖的包做差分。

---

## 四、三方 SDK 线程滥用治理

### 4.1 为什么必须「静态 + 运行时」两条腿

| | 静态扫描（插件） | 运行时归因（runtime 库 + Hook） |
|---|---|---|
| 回答 | **在哪儿**可能建线程、什么形态、队列有没有界 | **实际**多少、谁在持续建、还活着多少 |
| 成本 | 零（进 CI） | 需真机 + 采样开销 |
| 覆盖 | 全部依赖字节码 | 只覆盖被 hook 的执行路径 |
| 盲区 | **加壳 SDK**（运行时才解密） | 未被 hook 的纯 native 路径 |

**顺序不能反**：静态先跑给出可疑清单，运行时对可疑点做定量确认。
直接上真机采样 = 大海捞针。

**运行时归因不可替代的地方**：Hook 点在 **libc 的 `pthread_create`**，
与被 hook 代码是否加壳**无关** —— 加壳 SDK 的线程照样能抓到。

### 4.2 静态能给出的关键结论（实测）

`app` 共 115 个创建点，按依赖聚合。最有价值的不是计数，是**形态**：

- **自有代码 62 处、命名率仅 47%** → 一半以上的线程线上不可溯源
- **`androidx.print:print:1.0.0` 4 处全是 `AsyncTask`、命名率 0%**
- **`androidx.core` 9 处**（含 3 个 HandlerThread）

### 4.3 「无界队列」是比「线程多」更严重的问题

工具会把 `new LinkedBlockingQueue()`（无参）单独标红：

```
new LinkedBlockingQueue()  →  capacity = Integer.MAX_VALUE（逻辑无界）
ThreadPoolExecutor 扩容条件 = 「队列满」→ 永不成立
⇒ maximumPoolSize 是死配置，实际并发恒为 corePoolSize，任务无限堆积
```

**这不是「线程多」，是迟早 OOM。** 修复：改用有界队列 + 明确拒绝策略。
（拒绝策略**别用 `CallerRunsPolicy`**：泳道可能被主线程提交，饱和时让主线程跑 IO = ANR。）

### 4.4 运行时归因的设计要点

**① 归因必须跳过「机制帧」**
堆栈从内到外是 `Thread.<init>` → `ThreadPoolExecutor.addWorker` → `SDK 的类` → `业务调用方`。
不过滤的话结论永远是「100% 的线程由 `Thread.start` 创建」——正确但无用。
且过滤**必须按类名语义**，不能按固定下标（池/协程/Rx 的调用深度各不相同）。

**② 「拿不到堆栈」有两种含义，必须分开**
这是 `ThreadOrigin.Creator` 设计成三态而非两态的原因：

| 归因 | 含义 | 价值 |
|---|---|---|
| `JAVA` | 创建者是已 attach 的 Java 线程 | 拿到完整堆栈 = **谁建的** |
| `NATIVE` | 创建者是纯 native 线程 | 它**本来就没有** Java 栈 —— 这本身是结论：「这个线程是某 SDK 的 native 层建的」 |
| `SHUTTING_DOWN` | 创建者正在退出，堆栈不可靠 | 避免误归因 |

若把后两者都归成「未知」，就把「SDK 的 native 层在建线程」这个很有价值的结论淹没了。

**③ 「谁建的」与「谁占住的」是两个不同问题**

- 创建计数 → 谁**频繁**建（短命线程，危害小）
- 存活快照 → 谁**占住**了（常驻线程，直接吃地址空间与调度开销）

一个 SDK 建 500 次但都秒退（不严重），另一个建 20 个全常驻（严重）。
只看创建次数会把两者搞反。

⚠️ 存活快照按「线程**当前**执行位置」归类（`getAllStackTraces` 给的是线程自己的栈，
不是创建者的栈），休眠中的池线程会归到 `park`/`LockSupport`。
所以**两个表不应该逐行对应** —— 报告里明确写了这一点。

**④ 采样上限的语义要精确**
超限后**只计数不抓栈** —— 数量统计仍精确，只失去后 50 个的归因。
（容易写错的是「超限后连计数都不加」，那会让「到底建了多少线程」这个最基础的输出偏小。）

**⑤ 时间戳用单调时钟**
`System.nanoTime()/1e6` 而非 `System.currentTimeMillis()` —— 后者受改系统时间/NTP 校时影响，
会出现「后建的线程时间戳更早」。本类刻意避开 `android.os.SystemClock`
是为了让归因逻辑能在**纯 JVM 单测**里跑（13 个用例，秒级）。

### 4.5 治理手段（按「能否改源码」分层）

这部分原理见 [`线程滥用与治理.md`](../app/src/main/java/com/interview/线程治理/线程滥用与治理.md)，此处只列与本工具配合的落地顺序：

```
1. 先测量 ── sdkAuditDebug（静态画像，零成本）→ 可疑清单
2. 再规范 ── 自有代码：统一线程池 + thread-lint 卡 CI（命名率是直接指标）
3. 后收口 ── 三方：ASM 插桩（需 scope=ALL，见 thread-plugin 的 D13 注释）
4. 兜底   ── Hook pthread_create + 本 runtime 库归因；压缩栈；限流
```

---

## 五、实现里踩过的三个坑（都会导致**静默错误**）

这三个坑的共同特征：**不报错，报告看起来完全正常，只有结论是错的。**

### 坑 1：依赖 id 与产物文件**静默错位**

Gradle 的 `resolvedArtifacts` 是 `Provider<Set<...>>`，**Set 不保证顺序**；
`artifactView.files` 也是 Set。两者各自迭代顺序可能不同。
第一版按下标配对，结果是 `disklrucache` 报出 5.79 MB / 1758 类
（那其实是 `rxjava` 的数）——**体积排名、权限归属全部被污染**。

修复：让输入自带可匹配的键（文件绝对路径），任务侧按路径查表，
**彻底消除对迭代顺序的依赖**。

### 坑 2：判定条件写反，掩盖了最该看的结论

```kotlin
// ✗ 第一版：自有代码也被判成「反射高发」
d.isThirdParty && ...
```
写成了 `!d.isThirdParty || ...` → 自家 manifest 里的权限全部落进「人工确认」。
第一方代码是我们**能读**的，永远不该以「可能反射」为由放弃判定。

### 坑 3：体积字节数取自 ASM 的**非公开字段**

`ClassReader.classFileLength` / `.b` 是「为二进制兼容保留」的非公开 API，
拿它算体积会在 ASM 升级时静默出错 —— 而体积统计出错**不抛异常**，
只给出错误数字，属最坏的一类 bug。

修复：在读取处顺手把 `bytes.size` 通过回调传出，这是唯一稳定的来源。

---

## 六、面试速记

**被问「怎么确定 SDK 声明了哪些权限、有没有真用」**
→ 答双证据链。重点是主动指出**「扫字符串会漏掉绝大多数权限使用」**
（`getDeviceId()` 不需要任何权限字符串），以及四档结论
（自有在用/仅 SDK 用/可移除/反射高发人工）。

**被问「没用的权限怎么删」**
→ 给 `tools:node="remove"` 的具体写法；但强调
「仅 SDK 内部使用」**不能直接删**（删了 SDK 静默失效），
正确方向是移除 SDK / 关功能开关 / 升级。

**被问「依赖重复怎么解决」**
→ 先把两类拆开：版本冲突（被静默提升，用 BOM/constraints）
与重复类（字节码不一致 = 行为不定，用 exclude）。
强调「Gradle 默认仲裁不报错」和「不要盲目 force 到最高版」。

**被问「体积还能怎么降」**
→ 七条按杠杆排序，重点是 native 库（音视频 SDK 常占 50%+）
与「换实现/去重复」（本仓库 rxjava 一个就 5.79 MB，且功能为死）。
主动说「别把独立 application 模块改 dynamic feature」这种伪命题。

**被问「三方 SDK 线程怎么治」**
→ 四层防线 + 强调「静态定方向、运行时定责任」的分工，
以及 `pthread_create` 是唯一收口点（**加壳 SDK 也能抓到**）。
加分项：主动讲「拿不到堆栈有两种含义」与「无界队列比线程多更严重」。

---

## 七、当前状态

- [x] 插件四维度静态审计，实测产出报告（md + json）
- [x] `sdk-governance-runtime` 运行时归因库 + 13 个单测全绿
- [x] 报告内置局限声明（每次运行强制输出）
- [x] 三个静默错误已定位并修复，附复现依据
- [ ] **Hook 接线**：`runtime` 库是「记录与报告层」，
      native `pthread_create` hook 回调接入它需在
      [`thread_hook.cpp`](../app/src/main/cpp/thread_hook.cpp) 侧增加 JNI 回调
      （复用既有 `NativeThreadHook` 的 GOT Hook 方案，属独立改动）
- [ ] 重复类的**逐字节比对**（当前保守标为不一致）
- [ ] `sdk-audit.json` 接 CI 阈值卡口（如「可移除权限增加则失败」）
- [ ] 权限反查表补充厂商 ROM 私有 API
