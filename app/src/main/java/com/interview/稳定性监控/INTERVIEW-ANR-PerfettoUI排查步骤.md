# 在 Perfetto UI 里手把手排查这份 ANR（对着你的屏幕走）

> 你打开的这份：**`anr-full-with-processstats.perfetto`**，app 主线程 `com.example.myapplication 8628`。
> 本文每一步都对应 UI 上的具体位置，**照着点就行**。左侧那份是《排查实操》，本文只管 UI 操作。
>
> ✅ 下面所有坐标（秒数 / 线程 id）都是我从**这份 trace** 里查出来的实际值，不是我编的。

---

## 0. 先把答案放这里（方便你验证每一步走到没走到）

| 项 | 实际值 |
|---|---|
| trace 总长 | **31.63 s** |
| **ANR 冻结窗口** | **`1.902s` → `16.902s`（15.000 s）** |
| 主线程最后一次干活 | `syncAndDrawFrame` @ **1.901s** |
| 冻结后第一次恢复 | `binder transaction` @ **16.915s** |
| 主线程状态 | `S`（Sleeping）持续 **15000.3 ms** |
| 收到触摸 | `deliverInputEvent` @ 1.86s（耗时 8 ms） |
| 根因（对照 ANR 全文） | `Thread.sleep` @ `StabilityLabActivity.kt:228` |

**你现在选中的 `deliverInputEvent ... 00:00:01.925`** —— 正好是**冻结前的最后一次输入处理**。
它本身没问题（只跑了 8 ms），**问题在它之后主线程睡死了 15 秒**。

---

## 1. 第一步：把时间轴缩放到 ANR 窗口

界面上的时间轴（顶部那条带刻度的）：

1. 直接**拖拽选择** `1.9s ~ 16.9s` 这一段；
2. 或者按住 `W` 键在时间轴上拖（W/S 是缩放）。

> 💡 **更省事的办法**：鼠标滚轮放大的时候，注意看顶部刻度，把 **1.9** 和 **16.9** 分别顶到屏幕两端。
> 冻结窗口拉到满屏后，那 15 秒的空白会**一眼就看到**。

**你应该看到的**：主线程轨道上出现一大段**空白**（15 秒），前后各有密集的小色块。
那段空白就是 ANR。

---

## 2. 第二步：定位主线程轨道

左侧面板里找 **`com.example.myapplication 8628`**（你已经看到了），展开它：

```
com.example.myapplication 8628      ← 进程
  ├─ e.myapplication 8628           ← ⭐ 主线程（tid 8628 = pid，所以是主线程）
  ├─ main thread                    ← 同一线程的另一种视图
  ├─ RenderThread 8658
  ├─ app-anr-probe-1 8654           ← 你们自己的哨兵线程（有意思，后面说）
  ├─ app-main-sample 8655           ← 主线程采样器
  ├─ Binder:8628_1 8642 ...
  └─ ...
```

> 🔎 **怎么认主线程**：**tid == pid 的就是主线程**（这里 8628 == 8628）。
> 别被 `main thread` 那个重复轨道搞混——那是 Perfetto 给主线程的专用视图。

**点一下主线程轨道名**可以只看它（进入 focus 模式），排查时很有用。

---

## 3. 第三步 ⭐：四个必查视图（按顺序）

### 视图 ①：主线程状态 —— 一击定位

**操作**：点搜索框（左上角，提示写着 `Search or type '>' for commands, ':' for SQL query`），
**输入 `:` 开头就是 SQL**。粘贴这条：

```sql
select ts.state, round(sum(ts.dur)/1e6,1) as total_ms
from thread_state ts join thread t using(utid)
where t.tid = 8628
group by ts.state order by total_ms desc
```

**你应该看到**（实测值）：

| state | total_ms |
|---|---|
| **`S`** | **30775.7** |
| `Running` | 420.2 |
| `R` | 18.8 |
| `R+` | 8.6 |
| `D` | 1.7 |

