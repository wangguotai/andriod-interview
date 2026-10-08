# 线上内存监控（JVM + Native）实验模块

本目录是一个**可跑的**线上内存监控实验模块：把 JVM 堆、Native、图形内存
三条观测线装进进程生命周期，并给出三条**归因路径**回答"涨的是谁"。

⚠️ 本模块**不新建上报通道**：内存事件经
`StabilityReporter.forceReport(...)` 走**稳定性监控的同一条出口**
（`kind = MEMORY_HIGH / MEMORY_CRITICAL / MEMORY_TRIM / MEMORY_TREND`）。
理由在 `INTERVIEW-线上ANR监控方案.md` §2.2 已经论证过：**采集源会越加越多，
收敛点只能有一个**——内存是第三个来源（前两个是稳定性、线程治理）。

实验页入口：`MemoryLabActivity`，已登记在首页目录
（`HomeCatalog` 的「内存监控 Lab」）。所有 `TAG`：
`MemoryMonitor`（总装 + 实验页）、`MemoryMetrics`（指标层）、
`MemoryAnalyze`（归因/直方图/门闸）、`MemTrace`（native hook，C 侧同名）。

与既有 `image/MemoryProbe.kt` 的分工：那是**离线教学探针**（三个数，给大图 Lab
当场演示降采样）；本模块是**线上口径**（堆上限取 `min(maxMemory, memoryClass)`、
native 拆三层、收 `onTrimMemory` 平台原话、带基线与斜率）。**两者不复用类名**。

### 本文与 `INTERVIEW-线上内存监控方案.md` 的分工

同目录的 **[`INTERVIEW-线上内存监控方案.md`](./INTERVIEW-线上内存监控方案.md)**
是**端到端方案设计稿**（面试口径）：四条本质区别、服务端聚合口径、上报预算、
`M1~M12` 原始测量记录。**本文是操作手册**：代码里**实际是什么行为**、按钮怎么点、
边界在哪、以及踩过的坑。

⚠️ 分工要清楚，否则两边会互相抄、一起过时：
**本文只写"代码里能核实的行为"**（每条结论都能在源码里找到依据）；
方案稿写"为什么这样设计 + 实测数字"。数字冲突时**以本文的代码行为为准**，
但方案稿附录 A 的原始测量记录是**唯一出处**（本文不复制全套原始数据）。

设备基线（本会话所有实测数字的来源）：模拟器 `Medium_Phone_API_36`
（Android 16，arm64-v8a，Google Play 镜像，**无 adb root**，debug 可用 `run-as`）。

---

## 一、模块定位与三条观测线

`MemoryMetrics` 能告诉你「Java 堆到了 78%、native 涨了 30 MB」，
但**不能**告诉你「涨的是谁」。而线上处置动作完全取决于这个答案，所以本模块是
**总量监控 + 归因**两层：

| 层 | 回答什么 | 载体 |
|---|---|---|
| 指标层 | 用了多少、比基线涨没涨 | `MemoryMetrics`（快照 + 采样 + 趋势） |
| 归因层 | 涨的是**谁** | `MemoryAllocTracker` / `MemoryHeapAnalyzer` / `BitmapTracker` |
| 总装 | 生命周期接线、阈值判定、台账 | `MemoryMonitor` |

三条观测线（互相**不替代**，都要能自己造出来才算"监控有效"）：

```
  ① JVM 堆   —— ByteArray 负载打这条线（MemoryMonitor.makeHeapPressure）
  ② Native   —— 走 native 分配器要内存打这条线（MemTraceNative.allocBlocks）
  ③ 图形内存 —— Bitmap 负载打这条线（与 image Lab 的位图互补）
```

⚠️ 设计纪律：**"会改变被测系统的探针"必须显式开启，且"开着"这件事要能被看见**。
所以 native 归因探针（挂在全局 malloc 热路径）与堆直方图（stop-the-world）
**都不在** `MemoryMonitor.install()` 里自动装，只在实验页手动装，
并在「总览」里显示开关状态。这与 `StabilityMonitor` 不自动装 SIGQUIT 通道是同一个判断。

---

## 二、采集通道

```
JVM 水位   used = totalMemory() - freeMemory()   ← 存活对象（"真水位"）
           committed = totalMemory()             ← ART 已要到手的容量（含空白）
           max       = maxMemory()               ← 硬上限
           heapLimit = min(maxMemory(), memoryClass*1MB)  ← 水位分母（见下）
GC/ART     Debug.getRuntimeStats()（art.gc.*，**进程累计值必须差分**）
容器压力   ComponentCallbacks2.onTrimMemory(level)  ← 系统主动给的，唯一权威
Native 三层 Debug.getNativeHeapAllocatedSize（净账）/ nativePss（内核）/ VmRSS
分配器内部  mallinfo2 (used/free/mmap) + malloc_info（尺寸档，**仅小对象**）
图形内存   summary.graphics（系统口径）+ BitmapTracker（我们代码路径的自计数）
```

