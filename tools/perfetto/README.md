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
- ART 方法 trace 的 `ts/dur` 单位是**微秒**（`analyze_startup.py` 已按此解析）；
  它本身也是合法 trace，可直接喂给 `trace_processor_shell`。

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