> 🎯 **读数规则**：`S`（Sleeping）占了 **30.7 秒**中的绝大部分 ⇒
> **不是"算得慢"，是"在睡/在等"**。这时**不要**去找"哪个函数慢"。

再查那段具体的长睡眠：

```sql
select round((ts.ts - (select min(ts) from slice))/1e9,3) as at_s, ts.state,
       round(ts.dur/1e6,1) as dur_ms
from thread_state ts join thread t using(utid)
where t.tid = 8628 and ts.dur > 10000000000
```

**实测输出**：

```
at_s      state   dur_ms
1.902     S       15000.3      ← 与你选中的 deliverInputEvent 同一时刻开始
```

⇒ **`1.902s` 起睡了 `15000.3 ms`**，与 `Thread.sleep(15_000)` 严丝合缝。

### 视图 ②：输入链 —— 证明"系统在等你"

**操作**：点你已经选中的那个 `deliverInputEvent`，用 `↑/↓` 键在 slice 之间跳。

或者查：

```sql
select round((s.ts - (select min(ts) from slice))/1e9,2) as at_s,
       round(s.dur/1e6,1) as dur_ms, s.name
from slice s join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid
where t.tid = 8628 and s.name like 'deliverInputEvent%' order by s.ts
```

**实测输出**（**这就是铁证**）：

```
at_s     dur_ms
1.86     8.0        ← 正常处理
1.87     0.4        ← 正常处理
16.92    1.1        ← ← ← 中间空了 15 秒！
16.92    0.6
```

⇒ **输入处理在 `1.86s` 之后停了 15 秒**。这和 ANR 全文里的
`Waited 5003ms for MotionEvent(action=DOWN)` **完全咬合**。

> 📌 这里也解释了你们仓库那条重要结论：**主线程单纯 sleep 不触发 ANR**，
> 必须有输入在等。本次是实验页自己 `input tap` 注入触摸造出来的。

### 视图 ③：帧节奏 —— 看影响面

```sql
select count(*) from slice where name like '%doFrame%'
-- 实测：120
```

**操作**：在主线程轨道上搜 `Choreographer#doFrame`（搜索框直接输文字）。
**你应该看到**：`1.9s` 附近有帧，然后 **15 秒空档**，`16.9s` 之后又恢复。

### 视图 ④：跨进程 —— 排除"是别人卡了我们"

**先看有没有长 binder**：

```sql
select round(s.dur/1e6,1) as dur_ms, s.name
from slice s join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid
where t.tid = 8628 and s.name like 'binder%' order by s.dur desc limit 5
```

**实测**：最长只有 **2.6 ms** ⇒ **本次不是 binder 阻塞，可以排除**。

再确认 flow 箭头存在（排查别的 ANR 时会用）：

```sql
select count(*) from flow      -- 实测 5451 条
```

> 💡 **flow 箭头怎么用**：点一个 `binder transaction` slice，
> 如果有箭头连到另一个进程的 `binder reply`，那**箭头指向的就是"谁阻塞了你"**。
> 本次不适用（我们没有长 binder），但这是排查"对端卡死"的看家手段。

---

## 4. 第四步：交叉验证（两套工具对同一结论）

把窗口对齐好后，UI 里的证据和 ANR 全文应该**互相印证**：

| 证据 | Perfetto UI | ANR 全文（`anr-1-*.txt`） |
|---|---|---|
| 现象 | 主线程 `S` **15000.3 ms**（1.902s 起） | `"main" prio=5 tid=1 Sleeping` |
| 代码 | —— | `at com.interview.稳定性监控.StabilityLabActivity$onCreate$12$2.invoke(StabilityLabActivity.kt:228)` |
| 归因 | 输入处理停 15s | `Subject: Input dispatching timed out ... Waited 5003ms for MotionEvent(action=DOWN)` |