### 2.1 Java 堆："上限以谁为准"是最容易做废数据的口径

`Runtime.maxMemory()` 是 ART 的**硬上限**，厂商还会通过
`ActivityManager.getMemoryClass()` 下发一个**更小的软上限**。
实测（API 36 模拟器）：`maxMemory()` = 268435456（256 MB）、
`memoryClass` = 256、`largeMemoryClass` = 512。

⇒ `Snapshot.heapLimitBytes` 定义为 **`min(javaMax, memoryClass*1MB)`**，作为唯一水位分母。
只拿 `maxMemory()` 算，UI 会显示"才用了一半"而实际已贴到软线，
「快 OOM 了」这个信号永远不触发。

`used` 与 `committed` 的语义**必须分开**：GC 后 `used` 掉下来、`committed` 不还
（ART 的 `HeapGrowthLimit` 策略下不会立即还）——把 `committed` 当 `used`
是线上最常见的误判。而 `committed == max` 又是 OOM 的真实前兆。

### 2.2 trim：唯一权威的内存压力信号，且**必须用 `when` 精确匹配**

我们自己算的水位是**进程视角**（"我用了多少"）；系统给的 trim 级别是**整机视角**
（"外面还有多少"）。两者会背离，且**整机视角经常更准**：

```
本进程水位 40%（看着很安全）
但系统发来 TRIM_MEMORY_RUNNING_CRITICAL
⇒ 真实情况：整机快没内存了，我们随时被杀
```

⚠️ **实证级别的坑**：`TRIM_MEMORY_UI_HIDDEN(20)` 与 `TRIM_MEMORY_RUNNING_*`
是**两个不同的语义轴**（可见性 vs 内存压力），但数值交叉分布
（UI_HIDDEN=20 恰好落在 RUNNING_LOW=10 与 RUNNING_CRITICAL=15 之上）。
所以**必须用相等比较，绝不能写 `level > 10` 这种阈值判断**——那样会把
"退到后台"(20) 误判成"内存极度紧张"，在低端机上刷出假告警。
代码固化在 `MemoryMetrics.trimName` / `isPressureLevel` 的 `when` 里。

上报纪律（`MemoryMonitor.trimCallback`）：
`UI_HIDDEN` **不报**（一天几十次，与内存无关）；压力轴级别**必报**，
但同一级别重复发时**去重**；`onLowMemory` 单独一条（API 14+ 已废弃，
但低版本设备上仍是最后信号）。

### 2.3 Native 不是"一个数"，至少三层（面试的分水岭）

```
① Debug.getNativeHeapAllocatedSize()  ← malloc/free 净账（瞬间反映 free）
② nativePss（Debug.getMemoryInfo）     ← 内核视角：本进程 native 私有分摊页
③ /proc/self/status VmRSS              ← 进程全部驻留（含 code/graphics/stack）
```

⚠️ 三者**互不相等，且谁大谁小不固定**：大块分配可能走 mmap 而非主 arena
（①记它但②未必等量涨）；位图在 native 堆却归到 graphics 段（不计入②）；
释放后①立刻减、②要等页真正归还。⇒ 判断 native 泄漏要看
**①持续涨 且 ②/PSS 同步涨**，单看某一个都会误判（`Trend.analyze` 里的
`nativeSyncedRise` 就是这条，且**按比例判**：native 涨幅 ≥ Java 涨幅一半才算"同步"，
因为 Java 涨 80MB 而 native 只抖几百 KB 时说"同步"会把方向指错）。

### 2.4 分配器读数：`malloc_info` 只能看小对象，**不能算总量**

⚠️ 实测本机分配器是 **scudo**（`malloc_info` 返回 `<malloc version="scudo-1">`），
不是 dlmalloc。`malloc_info` 只列 **primary（≤65536 B）尺寸档**，
大块走 secondary 不出现：

> 实测：8 笔 100000 B 的存活分配 → `malloc_info` **只输出 157 字节**（只有头尾标签）。

⇒ 所以本模块**不能**拿 `malloc_info` 算总量，只能拿它看**小对象碎片**；
总量一律用 `mallinfo2().uordblks`。尺寸档表在报告里显式标注"只含小对象"。
（`parseSizeClasses` 按 `size × count` 排序——数量多但都很小的档位
与数量少但都很大的档位，后者才是账上主角。）

### 2.5 采样与趋势：为什么必须有"斜率"层

