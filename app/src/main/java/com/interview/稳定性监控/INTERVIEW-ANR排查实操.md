# ANR 排查实操指南（基于一次真实抓取）

> 配套：**线上怎么把这些文件拿回来** → [`INTERVIEW-ANR线上采集方案.md`](./INTERVIEW-ANR线上采集方案.md)
> 配套数据在同目录 `tools/perfetto/out/anr/`，**全部是这台 Redmi K40 上实测抓下来的**。
> 设备：Redmi K40（`alioth`）/ Android 12 / API 31 / arm64 / **user（非 root）**。
> 目标 App：`com.example.myapplication`（debuggable）。
>
> 本文不是"工具介绍"，而是**拿一份真 ANR 数据，把每一步该看哪里、看到什么算命中**写清楚。

---

## 0. 本次数据包（你可以直接用这些练手）

| 文件 | 是什么 | 用什么打开 |
|---|---|---|
| `anr-full-with-processstats.perfetto`（15.9 MB） | ⭐ **主战场**。ANR 现场的 Perfetto trace，含 app 线程名 | ui.perfetto.dev |
| `anr-1-inputtimeout.perfetto`（17.6 MB） | 同一类 ANR 的另一次抓取（无 process_stats） | ui.perfetto.dev |
| `anr-1-2026-10-17_222601.txt`（196 KB / 2733 行） | ⭐ **ANR 全文**（从 DropBox 提取），含全部线程栈 | 任意文本编辑器 |
| `anr-2-2026-10-17_222657.txt`（2730 行） | 第二次 ANR 的全文 | 同上 |
| `dropbox-print-all.txt`（1.3 MB） | `dumpsys dropbox --print` 原始全量输出 | 文本 |
| `heapprofd-native-10s.perfetto`（112 KB） | native 堆分配（顺带抓的） | ui.perfetto.dev |

**本次案情（先把答案给你，方便对照）**：
实验页有个「⚠真ANR(15s)」按钮，它做两件事：**主线程 `Thread.sleep(15_000)`** + 页面自己持续 `input tap` 注入触摸。
于是系统判定 input 分发超时（5s），产生真 ANR。
**根因就是那行 `Thread.sleep`——在 `StabilityLabActivity.kt:228`。**

---

## 1. 第一步：打开 trace，先把时间轴对齐

### 1.1 怎么打开

浏览器进 **https://ui.perfetto.dev** → 「Open trace file」→ 选 `anr-full-with-processstats.perfetto`。

> ⚠️ 别用命令行 `trace_processor_shell --httpd` 那种 RPC 加速方式接 UI：
> 本仓自带的 `trace_processor_shell` 是 **v49.0**，而现在的 ui.perfetto.dev 是 **v58.3**，
> 版本不匹配会直接报 `QueryError: INCLUDE: Package 'std.traceinfo.trace' not found` 并卡在 "Opening trace"。
> **实测踩过**。用 UI 自带的 WASM 引擎（也就是直接选文件）最省事。

### 1.2 本次 trace 的时间轴（记住这三个数）

```
0s ─────────────────────── 1.9s ═══════════════════ 16.9s ────────── 31.6s
                             ↑ 主线程开始 sleep 15.000s          ↑ trace 结束
                             这就是 ANR 现场
```

- **trace 总长 31.63 s**
- **`1.9 s` 处**：主线程进入 15 秒 sleep ← **ANR 起点**
- **`16.9 s` 处**：sleep 结束

⇒ **排查时先把时间轴缩放到 1.9s–16.9s 这一窗**，其他都是噪音。

---

## 2. 第二步：Perfetto 里看什么（四个必查视图）

### 视图 A ⭐ 主线程状态（一击定位的关键）

在搜索框（左上放大镜，或按 `P`）输入 `com.example.myapplication`，找到进程后展开 **主线程** 轨道。
主线程的**颜色块就是它的状态**。

**更精确的做法**：用 `Query (SQL)` 页签（左下角，或 `Ctrl/Cmd + Enter` 打开命令面板搜 "query"）跑：

```sql
select ts.state, round(sum(ts.dur)/1e6,1) as total_ms
from thread_state ts join thread t using(utid)
where t.tid = 8628
group by ts.state order by total_ms desc;
```

**本次实测输出**（`tid=8628` 就是 app 主线程）：

| state | 累计耗时 | 含义 |
|---|---|---|
| **`S`** | **30,775.7 ms** | Sleeping —— **就是它** |
| `Running` | 420.2 ms | 真正在跑 |
| `R` | 18.8 ms | 可运行但排队 |
| `D` | 1.7 ms | 不可中断 IO |

