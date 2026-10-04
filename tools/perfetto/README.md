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

- **配置和 trace 的路径**：`perfetto` 只保证能读 `/data/misc/perfetto-configs`、
  能写 `/data/misc/perfetto-traces`。放 `/data/local/tmp` 会 `errno 13 Permission denied`。
- **`process.name` 全是 NULL**：配置里少了 `linux.process_stats`（`scan_all_processes_on_start: true`）。
  没它就无法把 pid 映射到进程名，只能靠 `draw-VRI[` 反查主线程。
- **多设备**：模拟器和真机同时连着时**必须** `-s <serial>`，否则容易在真机上误抓。
- **trace_processor_shell 按行切分 SQL**：写 SQL 时**必须单行**，多行会被截断报语法错。
- **下载源**：`commondatastorage.googleapis.com` 可能不通（`perfetto` Python 包默认走它）；
  `storage.googleapis.com/perfetto-luci-artifacts/...` 通常可达，脚本用的后者。
- **模拟器结论不等于真机**：模拟器软渲染会产生大量 SurfaceFlinger/Prediction jank，
  **性能结论必须用真机复验**。

## 与其他工具的分工

- `adb shell dumpsys gfxinfo <pkg> framestats`：最轻量的帧耗时预检，先看有没有明显尖刺。
- Perfetto：需要归因、看 CPU 调度、看 RV 埋点时用。
- Layout Inspector：看 view 树深度与测量耗时（层级优化）。

## 关联

- 判据与结论的源码依据：`app/src/main/java/com/interview/image/NOTES-glide-source.md`
  （§7 measure→layout→draw 全链路、§9 RecyclerView 时序）。
- 列表侧优化清单：measure/layout、bind、回收缓存、DiffUtil、图片按尺寸解码。