`Sampler` 在主线程 Handler 上跑，间隔 **2000ms**、环形 **256 点 ≈ 8.5 分钟**窗口
（默认 1s 采样信息增益低，且同样容量覆盖的窗口只会更短）。
页面显示单次采样**平均耗时**（`avgCostMs()`）——监控要能自证没拖慢主线程。

`Trend.analyze` 刻意保持"能口算"：

```
基线 base = 窗口内最小值（不是第一个点！）    峰值 peak = 最大值    当前 last
① last ≤ base + 4MB              → STABLE
② peak - base ≥ 16MB             → 涨过：再分
      last ≤ base + 4MB          → GROW_AND_FALL 涨了会回落（缓存，正常）
      否则                        → LEAK_SUSPECT 涨了不回落 ⚠️
③ 否则                            → STABLE
```

⚠️ 基线取**最小值**而非第一个点：采样起点随机落在"刚进页面"还是"稳定后"，
取第一个点会让判决**依赖运气**；最小值是该窗口的实证下界，接近"最小工作集"。
**不用线性回归斜率做判据**：内存曲线天生是 GC 锯齿，回归斜率几乎只反映窗口长度。
要估泄漏速率，正确做法是只取**每次 GC 之后的谷值**再回归——本模块提供
`Trend.gcValleys`（依据 `art.gc.gc-count` 变化），但**默认不开启**（短窗口更不稳）。

⚠️ 诚实边界：**斜率不是泄漏的证明**，只给出"值得看一眼"。

---

## 三、两个 native hook 的实现要点与安全纪律

`memtrace.cpp` 用 **bytehook 1.0.10** 做 PLT/GOT hook，hook
`malloc/free/calloc/realloc` **四个符号 × 所有模块**。

⚠️ 版本是**实测卡出来的**，不是随手挑：bytehook **1.1.2** 的 AAR metadata
写了 `minCompileSdk=37`，而本仓库 `compileSdk = 34` + AGP 8.3 ——
直接依赖会让**所有**构建失败，报「依赖要求 compileSdk ≥ 37」这种与内存毫无关系的错。
实测 **1.0.10** 的 `minCompileSdk=1`，可用，且已带 prefab + 四个 ABI。

集成方式（prefab）：`buildFeatures { prefab = true }` +
`implementation("com.bytedance:bytehook:1.0.10")` + CMake 里
`find_package(bytehook REQUIRED CONFIG)` / 链接 `bytehook::bytehook`。
已验证 APK 里 `libbytehook.so` **只有一份**（prefab 与 `jni/` 两份副本被正确合并，无重复）。

### 3.1 两条硬纪律（少一条就是线上事故）

| 纪律 | 不做的后果 |
|---|---|
| **不在 hook 里 malloc** | hook 一旦装上，我们自己的任何 `malloc` 都会被自己 hook 到 → 递归。所以记账表全部 `mmap` 出来（`initTables`）、不用 STL，并用 `thread_local g_inHook` 防重入 |
| **不在 hook 里 dladdr** | 会重入动态链接器锁：`dlopen → malloc → dladdr` **死锁**。改为**出报告时**（`g_paused` 已开）用 `dl_iterate_phdr` 快照模块表，把地址解析成 `模块+偏移` |

（`unwind` 抓栈、`tryLock` 有限自旋、`shouldTrack` 抽样都在这两条约束内完成。）

### 3.2 有损采样口径（如实暴露）

```
全量跟踪 >= 8192 B
[64 B, 8192 B) 抽样 1/256
栈只抓 4 帧（跨过 __rust_alloc 这类包装层）
拿不到锁就跳过（不计）
```

报告里如实打出四个缺口计数器：`skippedContended` / `freeSkipped` /
`unknownFree` / `addrOverflow`。已知偏差也随数字一起附在报告尾：
`free` 拿不到锁会让存活字节**偏高**、地址表满会让它**偏低**。
站点表（512 槽）满时**不丢弃**，并入 `[OTHER]` 桶——丢弃会让总账对不上，
出现无法解释的缺口。

### 3.3 ⚠️ 踩过并修掉的两个真实 bug（都**不会报错**，只让报告看起来"像没开符号"）

**① 栈帧存成 `uint32_t` 截断了高 32 位。**
arm64 的地址高 32 位丢失后 `resolveFrame` **永远匹配不到任何模块**，
报告里只剩 `0x2ef58268` 这类裸地址。已改为 `uintptr_t`。

**② 模块偏移基准算错。**
原来取 `dlpi_phdr[0]` 想拿第一个 LOAD 段，但 **`phdr[0]` 是 `PT_PHDR` 不是 `PT_LOAD`**
（arm64/Android 实测），于是"第一个 LOAD"的条件在 `continue` 后**永远不成立**，
`base` 恒为 0（mmap 内存是清零的）→ 偏移 = 绝对地址，报告里打出
`libart.so+0x754873f504` 这种"偏移跟地址一样长"的鬼东西。
改成「遍历时记录第一个 `PT_LOAD` 的 `dlpi_addr + p_vaddr`」后正常，
实测输出 `libart.so+0x33f504`。