> 🎯 **判断口诀**：主线程 `S`/`D` 占了绝大多数 → **不是"算得慢"，是"在等"**。
> 这时不要去看"哪个方法慢"，要去查**它在等什么**。

再定位这一次具体的长睡眠：

```sql
select ts.state, round(ts.dur/1e6,1) as dur_ms
from thread_state ts join thread t using(utid)
where t.tid = 8628 order by ts.dur desc limit 3;
```

实测：**`S`, 15000.3 ms** ← 与 `Thread.sleep(15_000)` 严丝合缝对上。

### 视图 B 跨进程阻塞：binder flow 箭头

主线程卡在 binder 上时，**trace 里会有 flow 箭头**把「我发出的事务」和「对方的回复」连起来。
在 `binder transaction` / `binder reply` slice 上点一下，看看有没有箭头连出去。

```sql
-- 先看 app 进程的 binder 量级
select s.name, count(*) c
from slice s
join thread_track tt on s.track_id = tt.id
join thread t on tt.utid = t.utid
join process p using(upid)
where p.name like '%myapplication%' and s.name like 'binder%'
group by s.name order by c desc;
```

本次实测（**这 1018 条是背景噪音，不是凶手**）：

```
binder transaction        1018
binder transaction async   977
binder async rcv           951
```

> 🎯 **关键区分**：`binder transaction`（同步，调用方**阻塞**）vs `binder transaction async`（oneway，**不等**）。
> 只有**同步**且在**主线程**上的事务才可能是 ANR 原因，且要看**箭头指到的对端**在干嘛。

### 视图 C 输入分发链（input 类 ANR 必看）

ANR reason 里给了一个 channel id（本次是 `675cdf8`），**它能直接在 trace 里搜到**：

```sql
select round(s.ts/1e9,2) as at_s, substr(s.name,1,60) as name
from slice s where s.name like 'startDispatchCycleLocked%' order by s.ts limit 10;
```

实测：**`259469.71` s 有 `startDispatchCycleLocked(inputChannel=e499313 com.example.my...)`**
——而主线程是在 `259469.687` s 进入 sleep 的。
⇒ **输入事件到达的时刻 = 主线程开始睡的时刻**，两个证据咬合，闭环。

> 这也解释了本仓库那个重要结论（见 `INTERVIEW-稳定性监控.md` §1.1）：
> **主线程干睡不碰屏幕 → 系统不判 ANR**；必须有输入在等（本次靠注入触摸）。

### 视图 D 帧节奏（判断影响面）

```sql
select count(*) from slice where name like '%doFrame%';   -- 本次 120
```

也可以直接看主线程轨道的 `Choreographer#doFrame`，**15 秒空白**就是掉帧区间。

---

## 3. 第三步：读 ANR 全文（回答"卡在哪一行"）

Perfetto 告诉你**现象**（在睡 15 秒），全文告诉你**代码位置**。

### 3.1 怎么拿到（三条路，按优先级）

```bash
# ① DropBox（推荐，无需 root，实测可用）
adb shell dumpsys dropbox --print > dropbox_all.txt
grep -n data_app_anr dropbox_all.txt

# ② 应用内 App ExitInfo（有界环形，必须每次冷启动回捞）
adb shell dumpsys activity exit-info <pkg>

# ③ ❌ 不要走 /data/anr —— 非 root 上 Permission denied（实测）
```

### 3.2 读的顺序（**照这个顺序，别跳**）

**第 1 步：看元信息头**（回答"是什么类型的 ANR、谁卡的"）

```bash
sed -n '1,20p' anr-1-*.txt
```

本次实测：

```
Process: com.example.myapplication
PID: 8167
Foreground: Yes
Activity: com.example.myapplication/com.interview.稳定性监控.StabilityLabActivity
```

**第 2 步：看 `Subject` 行**（系统自己的判定，**最强的交叉验证**）

```bash
grep -n "^Subject:" anr-1-*.txt
```

本次实测（第 167 行）：

```
Subject: Input dispatching timed out (... StabilityLabActivity (server) is not responding.
         Waited 5003ms for MotionEvent(action=DOWN))
```

⇒ **一句话给出：超时类型（input）、超时时长（5003ms ≈ 5s 阈值）、宿主 Activity、事件类型（DOWN）**。
归因到哪个页面**不需要额外埋点**。

**第 3 步：按 `----- pid` 分段**（⚠️ 最容易漏的一步）

```bash
grep -n "^----- pid" anr-1-*.txt
```

