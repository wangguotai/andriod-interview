# Perfetto 使用说明（滚动性能 / 卡顿归因）

这套工具用来回答一个具体问题：**列表滚动时的卡顿，到底该算谁头上？**
先归因，再优化——避免对着模拟器的图形合成瓶颈去调 RecyclerView。

```
tools/perfetto/
├── scroll-trace.cfg      # 抓取配置（ftrace + process_stats + FrameTimeline）
├── record-scroll.sh      # 一条命令完成：选设备 → 抓取 → 拉回本机
├── analyze_trace.py      # 命令行读 trace，按"归因 → 主线程 → RV 埋点"出结论
└── out/                  # 抓到的 trace（已 gitignore）
```

## 最快用法

```bash
# 抓 10 秒：脚本开始后，在设备上手动滑动列表
tools/perfetto/record-scroll.sh -s emulator-5554 -p com.example.myapplication

# 无人值守：脚本自己发起滑动，抓完立刻分析
tools/perfetto/record-scroll.sh -p com.example.myapplication --auto-swipe --analyze
```

手动版等价于：

```bash
adb push tools/perfetto/scroll-trace.cfg /data/misc/perfetto-configs/cfg   # 目录有讲究，见下
adb shell perfetto -c /data/misc/perfetto-configs/cfg --txt -o /data/misc/perfetto-traces/trace
adb pull /data/misc/perfetto-traces/trace ./scroll.perfetto
```

打开：浏览器进 <https://ui.perfetto.dev> → Open trace file。

命令行读（没装 `trace_processor_shell` 时脚本会自动下载到 `tools/perfetto/bin/`）：

```bash
tools/perfetto/analyze_trace.py ./scroll.perfetto --pkg com.example.myapplication
```

## 怎么读：顺序不能反

### 第 1 步：jank 归因——是不是你的锅

先看 `actual_frame_timeline_slice.jank_type` 的分布：

| jank_type | 归谁 | 要不要管 |
|---|---|---|
| `App Deadline Missed` | app 主线程超时 | **要**，这才是你的代码 |
| `SurfaceFlinger CPU Deadline Missed` | SurfaceFlinger（模拟器软渲染常见） | 不用 |
| `Buffer Stuffing` | buffer 积压/消费不掉 | 一般不用 |
| `Prediction Error` | 帧预测/调度 | 多半是模拟器假象 |
| `None` | — | — |

实测（模拟器 emulator-5554，瀑布流页，10 秒滚动抓取）：

```
jank_type                                                    n
SurfaceFlinger CPU Deadline Missed                          61
SurfaceFlinger CPU Deadline Missed, App Deadline Missed,... 53
...
```

⚠️ `jank_type` 可能是**复合值**（多项逗号拼接），所以按子串分类会互相重叠
（同一帧可能既算 SurfaceFlinger 又算 app）。别把各类相加——**先看有没有
`App Deadline Missed`，再把那些帧拿去第 2 步下钻**。

这份 trace 里绝大多数 jank 带 SurfaceFlinger / Buffer Stuffing 标签（模拟器软渲染），
app 侧的计算耗时只有几百毫秒量级——此时去优化 RecyclerView 的 bind 收益极低。
这就是先归因的价值。

### 第 2 步：定位 app 主线程，看时间花在哪

```
主线程 traversal                               4402.6 ms
 └ postAndWait（等一块空闲 buffer）             4255.6 ms   ← 97% 在等
RV Scroll (20) / RV Prefetch (2)                  ~5 ms
Choreographer#doFrame 单帧 max                    181.9 ms
```

RenderThread 侧对应 `dequeueBuffer → waiting for free buf`、`eglSwapBuffers` 同样是大头
——主线程在 `postAndWait` 空等、RenderThread 在等 buffer，**是同一件事**。
真正属于 app 的计算只有几百毫秒量级。判定逻辑已内置在 `analyze_trace.py`：
当「等 buffer」占 `traversal` 超过 50% 时会直接给出结论。

### 第 3 步：RecyclerView 自带埋点

RecyclerView 1.3.2 源码里内置了 `TraceCompat` 段（`RecyclerView.java:329~386`），
抓 `view` 分类即自动带上，不用自己插桩：

| trace 名字 | 对应代码 |
|---|---|
| `RV OnBindView` | `onBindViewHolder` |
| `RV CreateView` | `onCreateViewHolder`（inflate 成本） |
| `RV OnLayout` | `onLayout` → `dispatchLayout` |
| `RV Scroll` | `scrollStep()` |
| `RV FullInvalidate` | `notifyDataSetChanged` 引发的全量重排 |
| `RV PartialInvalidate` | 局部更新 |
| `RV Prefetch` / `RV Nested Prefetch` | GapWorker 预取 |

最常用的一条：数 `RV FullInvalidate` 的次数——不为 0 就说明还在用 `notifyDataSetChanged()`
全量刷新，应改 `DiffUtil` / `ListAdapter`。

## 常见坑（都是实测踩过的）