### 3.4 符号化链路（设备上只给"模块+偏移"）

线上 App 里**没有符号表**（Release 只带 `.dynsym`），所以设备端**故意不做符号解析**，
报告只携带 `module+offset`，由 `tools/native-mem-symbolize.sh` 用本机 NDK
（25.1.8937393）的 `llvm-symbolizer` 离线还原。**端到端已验证**：

```
libmemtrace.so+0x17268  →  my_malloc                        memtrace.cpp:434
libmemtrace.so+0x19750  →  Java_..._allocBlocksNative       memtrace.cpp:923
```

正好对上「点【造 Native 负载】→ 走 native 分配器要 32MB」这条因果链。

⚠️ **系统库（`libart.so` / `libhwui.so`）解不出来**：设备上 pull 下来的 `.so`
符号表是 strip 的，`llvm-symbolizer` 返回 `??  ??:0:0`。**必须用与 build-id 匹配的
symbol file**（官方 per-build 符号包 / AOSP `symbols/`），且**用错的 symbol file
会给出错误但看起来合理的函数名**——比解析不出来更危险。脚本对这种情况
**明确标注"需 symbol file"而不是猜**。我们要归因的是**自己代码**的分配，
系统库帧只提供上下文。

---

## 四、归因三路径与各自能力边界

| 路径 | 回答什么 | 成本 | 本机（模拟器 API 36 / arm64） |
|---|---|---|---|
| `MemoryAllocTracker`（native hook） | native 里**哪个函数**要的内存 | 热路径开销，需显式安装 | ✅ 实测可用 |
| `MemoryHeapAnalyzer`（Java 直方图） | Java 堆里**哪一类对象**占了多少 | 一次 dump（秒级冻结） | ✅ 自研 hprof 解析 |
| `BitmapTracker`（图形内存） | 位图几张、共多少、谁占大头 | 每次创建一次原子累加 | ✅ |

### 4.1 路径一：Native 分配归因（`memtrace`）

**默认不装**（与 ANR 的「通道 4」同一纪律）：它挂在全局 malloc 热路径上，
**会改变被测对象**（所以装/不装的数字不能直接对比）。
`MemoryAllocTracker.report()` **必须在后台线程调用**（几十毫秒 + 几 KB 文本），
且出报告前 `pause(true)`——报告自己的分配不进账，避免污染正在看的数字。

⚠️ 实测（点【造 NATIVE 负载】32×1MB 后）：

```
site#0 = 32.00 MB / 32 笔
liveBytes 33.05 MB  vs  mallinfo2.uordblks 53.26 MB
```

两个数字**不等是正常的**（harness 与 ART 的其它分配也计入 uordblks）；
报告里同时给两者，正是为了让读者看到差额而不是被单一数字骗。
**刻意不提供运行时卸载**：卸载后已分配的块 `free` 就看不见了，
存活字节会永远停在那里，比"不卸载"更容易被误读成泄漏——要停观察用 `pause`/`reset`。

### 4.2 路径二：图形内存（`BitmapTracker`）

线上 OOM 成因里位图长期排第一，且它有个迷惑性：**API 26+ 像素数据在 native 堆，
Java 堆上"看不见"**——"Java 堆没涨却 OOM 了"十有八九是位图。

不持有任何 `Bitmap` 引用、不用 `WeakHashMap`，改**分类计数**（按来源/Config 累加）。
⚠️ 字节数必须用 `allocationByteCount` 而**不是** `byteCount`：`inBitmap` 复用时
（Glide 的 BitmapPool）两者不一致，用 `byteCount` 会**系统性低估**。

⚠️ **诚实边界**：计数依赖调用方老实调用，Glide 内部创建的位图**不经过这里**
（除非挂 `RequestListener`）。所以 **本计数恒 ≤ 系统 `summary.graphics`**，
差额 = 三方库 + 系统资源，**差额是信息不是 bug**：差额大 ⇒ 图形内存大头不在我们的代码路径上。

### 4.3 路径三：Java 直方图（`HeapDumpGate` + `HeapDiff`）

**抓 dump 是"取证"不是"监控"**，所以有两道闸门：

- `HeapDumpGate`：同一时刻只允许一次（`AtomicBoolean`），两次之间最小间隔
  **10 分钟**。`dumpHprofData` 是 **stop-the-world** 的，并发两次不会有"两倍信息"，
  只有**两倍的冻结时间**；没有闸门的自动触发会在内存告急时把自己压死。