本次实测：

```
169:----- pid 8167 at 2026-10-17 22:25:57.740 -----   ← 我们的进程
...:----- pid 1916 at 2026-10-17 22:25:57.784 -----   ← system_server
```

> 🎯 **为什么必须分段**：ANR 报告里**同时包含 system_server 的线程栈**。
> 实测这条 196 KB 的报告里，**71.7% 是 system_server 的 98 个线程块**。
> 不分段就会把别人的线程当成自己的，直接看晕。
> （但**别急着丢**：主线程卡在 binder 时，对端的栈**就在这一段里**，见 §5。）

**第 4 步：看 `"main"` 线程块**（回答"卡在哪一行"）

```bash
awk '/^"main"/{f=1} f{print; n++} n>13{exit}' anr-1-*.txt
```

本次实测：

```
"main" prio=5 tid=1 Sleeping
  | sysTid=8167 nice=-10 cgrp=default sched=0/0 handle=0x7d1fcea4f8
  | state=S schedstat=( 525387601 27860786 414 ) utm=44 stm=8 core=5 HZ=100
  | held mutexes=
  at java.lang.Thread.sleep(Native method)
  - sleeping on <0x0b665df9> (a java.lang.Object)
  at java.lang.Thread.sleep(Thread.java:451)
  - locked <0x0b665df9> (a java.lang.Object)
  at com.interview.稳定性监控.StabilityLabActivity$onCreate$12$2.invoke(StabilityLabActivity.kt:228)
```

⇒ **精确到 `StabilityLabActivity.kt:228`，与 Perfetto 的 "S 15000ms" 互证。**

### 3.3 `main` 线程状态 → 根因类别的对照表

| `"main"` 的状态 | 怎么读 | 本次是否命中 |
|---|---|---|
| `Sleeping` / `TimedWaiting` | ⚠️ **看到 Sleep 不要跳过**——它可能就是凶手（本次就是） | ✅ |
| `Waiting` + `held mutexes=` 非空 | 锁竞争。**要去找"持锁的那个人"**（`- locked <addr>` 在别的线程里） | ❌ |
| `Native` | 大概率 binder / IO。Java 层看不出，**必须配合 Perfetto 的 flow 或对端栈** | ❌ |
| `Runnable` | 真在算（死循环 / 大计算 / 密集布局） | ❌ |

---

## 4. 第四步：两条工具交叉验证（本次的完整证据链）

单看一个工具容易误判，**用两个工具指向同一结论**才算坐实：

| 证据 | 工具 | 实测结论 |
|---|---|---|
| 现象 | Perfetto | 主线程 `state=S` 累计 **30.7 s**，单段最长 **15.000 s** |
| 代码位置 | ANR 全文 | `"main" Sleeping` → `Thread.sleep` → **`StabilityLabActivity.kt:228`** |
| 触发原因 | Perfetto + 全文 | 输入到达(`259469.71s`) = 主线程开始睡(`259469.687s`)；系统 `Waited 5003ms for MotionEvent(action=DOWN)` |

⇒ **三条证据互相咬合，根因确定为「主线程主动 sleep 15s，输入事件在此期间超时」。**

---

## 5. 进阶：Perfetto 拿不到的部分，去全文里拿

**这是两套数据的核心分工**：

| 问题 | Perfetto | ANR 全文 |
|---|---|---|
| 主线程在等什么？ | ✅ 状态块 + 时长 | ✅ 栈帧到行号 |
| **对端进程在干嘛？** | ⚠️ 只能靠 flow 箭头推 | ✅ **system_server 段里有它的线程栈** |
| 卡了多久？ | ✅ 精确到 ns | ⚠️ 只有 `schedstat` 累加值 |
| 历史多次 ANR 的趋势 | ❌ | ✅ DropBox 存多条（实测本机 2 条 / 上限 1000） |

**本次没用上但对端排查的标准动作**：主线程 `Native` 且停在 `BinderProxy.transact` 时，
去 `----- pid <system_server>` 段里找对应线程（看 `Binder:` 开头的线程名 + `transact` 帧），
回答"是 system_server 在忙，还是它在等别的进程"。

---

## 6. 一键复现（照抄即可）