- **`duration_ms` 是毫秒，不是秒**：脚本参数 `-d` 是秒，必须 ×1000 再写进配置。
  踩过：`-d 12` 写成 `duration_ms: 12` → 只抓了 **12 毫秒**，trace 里什么都没有。
  这个坑在模拟器上没暴露（默认 10s 时不追加该字段，用的是模板里的 10000）。
- **`duration_ms` 不能重复出现**：模板已含该字段，再追加一份会被 protobuf 直接拒绝
  （`Saw non-repeating field 'duration_ms' more than once`）。脚本用 grep 删掉模板行再追加。
- **抓取顺序：先停 app → 起 perfetto → 再启动 app**。`atrace_apps` 是 app **进程启动时**
  读取的；若 app 先启动再起 perfetto，**旧版本（实测 Android 12）不会追溯开启**，
  app 的 view/gfx 埋点全丢（trace 里只剩 systemui/surfaceflinger 的 slice）。
- **`process.name` 缺失或错记成 `zygote64`**：有两个独立原因，**两个都要治**：
  1. app 在 `process_stats` 首次扫描**之后**才启动 → 配置里加 `proc_stats_poll_ms: 1000` 周期轮询。
  2. **进程改名事件没订阅**（真机实测的根因）。app 由 zygote fork 时，内核里进程名先是
     `zygote64`；直到 `ActivityThread` 调 `setProcessName` 触发 **`task/task_rename`**，
     才变成真实包名。`process_stats` 的周期轮询**不看**这个事件，拍到的永远是 `zygote64`。
     配置里必须订阅 `ftrace_events: "task/task_rename"`（真机 `/sys/kernel/tracing/events/task/`
     下确认存在）。修复前后实测：`task_rename` 事件数 0 → 221，
     `process.name` 从 `zygote64` → `com.example.myapplic`（Linux comm 上限 15 字符）。
  `analyze_trace.py` 另用线程名 `e.myapplication` / `draw-VRI[` 兜底，所以**即使漏了
  第 2 条，命令行分析仍能跑对**——但 Perfetto UI 的轨道会挂在 `zygote64` 下，很难找。
- **配置和 trace 的路径**：`perfetto` 只保证能读 `/data/misc/perfetto-configs`、
  能写 `/data/misc/perfetto-traces`。放 `/data/local/tmp` 会 `errno 13 Permission denied`。
- **⚠️ 只订阅 `binder/binder_transaction` 会摧毁所有 atrace 相位**（真机实测，很隐蔽）：
  Perfetto 里 binder 事务是**成对**事件 —— `binder_transaction`（发起）＋
  `binder_transaction_received`（到达）等。若配置里**只写 `binder_transaction`**、
  漏掉配对方，trace_processor 会积压 ~8500 个**永远配对不上、`dur=-1` 的 binder
  slice**，并进而打乱这些线程的 slice 栈 —— 表现是
  `bindApplication` / `app.onCreate` / `activityStart` / `performCreate` 这些
  atrace 埋点的 `dur` **全变成 -1**，启动相位直接报废（但 trace 里 atrace 的 B/E
  原始 print 事件一个不少，所以**光看事件数会误判"没丢数据"**）。
  实测隔离矩阵（每次全新冷启动抓取，仅改 ftrace_events）：

  | ftrace_events | unclosed slice | 关键相位 dur |
  |---|---|---|
  | `sched_switch`+`sched_waking`+`task_rename`+`task_newtask` | 5 | ✅ 正常 |
  | ＋`kmem/mm_page_alloc`＋`input` 分类＋frametimeline | 3 | ✅ 正常 |
  | ＋**`binder/binder_transaction`（单独）** | **8513** | ❌ 全部 -1 |
  | ＋`sched/sched_blocked_reason` | 5 | ✅ 正常 |

  **结论**：要么不订阅 binder（本站排查启动耗时用不到，`startup-trace.cfg` 已删掉），
  要么把 `binder_transaction_received` / `binder_transaction_alloc_buf` 等配对方
  **一并订阅**（系统自动开 binder 时就是整组，所以那样配不会出问题）。
  判别手法：`select count(*) from slice where dur<0` 突然上千、且
  `select name,count(*) from slice where dur<0 group by name` 里全是
  `binder transaction` → 就是踩了这个。
- **多设备**：模拟器和真机同时连着时**必须** `-s <serial>`，否则容易在真机上误抓。
- **trace_processor_shell 按行切分 SQL**：写 SQL 时**必须单行**，多行会被截断报语法错。
- **下载源**：`commondatastorage.googleapis.com` 可能不通（`perfetto` Python 包默认走它）；
  `storage.googleapis.com/perfetto-luci-artifacts/...` 通常可达，脚本用的后者。
  Android 12 设备自带 perfetto v15，与 v49 抓出的 trace 解析兼容。

## 模拟器 vs 真机：结论会完全相反（实测对照）

同一套工具、同一个教学页，两台设备的结论截然不同：

