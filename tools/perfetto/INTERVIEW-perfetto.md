# 面试向：用 Perfetto 排查性能问题

> 以本仓库瀑布流页（`com.interview.image.ImageLabActivity`）的真实 trace 为例。
> 所有数字都来自实测：真机 Redmi K40 / Android 12，`tools/perfetto/out/autoload-realdex-fixed-14s.perfetto`，
> 14 秒滚动抓取，期间滚动到底自动加载触发了 7 次。

## 0. 一句话框架

面试里答性能题，最容易丢分的不是"不知道工具"，而是**上来就优化**。正确的顺序是一条单向漏斗：

```
有没有问题 → 是不是我的问题 → 我的哪一段慢 → 为什么慢 → 改完有没有变好
   帧率         jank 归因        主线程切片        代码/源码        复测对比
```

Perfetto 的价值是把每一步都变成**可引用的证据**，而不是"我觉得"。下面按这条漏斗展开。

---

## 1. 第一步：先量化"有没有问题"

不要凭手感。两个层次：

| 层次 | 命令 / 位置 | 看什么 |
|---|---|---|
| 粗筛 | `adb shell dumpsys gfxinfo <pkg> framestats` | 有没有 >16ms 的尖刺、janky 帧占比 |
| 精查 | Perfetto：**Expected Timeline / Actual Timeline** | 每个 VSYNC 周期的预期帧 vs 实际帧 |

**Expected / Actual 两条轨道是本案例的入口**：Expected 是系统按刷新率给出的"应该在第几帧画完"，
Actual 是实际提交的帧。两者错位、并且 Actual 块被标红/橙，就是被判定为 jank 的帧。
点开有 `jank_type`（归因标签）——这就直接把我们带进第二步。

> 面试点：能说清"16.6ms 是 60Hz 的帧预算"，并知道 120Hz 是 8.3ms，是基本盘。

---

## 2. 第二步：归因——这卡顿是不是我的锅（最关键的一步）

`actual_frame_timeline_slice.jank_type` 是 Perfetto 给出的**归因**，本案例实测分布：

```
jank_type                      app 侧帧数（滚动稳定期 4–13s）
Buffer Stuffing                241     ← 合成侧，不是你的代码
None                           167
App Deadline Missed              6     ← 这才是 app 主线程超时
```

面试可以这样讲：

- **`App Deadline Missed`** = app 主线程没在预算内把这一帧做完 → **要管**，往下钻。
- **`SurfaceFlinger ... Deadline Missed` / `Buffer Stuffing`** = 合成侧（软渲染、buffer 积压）→ 通常**不管**，
  但在模拟器上它会是绝对主角。

⚠️ 两个必须说清的坑：
1. `jank_type` 可能是**复合值**（逗号拼接，一帧同时算 app 和 SurfaceFlinger），
   所以分类统计会**互相重叠**，不能相加。
2. **同一份代码，模拟器和真机结论可能相反**。本仓库实测过：模拟器上 jank 几乎全是
   SurfaceFlinger/Prediction（软渲染瓶颈），真机上 SurfaceFlinger 超时 = 0、全是 app 侧。
   → **性能结论必须真机复验**。这句在面试里是高光点，因为它说明你知道"环境会污染结论"。

---

## 3. 第三步：定位主线程，看时间花在哪

归因到 app 之后，找 **app 进程的主线程轨道**，看耗时切片排行。本案例（真机，14s）：

```
name                                n     max_ms   total_ms
traversal                          550    78.67     779.4
draw                               550    19.54     479.0
animation                          497     5.22     312.8
Record View#draw()                 550     9.0      268.8
RV Scroll                          470     4.8      222.7
RV Prefetch                        466    11.69     197.7
inflate                             15    72.13     154.0
performCreate:ImageLabActivity       1   153.36     153.4
RV OnLayout                         28    69.53     125.1
RV OnBindView                       54     5.79     102.0
RV CreateView                       12     8.53      78.3
```

读法要点：

- `traversal` 是 `ViewRootImpl` 的一次遍历（measure→layout→draw），**它的子段才是真正的耗时**：
  展开能看到 `measure` / `layout` / `draw`，`draw` 里还有 `Record View#draw()`。
- `inflate` / `RV CreateView` / `RV OnBindView` 是 RecyclerView 相关，`RV OnLayout` 是 `dispatchLayout`。
- **不能只看 total**：本例 `inflate` 只有 15 次但 max 72ms，说明它是"**偶发但致命**"，
  集中在首屏；`RV Scroll` 470 次但 max 4.8ms，是"**高频但便宜**"。

### 关键手法：把总账拆成"阶段"

把切片按**时间窗口**分段，而不是只看全程总计——这是本案例得出正确结论的核心动作：

| 阶段 | 时刻 | app 帧情况 | 主线程消耗 |
|---|---|---|---|
| 首屏冷启动 | 0–2.7s | 3 帧 App Deadline Missed，最长 **263ms** | `performCreate` 153ms、`inflate` 72ms |
| 滚动 + 自动翻页 | 4–13s | 仅 6 帧超时，其余 ~16ms 正常 | `RV Prefetch` 11ms → `RV CreateView`+`inflate` ≈8ms |