```bash
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
PKG=com.example.myapplication
LAB=com.interview.稳定性监控.StabilityLabActivity

# ① trace 配置放 perfetto 专用目录（放 /data/local/tmp 会 Permission denied，实测）
adb shell 'mkdir -p /data/misc/perfetto-configs /data/misc/perfetto-traces'
# （cfg 见 §7）

# ② 顺序要求：atrace_apps 不追溯 —— 先 force-stop → 起 perfetto → 再启动 app
adb shell am force-stop $PKG
adb shell "perfetto -c /data/misc/perfetto-configs/anr.cfg --txt -o /data/misc/perfetto-traces/anr.perfetto" &
sleep 3
adb shell "am start -n $PKG/$LAB"

# ③ 点「⚠真ANR(15s)」按钮（坐标用 uiautomator dump 查）
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml .
adb shell input tap 878 773

# ④ 等 30s 后收尾
adb pull /data/misc/perfetto-traces/anr.perfetto .
adb shell "dumpsys window | grep -i -A4 lastanr"        # 确认系统判了 ANR
adb shell dumpsys dropbox --print > dropbox_all.txt     # 取 ANR 全文
```

### 命令行查（不开 UI 时）

```bash
# ⚠️ 是 -Q（查询串），不是 -q（读查询文件）—— 实测踩过
tools/perfetto/bin/trace_processor_shell anr.perfetto -Q \
  "select ts.state, round(sum(ts.dur)/1e6,1) from thread_state ts
   join thread t using(utid) where t.tid=<主线程tid> group by ts.state;"
```

---

## 7. Perfetto 配置（ANR 版）

现有 `tools/perfetto/scroll-trace.cfg` 是给滚动优化的，**缺 `am` / `input` / `ss` / `binder_driver`**，
抓 ANR 要补上：

```protobuf
buffers: { size_kb: 131072 fill_policy: RING_BUFFER }

data_sources: { config {
  name: "linux.ftrace"
  target_buffer: 0
  ftrace_config {
    atrace_categories: "am"          # ← ANR 相关（AMS 侧）
    atrace_categories: "input"       # ← 输入分发超时必开
    atrace_categories: "ss"          # ← system_server
    atrace_categories: "view"
    atrace_categories: "wm"
    atrace_apps: "com.example.myapplication"

    ftrace_events: "sched/sched_switch"
    ftrace_events: "sched/sched_waking"
    ftrace_events: "task/task_rename"
    ftrace_events: "binder/binder_transaction"
    ftrace_events: "binder/binder_transaction_received"
    buffer_size_kb: 32768
  }
}}

# ⚠️ 这条必须有，否则进程名全是 [NULL]，你会"抓到了但找不到 app 主线程"（实测踩过）
data_sources: { config {
  name: "linux.process_stats"
  target_buffer: 0
  process_stats_config { scan_all_processes_on_start: true proc_stats_poll_ms: 1000 }
}}

duration_ms: 30000
```

---

## 8. 四个实测坑（照抄会省你很多时间）

| # | 坑 | 现象 | 解法 |
|---|---|---|---|
| 1 | `atrace_apps` **不追溯** | trace 里没有 app 的埋点 | 先 `force-stop` → 起 perfetto → 再启动 app |
| 2 | 缺 `linux.process_stats` | 进程名全是 `[NULL]`，认不出主线程 | 加该数据源（见 §7） |
| 3 | cfg 放错目录 | `Could not open ... Permission denied` | 只放 `/data/misc/perfetto-configs` |
| 4 | `trace_processor` 参数写错 | 报 "Unable to read file select..." | 查询串用 **`-Q`** |

另外：**RPC 加速接 UI 会有版本不匹配**（本仓 TP 是 v49.0，UI 是 v58.3）→ 直接用 UI 自带 WASM 打开文件。

---

## 9. ⚠️ 诚实边界

1. **本指南只覆盖 input-dispatch 超时这一类 ANR**。广播 10s / Service 20s / Provider 10s
   这几类**没有模拟器可用**（本仓无 `BroadcastReceiver` / `Service` / `ContentProvider`），
   因此"这几类用同一套流程能否采到"**未验证**。
2. **Perfetto 与 ANR 靠时间窗对齐，不是硬关联**。trace 里**没有稳定的"ANR 标记"事件**，
   本次是靠 `dumpsys window lastanr` 的时间 + 主线程 15s 空白人工对齐的。
3. **本机 App 是 debuggable**；release 上要加 `<profileable android:shell="true"/>` 才能剖析，**未验证**。
4. **未做长时间压测**：Perfetto 常开的存储/功耗开销、以及观察者效应（抓 trace 本身是否影响 ANR），都**未测**。
5. 全部结论来自**单台 Redmi K40 / Android 12 / MIUI**，**未跨版本验证**。
6. `dumpsys dropbox --print` 实测本机 207 条 / 上限 1000；**生产设备的保留策略未验证**。