| | 模拟器 emulator-5554 (API 36) | 真机 Redmi K40 / Android 12 |
|---|---|---|
| 主线程 `traversal` / 等 buffer | 4402 ms 中 97% 在 `postAndWait` 等 buffer | 694.9 ms 中无等 buffer 占比 |
| jank 归属 | 几乎全是 SurfaceFlinger / Prediction | 以 **App Deadline Missed** 为主 |
| RecyclerView 埋点 | `RV Scroll` 毫秒级，可忽略 | `RV OnLayout` 153ms / `inflate` 155ms 可见 |
| 结论 | 瓶颈在图形合成，**动 RecyclerView 无用** | 瓶颈在 **app 的 layout/inflate** |

真机上最贵的动作集中在**首屏与跨页**：`inflate` 63ms、`RV OnLayout` 74-80ms
（时刻约在 trace 的 2.3-2.6s，即首次填充与翻页），而滚动期间的单帧超时多在
14-27ms。对应的优化方向因此完全不同：

- **模拟器上**：不要改代码，先换真机复验。
- **真机上**：`RV OnLayout` 偏贵 → 检查 item 布局深度、是否有 `wrap_content` 触发二次测量；
  `inflate` 155ms → 收敛 viewType、复用布局；`RV CreateView` 91ms 说明新建 holder 偏多。

**所以：性能结论必须用真机复验，且两边的优化方向可能完全相反。**

## 实测：滚动自动加载下一页（infinite scroll）——分段结论

给瀑布流页加上「滚动到底自动加载下一页」后再抓一次真机 14s trace（Redmi K40 /
Android 12，滚动期间自动加载了 7 次，每次 +20 张）。app 侧帧的完整账：

| 阶段 | 时刻 | app 帧情况 | 主线程主要耗时 |
|---|---|---|---|
| 首屏冷启动 | 0 – 2.7s | 3 帧 App Deadline Missed，最长 **263ms** | `performCreate` 153ms、`inflate` 72ms |
| 滚动 + 自动翻页 | 4 – 13s | 每帧中位 ≈16ms；JankType 上 **241 Buffer Stuffing / 6 App Deadline Missed** | `RV Prefetch` 11ms → `RV CreateView`+`inflate` 约 8ms 的连锁 |

- **首屏是唯一的严重卡顿**（263ms），要治的是 Activity 启动与首次 inflate，
  **不是滚动** —— 它只发生一次，与 auto-load 无关。
- **滚动期本身不到卡顿线**：多数帧停在屏幕刷新率附近，app 侧只有 6 帧真超时。
  剩下的 Buffer Stuffing 属合成侧，真机上同样不该算到 app 头上（这一点和模拟器
  结论一致，只是量级小得多）。
- **自动翻页的代价被看得见**：超时帧里 `RV Prefetch`（GapWorker 预取）最长 11ms，
  紧跟着 `RV CreateView` / `inflate` —— 就是「下一页 20 个新 holder」在建。
  这是 auto-load 引入的**新**开销，真实存在但**没到**会造成明显掉帧的程度。
  若要进一步压：把 `AUTO_LOAD_THRESHOLD` 调大（更早预取、摊薄单帧），
  或把 item 布局压浅（`inflate` 是这里最贵的一环）。
- 尾段 11–12.3s 的 3 帧超时（12–13ms）出现在停止滑动之后，属收尾，量级可忽略。

一个**仍未解释**的现象：`actual_frame_timeline_slice` 的 jank 帧上做时间区间相交
（`s.ts < a.ts+a.dur and a.ts < s.ts+s.dur`）常常一行都返回不了，但放宽到
`a.ts > …` 的时间窗查询又能看到 RV Prefetch/CreateView。说明 jank 帧的
`ts/dur` 语义与普通 slice 不完全对齐，**用相交条件做归因不可靠**；
上表的数字来自时间窗下钻，不是相交。这一点没有查清根因，记录下来避免后人踩。

## UI 里的操作路径（看上面的结论怎么来的）

1. <https://ui.perfetto.dev> → **Open trace file** 选 `tools/perfetto/out/*.perfetto`。
2. 搜索框输入 `com.example` → 展开 app 进程组 → 勾选固定它的主线程轨道。
3. 看 **Expected Timeline / Actual Timeline** 两条：红色/橙色小块就是 jank 帧，
   点开有 `jank_type` 归因（谁超的时）。
4. 在 app 主线程轨道上，把时间轴缩放到某个 jank 帧，看同一时刻有哪些
   `RV Prefetch` / `RV CreateView` / `inflate` —— 这就是「谁把这一帧撑爆了」。
5. 需要跨表统计时改用 **Query (SQL)** 页，或命令行 `analyze_trace.py`。

> 若 app 轨道挂在 `zygote64` 下找不到，回到上面的坑第 2 条补 `task/task_rename`。

## 与其他工具的分工

- `adb shell dumpsys gfxinfo <pkg> framestats`：最轻量的帧耗时预检，先看有没有明显尖刺。
- Perfetto：需要归因、看 CPU 调度、看 RV 埋点时用。
- Layout Inspector：看 view 树深度与测量耗时（层级优化）。

---

# 冷启动 / 静态代码块（`<clinit>`）排查线

回答的问题：**冷启动的时间花在哪个阶段，静态代码块又是谁在拖？**