**结论直接翻盘**：全程总账看着"inflate 很贵"，分段后才发现贵的那次**只在首屏**
（一次性，与滚动无关）；滚动本身基本贴着帧预算。如果只报总账，就会得出
"要优化滚动时的 inflate"这种**错误结论**。

> 面试点：**"平均耗时"会骗人，要看分布、看时间线、看单帧 max。** 这句话很加分。

---

## 4. 第四步：为什么慢——顺着证据下到源码

Perfetto 只告诉你"慢在哪一段"，"为什么慢"要回源码。本案例的两处：

**（a）首屏 263ms 的那一帧**：`performCreate` 153ms + `inflate` 72ms。
`performCreate` 是 `Activity.onCreate`，说明**首屏 inflate 的布局树本身太重**
（本页顶部水位面板 + 两个 Switch + 底部按钮，一次全 inflate）。
方向：`ViewStub` / 按需 inflate / 收敛布局深度。

**（b）自动翻页的连锁**：超时帧里 `RV Prefetch` 最长 11ms，紧跟 `RV CreateView` / `inflate`。
`RV Prefetch` 是 GapWorker 在帧间隙预取下一页，**预取本身要建 holder 并 inflate**，
所以它把这一帧一起抬高。这是"自动加载下一页"引入的**真实但可接受**的代价。
方向:调大预加载阈值让预取更早更分散，或压浅 item 布局（`inflate` 是最贵的一环）。

**（c）跨进程阻塞**：主线程有一条 `binder transaction` max **19.74ms**，对端 `binder reply` 18.83ms
——主线程几乎全程在**等对端进程**。这就是"不是我慢，是我在等别人"的典型，
要拿证据说话就得会读 **flow 箭头**（见 §5）。

> 面试点：能从 slice 名字（`RV OnLayout`→`dispatchLayout`、`RV CreateView`→`onCreateViewHolder`→inflate）
> **反推到源码位置**，才算真的会用 Perfetto，而不是只会"看火焰图"。

---

## 5. 附：三个高频 UI 细节（面试常被追问）

**Flow 箭头（slice 上的连线）** — 本案例 `flow` 表 16437 条，构成：
```
binder transaction   → binder reply       11455 条   同步 binder（调用方阻塞）
binder transaction a → binder async rcv    4116 条   异步 binder（oneway，不等）
```
它就是"同一笔跨进程事务的 caller/callee 锚点"。跨进程卡顿必须靠它——
不连线，你在两根轨道上看到两段互不相干的 slice，根本不知道谁在等谁。

**`category: binder`** — 这类 slice 全由内核 `binder/binder_transaction` 埋点自动生成，
不用自己插桩；`async` 指的是 binder 的 **oneway 异步事务**那一支。

**RecyclerView 自带埋点** — `view` 分类一开就有，对应源码里的 `TraceCompat` 段：
`RV Scroll`/`RV Prefetch`/`RV OnBindView`/`RV CreateView`/`RV OnLayout`/`RV FullInvalidate`。
最常被问的一条：**数 `RV FullInvalidate`——不为 0 就说明还在 `notifyDataSetChanged()` 全量刷新**，
应改 `DiffUtil` / `ListAdapter`。

---

## 6. 第五步：改完复测，闭环

优化不是终点，**复测对比**才是。同样的采集命令、同样的操作序列，对比：

- `App Deadline Missed` 帧数（本案例滚动期 6 帧 → 优化后应 ≤ 6）
- 单帧 max（首屏 263ms → ?）
- 目标切片的 max/total（如 `RV CreateView` 8.5ms → ?）

这一条在面试里体现"工程闭环"：**没有复测的性能优化等于没做。**

---

## 7. 30 秒口述版（背这段）

> 排查滚动卡顿我按五步走。**一，先量化**：用 Expected/Actual Timeline 看有没有 jank。
> **二，归因**：看 `jank_type` 是 `App Deadline Missed`（我的锅）还是 SurfaceFlinger/Buffer Stuffing
> （合成侧，不管）——注意复合值不能相加，而且要真机复验，模拟器结论经常相反。
> **三，定位**：到 app 主线程看耗时切片排行，**关键是按时间窗口分段看**，
> 因为平均会骗人——我们这个 case 全程总账像是 inflate 很贵，
> 分段后发现 263ms 那帧只出现在首屏，滚动本身是贴着 16ms 的。
> **四，下源码**：slice 名字能反推代码，比如 RV Prefetch 贵是因为 GapWorker 预取要建 holder + inflate。
> **五，复测**：同样的抓取对比 jank 帧数和单帧 max。

---

## 8. 自测清单（能答上来再上场）

1. FrameTimeline 的 Expected 和 Actual 分别代表什么？`jank_type` 有哪几类，各归谁？
2. `traversal` 和它下面的 `measure/layout/draw` 是什么关系？
3. `RV Prefetch` 为什么会出现在超时帧里？GapWorker 做了什么？
4. 主线程上的 `binder transaction` 18ms，你怀疑什么？怎么用 flow 箭头求证？
5. 为什么"平均帧耗时"不能作为结论？你会怎么分段？
6. 模拟器上 90% 是 SurfaceFlinger jank，你要怎么判断该不该改代码？

## 关联

- 采集脚本与踩坑：[`README.md`](README.md)（含 `duration_ms` 单位、抓取顺序、`task_rename` 进程命名）
- 源码级判据：`app/src/main/java/com/interview/image/NOTES-glide-source.md`