**推荐操作顺序**：在 UI 里定位到 `1.902s` → 记住这个秒数 →
打开 `anr-1-2026-10-17_222601.txt`（我在数据包里给了）→
`grep -n '"main"' ` 看它的栈 → **两边指向同一件事，结论坐实**。

---

## 5. 第五步：一个有意思的细节（可以写进面试）

左侧轨道里有这三条 —— **你们自己的监控线程**：

```
app-anr-probe-1 8654      ← AnrMonitor 的哨兵看门狗
app-main-sample 8655      ← MainThreadSampler
app-frame-metri 8663      ← JankMonitor
```

**在 UI 里点开 `app-anr-probe-1`**，你会看到它在 15 秒冻结期间**仍在周期性活动**。
实测它每 ~1020 ms 醒一次：

```
2.12s  S 1020.5ms   ← 睡
3.14s  R/Running    ← 醒来检查"主线程响应了吗" → 没响应 → 记一轮
3.14s  S 1007.3ms   ← 再睡
4.14s  R/Running    ← 再检查
...
```

⇒ 这正好可视化了你们文档里的结论：
**「哨兵是近似判定、系统判定才是权威」**——
哨兵在 1 秒粒度上**及时发现**了（比系统 5s 阈值还早），
而系统判定（`lastanr`）给出的是精确的 `Waited 5003ms`。
**两者在 UI 上能看到时间差**，这是很好的讲解素材。

---

## 6. 排查别的 ANR 时的通用清单（背这个）

```
① 缩放到 ANR 窗口
② 主线程状态聚合 → S/D 占大多数？ 是 → "在等"，不是"算得慢"
③ 输入链 deliverInputEvent 有没有空档     → 有 → input 类 ANR
④ binder 最长多少 ms                      → 长 → 用 flow 箭头追对端
⑤ 帧 doFrame 在冻结期间是否停止            → 是 → 确认 UI 真的卡了
⑥ 右边 Current Selection → "Slice" 面板   → 看 Duration 的 Running/Sleeping 拆解
⑦ 交叉验证 ANR 全文的 "main" 栈
```

> 💡 第 ⑥ 条就是你**现在屏幕上**那个面板：选中 slice 后右侧会给出
> `Duration / Running / Sleeping / Runnable` 的**百分比拆解**。
> 你现在选中的 `deliverInputEvent` 显示 `Running 82.53%` —— 说明**这一个 slice 本身很健康**，
> 别被它骗了，**凶手在它之后的那 15 秒空白里**。

---

## 7. UI 常用操作速查

| 想做什么 | 怎么做 |
|---|---|
| 找轨道 | 搜索框输名字，或按 `P`（Find tracks） |
| **跑 SQL** | 搜索框输 **`:`** 开头 |
| 命令面板 | `Cmd/Ctrl + P` → 搜 "Query (SQL)" |
| 缩放 | `W` / `S`，或时间轴上拖选 |
| 平移 | `A` / `D` |
| 选中 slice | 单击；`↑` / `↓` 在 slice 间跳 |
| 看单条轨道 | 点轨道名进入 focus；`Esc` 退出 |
| 度量区间 | `Shift` + 拖选，会显示耗时 |
| 重置视图 | `F`（fit to screen） |

---

## 8. ⚠️ 诚实边界

1. **本文所有数值来自这一份 trace + 那两条 ANR 全文**，是**单个样本**，不能当通用常量。
2. **Perfetto 与 ANR 靠时间窗人工对齐** —— trace 里**没有**稳定的"ANR 标记事件"。
   这也正是《线上采集方案》§5 提的那个技巧（App 写 `trace_marker` 打锚点）要解决的问题。
3. **本机 App 是 debuggable**；release 上要 `<profileable>` 才能这样剖析（未验证）。
4. **未验证**：其他 ANR 类型（广播/Service/Provider 超时）在这套 UI 流程下的表现，
   因为本仓没有那三类组件、造不出样本。
5. 我只在 Chrome 里核对了 UI 元素与查询，**未在其它浏览器验证**。