```
tools/perfetto/
├── startup-trace.cfg     # 相位抓取配置（dalvik + view/gfx + am/wm + res）
├── record-startup.sh     # 一条命令：相位 trace + ART 方法 trace 一起抓
└── analyze_startup.py    # 相位给"阶段"，ART 方法 trace 给"每个 <clinit> 的墙钟"
```

## 最快用法

```bash
tools/perfetto/record-startup.sh -p com.example.myapplication \
    -a com.interview.home.HomeActivity --analyze
```

产物（`out/` 下，已 gitignore）：
- `<时间戳>-startup.perfetto` —— 相位 trace，拖进 <https://ui.perfetto.dev> 看
- `<时间戳>-startup.art.trace` —— ART 方法 trace，静态代码块的**唯一来源**

## ⚠️ 第一个坑：Perfetto 抓不到静态代码块

`atrace` 的 `dalvik` 分类在这台 Android 12 上**只产出 ART native 切片**
（`art::IndirectReferenceTable::Trim` / `TrimSpaces` / monitor contention），
**不产出 Java 的 `<clinit>` / `InitializeClass` 切片**。实测：

```
strings trace.perfetto | grep -c clinit            → 0
（system-wide 抓取也一样是 0）
```

所以「找静态代码块耗时」这件事，**单靠 Perfetto 走不通**。两个替代口：

| 口径 | 命令 | 能否给出 `<clinit>` 墙钟 | 代价 |
|---|---|---|---|
| ART 方法 trace | `am start --start-profiler <f>` | ✅ 精确到方法 | 需 debuggable；有开销 |
| simpleperf | `app_profiler.py --launch -g` | ⚠️ 只能给采样 + 部分栈 | 采样噪声大 |

- **`--start-profiler` 是唯一能在进程启动前就开始采样的入口** ——
  `am profile start` 要等进程起来，会**漏掉 `Application.<clinit>`**。
- ⚠️ **不要加 `--sampling N`**：ART 的**采样模式根本不产出 `<clinit>` 记录**。
  实测同一页面：`--sampling 1000` → 956 条方法记录、**0 个 clinit**；
  全量模式 → 29.96 万条、**199 个 clinit**，累计 164ms。
  偶发一次的 clinit 必然被采样漏掉 —— 采样模式是给"找热点函数"用的。
  （`record-startup.sh` 默认走全量；要采样得显式 `--sampling <ms>`。）
- ART 方法 trace 里的 `ts/dur` 在**文件里**是微秒，但经 `trace_processor_shell`
  导入后**统一换算成纳秒**（与相位 trace 一致，所以脚本里到处 `/1e6` 得毫秒）。
  实测：`StabilityReporter.<clinit>` 原始 dur=4,306,000 → 4.3 ms（若当微秒就是 4.3 s，
  对一个 clinit 不可能）。两种 trace 都能直接喂给 `trace_processor_shell`。

## 实测：本仓冷启动的账（Redmi K40 / Android 12，debug 包）

`am start -W` 的 `WaitTime≈3.0s` **不是 app 的锅**：那是 MIUI 上先 `am force-stop`
再起、等待应用从被杀状态重新拉起 + 首次 dex 校验的时间，
`TotalTime` 才是 app 自己的。拿相位 trace 算的 app 侧账：

| 相位 | 耗时 | 说明 |
|---|---|---|
| `ActivityThreadMain` | 28 ms | 进程入口 |
| `bindApplication` | **222 ms** | 其中 `app.onCreate` **132 ms** |
| └ `Startup`（androidx.startup） | 14.6 ms | EmojiCompat / ProcessLifecycle / ProfileInstaller |
| `activityStart`（含 performCreate） | **335 ms** | HomeActivity.onCreate |
| └ 首帧 `traversal` | **276 ms** | `RV OnLayout` 217ms / `inflate` 263ms（TabLayout 25ms）/ `RV CreateView` 160ms |

**`<clinit>` 全景（ART 方法 trace，共 199 个）**：累计 **164 ms**，
其中自有代码 51 个、**40.2 ms**（数字来自全量方法 trace；采样模式会漏 clinit，见上）：

| 类 | `<clinit>` | 根因 |
|---|---|---|
| `稳定性监控.StabilityReporter` | **17.2 ms** | 静态 `SimpleDateFormat("MM-dd HH:mm:ss.SSS")` → 里面 `initializeCalendar` 要拿时区 → `ZoneInfoDb` 载 tzdata |
| `内存.MemoryMonitor` | 6.5 ms | 静态 `ConcurrentHashMap`/枚举/AtomicLong |
| `稳定性监控.CrashJournal` | **6.2 ms** | 又一个静态 `SimpleDateFormat`（同上，**重复付时区代价**） |
| `稳定性监控.MainThreadSampler` | 2.8 ms | 静态字段 |
| `稳定性监控.StabilityMonitor` | 1.9 ms | |
| `thread.ThreadPools` | 1.5 ms | 9 个 `Lazy` lane 的 lambda 类 |

