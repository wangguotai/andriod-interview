# 线上内存监控方案（端到端设计稿）

> 起因：`readme.md` day 2.22 那场面试的薄弱点是「**ANR 的概念、检测机制、线上发生 ANR 如何监控**」。
> 同目录 [`INTERVIEW-线上ANR监控方案.md`](../稳定性监控/INTERVIEW-线上ANR监控方案.md) 回答了 ANR 那一问，
> 并把**采集侧**做成了可跑的 [`StabilityLabActivity`](../稳定性监控/StabilityLabActivity.kt)。
>
> 本文回答它的**姊妹问题**：**内存**怎么监控 —— JVM + Native 两条线。
>
> 全文口径同 ANR 文档：**只写本机实测过的**。本次新测的数字统一带 `M1~M12` 编号，
> 原始记录在 [附录 A](#附录-a模拟器测量原始记录)；
> 未验证的显式标 `[未验证]`；实测**推翻/修正**了我自己第一版做法的，标 `⚠️实测`。
>
> 本文档写作时，采集侧**已经能跑**（[`MemoryLabActivity`](./MemoryLabActivity.kt) 可交互），
> 上报链路已接通到**模拟出口**（`StabilityMonitor.ReportDriver`：周期 + 进后台触发，
数据在 App 内可查看；见 §五、§八）—— 但**真实 upload 出口与远程配置仍未做**，
仍属"实验版到线上版"的最后一公里。我把这个边界写在最前面，避免把设计意图当成已实现。

**设备**：Android 模拟器 `Medium_Phone_API_36`（**Android 16 / arm64-v8a** / Google Play 镜像），
包 `com.example.myapplication`（debug）。
⚠️ 与 ANR 文档的 Redmi K40 真机**不是同一台**，所以本文所有数字**只在本环境成立**，
跨机型外推时带上这个前提。

---

## 〇、一句话定位：本文与既有模块的分工

```
采集（本文 §二~四）                          出口（复用，§五）
──────────────────────                       ──────────────────────
JVM 水位 / GC / trim  ─┐
Native 三层（分配器/PSS/RSS）─┼→ MemoryMonitor ─→ StabilityReporter.forceReport(Event)
图形内存 / 位图       ─┘                            │
                                                    ▼
                                          批量 / 分级 / 退避 / 配额（已有）
```

一句话结论（也是面试可以先抛的那句）：

> **内存监控与 ANR 监控最大的不同是"上限"这件事**：Java 堆有一个**明确的软/硬上限**，
> 于是"离满还有多远"可以做成一个**可判级的水位**；而 native 内存**没有上限**，
> 它只会一路涨到被系统整进程杀掉 —— 所以 **native 侧不能用水位判级，只能用"斜率/增长"**。
> 这两条线必须用两套判据，混用会得出"native 只用了 30 MB，很健康"这种**毫无意义**的结论（实测 M8）。

---

## 一、四条本质区别：为什么"内存监控"不能照抄"ANR 监控"

这节是全文的地基。四条区别各自决定了一个设计点，后面每一节都能映射回这里。

| # | 区别 | 后果（设计点） |
|---|---|---|
| 1 | Java 堆**有上限**（软=memoryClass，硬=maxMemory）；**native 没有上限** | JVM 用**水位判级**；native 用**斜率判级**（§二.1 / §二.2） |
| 2 | **OOM 是内核直接杀进程**，不是"抛异常让我们接住" | 证据必须**在临界那一刻落盘**，不能等"稍后统一上报"（§五） |
| 3 | 内存曲线**天生锯齿**（GC sawtooth），不像帧耗时那样单调可比 | 判据取**窗口最小值做基线**，且**不用线性回归**（§二.3） |
| 4 | Native 占用**在 Java 里看不见**（位图/解码缓冲/三方库） | 必须有一条**独立的** native 观测线 + 分配归因（§三） |

### 1.1 区别 2 的现场：为什么"遗嘱"必须在临界当场写

这是我这一版**修掉的一个真实缺口**（自我纠错，记在这里）：

第一版把台账只挂在 `onActivityResumed/Paused` 上（"进页面/退页面各记一笔"）。
看起来足够——但它漏掉了线上**最典型**的内存事故链路：

```
进页面 → 内存一路涨到临界 → 被系统杀掉
         └ 这条链路上**没有 pause**！进程是被内核 KILL 的，不会走生命周期回调
```

也就是说：这条链路上台账**一条记录都不会留下** —— 恰恰是最需要证据的那种情况。

**修法**：在水位**升到 CRITICAL 的那一个采样点**立刻落遗嘱。
实测证据（M11）：

```
files/memory/session-memory.tsv 最后一行：
1791441362099  2026-10-08 14:36:02  200437328  201326592  ...  CRITICAL:NORMAL->CRITICAL
```
换算（本模块 `fmt` 用 **1024 基**）：javaUsed = 200437328 B = **191.15 MB**，
heapLimit = 201326592 B = **192.00 MB** ⇒ **99.6%**，正好越过 CRITICAL(85%)。
⚠️ 这两个字节数要**原样引用**，别在文档里换成十进制 MB ——
1000 基会算成"200.4 MB / 201.3 MB"，看着合理但**与本模块自己的口径不符**。

⚠️ 这**不违反**本模块"采集点零 IO"的纪律，理由要能当场说清：
这段代码位于 `level.ordinal > from.ordinal` 之内（**只在上升沿触发**），
一旦进入 CRITICAL，`lastLevel` 就停在 CRITICAL，不会再次进入 ——
**整个进程生命周期里它最多写一次**，是有界的一次写，不是"每次采样都写盘"。

---

## 二、三条观测线，以及每一线的口径

先把"口径"钉死。**内存监控最容易做成废数据的地方全在这里** —— 同一个"内存占用"，
在四个 API 下能给出四个不同的数，而且谁大谁小**不固定**。

### 2.1 JVM：一个水位，但分母必须取两个上限的**较小者**

```
used      = totalMemory() - freeMemory()    ← 存活对象（"真水位"）
committed = totalMemory()                   ← ART 已向系统要到的内存（含空白）
max       = 上限
```

实测（本机，M1）：`Runtime.maxMemory()` = **268435456（256 MB）**、
`ActivityManager.memoryClass` = **256**、`largeMemoryClass` = **512**。

⚠️ **两个上限都要看**：`maxMemory()` 是 ART 的**硬**上限；厂商还会通过
`getMemoryClass()` 下发一个**更小的软**上限。只拿 `maxMemory()` 当分母，UI 会显示
"才用了一半"而实际已经贴到软线 —— 于是"快 OOM 了"这个信号**永远不触发**。

所以 [`MemoryMetrics.Snapshot.heapLimitBytes`](./MemoryMetrics.kt) 定义为**两者取小**，作为唯一的分母。

**分级（默认值，生产口径要求可远程下发）**：

| 级别 | 判据 | 为什么是这个数 |
|---|---|---|
| HIGH | used ≥ **70%** 软上限 | ART 的 `HeapGrowthLimit` 策略在接近上限时 GC 频率**指数上升** —— **卡顿先于 OOM 到来** |
| CRITICAL | used ≥ **85%** 软上限 **或** committed ≥ **95%** 硬上限 | committed 贴硬上限 = 下一次大分配**没有退路** |

⚠️ **低内存设备必须单独降档**：`isLowRamDevice` 的机器上（Go 版 / 1GB 机型）系统给的
后台余量极小，70% 就已经很危险。实测经验口径：这类设备把 HIGH 降到 **60%**、
CRITICAL 降到 **75%**（代码里是 `bump = 0.10f`）。

⚠️ **"只在越级时报"是这份设计的核心，不是省事**：水位是**连续量**。
每次采样都报的话，一次内存紧张会刷出**上百条**事件 —— 那不是监控，是噪音。
越级（状态机**边沿**）才是"变化"，才值得一条事件。

### 2.2 Native：不是一个数，是**三层**，而且用水位判它是错的

```
① Debug.getNativeHeapAllocatedSize()   ← malloc/free 的净账（**分配器视角**）
② MemoryInfo.nativePss                 ← 内核视角：本进程 native 私有的**分摊**页
③ /proc/self/status  VmRSS             ← 进程**全部**驻留（含 code / graphics / stack）
```

⚠️ **三者互不相等，且谁大谁小不固定**：
一个 8 MB 的 malloc 在 API 26+ 可能来自 mmap 而不是主 arena，
于是 ① 会把它**排除在外**，而 ②③ 会把它算进去。

**关键设计点（区别 1）**：native **没有上限**，所以 **native 侧不判水位、只判斜率**。
实测一组能说明问题的对照（M8）：

```
某时刻：nativeAlloc = 53.28 MB（Java 堆此时 5.34 MB，几乎不动）
```

如果拿"native 用了 53 MB"去比某个水位线，那是**伪指标** —— 53 MB 没有任何意义，
真正有意义的是"**它在涨吗、涨多快、涨了会不会回落**"。

**分配器视角的两个坑（实测 M2）**：

1. 本镜像的 allocator 是 **scudo**，不是 dlmalloc（`malloc_info` 输出头是
   `<malloc version="scudo-1">`）。
2. `malloc_info` **只输出 primary（尺寸档）分配器的存活统计** ——
   大块分配（走 secondary / mmap）**根本不在里面**。
   实测：8 笔 100000 B 的存活分配 → `malloc_info` 只输出 **157 字节**（只有头尾标签）。

⚠️ 所以 `malloc_info` **不能**用来算总量，**只能**拿它看**小对象碎片**。
这不是 bug，是 scudo 的实现边界 —— 但它非常容易让人误以为"分配器报告里没多少内存，
所以 native 没问题"。

### 2.3 趋势判据：为什么是"窗口最小值做基线"，而不是回归斜率

**算法（刻意保持"能口算"）**：

```
基线 base = 窗口内**最小值**        ← 不是第一个点！
峰值 peak = 窗口内**最大值**
当前 last = 最后一个点

① last ≤ base + 回落容差(4MB)      → STABLE            回来了，没事
② peak - base ≥ 上涨阈值(16MB)     → 涨过（再分两种）
    └ last ≤ base + 回落容差        → GROW_AND_FALL   涨了会回落 = 缓存，正常
    └ 否则                          → LEAK_SUSPECT    涨了不回落 ⚠️
③ 否则                              → STABLE
```

**为什么基线取最小值而不是第一个点**：采样起点随机落在"刚进页面"还是"页面稳定后"，
取第一个点会让判决**依赖运气**。最小值是该窗口内的**实证下界**，
更接近"这个 App 的最小工作集（min working set）"这个物理含义。

⚠️ **为什么不用线性回归的斜率做判据**（这一点面试很常被追问）：
内存曲线**天生不单调**（GC sawtooth）。对锯齿曲线做回归，得到的斜率**几乎只反映采样窗口长度**，
而不是泄漏速率 —— 换个窗口长度，斜率就变，结论就变。
要估泄漏速率，正确做法是**只取每次 GC 之后的谷值**再回归
（依据 `art.gc.gc-count` 的变化定位谷值），本模块在 `Trend.gcValleys` 里提供了这个判据，
但**默认不开启**：它需要足够长的窗口，短窗口上会比最小值法更不稳。

**采样间隔为什么是 2s 不是 1s**（M10）：
- 内存问题是**分钟级**的（对比卡顿是毫秒级），1s 采样信息增益极低；
- 环形缓冲是**内存有界**的，采得越密，同样容量覆盖的时间窗越短。
  **2s × 256 点 ≈ 8.5 分钟**窗口，足够看清"涨了有没有回落"。

**GC 统计的一个必须注意的口径（M9）**：
`Debug.getRuntimeStats()` 给的 `art.gc.*`（gc-count / gc-time / bytes-allocated / bytes-freed）
是**进程累计值** —— 必须**做差分**才有意义。
直接把 `art.gc.bytes-allocated` 上报，那是个只会涨的里程表，答不了"这段涨了多少"。

### 2.4 trim：系统给的**整机视角**，是唯一权威的内存压力信号

我们自己算的水位是**进程视角**（"我用了多少"）；`onTrimMemory` 给的级别是
**整机视角**（"外面还有多少"）。两者会**背离**，而且整机视角经常更准 ——
它说的就是"为什么这个 App 在特定机型上被杀"。

⚠️ 但 trim 级别**不能一律上报**：`TRIM_MEMORY_UI_HIDDEN` 只是"退到后台"，
一天几十次，与内存压力**无关**。所以只上报**压力轴**级别
（`RUNNING_LOW` / `RUNNING_CRITICAL` / `COMPLETE`）。
⚠️ 代码里用 `when` **精确相等**匹配而不是 `level > 10` 这类阈值比较 ——
trim 常量是**位标志语义**（`UI_HIDDEN=20` 就夹在 `RUNNING_CRITICAL=15` 和
`RUNNING_LOW=10` 之间），用大小比较会把不相关的事件（如 `UI_HIDDEN`）判成内存压力。

---

## 三、Native 归因：为什么"知道涨了 30 MB"还不够

`MemoryMetrics` 能告诉你「native 涨了 30 MB」，但**不能**告诉你「**涨的是谁**」。
而处置动作完全取决于这个答案：

```
native 涨 → 找分配者（我们的 .so？三方库？位图？） → 该改算法 / 该升级库
两者都不涨但 PSS 涨 → 找映射（so / 字体 / 资源）   → 该瘦身包体 / 该按需加载
```

### 3.1 选型：引 `bytehook`

本仓已有自研的 GOT/PLT hook（`app/src/main/cpp/thread_hook.cpp`），但本次选择引入
**bytehook 1.0.10**（字节跳动）。理由与避坑：

- **版本必须锁 1.0.10**：`1.1.2` 声明 `minCompileSdk=37`，与本仓 `compileSdk=34` 冲突（构建期直接拒绝）；
  `1.1.0` 可用但最终锁在 **1.0.10**。
- **集成方式走 prefab**：`buildFeatures { prefab = true }` +
  `implementation("com.bytedance:bytehook:1.0.10")` + CMake 里
  `find_package(bytehook REQUIRED CONFIG)` / 链接 `bytehook::bytehook`。
- ⚠️ **一个必须验证的打包风险**：该 AAR **同时**带了 `jni/` 和 `prefab/` 两份
  `libbytehook.so` 副本，理论上可能在 APK 里落地**两份**同名 so（会引发加载不确定）。
  实测（M12）：APK 里 `lib/arm64-v8a/libbytehook.so` **只有一份**（63520 B）——
  AGP 的 merge 环节正确去重了。

### 3.2 hook 内两条**硬纪律**（违反任何一条都会死锁或崩溃）

这是本节最重要的部分，比"怎么装 hook"重要得多：

**① hook 里绝对不能 malloc。**
被 hook 的就是 malloc 本身 —— 在 hook 里再调 malloc = 直接递归。
所以追踪表**全部**是固定大小的 `mmap` 开放寻址数组（**不用 STL**、不用 `std::map`），
外加 `thread_local` 重入标志。

**② hook 里绝对不能 `dladdr`。**
`dladdr` 会**重入动态链接器锁**，而路径上又可能触发 `malloc`：

```
dlopen → malloc → dladdr  →  与 hook 内的 malloc 互相等待 ⇒ 死锁
```

**替代方案**（这是正确做法）：hook 内**只存裸地址**；
出报告时（此时可安全上锁）用 `dl_iterate_phdr` 快照模块表，
把地址解析成 `模块名+偏移`。

### 3.3 归因实测：能精确指到"哪一行代码要的内存"（M7）

点【造 NATIVE 负载】（32 × 1 MB）后的真实报告：

```
sites: used=106 liveBytes=34499038(32.90 MB) liveCount=65
site#0 bytes=33554432(32.00 MB) count=32 peak=32.00 MB frames=4
  frame[0] libmemtrace.so+0x17268      ← my_malloc            memtrace.cpp:434
  frame[1] libmemtrace.so+0x19750      ← Java_..._allocBlocksNative  memtrace.cpp:923
  frame[2] libart.so+0x33f504
  frame[3] libart.so+0x328198
```

`site#0 = 32.00 MB / 32 笔` 与"32 × 1 MB"**逐字对上**；frame[0..1] 符号化后
正好是"点按钮 → 走 native 分配器要 32 MB"这条**因果链**（符号化见 §3.5）。

⚠️ **有损采样（必须如实暴露，不能悄悄丢）**：
全量 ≥8192 B；`[64B, 8192B)` 抽样 **1/256**；拿不到自旋锁就**跳过**。
计数器 `skippedContended` / `freeSkipped` / `unknownFree` / `addrOverflow` 全部出现在报告里。
它回答"**大头在哪**"，**不回答**"有没有漏 200 KB"。要精确账请用 smaps（§四.3）。

### 3.4 ⚠️实测：两个**只会静默出错**的 native bug

这两个都**不崩溃、不报错**，只是让报告给出看似合理但完全错误的内容 ——
比崩溃危险得多，记在这里当案例：

**① 栈帧截断（`uint32_t` vs `uintptr_t`）**
帧地址存成 `uint32_t` 时高 32 位被截掉（实测拿到 `0x2ef58268`），
于是 `resolveFrame` **永远匹配不到任何模块**，报告里只剩裸地址。
改 `uintptr_t` 后正常。⚠️ 这个 bug 在 **32 位设备上完全不会出现** ——
只在 arm64 上现形。

**② 模块偏移基准取错（`phdr[0]` 不是第一个 `PT_LOAD`）**
第一版想用 `dlpi_phdr[0].p_vaddr` 拿"第一个 LOAD 段的起始"，但
**`phdr[0]` 是 `PT_PHDR`，不是 `PT_LOAD`**（arm64/Android 实测）。
后果：`if (i == 0) base = ...` 这个条件在 `continue` 之后**永远不成立**，
`base` 保持 mmap 清零后的 **0**，偏移算成 `ip - 0` = **绝对地址**：

```
修之前： libart.so+0x754873f504        ← 偏移跟地址一样长（一眼就能看出不对）
修之后： libart.so+0x33f504
```

正确做法：**遍历时记录第一个遇到的 `PT_LOAD`**，基准是
`dlpi_addr + 第一个 PT_LOAD 的 p_vaddr`（不是单独的 `dlpi_addr` —— vaddr 可能是 0
但**不保证**，用错会让所有符号整体偏一个常量，同样**不会报错**）。

### 3.5 符号化必须**离线**做（线上不做）

线上报告只携带 **`module+offset`**（稳定、体积小、不依赖设备环境），
由构建侧/事后用本机 NDK 的 llvm-symbolizer 还原 —— 这是 Android 平台的标准做法
（`ndk-stack` / perfetto symbolize 同理）。

工具：[`tools/native-mem-symbolize.sh`](../../../../../../../tools/native-mem-symbolize.sh)
（实测 M7 的输出即由它产生）。

⚠️ **两个必须知道的边界**：

1. **必须用未 strip 的库**。`merged_native_libs` 和 APK 里的都是 strip 过的，
   只能给导出符号、**没有行号**。脚本默认取 `intermediates/cmake/debug/obj/<abi>/`。
2. **系统库（libart.so / libhwui.so）解不出来，这是事实**。
   实测：从设备 pull 的 `libart.so`（12.8 MB）用 llvm-symbolizer 解，输出是
   `??  ??:0:0` —— 符号表被 strip 了。系统库只带 `.dynsym`，而帧偏移常落在
   **内部静态函数**上。要解析**必须**有与 **build-id 严格匹配**的 symbol file
   （官方镜像用 Google per-build 符号包；自编译镜像用 AOSP `out/.../symbols/`）。
   ⚠️ 用**对不上**的 symbol file 会给出**错误但看起来合理**的函数名 —— 比解析不出来更危险。
   所以脚本对这种情况**明确标注"需 symbol file"，不猜**。
   要归因的是**自己代码**的分配（那才是可改的部分），系统库帧只提供上下文。

---

## 四、Java 归因：自研 hprof 直方图解析器

### 4.1 为什么自己写

`Debug.dumpHprofData` 出一个 45 MB 的 dump 只要 **913 ms**（实测 M5），
但在**线上**（尤其低端机）用它做解析会：
- 需要把整份 dump 读进内存（数百 MB）→ 反而 OOM；
- 依赖 MAT/Shark 这种重库。

我们的需求很窄：**只要"类 → 数量/字节"的直方图**，不需要引用链。
那就单遍流式扫一遍，只累加两个 `HashMap` —— 内存占用与 dump 大小**无关**。

### 4.2 ⚠️实测：三个格式坑，每一个都会让解析静默错位

我从 **LeakCanary 的 shark 2.14 源码**逐条核对格式（不是凭"常见描述"），
纠正了自己第一版的**前提性错误**：

**坑 ① INSTANCE 记录是自描述的 —— 不需要两遍扫描。**
我第一版以为"必须先从 CLASS_DUMP 拿到 `instanceSize` 才能跳过 INSTANCE"，
于是设计成两遍扫描。**这是错的**：

```
INSTANCE = ID objectId, u4 stackTraceSerial, ID classObjectId,
           u4 remainingBytesInInstance, u1[remainingBytes] fieldValues
                                 ↑ 长度自带了
```

正确设计是**单遍**，而且 `remainingBytesInInstance` 是更准的字节数（含 padding）。

**坑 ② `0xFE HEAP_DUMP_INFO` 是 Android 专属，最容易失步。**
格式是 `u4 heapId + ID`（注意 **heapId 是个 Int，不是 ID**）。
我第一版完全不认识这个 tag —— 它就是我暴力扫描时反复撞到的"未知 tag `0xfe`"。
⚠️ 这个坑连 shark 的调用方也会踩：如果你把 `HEAP_DUMP_INFO` 放进
`readRecords` 的 tag 集合，就**必须**在回调里把子记录读掉，忘了读就整段失步
（报 `Unknown tag 0x00 at ... after 0xfe`，看着像文件坏了，其实是自己没读完）。

**坑 ③ 字段类型 `2`（对象引用）的宽度是 `idSize`，不是 1。**
基元类型的"tag 值本身就是宽度"（4/8=1B、5/9=2B、6/10=4B、7/11=8B），
但 **`2` 是引用**，宽度 = `idSize`。这一条写错会在"某个类恰好有引用类型的 static 字段"时
**整段静默失步** —— 读到错位后可能刚好又撞上某个合法 tag，于是继续解析、数字全错。

**顺带记下的几处**（都是从 shark 源码核对的）：
- GC-root 各子 tag **宽度不统一**：1 个 ID 的是 `0xFF/0x05/0x07/0x89/0x8B/0x8C/0x8D/0x90`；
  2 个 ID 的是 `0x01`；`ID + 2×u4` 的是 `0x02/0x03/0x08/0x8E`；`ID + u4` 的是 `0x04/0x06`。
  "GC root 都是一个 ID 宽"是**错的**。
- `0xC3 PRIMITIVE_ARRAY_NODATA` **不可解析**（shark 直接抛异常）。
- 对象数组字节数 = 元素数 × `idSize`。

**防错位的工程兜底**：每个 segment 记下**绝对 end 位置**，逐个 tag 走；
遇到未知 tag 就**丢弃该 segment 的全部贡献**并计入 `skippedSegments`，
而不是继续读出一堆垃圾数字。报告里 `skippedSegments > 0` 会显式提示"数字偏低"。

### 4.3 正确性证据：与 shark **逐类**对拍（这是本模块最强的证据）

**⚠️ 只跟自己写的迷你 hprof 对拍是不够的** —— 迷你 hprof 是按"我以为的格式"造的，
格式理解错了，测试和实现会**一起错、还一起通过**。
我的第一版解析器就是"在自造样例上通过、一上真实 dump 就
`ArrayIndexOutOfBoundsException`"（真实 dump 有 **9893** 个 segment，
里面全是我没料到的东西）。

所以基准换成 **shark 2.14**（与我的实现**没有任何共用代码**）
逐条流式读**同一份真实 dump**，输出逐类统计。
生成器入库在 [`tools/shark-oracle/Oracle.java`](../../../../../../../tools/shark-oracle/Oracle.java)，
基准数据入库在 `app/src/test/resources/real_dump_shark_classes.csv`。

**对拍结果（真实 dump：47,476,497 B / 45.28 MB，M6）**：

| 指标 | shark | 本解析器 |
|---|---|---|
| idSize | 4 | 4 |
| 类数 | 31,027 | 31,027 |
| 对象总数 | 242,779 + 181,632 + 35,537 = **459,948** | **459,948** |
| 逐类（数量 + 字节） | — | **4,996 个类全部完全相等** |
| 因未知 tag 中止的 segment | — | **0** |
| 未匹配类名的实例 | — | **0** |

逐类样例（第一名就这么对上了）：

```
byte[]              140844 个   7.73 MB
java.lang.String    110764 个   2.11 MB
java.util.HashMap$Node 36456 个 0.83 MB
```

⚠️ 对拍口径必须写清楚，否则数字对不上时无从判断谁错：
shark 的 **490,975** 是**全部堆记录数** = CLASS_DUMP + INSTANCE + PRIMITIVE + OBJ_ARRAY；
本解析器**不把 CLASS_DUMP 计入对象**（它是类的元数据），
所以正确期望值是 `490,975 − 31,027 = 459,948`。

**⚠️ 为什么"总数对上"不能算证据**：只要"多算的类"恰好等于"少算的类"，
**总数就会一致而逐类全错**。所以最强的是**逐类**断言，而且是**全等**、不是"误差 1% 内"。

**单测现状**（M6）：**38 通过**
（`HprofParserTest` 11 + `MemoryMetricsLogicTest` 25 + `RealDumpTest` 2）。
真实 dump 不进版本库，通过 `-Dmem.realHprof=<path>` 传入：

```bash
./gradlew :app:testDebugUnitTest --tests "com.interview.内存.*" -Dmem.realHprof=/path/to/dump.hprof
```

⚠️ 不提供 dump 时这两个对拍用例报 **skipped**（用 `Assume`），
**故意**不写成"通过" —— 让**未执行**的检查看起来是通过的，
在一个"防假绿比防红灯更重要"的模块里是最坏的一类错误。

### 4.4 图形内存与位图

`BitmapTracker` 在每次位图创建/释放时做一次原子累加，回答"位图几张、共多少、谁占大头"。

⚠️ **它恒 ≤ 系统的 `summary.graphics`**：它只统计**经过我们代码**的位图。
**差额是信息不是 bug** —— 它说明图形内存的大头不在我们的代码路径上
（可能是 Surface/硬件位图/系统字体缓存）。

---

## 五、出口与上报预算：复用统一出口

**本模块不新建上报通道**（与 ANR 文档 §2.2 的结论一致：**"采集源会越加越多，
收敛点只能有一个"**）。内存事件走同一套：

```
MemoryMonitor（采集与判定）
     │  StabilityReporter.forceReport(Event(kind = MEMORY_*))
     ▼
StabilityReporter（唯一出口 + 有界环形缓冲 + 采样率 + 分类配额）
     │  StabilityMonitor.flush { batch -> platform.upload(batch) }
     ▼
平台（可替换）
```

⚠️ 如果内存自带一套限流/字段/重试，线上就会重演"**一次内存告警把崩溃配额打满**"。

**`Kind` 枚举的约定**：内存相关项 **追加在枚举末尾**，保持既有的
「**顺序即优先级**」约定不被破坏（枚举序 = 上报优先级，插在中间会静默改变既有语义）。
现有：`MEMORY_HIGH` / `MEMORY_CRITICAL` / `MEMORY_TRIM` / `MEMORY_TREND`。

**采集侧采样率（内存比卡顿量更大，必须比"必报"更克制）**：

| 事件 | 采样 | 依据 |
|---|---|---|
| 水位越级（NORMAL→HIGH→CRITICAL） | **必报**（但**只在越级时**） | 稀有，每条都是"该看版本了"的信号 |
| trim 压力级别 | **必报** | 系统自己的判断，最权威，且不频繁 |
| trim 的 UI_HIDDEN | **不报** | 与内存无关，一天几十次 |
| 周期聚合（趋势报告） | **1/5 会话** | 聚合后信息密度高，量大 |
| 大对象/直方图 | **显式触发** | 秒级冻结，绝不自作主张 |

**跨会话台账（"允许丢"的台账）**：`appendJournal` / `readJournal` / `crossSessionReport`，
落在 `files/memory/session-memory.tsv`。
⚠️ 为什么需要它：**采样器的环形缓冲随进程消失**，而"跨会话的泄漏"（每次冷启动
都比上次高一个台阶）恰恰是**最典型的线上内存问题形态**，单次会话里完全看不出来。

⚠️ 它与**崩溃遗嘱**（`CrashJournal`，走 `fsync`）的取舍**不同**：台账**允许丢**，
所以**故意不 fsync**（给它 fsync 会让每次采样都付磁盘同步的代价，
而它承载的信息量远不值这个价）。

### 5.1 上报驱动（`StabilityMonitor.ReportDriver`）—— 本次补上的那个缺口

在补它之前，全仓**没有任何**定时器/`WorkManager`/`AlarmManager` 调用 `flush`，
唯一会 `flush` 的是实验页的「模拟上报」按钮。后果是链路断在最后一步：

```
采集 → 判定 → 环形缓冲 → ✗ 停在这里（数据永远到不了平台）
```

这不只是内存的问题 —— 它与 ANR 文档 §1 审出的「周期性上报驱动不存在」
是**同一个缺口**，因为两边复用同一个出口，所以**补一次两边都通**。

**三条触发点 + 三条纪律**：

| 触发 | 时机 | 为什么需要 |
|---|---|---|
| 周期 | 默认 **30s**（实验页用 5s 便于观察） | 保证长时间不开后台也能上 |
| **进后台** | `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` | ⭐ 移动端大量会话是"**退到后台后再也没回来**"，不在这时发，整个会话的数据随进程回收一起消失 |
| 手动 | 实验页「立即上报」 | 与周期驱动走**同一段代码**（`flushNow`），避免"手动的能清账、周期的不能" |

1. **不在主线程跑**：丢到 `ThreadPools.scheduled`（治理过的线程池：命名、优先级、
   拒绝策略、队列深度可观测）。⚠️ 不能裸用 `new Thread` / `Executors` / `HandlerThread` ——
   ASM 插件 + lint 会直接拦（本仓的既有约束）。
2. **进后台触发 ≠ 把 UI_HIDDEN 变成一条事件**。前者是"把已缓冲的发出去"，
   后者是"产生一条新事件"。两件事，别混（UI_HIDDEN 仍然**不产生事件**）。
3. **库侧默认关、App 侧显式开**：`ReportDriver` 默认不跑（与 SIGQUIT 通道、
   native 归因探针同一纪律），但 `MyApplication` 里**显式** `start()` ——
   ⚠️ 库侧保持默认关，是为了不替调用方决定"要不要自动消耗唤醒 + IO"；
   App 侧必须显式开，否则链路仍是断的。⚠️ 关掉时
   `onEnterBackground()` 必须 **no-op** —— 否则"默认关"就是假话
   （"关"的语义是**一条都不发**，不是"少发一条"）。

### 5.2 ⚠️ 本仓库的 upload 是**模拟提交**（刻意如此）

真实上传需要 host/token，把那些写进开源 demo 属于"**演示项泄露成生产配置**"。
所以注入的是一个**明确标注为模拟**的 upload：**不联网**，只把批次记进
`StabilityReporter.uploadLog`（有界只读镜像，保留最近 10 批）+ 打一行 logcat。
接真实平台时替换**一个 lambda** 即可：`upload = { platform.send(it) }`。

⚠️ **两个必须记住的语义陷阱**：

1. `upload` 返回 `true` 的语义是"**已发送成功**"，只有 true 才清账（从环形缓冲移除）。
   模拟实现恒返回 true 是**故意的**（好让链路完整可验证），
   **接真实平台时必须换成真实的成败** —— 否则会出现"发送失败但事件被清掉"的数据丢失。
2. 清账后事件从 `snapshot()` **立刻消失**，实验页上会表现为"点完上报，列表空了"。
   所以另留了 `uploadLog()` 这个**只读镜像**来回答"刚才到底发了什么"。
   实测（M13）：`批次#1：2 条 | ANR=1 MEMORY_CRITICAL=1`，且列表里能看到
   `[MEMORY_CRITICAL] level:NORMAL->CRITICAL` 的完整 detail。

---

## 六、服务端要什么：指标定义与聚合口径

客户端只负责"**在正确的时刻，把正确的字段送出去**"；能不能答出问题，取决于服务端的口径。
这一节是**给服务端/数仓的接口约定**，也是面试里"那你们怎么算内存问题"的正解。

### 6.1 事件宽表（客户端已能产出，字段与 `Event` 一一对应）

| 字段 | 类型 | 来源 | ⚠️ 口径陷阱 |
|---|---|---|---|
| `kind` | enum | `StabilityReporter.Kind` | 内存相关只 4 个：`MEMORY_HIGH`/`MEMORY_CRITICAL`/`MEMORY_TRIM`/`MEMORY_TREND` |
| `timestamp` | epoch ms | `System.currentTimeMillis()` | 用**客户端**时间，跨时区统一 UTC 存储 |
| `name` | string | 如 `level:NORMAL->CRITICAL` | **不是自由文本**，是可切成维度的枚举串 |
| `valueMs` | long | 内存事件恒 **0** | 量纲由 kind 决定；内存事件把数值放 `detail`，不要塞进 `valueMs`（那是卡顿的耗时） |
| `isForeground` / `isMainThread` | bool | 采集时现场取 | **必须采集时定死** —— 事后从别的表 join 会错（那时已在前台） |
| `pid` / 会话 id | int / long | `Process.myPid()` / 会话标记 | 聚合的**最小分组单位**，见 6.2 |
| `detail` | string（KV） | `Snapshot.toEventDetail()` | ⚠️ **上采样率后必须仍能分辨"哪一档"**，见 6.4 |

⚠️ **`valueMs` 与 `detail` 的关系要说清**：内存事件的"数值"是**多字段**的
（used / committed / limit / native 三层 / trim level），塞不进一个 `long`。
把它压成 `valueMs` 会丢掉口径（是 used 还是 committed？对谁取比？），
所以**内存事件一律 `valueMs=0`**，数值进 `detail` 的结构化 KV。

### 6.2 三条必须由服务端算、客户端算不了的指标

| 指标 | 分子 / 分母 | ⚠️ 为什么客户端算不了 |
|---|---|---|
| **会话组水位（分位数）** | 按**会话**取水位 p50/p90/p99 / 总会话数 | 单个客户端只能看到一条曲线；"这个版本的 p90 抬了 5%"是**跨设备聚合**出来的 |
| **内存致杀率** | `ExitInfo(REASON_LOW_MEMORY)` 的启动数 / 总会话数 | 客户端**看不到自己被谁杀的**，只能靠**下次启动**用 `ApplicationExitInfo` 回捞（与 ANR 文档 §1.1 同一机制） |
| **越级率** | 发生 `MEMORY_CRITICAL` 的会话数 / 总会话数 | 需要跨会话去重（同一个会话只计一次），客户端无全局视角 |

⚠️ **"内存致杀率"是内存模块的北极星指标** —— 它比 p90 水位更接近"用户受损"：
水位高但没被杀，用户无感；水位不高却被杀（后台压力），用户下次回来就是冷启动。
它需要把 `MEMORY_TRIM` / `MEMORY_CRITICAL` 与 `ExitInfo.reason=LOW_MEMORY` **关联**，
所以客户端**必须**送够 pid/会话标记，否则服务端关联不上。

### 6.3 聚合维度（按优先级）

```
① 版本（versionCode）      ← "这个版本抬了"是最主要的可行动信号
② 机型 + isLowRamDevice    ← 内存问题与机型的相关性**远高于** ANR
③ 前台页面（pageTag）      ← "哪个页面在涨"（来自 onActivityPaused 的窗口趋势）
④ 会话阶段（冷启/后台/前台）← 后台被杀要单独看
```

⚠️ **机型维度必须带 `isLowRamDevice`**，否则会把 Go 版机型和旗舰机混在一张图上 ——
前者的"正常水位"在后者看来是事故（阈值本来就不同档，见 §2.1）。

### 6.4 ⚠️ 一个最容易埋雷的地方：聚合与采样率的相互作用

内存事件带采样率（趋势报告 1/5 会话）。**服务端做 sum 必须除以采样率、
做均值/分位数则不能**：

| 统计量 | 采样后的正确做法 | 做错会怎样 |
|---|---|---|
| 计数（越级次数、被杀次数） | **加权还原**（×1/采样率） | 系统性**低估** 80% |
| 均值 / p50 / p90 | **直接算，不要加权** | 加权后分位数**偏移**（高采样权重的会话被放大） |
| 比值（率） | 分子分母**同口径**（都采样或都不采样） | 分子采样、分母全量 ⇒ 率**偏低** |

⚠️ **这条是内存模块特有的坑**：ANR/崩溃是"必报"，不存在这个问题；
内存的周期聚合天然带采样率，第一次接服务端几乎一定会踩。

### 6.5 告警：分"率"和"绝对值"两档，且必须带分母

| 档 | 触发 | 为什么 |
|---|---|---|
| **率** | 内存致杀率 > 基线 × 1.5 | 分母是总会话数，**可比、可跨版本** |
| **绝对值** | `MEMORY_CRITICAL` 会话绝对数 > 阈值 | 防"率降但量升"（版本铺开、大盘上涨） |

⚠️ **率和绝对值**都要**，只有其中一个都会漏**：只看率，用户量涨了会漏；
只看绝对数，小版本会被大版本的量淹没。与 ANR 文档 §3.3 是同一个判断。

### 6.6 隐私

内存事件**不上报**任何对象内容、类名字符串以外的堆数据（直方图只上报
"类名 → 数量/字节"的 Top-N 聚合，不上报实例内容）；
trim / 水位是纯数值；页面标识来自 `Activity` 类名（**不含**用户数据）。
⚠️ 直方图里的类名可能间接暴露第三方 SDK —— 生产上报前需过**类名白名单/脱敏**。

---

## 七、面试速答版（先背这一页）

**Q：线上内存怎么监控？**

> 分**两条线**，判据完全不同 —— 这是第一句就要说清的：
>
> **JVM 侧有上限，做水位**。分母取 `min(Runtime.maxMemory(), memoryClass × 1MB)`
> （只取 maxMemory 会漏掉厂商的软上限，导致水位永远不触发）；
> 分子用 `total - free`（**不是 committed** —— committed 是容量，GC 后不还，
> 拿它当"用了多少"会看到一条永不下降的假曲线）。分 HIGH 70% / CRITICAL 85%，
> **只在越级时上报**，不是每次采样。
>
> **Native 侧没有上限，做斜率**。三个视角要分开讲：分配器净账
> （`getNativeHeapAllocatedSize`）、内核 PSS、进程 VmRSS —— 三者不相等是因为
> 大块 malloc 可能走 mmap 不进主 arena。判据用"窗口最小值做基线 + 是否回落"，
> 拆成 `GROW_AND_FALL`（缓存，正常）与 `LEAK_SUSPECT`（涨了不回落）。
> **不用线性回归**，因为内存曲线是 GC 锯齿，回归出的斜率只反映窗口长度。
>
> **要能指出是谁**，所以有归因：native 用 hook（bytehook 挂 malloc 族）拿到
> 调用栈 —— 实测能精确指到"32 笔 × 1 MB = 32.00 MB"以及**是哪一行代码**要的；
> Java 用单遍流式解析 hprof 出直方图。
>
> **OOM 不是异常是内核杀进程**，所以证据必须在临界当场落盘，不能等"稍后统一上报"。

**Q：为什么 native 内存"用了 53 MB"不能作为告警依据？**
> 因为 native **没有上限** —— 53 MB 在 2 GB 机器上是健康，在 512 MB 机器上要死了。
> 水位需要一个**分母**，native 没有这个分母。所以只能看**趋势**：
> 涨得快不快、涨了会不会回落、是哪个调用点涨的。

**Q：你怎么保证自己写的 hprof 解析器是对的？**
> 拿 **shark** 当独立 oracle，逐条流式读**同一份真实 dump**，比对**逐类**的数量与字节
> —— 全等，不是"误差 1% 内"。真实 dump 有 9893 个 segment、4996 个类，全部对上。
> ⚠️ 关键是不能只跟自己造的样例对拍：样例是按"我以为的格式"造的，
> 理解错了会**测试和实现一起错、还一起通过**（我第一版就是这样，
> 一上真实 dump 就数组越界）。

---

## 八、诚实边界汇总（先看这节，否则报告会被误读）

1. ⚠️ **上报链路：驱动已补，但出口是"模拟提交"。**
   周期（默认 30s）+ 进后台触发已实现（`ReportDriver`），数据在 App 内可查看（实测 M13）。
   **仍未做**：真实 `upload` 出口（host/token）、**远程可调采样率**、
   失败重试与退避。这三项是上生产的必要条件（见 §九 待办 1~2）。
2. ⚠️ **native 归因是有损采样**（`≥8KB` 全采、`[64B,8KB)` 1/256、拿不到锁跳过）。
   它回答"大头在哪"，**不回答**"有没有漏 200 KB"。
3. ⚠️ **Java 直方图只有"类 → 数量/字节"，没有引用链**。它指向"数量异常的**种类**"，
   读不出"数量正常但没人放开的**循环引用**"（那需要支配树，是 MAT / Shark 的领域）。
4. ⚠️ **斜率不是泄漏的证明**。它只给出"值得看一眼"。真正的结论需要
   **多窗口确认**（连续三个窗口都 `LEAK_SUSPECT` 且无 trim 降级）+ 前台/页面前缀归因。
5. ⚠️ **系统库帧需要匹配的 symbol file** 才能符号化，线上拿不到；只能标注。
6. ⚠️ **`BitmapTracker` 恒 ≤ 系统 graphics**，差额是信息不是 bug。
7. ⚠️ **本文所有数字来自模拟器**（Android 16 / arm64 / **scudo** 分配器）。
   真机（如 dlmalloc/其它厂商定制）上 `malloc_info` 的行为可能不同，需重测。
8. ⚠️ **`smaps` 级映射聚合尚未独立成组件**（目前 native 走
   `getNativeHeapAllocatedSize` + `nativePss` + `VmRSS` 三层口径）。
   要做"到底哪个 .so / 哪段映射在涨"，需要逐行解析 `/proc/self/smaps`。
9. 三条归因路径**都不回答**"这块内存该不该存在" —— 那是业务判断。

---

## 九、待办 / 下一步

| # | 事项 | 为什么 |
|---|---|---|
| 1 | ~~补周期性上报驱动~~ → **已实现**（周期 + 进后台 + 手动，`ReportDriver`） | 实测 M13 |
| 2 | **真实 `upload` 出口 + 采样率远程可调 + 失败重试/退避** | 出口仍是模拟提交；且内存量比卡顿大，必须能被"拧小" |
| 3 | `smaps` 逐行解析（映射级归因） | 边界 8 |
| 4 | 多窗口泄漏确认（连续三窗口 + trim 降级排除） | 边界 4 |
| 5 | 位图归因补 `HardwareBitmap` 路径 | 边界 6 的差额来源之一 |
| 6 | 真机复测（换 dlmalloc 设备验证 `malloc_info` 口径） | 边界 7 |

---

## 附录 A：模拟器测量原始记录

**设备**：模拟器 `Medium_Phone_API_36`，Android 16，arm64-v8a，
`system-images/android-36/google_apis_playstore/arm64-v8a`，
Google Play 镜像（**无 `adb root`，debug 包用 `run-as`**）。
采集时间：2026-10-08。

### M1 — 堆上限三个数的实测
```
Runtime.maxMemory()               = 268435456  (256 MB)
ActivityManager.memoryClass       = 256
ActivityManager.largeMemoryClass  = 512
isLowRamDevice                    = false
```

### M2 — 分配器是 scudo，且 `malloc_info` 不含大块
```
malloc_info 输出头： <malloc version="scudo-1">
8 笔 100000 B 存活分配 → malloc_info 输出 157 B（只有头尾标签）
malloc_info（正常运行时）：version=scudo bytes=13372（只含 primary/尺寸档）
sizeclass 实测分布： 24B×2409、16B×1813、8B×1123、4B×527 …
```
结论：大块走 secondary/mmap，**不出现**在 `malloc_info` 里 ⇒ 它只能看小对象碎片。

### M3 — `mallinfo2` / `smaps_rollup` / `VmHWM` 均可用
```
mallinfo2: uordblks=55857712(53.27 MB) fordblks=1899712(1.81 MB) hblkhd=62177280(59.30 MB)
```
⚠️ `hblkhd`（58 MB）> `uordblks`（53 MB）—— 说明**映射提交的部分**不能忽略。

### M4 — hook 装载与符号覆盖
```
installed=1  version=bytehook-1.0.10  sites=4/512  addrSlots=65536
```
4 个 hook 点 = `malloc` / `free` / `calloc` / `realloc`。
`_Unwind_Backtrace` 在 arm64 上可用，但**只在有 `.eh_frame` 时**能走栈：
CMake 必须加 **`-funwind-tables -fno-omit-frame-pointer`**；
实测 `-O2`（内联）只回 2 帧，`-O1 -fno-inline` 回满 4 帧。

### M5 — dump 的代价
```
Debug.dumpHprofData → 913 ms / 45.28 MB
```
⚠️ 这是**主线程可感知的冻结**（秒级），所以它只在
**CRITICAL 一次/会话 + 手动触发**时才做，绝不自作主张。

### M6 — 与 shark 对拍（§4.3 的原始数字）
```
文件 47,476,497 B（45.28 MB）
shark： idSize=4  CLASS_DUMP=31,027  INSTANCE=242,779  PRIMITIVE=181,632  OBJ_ARRAY=35,537
        全部堆记录 490,975；bytesRead=47,476,497 == 文件长度（顶层 walk 精确落点）
本解析器： 类 31,027 / 对象 459,948 / 解析 1041 ms / 总字节 19.71 MB
           skippedSegments=0  unresolvedInstances=0
逐类对拍： 4,996 个类，数量与字节**全部完全相等**
单测：    38 通过（HprofParserTest 11 + MemoryMetricsLogicTest 25 + RealDumpTest 2）
          不给 dump 时 RealDumpTest 报 skipped（Assume，刻意不为假绿）
```

### M7 — 归因 + 符号化端到端
```
报告： site#0 bytes=33554432(32.00 MB) count=32 frames=4
       frame[0] libmemtrace.so+0x17268
       frame[1] libmemtrace.so+0x19750
符号化： libmemtrace.so+0x17268 → my_malloc                            memtrace.cpp:434
        libmemtrace.so+0x19750 → Java_..._MemTraceNative_allocBlocksNative  memtrace.cpp:923
        libmemtrace.so+0x175f4 → my_calloc                           memtrace.cpp:467
系统库： libart.so+0x33f504 → `??  ??:0:0`（设备上的 so 已 strip，需 symbol file）
```

### M8 — JVM / Native 的"背离"现场（§2.2 的对照）
```
造 32×1MB native 负载后：
  nativeAlloc = 53.28 MB      ← 分配器视角立刻反映
  Java 堆     =  5.34 MB      ← 基本没动（"位图/缓冲在 native"的现场）
  sites liveBytes = 33.05 MB  vs  mallinfo2.uordblks = 53.27 MB
```

### M9 — `art.gc.*` 是进程累计值（必须差分）
```
Debug.getRuntimeStats() 键：art.gc.gc-count / gc-time / blocking-gc-count /
                            blocking-gc-time / bytes-allocated / bytes-freed
```

### M10 — 采样器参数与窗口
```
间隔 2000ms / 环形 256 点 ≈ 8.5 分钟窗口
水位阈值： HIGH=70.0%  CRITICAL=85.0%（低内存设备 -10%）
趋势： 回落容差 4 MB / 上涨阈值 16 MB
```

### M11 — 遗嘱在临界当场落盘（§1.1 的修法验证）
```
files/memory/session-memory.tsv 末行：
1791441362099  2026-10-08 14:36:02  200437328  201326592  201326592  21466672 …  -1  CRITICAL:NORMAL->CRITICAL
                                     ↑javaUsed=191.15MB  ↑heapLimit=192.00MB ⇒ 99.6%
logcat： 内存监控已装… 水位越级次数：1  当前级别=CRITICAL
```
⚠️ 这一行是在**被 OOM 杀掉之前**写下的 —— 正是它存在的意义。

### M13 — 上报驱动端到端（§5.1/§5.2 的验证）
```
logcat： 上报驱动已开：每 5s 一次 + 每次进后台一次（模拟提交，不联网）
        [模拟提交:周期] 批次#1：2 条 | ANR=1 MEMORY_CRITICAL=1
实验页「已提交数据」：
        驱动：运行中（每 5s + 每次进后台）
        出口：模拟提交（不联网；接平台时替换 upload 一个 lambda）
        待发（内存类）：1 条   累计已提交：2 条 / 1 批次（周期）   最近一批：2 条
        批次[-0]：2 条
          [ANR] probe:主线程无响应 …
          [MEMORY_CRITICAL] level:NORMAL->CRITICAL …
```
⚠️ 修掉一个计数假象：第一版让**空批次**也自增计数，
于是"已提交 N 批次"在数**定时器滴答**而不是数**数据**（第一条真实事件显示成"批次#2"）。
现在空批次提前返回、不计入 —— 计数器必须能回答"到底发出去了没有"，否则它没有意义。

### M12 — bytehook 打包去重（§3.1 的风险验证）
```
unzip -l app-debug.apk | grep '\.so$'
  lib/arm64-v8a/libmemtrace.so   238656
  lib/arm64-v8a/libbytehook.so    63520   ← 只有一份（prefab + jni/ 已正确合并）
```