- 落盘有界：`cleanupOldDumps(keep = 2)`，超出自动清理（与 `CrashJournal` 同一纪律）。
- 取证动作**失败必须可见**（抛异常由调用方处理），与"采样失败静默降级"**刻意相反**。
- 后台泳道饱和（`ThreadPools.background` 提交失败）时把闸门**放回去**，
  别让"提交失败"白白吃掉一个窗口期。

`HeapDiff.diff` 是纯函数、无 Android 依赖（可在宿主 JVM 单测）。
单张直方图只能看"谁最大"（可能一直如此、属正常）；
**差分**才回答"谁在这次操作里**涨了**"——那才是要动手改的地方（默认阈值 256KB）。

---

## 五、自研 hprof 解析器与 shark 对拍

`HeapHistogramParser.kt`（`MemoryHeapAnalyzer`）**自己解析 hprof**，不依赖
MAT / LeakCanary / Shark。取舍：线上绝大多数内存问题是「某一种对象数量远超预期」，
直方图直接指出；**循环引用泄漏**（数量正常但没人放开）才需要支配树——
那是下一个工具，不是这个工具能顺带解决的（MAT / Shark 依赖在本仓库离线缓存里也没有）。

实现要点：

- **单遍流式**，只保留 `类名 → (count, bytes)` 聚合表，单条记录载荷**按长度跳过不缓存**。
- 上限 `MAX_DUMP_BYTES = 64MB`，超过直接拒绝（避免把"一个内存问题"变成两个）。
- `CountingInputStream` 用**绝对 position** 划 segment 边界（`bodyStart + len`）。
  ⚠️ **不能用 `BufferedInputStream.available()`**：它的语义是"缓冲区里还有多少字节"，
  与"本段还剩多少"无关，会提前结束或越界读下一段（第一版就是这么错的）。

关键格式事实（都是从 LeakCanary **shark 2.14** 源码逐条核对来的）：

| 事实 | 说明 |
|---|---|
| INSTANCE **自描述** | `0x21` 带 `u4 remainingBytesInInstance` ⇒ **不需要**先扫 CLASS_DUMP 拿 instanceSize，**单遍即可** |
| `0xFE HEAP_DUMP_INFO` | = `u4 heapId + ID`（heapId 是 **Int 不是 ID**）——这是**最容易失步**的 tag |
| GC-root 各子 tag 宽度不统一 | 1 个 ID：`0xFF/0x05/0x07/0x89/0x8B/0x8C/0x8D/0x90`；2 个 ID：`0x01`；ID+2×u4：`0x02/0x03/0x08/0x8E`；ID+u4：`0x04/0x06` |
| 字段类型 `2` | = 对象引用，宽度是 **`idSize` 而不是 1**（这处写错会**静默**失步） |
| 基元数组宽度 | **tag 值即宽度**：`4/8=1B、5/9=2B、6/10=4B、7/11=8B` |
| `0xC3 PRIMITIVE_ARRAY_NODATA` | **不可解析**（shark 也直接抛），本实现中止该 segment |
| CLASS_DUMP 不计字节 | 它是类元数据，计入会虚增 |

遇到未知子 tag 只能中止该 segment（不知长度，硬猜必然错位），
并把 `skippedSegments` **计数暴露出来**——静默偏低比留白更危险。

### 与 shark 的对拍（本模块最强的正确性证据）

真实 dump（**45.28 MB / 47,476,497 B**，idSize=**4**）实测：

```
类 31,027 个 · 对象 459,948 个 · 解析 1041 ms
  （= shark 的 INSTANCE 242,779 + PRIMITIVE 181,632 + OBJ_ARRAY 35,537；CLASS_DUMP 不计入对象）
0 个 segment 因未知 tag 中止 · 0 个未匹配类名 · 总字节 19.71 MB
```

**逐类对拍全部 4996 个类的数量与字节完全一致**（全等，不是"误差在 1% 内"）。
基准 `app/src/test/resources/real_dump_shark_classes.csv` 由 shark 生成，
生成器 `tools/shark-oracle/Oracle.java`，与本实现**没有共用代码**——
这正是需要独立 oracle 的原因：自研解析器只跟自己造的迷你 hprof 对拍，
格式理解错了测试和实现会**一起错、还一起通过**。

另一条实测：`Debug.dumpHprofData` = **913 ms / 45.28 MB**（这就是它绝不能放主线程的原因）。

单测：**38 通过**（`HprofParserTest` 11 + `MemoryMetricsLogicTest` 25 + `RealDumpTest` 2）。
⚠️ 不提供真实 dump 时 `RealDumpTest` 报 **skipped**（用 `Assume`，
**故意不写成"通过"**）——在一个"防假绿"比防红灯更重要的模块里，
让未执行的检查看起来是通过的，是最坏的一类错误。