> 两个 `SimpleDateFormat` 合计 **23.1 ms**（17.2 + 6.2，同一轮）。
> 这是全工程最容易摘的一颗果子 —— 且**第二个只省了一点**，说明时区数据的
> 代价主要在**首次类初始化**（`ZoneInfoDb` / tzdata），不是每实例的。
> 另有 `kotlin.jvm.internal.ClassReference.<clinit>` **51.6 ms**：它**不是**本仓
> 手写的静态块，而是 `androidx.lifecycle` 在
> `SavedStateHandleSupport.getSavedStateHandlesVM → Reflection.getOrCreateKotlinClass`
> 里触发的 Kotlin 反射链路（`ClassReference` 的 clinit 要去载反射元数据）。
> 这一段在库里，改动面大；但知道它存在，就不会把它误算到自己的静态块上。
> `HomeCatalog.<clinit>` 只有 **8.5 ms**（14 个 `::class.java` 的直接引用 +
> `listOf` 常量表），不是主要矛盾。

**`app.onCreate` 132 ms 的真正大头**（ART 方法 trace 下钻）：

| 调用 | 耗时 | 性质 |
|---|---|---|
| `MemoryMonitor.install` | **130 ms** | ⚠️ 见下 |
| └ `MemoryMetrics.Sampler.start` | **128.8 ms** | 同步跑了一次 `capture()` |
| └ `MemoryMetrics.capture` | **127.7 ms** | **在 `Application.onCreate` 里做 IO/Binder** |
| └ `Debug.getMemoryInfo` | 71 ms | 读 `/proc/self/*` |
| └ `ActivityManager.getProcessMemoryInfo` | 26 ms | Binder 到 system_server |
| └ `readSelfStatus`（读 /proc） | 23 ms | |
| └ `getHistoricalProcessExitReasons` | 11.7 ms | ExitInfo 回捞 |
| `StabilityMonitor.installOnCreate` | 45.9 ms | 其中 `CrashJournal.beginSession` 24.4 ms（读+写状态文件） |
| `NetClient.init` | 17.4 ms | 注册回调 + 读一次网络状态 |

**结论**：`Sampler.start()` 里那句"立刻给一个基线点"的
`runCatching { buffer.addLast(capture(context)) }` 是**同步**的，
把一次完整的内存采集（71ms 的 proc 读取 + 26ms 的 Binder）直接压进了启动路径。
`MemoryMonitor.install` 的注释写着"不阻塞启动"，但代码不是 —— 这是一个
**注释与实现不一致**的真实缺陷。

**优化方向（按性价比排序）**：
1. `Sampler.start()` 的首次 `capture()` 挪到 `handler.post { }` 或首个 `tick` ——
   基线点晚 2s 出现没有业务影响，但**省掉启动路径上 ~128 ms**。
2. 两个静态 `SimpleDateFormat` → 改成 `ThreadLocal` 或首次使用时才建
   （`SimpleDateFormat` 非线程安全，`ThreadLocal` 是标准解）。
3. `CrashJournal.beginSession` 的读写文件挪到后台泳道（它只是标记，晚 100ms 无影响）。
4. 首帧的 `RV OnLayout` 217ms / `inflate` 263ms 是**另一条线**（首屏布局），
   与静态块无关，见上半篇的滚动卡顿排查法。
5. （库侧）`ClassReference` 反射链 51.6ms 由 androidx.lifecycle 触发，
   换 ViewModel 恢复路径或升级 androidx.lifecycle 可能规避，改动面大，优先级低。

> ⚠️ 上面 132ms / 128ms 是 **debug 包 + ART 方法 trace 插桩**下的数字，
> 绝对值会偏高（插桩本身有开销）；**相对排序可信，绝对值要拿 release 复测**。
> 但"同步 capture 阻塞启动"这个**结构问题**与构建类型无关。
>
> 复现命令：
> ```bash
> tools/perfetto/record-startup.sh -p com.example.myapplication \
>     -a com.interview.home.HomeActivity --analyze
> ```

## 两种 trace 的区别（同一台设备、同一次抓取）

`record-startup.sh` 会产出两个文件，**格式、来源、能看到的东西完全不同**，
互补而非重复。以下数字都是在 Redmi K40 / Android 12 上实测同一轮的产物。

| | 相位 trace `*-startup.perfetto` | ART 方法 trace `*-startup.art.trace` |
|---|---|---|
| 文件格式 | **protobuf**（magic `0a6e`，二进制） | **文本**（magic `*version\n3\n`） |
| 产出者 | `perfetto` traced 服务 | ART 运行时（`am start --start-profiler`） |
| 采集方式 | ftrace（内核 ring buffer 流式） | 方法插桩（进入/退出各记一条，写内存缓冲） |
| 覆盖范围 | **全系统**：878 进程 / 5470 线程 | **仅本 app**：1 进程 / 19 线程 |
| 核心表：slice | 63,750 | 299,644 |
| 核心表：thread_state | **172,294** | **0**（无调度事件） |
| 核心表：ftrace_event | **290,457** | **0** |
| frametimeline | **428**（有掉帧归因） | 0 |
| 体积（同一轮） | 15 MB | 8.9 MB |
| 抓取开销 | 低（内核侧采样） | **高**（每次都插桩，`clock-call-overhead-nsec=442`） |

### 能力对照：同一个调用，两条 trace 各能看到什么

以 `MemoryMonitor.install` 为例（实测同一份代码）：

