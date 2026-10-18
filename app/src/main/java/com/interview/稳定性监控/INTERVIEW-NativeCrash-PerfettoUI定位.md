# 在 Perfetto UI 里定位 Native Crash（对着你的屏幕走）

> **你打开的这份**：`segv-with-heapprofd.perfetto`（16 MB），
> 里面是**一次真实的 Native SEGV**：app 进程 `com.example.myapplication` pid **30592** 崩在 **17.53s**。
> **配套**：[`INTERVIEW-Crash-Perfetto排查方法.md`](INTERVIEW-Crash-Perfetto排查方法.md)（命令版原理）、
> [`record-crash-heapprofd.sh`](../../../../../../../tools/perfetto/record-crash-heapprofd.sh)（怎么抓的）
>
> ✅ 本文所有秒数、pid、线程数都是从**你正在看的这份 trace** 里查出来的，不是泛例。

---

## 0. 先立规矩：Perfetto 定位 native crash 能做什么、不能做什么

这是最重要的一节。**别指望 Perfetto 告诉你"崩在哪一行"** —— 它做不到。

**Perfetto 没有 crash 数据源。** 我实测列过设备端 perfetto 的全部 data source，
**没有任何 tombstone / minidump / signal / debuggerd 相关项**。

所以两个证据源必须**配合**：

| | Perfetto trace | logcat / tombstone |
|---|---|---|
| 回答 | **死之前发生了什么** + **线程怎么死的** | **崩在哪一行**（signal + 符号化栈） |
| 你这份里 | ✅ 有（15.8 MB） | ✅ 有（`...logcat.txt`） |
| 擅长 | **时序、全线程、GC、CPU** | **精确定位代码行** |

> 🎯 **一句话**：**Perfetto 负责"缩小范围 + 证明是崩溃"，tombstone 负责"指认凶手"。**
> 本文教的是前者。

---

## 1. 第一步：缩放到崩溃窗口（17.4s ~ 17.7s）

**操作**：时间轴上把 **17.4s ~ 17.7s** 拖到你面前（`W` 放大 / `A`、`D` 平移 / `F` 复位）。

> 💡 **怎么知道是 17.5s 附近**？这就是 Perfetto 的看家本领 —— 往下看第 2 步。

---

## 2. ⭐ 第二步：看"线程集体死亡" —— native crash 的决定性指纹

### 2.1 先找到崩溃进程的线程轨

左侧面板找 **`com.example.myapplication 30592`**（注意是 **30592**，不是 `heapprofd 30172`）展开。

> ⚠️ **别搞混**：你之前点过 `heapprofd 30172` —— 那是 **heapprofd 守护进程**（`/system/bin/heapprofd`），
> **不是你的 App**，也不是堆剖析数据。真正的 App 是 **30592**。
> （实测确认：`process` 表里 `30172` 的 name 就是 `/system/bin/heapprofd`。）

### 2.2 看崩溃瞬间：20 个线程在同一毫秒消失

**操作**：把窗口拉到 **17.5s 附近**，看 App 的各条线程轨 ——
你会看到它们**在同一个位置整齐地断掉**，之后全是空白。

**实测数据**（这份 trace 的真相）：

```
17.525s   1 个线程转为 X
17.526s  20 个线程转为 X   ← ⭐ 20 个线程在同一毫秒集体消亡
17.527s   4 个线程
17.529s   1 个线程
17.544s   1 个线程
17.588s   1 个线程
```

**用 SQL 确认**（搜索框输 `:` 直接跑）：

```sql
select round((ts.ts - (select min(ts) from slice))/1e9, 3) as at_s,
       count(*) as threads
from thread_state ts
where ts.utid in (select utid from thread where upid = 876)
  and ts.state = 'X'
group by at_s order by at_s
```

**实测输出**：
```
at_s     threads
2.864    4        ← 启动期的短命线程，别误读
3.907    1
17.525   1
17.526  20        ← ⭐ 就是这里
17.527   4
17.529   1
17.544   1
17.588   1
```

> ⚠️ **判读时要注意**：`2.864s / 3.907s` 那几条是**进程启动早期的短命线程**
> （Runtime worker / pool 之类），它们正常结束也是 `X`。
> **真正的崩溃指纹是"聚类"** —— 17.525~17.529 这 5 个时间点合计 27 个线程，
> **集中在 4 毫秒内**。所以看的是**聚集度**，不是"有没有 X"。

> `upid = 876` 是 pid 30592 的内部 id（也能写成
> `(select upid from process where pid=30592)`）。

### 2.3 判读规则（这是核心）

| 现象 | 结论 |
|---|---|
| **多线程在同一时刻集体消亡**（跨度 < 0.1s） | ⭐ **Native 崩溃**（本例：20 个线程在 1ms 内） |
| 主线程先变 `Z`，**其余线程还在**（逐个 detach） | **Java 崩溃**（ART 逐个拆线程） |
| 没有任何线程终止 | **没死** —— OOM 被 catch / 未真崩 |