```bash
./gradlew :app:testDebugUnitTest --tests "*RealDumpTest*" \
    -Dmem.realHprof=/path/to/heap.hprof
```

---

## 六、实验页按钮清单

| 按钮 | 作用 | 对应的面试点 |
|---|---|---|
| 0.接入监控 / 总览 / 清空 | 看接入清单、`formatOverview()`、清全部内存态 | 全局视角 |
| 堆快照 | 单点快照 + 水位迷你波形（`sparkline`） | 一次采样的口径 |
| 加压64MB / 释放压力 | Java 堆负载（`ByteArray`）；**不主动 GC**，让数据说话 | used vs committed |
| 触发GC | 测 GC 耗时 + `art.gc.gc-count` 变化，**验证它到底生没生效** | GC 建议 ≠ 命令 |
| 抓直方图(SW冻结) | `HeapDumpGate.request` → hprof 直方图（Top20 字节/数量） | 归因路径三 |
| 压力前后差分 | 第二份 dump 与基线做 `HeapDiff` | "谁在这步涨了" |
| 直方图历史 | `HeapDumpGate.lastResult` | 取证留档 |
| Native快照 | 三/四条线一起显示（**互不相等**是重点） | native 三层 |
| 装归因探针 | `MemoryAllocTracker.install()`（幂等，含降级原因） | native 归因 |
| 归因报告 | 后台泳道出报告 + 结构化站点解析（Top1） | "哪一行代码要的" |
| 造 Native 负载 / 释放 Native | 32×1MB 走 native 分配器，**不占 Java 堆** | 位图/缓冲在 native |
| 归因清零 | `reset`（刻意不卸探针） | 存活字节的误读 |
| 造位图(8×1MB) / 释放位图 | `BitmapTracker` 计数 + 系统 graphics 对照 | 图形内存差额 |
| 趋势报告 | `flushTrend` + GC 谷值包络 | 采样/趋势/斜率 |
| 模拟trim压力 | 走**系统同一条路径**逐个级别触发，看压力轴判定 | trim 语义轴 |
| 跨会话台账 | `crossSessionReport` + 冷启动台阶 | 跨会话泄漏形态 |
| 内存事件 | 统一出口里 `MEMORY_*` 事件流水 | 与 StabilityReporter 接缝 |
| 开上报驱动 / 立即上报 / 已提交数据 | 周期+进后台+手动触发；查看**模拟提交**了什么 | "采集到了怎么到平台" |

页面接线（与稳定性模块同纪律：**由页面自己挂**，不在
`ActivityLifecycleCallbacks` 里给每个页面自动挂）：`onResume` →
`MemoryMonitor.onActivityResumed`（记基线）；`onPause` →
`MemoryMonitor.onActivityPaused`（出本页收口报告）。

---

## 七、生产接线

`MyApplication.onCreate` → `MemoryMonitor.install(this)`
（⚠️ **必须在 `onCreate` 后调用**：`attachBaseContext` 里 `ActivityManager` 可能还没
准备好，且 trim 回调注册需要 app context 稳定）。

```kotlin
override fun onCreate() {
    super.onCreate()
    StabilityMonitor.installOnCreate(this)
    MemoryMonitor.install(this)   // ① 注册 ComponentCallbacks2；② 起 2s 采样器
    // ⚠️ 刻意不在这里装：native 归因探针（malloc 热路径）、hprof（stop-the-world）
    //    —— 两者入口都在「内存监控 Lab」页，且"开着"这件事在总览里可见。
}
```

事件出口**不新建通道**：`MemoryMonitor` / `trimCallback` 全部经
`StabilityReporter.forceReport(Event(kind = MEMORY_*))` 进统一出口。
`Kind` 枚举里内存相关的是 `MEMORY_HIGH` / `MEMORY_CRITICAL` / `MEMORY_TRIM` /
`MEMORY_TREND`，**追加在枚举末尾**（保持"顺序即优先级"的既有约定：
崩溃 > ANR > 卡顿，内存靠后）。

上报采样率（`MemoryMonitor` 类注释 §二）：

| 事件 | 采样 | 依据 |
|---|---|---|
| 水位越级 NORMAL→HIGH→CRITICAL | **必报，但只在越级时** | 稀有、每条都是"该看版本了" |
| trim 压力级别 | **必报** | 系统的判断，最权威，且不频繁 |
| trim 的 UI_HIDDEN | **不报** | 只是"退到后台"，一天几十次 |
| 周期趋势 `MEMORY_TREND` | 1/5 会话 | 聚合后信息密度高、量大 |
| 大对象 / 直方图 | 显式触发 | 秒级冻结，绝不自作主张 |