| 问题 | 相位 trace | ART 方法 trace |
|---|---|---|
| `MemoryMonitor.install` 花了多久？ | ❌ 查不到（`glob '*MemoryMonitor*'` → **0 行**） | ✅ **51.03 ms** |
| `app.onCreate` 花了多久？ | ✅（atrace 埋点，如 132 ms） | ✅（如 210 ms，但两轮波动大，见下②） |
| 主线程在等还是在跑？ | ✅ **只有它有**（`thread_state` S/D/Running） | ❌ 无调度数据 |
| 谁被 CPU 抢占 / 在哪个核？ | ✅（sched_switch + cpu） | ❌ |
| 掉帧了没、算谁头上？ | ✅（`jank_type` 归因） | ❌ |
| `<clinit>` 谁最慢？ | ❌ **0 行**（见下） | ✅ 199 个，逐个给墙钟 |
| 方法级调用树？ | ❌ 只有手写埋点 | ✅ 全量（29.9 万条） |

**一句话选型**：
- 问"**哪个阶段**慢 / 等在哪 / 掉帧算谁的" → 相位 trace
- 问"**哪个方法/静态块**慢" → ART 方法 trace

### 三点必须注意的差异

**① atrace 埋点是"稀疏的"，且不含自有 Java 方法。**
相位 trace 只能看到**框架预先埋好**的点（`app.onCreate` / `inflate` / `RV OnLayout`
这类），你自己的 `MemoryMonitor.install` 之类**一个都没有**（实测查 0 行）。
ART trace 则是**全量方法**，连 `<clinit>` 都在。

**② ART trace 有插桩开销，绝对值不稳定 —— 只信排序，别信绝对值。**
文件头有硬证据：`clock-call-overhead-nsec=442` —— 每次方法调用多花 442ns。
但实测同一份代码、不同轮次，`MyApplication.onCreate` 的量级是：

| 轮次 | ART 方法 trace | 相位 trace (atrace) |
|---|---|---|
| 15:50 抓取 | 210.5 ms | 131.9 ms |
| 16:46 抓取 | 76.5 ms | （该轮相位被 binder_transaction 配置坑拖垮，dur 全 -1，取不到；见「常见坑」） |

两轮差 3 倍，且 ART 时而高于 atrace、时而低于。原因有二：
插桩开销（442ns/调用，调用上万次就累加）＋ 两者界定的**起止点不完全一致**
（atrace 从 `callApplicationOnCreate` 到返回，ART 是方法自身的进出）。

⇒ 结论：**ART trace 用来比"谁比谁慢"（同一轮内的排序可靠），
不要用来报绝对值。** 要准的绝对值用相位 trace 里已闭合的 atrace 数字，
或 release 包 + simpleperf。

**③ 两者的时间基准不同（这是最容易被忽略的）。**
ART 文件头是 `clock=dual`，含 `elapsed-time-usec=13442956`（13.44s），
而相位 trace 的 `ts` 是 CLOCK_BOOTTIME 量级，两者**不能相减**。
所以脚本对两条 trace 各开一个 `TraceProcessor`，各自内部算，绝不跨 trace 对齐。
同一个 `MemoryMonitor.install`，ART trace 给 51ms；另一轮相位 trace 里
最接近的对应块是 128ms —— **这不能相减、也不能说"差 77ms"**：
是两次抓取 + 两种口径（插桩 vs atrace），不是同一次测量。

### 怎么按需求只用一条

```bash
# 只要相位（快、开销低、能看系统侧）
adb shell perfetto -c startup-trace.cfg --txt -o t.perfetto
# 只要 ART 方法级（找慢方法/静态块，必须 debuggable）
adb shell am start -W --start-profiler /data/local/tmp/t.trace -n pkg/Act
# 两条都要
tools/perfetto/record-startup.sh -p pkg -a Act --analyze
```

## 分析脚本的原理


一句话：**脚本自己不解析 trace,而是把两条 trace 分别喂给
`trace_processor_shell`(Perfetto 官方解析器)做 SQL 查询,再把结果整理成人读的结论。**

```
tracer(设备) → .perfetto 文件 → trace_processor_shell 导入成 SQLite 表
                                        ↑
                          analyze_startup.py 通过 Python RPC 发 SQL
```

### 三个关键机制

**① 依赖官方的 trace_processor,不自己写解析器。**
`trace_processor_shell` 把二进制 trace 导入成一批 SQLite 表,核心几张:

| 表 | 内容 | 脚本在哪用 |
|---|---|---|
| `slice` | 带名字/时间跨度的代码段 | 相位、clinit |
| `thread` / `process` | 线程/进程元数据(含 `is_main_thread`) | 找主线程 |
| `thread_state` | 线程调度状态(Running/S/D) | `[C]` 阻塞分析 |
| `ftrace_event` / `raw` | 内核原始事件 | 排查溯源 |

脚本用 `perfetto` Python 包走 RPC 查询(不是起子进程跑 SQL 文件),
好处是结果**不做列宽截断**;找不到包时用仓库约定的 `uv run --with perfetto` 自动重入。