> 🔑 **为什么能这样区分**：Native 信号崩溃是**内核直接杀整个进程**，
> 所有线程同时被终止；Java 崩溃走 ART 的收尾流程，主线程先死、其他线程一个个 detach。
> **这个差别在 trace 上一眼可见。**

---

## 3. 第三步：看主线程死前在干什么

**操作**：点开主线程轨（`e.myapplication`），看 **17.2s ~ 17.53s** 这一段。

**实测**（崩前最后几件事）：

```
17.266  Loading font /system/fonts/NotoSansLaoUI-Regular.ttf
17.260  syncAndDrawFrame
17.256  Record View#draw()     4.57ms
17.255  Choreographer#scheduleVsyncLocked
17.255  animation
17.250  Choreographer#scheduleVsyncLocked
```

**判读**：死前是**完全正常的绘制流程**（画帧、加载字体、调度 vsync）——
**没有任何卡顿、没有长时间阻塞**。

⇒ 这排除了"被拖死/卡死"的可能，符合 **"运行时突然 SEGV"** 的特征。

> 💡 **对照**：如果这里看到的是**一大段空白**或**超长 slice**，
> 那方向就完全不同了（要往"阻塞/等待"查，而不是"突发崩溃"）。

**辅助查询 —— 主线程状态聚合**：

```sql
select ts.state, round(sum(ts.dur)/1e6, 1) as total_ms
from thread_state ts join thread t on ts.utid = t.utid
join process p using(upid)
where p.pid = 30592 and t.is_main_thread = 1
group by ts.state order by total_ms desc
```

本份实测：`S` 15157ms（大部分时间在等输入）、`Running` 356ms、`Z` 收尾 ——
**主线程是"闲"的**，再次印证不是被计算拖死。

---

## 4. 第四步：右侧 Slice 面板（点选单条看细节）

**操作**：单击任意 slice → 右侧 **Current Selection** 面板给出：

| 字段 | 用途 |
|---|---|
| **Duration / Running / Sleeping / Runnable** | 该 slice 的时间构成（百分比） |
| **Flow**（下拉切换） | 跨进程箭头 —— 追 binder 对端 |
| **Args** | 该 slice 的参数（如 `inputChannel=e499313`） |

> 💡 排查 native crash 时这一步用得少（因为崩前没有异常 slice），
> 但排查"卡死型"问题时是主力。

---

## 5. 第五步：heapprofd 面板（内存归因，本次要**打折看**）

你打开的这份 trace 里带了 heapprofd。UI 底部有 **"Native heap profile flamegraph"** 面板。

**操作**：
1. 底部找 **`Native heap profile flamegraph`** 面板
2. 面板顶部有 **`Snapshot time:`** 下拉 —— **⭐ 选最后一个快照（17.53s）**
3. 看 **Top Down** 火焰图；**Measure:** 下拉可切 `Unreleased Malloc Size` 等指标
4. 还有 **`Highlight`（搜索）、`Export`、`Timeline Sync`** 按钮

**⭐ 为什么要选最后一个快照 —— 这是本份 trace 的一个关键细节**：

配置里 `continuous_dump_config { dump_interval_ms: 3000 }` 会周期性出快照，
**实测共 6 个**：

```
快照时刻    分配条数   字节数
2.00s         16          0
6.08s         48          0
8.22s        128          0
12.21s        48          0
14.25s        16          0
17.53s       454     584244   ← ⭐ 只有这个有数据，且正好是崩溃时刻
```

**前 5 个字节数都是 0，只有 17.53s 那个有 584 KB** ——
因为 **heapprofd 在进程退出时会做最后一次 dump**，那一刻抓的才是**进程死时的存活堆**。
**所以看堆就得看这最后一个快照**（前几个是周期快照，本份里没采到存活数据）。

**本次实测的归因结果 —— 这是关键**：

```
frame    mapping                                         bytes
malloc   /apex/com.android.runtime/lib64/bionic/libc.so  449280
calloc   /apex/com.android.runtime/lib64/bionic/libc.so  102196
realloc  /apex/com.android.runtime/lib64/bionic/libc.so   32768
```

**⚠️ 所有分配都落在 bionic 的 libc，App 自己的帧一条都没有。**

**这不是你操作错了，而是本机（MIUI）的策略边界** ——
`record-heapprofd.sh` 头部"坑④"记录了原因：

> MIUI 的 SELinux 拒绝 heapprofd 守护进程读 App 自己的文件
> （`avc: denied { search } scontext=u:r:heapprofd:s0 tcontext=object_r:app_data_file:s0`）
> ⇒ **栈帧落在 App 自己 .so 里的分配无法 unwind，样本被整个丢弃**。

**判别方法**：`adb logcat -d | grep heapprofd` 若出现 `avc denied`，即是此因。

> 🔑 **实战含义**：在这台机器上，heapprofd **只能看"框架层分配总量"**，
> **不能归因到你自己的 native 代码**。要归因自己的代码，两条路：
> ① 换 AOSP 系镜像（本仓 API 36 模拟器实测可 unwind 到 App 帧）；
> ② 用本仓 `memtrace`（bytehook + 离线符号化，不受此限制）。