⚠️ **"只在越级时报"是这份设计的核心**：水温是**连续量**，每次采样都报的话，
一次内存紧张会刷出上百条事件——那不是监控，是噪音。越级（状态机边沿）才是"变化"。
`onSampled` 里只对**升高**产生事件（从 CRITICAL 落回 NORMAL 是"GC 生效了"，
不需要一条上报，但会体现在下一次周期聚合里）。

### 上报驱动（`StabilityMonitor.ReportDriver`）—— **模拟提交，数据在 App 内可看**

⚠️ 在补它之前，全仓**没有任何**定时器/`WorkManager`/`AlarmManager` 调用 `flush`，
唯一会 `flush` 的是实验页那个按钮 —— 链路断在最后一步：
`采集 → 判定 → 环形缓冲 → ✗ 停在这里`。这与 ANR 那侧是**同一个缺口**
（复用同一个出口，所以补一次两边都通）。

| 触发点 | 时机 | 为什么需要 |
|---|---|---|
| 周期 | 默认 **30s**（实验页可临时切到 5s 观察） | 长时间不开后台也能上 |
| **进后台** | `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` | ⭐ 大量会话是"**退到后台后再也没回来**"，不在这时发，整个会话的数据随进程回收一起消失 |
| 手动 | 「立即上报」 | 与周期走**同一段代码**（`flushNow`），避免"手动能清账、周期不能" |

三条纪律：
1. **不在主线程**：走 `ThreadPools.scheduled`（治理过的线程池）。
   ⚠️ 不可裸用 `new Thread` / `Executors` / `HandlerThread`（ASM + lint 会拦）。
2. **进后台触发 ≠ UI_HIDDEN 变成一条事件**：前者是"把已缓冲的发出去"，
   后者是"产生新事件"。两件事 —— **UI_HIDDEN 仍然不产生事件**。
3. **库侧默认关、App 侧显式开**：`MyApplication` 里调了 `ReportDriver.start()`（否则链路是断的）；
   ⚠️ 关掉时 `onEnterBackground()` 必须 **no-op**，
   否则"默认关"就是假话（"关"= **一条都不发**，不是"少发一条"）。

**本仓库的 upload 是「模拟提交」（刻意如此）**：真实上传要 host/token，
写进开源 demo 属于"演示项泄露成生产配置"。所以注入的是**不联网**的实现 ——
只把批次记进 `StabilityReporter.uploadLog`（有界只读镜像，保留最近 10 批）+ 打 logcat。
接平台时替换**一个 lambda**：`upload = { platform.send(it) }`。

⚠️ **两个语义陷阱**：
- `upload` 返回 `true` 表示"**已发送成功**"，只有 true 才清账（从环形缓冲移除）。
  模拟实现恒 true 是**故意的**（让链路可验证），**接真实平台必须换成真实成败** ——
  否则会出现"发送失败但事件已清掉"的数据丢失。
- 清账后事件从 `snapshot()` **立刻消失**，实验页会像"点完上报列表就空了"。
  所以另留 `uploadLog()` 回答"刚才到底发了什么"（实测：`批次#1：2 条 | ANR=1 MEMORY_CRITICAL=1`）。

### 跨会话台账（`appendJournal` / `readJournal` / `crossSessionReport`）

`Sampler` 的环形缓冲随进程消失，而**"每次冷启动都比上次高一个台阶"恰恰是最典型的
线上内存问题形态**——单次会话完全看不出来。所以有一条跨进程存活的最小台账：

- 落盘 `filesDir/memory/session-memory.tsv`，上限 **200 行**，超限丢最旧。
- ⚠️ **允许丢，故意不 fsync**（与崩溃遗嘱的区别：台账不是证据，是趋势）。
  给它 `fd.sync()` 会让每次采样付磁盘同步的代价，而它承载的信息量远不值这个价。
- `crossSessionReport` 把每个会话的第一个采样挑出来比（判据：时间间隔 > 60s），
  首个会话 → 最近会话抬升 **>8MB 且 ≥3 个会话**时提示"跨会话泄漏"，
  ⚠️ 但同时提醒：也可能是"本次安装后功能变多"，必须结合 trace 看是哪类对象在涨。

### GC 统计

来自 `Debug.getRuntimeStats()`（`art.gc.*`）。⚠️ 这些是**进程累计值，必须做差分**——
`Trend.gcValleys` 用 `art.gc.gc-count` 的**变化**定位 GC 之后的谷值。
⚠️ 计数增加**不代表该点就是谷值**（GC 可能发生在采样间隔中间），
所以只是**近似包络**，用于看方向，不能算精确泄漏速率。

---