**② 相位 trace:atrace 的 B/E 配对 → slice。**
`view`/`gfx`/`am` 等分类开启后,框架在代码里插桩,往 ftrace 的 trace_marker
写 `B|pid|名字`(begin)和 `E|pid|`(end)。实测 trace 里能看到
`B|9832|AttachCurrentThread` 这类原始串;trace_processor 把它们按 **pid 配对**,
begin/end 之间就是 `slice` 的 `ts/dur`。

- `slice.ts` = B 的时间戳,`dur` = E − B;
- 主线程用 `is_main_thread=1` 认(比"slice 最多的线程"可靠 —— RenderThread 的 slice 通常更多);
- **`dur = -1` = 只看到 B 没看到 E**(进程被杀/抓取截断),脚本里到处用
  `s.dur < 0` 兜这个 —— 这就是上一版 owner 列返 NULL 的根因。

**③ ART 方法 trace:`am start --start-profiler` 的方法进出记录 → slice。**
ART 在方法进入/退出时各记一条 call 记录(名 `类.方法: 签名`),先写进内存缓冲,
`am profile stop` 时一次性 dump。trace_processor 把这些 call 记录**按时间还原成嵌套 slice**,
所以 `<clinit>` 会以真实墙钟段出现在 `slice` 表里 —— 这是 Perfetto 相位 trace 拿不到的部分。

### 一个必须知道的限制:两条 trace 不共享时间基准

```
相位 trace ts: 6584912117251970 …   (CLOCK_BOOTTIME 量级)
ART  trace ts: 2348203965702000 …   (另一个时钟基准)
```

差着 4×10^15 ns。**所以脚本对两条 trace 各开一个 `TraceProcessor` 实例,
靠"各自内部的时间"分别分析,绝不把两边的 `ts` 相减/对齐。**
后果:

- ✅ 相位 trace 答"哪个阶段多长"、ART trace 答"哪个方法/clinit 多长" —— 各自成立;
- ❌ **不能**直接说"这个 clinit 落在 `app.onCreate` 的第几毫秒" ——
  跨 trace 的时间对齐需要额外的时钟同步(perfetto 的 `clock_snapshot` / `trace_uuid`),
  本脚本没做,也不假装做了。

### 口径来自实测,不是猜的

脚本里每段都对应一次真实踩坑,例如:
- 判断"阻塞"必须先框启动窗口(全 trace 的 S 态 7740ms 里 99% 是 Looper 空闲);
- `blocked_function` 靠 `sched/sched_blocked_reason` 事件填,缺了就全 NULL;
- 该事件在 kernel 4.19 / perfetto v15 上 `caller` 解不出符号 —— 这些边界都写进了
  README 与脚本注释,而不是当成"应该能用"。



`analyze_startup.py` 的 `[C]` 段就是干这个的。核心是**两个不能跳过的区分**：

**① 先框出启动窗口** —— 不框就会得出完全相反的结论。

主线程在 trace 里的 S 态累计往往有好几秒（实测本仓 **7740 ms**），
但那**几乎全是启动完成后的 Looper 空闲**（`MessageQueue.next` 阻塞等消息），
不是你说的卡顿。真正的启动窗口只有 **1520 ms**（主线程首个 slice → 首帧结束），
窗口内 S 只有 **80 ms**。拿全 trace 的 S 当阻塞，会把空闲算成瓶颈。

**② 窗口内再分「等」和「干」**：

| 状态 | 含义 | 本仓窗口内 |
|---|---|---|
| `Running` | 真在 CPU 上跑 | **1407 ms** ← 大头 |
| `S` | 可中断睡眠（binder / futex / 锁） | 77 ms |
| `D` | 不可中断（磁盘 IO），超过 ~700ms 就是 IO 卡死 | 2.5 ms |
| `R` / `R+` | 就绪但没排上 CPU（**被抢占**，不是阻塞） | 31 ms |

⇒ Running ≫ 等待，说明本仓冷启动是 **CPU 密集**（类加载 / inflate / 计算），
**不是 IO 或锁等待主导**。「阻塞点」到这里就只有 80 ms 的量级，逐个下钻即可：

```
rel_ms  state  block_ms  所属 slice
226.8   S      21.74     binder transaction     ← AMS 事务
1306.9  S      14.38     binder transaction
779.7   S      11.52     binder transaction
104.4   D      1.11      setSystemFontMap       ← 读字体文件
```

**怎么定位到具体代码**：拿每个阻塞块的 `ts`，去主线程 slice 里找**包含它的最深 slice**
（`s.ts <= X and (s.dur < 0 or s.ts+s.dur >= X)` —— `dur<0` 是未闭合 slice，
漏了这个条件会大量漏判，实测 `app.onCreate` / `binder transaction` 的 dur 就是 -1）。

**`blocked_function` 的口径与坑**：`thread_state.blocked_function` 本应直接给出
内核阻塞原因（`futex_wait_queue_me` / `do_epoll_wait` …），它由
`sched/sched_blocked_reason` 事件填充 —— **`startup-trace.cfg` 已订阅**。
但实测（Redmi K40 / kernel **4.19** / perfetto **v15**）该事件的 `caller`
字段被解成无效小整数（`%pS` 没生效），**符号映射不出来**，该列仍是 NULL。
此时用上面的「所属 slice + 相对时刻 + 被谁唤醒」定位，结论不受影响。