**用 SQL 确认有几个快照、哪个有数据**：

```sql
select round((ts - (select min(ts) from slice))/1e9, 2) as snapshot_s,
       count(*) as allocs, sum(size) as bytes
from heap_profile_allocation group by ts order by ts
```

> ⚠️ **为什么前几个快照 `bytes` 是 0**：heapprofd 周期 dump 记录的是
> **"该时刻仍存活的分配"** —— 本份里只有**进程退出时的最终 dump**（17.53s）采到了 584 KB。
> 这个细节在命令行分析里会静默显示成"总量为 0"，**很容易误导**。

---

## 6. 第六步：⭐ 交叉验证 —— 去 logcat 拿"怎么死的"

Perfetto 到这里已经把范围缩到 **17.53s、进程整体死亡、崩前无异常**。
现在去 `segv-with-heapprofd.perfetto.logcat.txt` 找真相：

```bash
grep -E "Fatal signal|F DEBUG|tombstone" segv-with-heapprofd.perfetto.logcat.txt | head
```

**实测输出**：
```
F libc      : Fatal signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
              in tid 30592 (e.myapplication), pid 30592
I crash_dump64: obtaining output fd from tombstoned
I tombstoned  : Tombstone written to: tombstone_08
I BootReceiver: Copying /data/tombstones/tombstone_08 to DropBox (SYSTEM_TOMBSTONE)
```

**两边一对**：

| 证据 | 值 | 说明 |
|---|---|---|
| trace | 线程集体死在 **17.53s** | 范围锁定 |
| logcat | `SIGSEGV / SEGV_MAPERR / fault addr 0x0` | 死因明确 |
| 符号化栈 | `NativeCrashHandler_nullDerefNative+24` | **凶手指认** |

> ⚠️ **注意 `SEGV_MAPERR` + `fault addr 0x0` = 空指针解引用** ——
> 而 logcat 里 `StabilityLab` 那行会写着"即将空指针解引用"。
> 这是**故意造的**，别当成真实 bug。

---

## 7. 完整排查流程（背这张）

```
① 缩放时间轴，找"线程集体断掉"的位置          ← 不预设时间
        │
② 看线程终态：多线程同刻消亡？
        ├─ 是（跨度<0.1s）→ ⭐ Native 崩溃
        ├─ 主线程先 Z、其余还在 → Java 崩溃
        └─ 无线程终止 → 没死，转查 OOM/卡顿
        │
③ 看主线程死前的 slice
        ├─ 有超长 slice / 空白 → 阻塞类，另查
        └─ 正常绘制流程 → 突发崩溃，符合预期
        │
④ heapprofd 火焰图（有就顺手看）
        ├─ 有 App 帧 → 归因到代码
        └─ 只有 bionic → 本机 SELinux 限制，换镜像/memtrace
        │
⑤ ⭐ 交叉验证 logcat/tombstone → 拿到 signal + 符号化栈 → 定位代码行
```

---

## 8. UI 操作速查

| 想做什么 | 怎么做 |
|---|---|
| **跑 SQL** | 搜索框（左上）输 **`:`** 开头 |
| 找轨道 | 搜索框输名字（如 `30592`），或按 `P` |
| 缩放 / 平移 | `W` / `S`；`A` / `D` |
| 复位 | `F` |
| 选中 slice | 单击；`↑`/`↓` 在 slice 间跳 |
| 只看某条轨 | 点轨道名进 focus，`Esc` 退出 |
| 度量区间 | `Shift` + 拖选 |
| **时间同步火焰图** | 底部面板的 **`Timeline Sync`** 按钮 |

---

## 9. ⚠️ 诚实边界

1. **本文所有数值来自你正在看的这份 trace**（真机 Redmi K40 / Android 12 / user build），可复验。
2. **`blocked_function` 在本机查不到**（全 NULL）——
   已订阅 `sched_blocked_reason`（20,542 条事件在），但符号解析需读内核 `format` 文件，
   SELinux 拒绝。**这是 user build 的权限天花板，不是配置问题。**
3. **`android.log` 数据源收不到日志**（`android_log_num_total=0`，需 `READ_LOGS` 特权）⇒
   logcat 必须单独 `adb logcat` 抓。**所以本文第 6 步是去读外部文件，不是读 trace 内日志。**
4. **heapprofd 在本机拿不到 App 自己代码的帧**（§5），这是 SELinux 策略边界。
   **换成 AOSP 镜像或 memtrace 才能归因自己的 native 代码**（后者本仓已实现，未在本篇验证）。
5. **"集体死亡 = Native 崩溃"这条判读**基于本次样本 + 此前两份样本（Java/Native 各一）实测；
   **多进程连带崩溃、Zygote 相关、被 SIGKILL（LMK）** 等形态**未验证**
   （LMK 杀进程在 trace 上可能表现为类似的集体消亡，需结合 ExitInfo 区分）。
6. **崩溃时刻无内置标记**：trace 里没有"崩溃"这个事件，是**靠线程状态变化人工推断**的。
   想省事可让 App 崩溃前写 `trace_marker` 打锚点。