## 八、诚实边界

本模块**做不到**的事：

1. ~~`onSample` → CRITICAL 时没有自动落"内存遗嘱"~~ —— **已修复**。
   现已在**水位升到 CRITICAL 的那一个采样点**当场落台账（只因上升沿触发，
   全进程最多写一次，不违反"采集点零 IO"）。
   实测末行：`... 200437328  201326592 ... CRITICAL:NORMAL->CRITICAL`（191.15MB / 192.00MB = 99.6%）。
   ⚠️ **但仍有残余缺口**：遗嘱里目前只有水位 + trim + native 总量，
   **没有** native 的 Top-N 站点快照（那需要 `pause` 探针后遍历，成本更高）；
   且 `appendJournal` 仍**不 fsync**，极端情况下（进程被杀前 RST 页面未刷）仍可能只留一部分。
2. **smaps 级映射聚合尚未独立成 `SmapsReader`**。目前 native 视角用
   `MemoryInfo` / `VmRSS` 口径，还拆不到"so / 字体 / 资源"的映射明细。
3. **系统库帧需要匹配的 symbol file 才能符号化**（见 §3.4），
   否则只能标"需 symbol file"，不猜。
4. **native 归因是有损采样**，只回答"大头在哪"，**不回答**"有没有漏 200KB"。
   高频小对象泄漏会被漏掉（那要 `malloc_debug` / heapprofd，不是本模块的定位）。
5. **Java 直方图只有"类 → 数量/字节"、没有引用链**，定位不了
   "数量正常但没人放开的循环引用"（那需要支配树）。
6. **`BitmapTracker` 只统计经过我们代码的位图**，恒 ≤ 系统 `graphics`，
   差额是信息不是 bug。
7. **趋势判决 `LEAK_SUSPECT` 不是泄漏的证明**，只是"值得看一眼"；
   基线取窗口最小值、阈值是固定经验值，长/短窗口行为不同。
8. **`Runtime.gc()` 只是建议**：ART 有权忽略，所以按钮对比的是
   "gc-count 有没有真的增加 + used 有没有真的掉"，不假定它生效。
9. **`SwapPss` 在 API 30 以下恒为 0**；`egl / gl` 是 ROM 相关项（无 GPU 驱动统计时缺项）。
10. **`memtrace` 不提供运行时卸载**（会制造"存活字节永远停在那"的误读，
    见 §4.1），停观察只能用 `pause`/`reset`。

### 已知缺口（会漏看的场景）

- ⚠️ **上报出口是"模拟提交"**：驱动（周期 + 进后台 + 手动）已实现且实测可跑，
  但**真实 `upload`**（host/token）、**远程可调采样率**、**失败重试与退避**都还没做 ——
  这三项是上生产的必要条件。另：遗嘱目前只有水位 + trim + native 总量，
  **没有** native Top-N 站点快照（那需要 `pause` 探针后遍历，成本更高）。
- 阈值是**本地默认值**（HIGH 70% / CRITICAL 85%，低内存设备降档 10%），
  生产口径应可远程下发；本模块给的是"能跑的默认值"。
- 低内存设备的降档系数是**实测经验**（HIGH→60%、CRITICAL→75%），非通用公式。

---

## 九、环境与构建约束

- 设备基线：模拟器 `Medium_Phone_API_36`（Android 16，arm64-v8a，
  Google Play 镜像，**无 adb root**，debug 可用 `run-as`）——
  本模块所有实测数字都来自这台。
- `compileSdk = 34`、`minSdk = 24`、`targetSdk = 33`；`ndkVersion = 25.1.8937393`。
- **`ndk.abiFilters = arm64-v8a`** ⇒ 32 位 / 非 arm64 设备上 `memtrace`
  必然不可用（`loadLibrary` 失败 → 优雅降级），其余内存监控完全不受影响。
  `MemTraceNative` 的读数 API 全部返回可空/带 `available` 标志，**不抛异常**。
- **JNI 边界必须在 ASCII 包下**：导出符号形如 `Java_<包名下划线化>_...`，
  C++ 标识符不允许非 ASCII ⇒ JNI 声明层落在 `com.interview.memtrace.MemTraceNative`，
  业务层仍在中文包 `com.interview.内存`，中间由这一个薄类隔开。
- **bytehook 必须锁 1.0.10**（1.1.2 的 `minCompileSdk=37` 与 compileSdk 34 冲突，
  见 §三），且需 `buildFeatures { prefab = true }`。
- **符号化需要未 strip 的库**：`intermediates/merged_native_libs` 与 APK 里的
  都是 strip 过的，只能给导出符号、**没有行号**；脚本默认取
  `app/build/intermediates/cmake/debug/obj/<abi>/`（未 strip）。