> 若换了新内核想用 `blocked_function`：抓完先跑
> `select count(blocked_function) from thread_state where state in ('D','S')`，
> 非 0 就说明能直接读函数名。

---

# heapprofd：native 堆分配剖析（内存归因线）

回答另一个问题：**native 内存涨了，到底哪行代码要的？**
和滚动线的工具形态一致（cfg + 一条命令的脚本），但目标是
**内存 Lab 的按钮负载**，且脚本自带自动点按编排。

```
tools/perfetto/
├── heapprofd.cfg         # 抓取配置模板（纯 ASCII！坑②：设备端解析器吃不了 UTF-8 注释）
└── record-heapprofd.sh   # 选设备 → 验配置 → 抓取 → 自动点按 → 拉回 → 健康检查 → 出报告
```

## 最快用法

```bash
# 交互：脚本起好 trace 后自己在屏幕上操作，30s 自动停
tools/perfetto/record-heapprofd.sh -p com.example.myapplication

# 无人值守对拍：t=8s 装归因探针，t=15s 点「造 NATIVE 负载」（内存 Lab 页）
tools/perfetto/record-heapprofd.sh -s emulator-5554 \
    --tap "539 791" @8 --tap "201 917" @15 -d 50 --analyze

# 只分析已有 trace
tools/perfetto/record-heapprofd.sh --analyze-only tools/perfetto/out/xxx.perfetto
```

坐标用 `uiautomator dump` 现取（不同设备/滚动位置会变）。点按全部拼成**一条
设备侧序列**挂设备时钟 —— 宿主侧两个进程各 sleep 会漂移几十秒（坑⑤）。

## 脚本里的六个实测坑（都在真机/模拟器踩过，详见脚本头注释）

1. 手测两台设备（Android 12 真机、Android 16 模拟器）的 `HeapprofdConfig`
   都**没有 `target_cmdline`** —— 手写配置只能 pid 附加（脚本自动降级并打印原始报错）。
2. 设备端 pbtxt 解析器吃不了 UTF-8 注释和单行嵌套 —— cfg 模板必须纯 ASCII、一行一字段。
3. 客户端共享内存环默认很小，突发分配**静默丢数据**（`heapprofd_buffer_overran`）——
   必须 `shmem_size_bytes=16MB` + `block_client=true`。
4. ⚠️ **MIUI 的 SELinux 拒绝 heapprofd 读 App 文件 ⇒ App 自己 .so 里的栈帧无法
   unwind ⇒ 样本整个丢弃**（框架分配不受影响）。判别：`adb logcat -d | grep heapprofd`
   出现 `avc: denied` 即中招。此时归因 App native 代码用本仓 memtrace（bytehook）。
5. 抓 trace 和点按钮必须共用设备时钟（一条 `adb shell "sleep N; input tap …"`）。
6. 模拟器闲置十几分钟屏幕会灭，`input tap` 白点 —— 抓之前 `svc power stayon true`。

## 读数口径

- **大于采样间隔**（默认 4KB）的分配：确定性精确记录（32×1MB 会精确记成
  33,554,432 B / 32 objs）；**小分配是外推估计**，别当精确值用。
- 每张 dump（默认 5s 一张）是一个**窗口增量**；`size` 为负 = 窗口内释放。
- 表：`heap_profile_allocation`（账）→ `stack_profile_callsite/frame/mapping`（栈），
  老版 trace_processor 无 `allocation_size` 列，用 `sum(size)/sum(count)`。
- App 自己 .so 的帧常是**无名**的（APK 里 strip 过，内部函数不在 .dynsym）——
  拿 `stack_profile_frame.rel_pc` 走 `tools/native-mem-symbolize.sh` 的离线符号化流程。

对拍结论与原始数字（heapprofd vs memtrace/bytehook、真机 SELinux 边界）：
**内存方案文档 §3.6 + 附录 M14** ——
[`app/src/main/java/com/interview/内存/INTERVIEW-线上内存监控方案.md`](../../app/src/main/java/com/interview/内存/INTERVIEW-线上内存监控方案.md)。

## 关联（heapprofd 线）

- 方案与对拍：内存方案文档 §3.6（选型/机制差异/计数差 2 的解释）+ 附录 M14（原始数字）。
- 离线符号化：[`../native-mem-symbolize.sh`](../native-mem-symbolize.sh)。
- heap_profile 官方文档：<https://perfetto.dev/docs/data-sources/native-heap-profiler>

## 关联（滚动卡顿线）

- **面试向总结（五步排查法 + 本案例口述版）**：[`INTERVIEW-perfetto.md`](INTERVIEW-perfetto.md)
- 判据与结论的源码依据：`app/src/main/java/com/interview/image/NOTES-glide-source.md`
  （§7 measure→layout→draw 全链路、§9 RecyclerView 时序）。
- 列表侧优化清单：measure/layout、bind、回收缓存、DiffUtil、图片按尺寸解码。
