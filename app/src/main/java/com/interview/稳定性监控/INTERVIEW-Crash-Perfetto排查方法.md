# 用 Perfetto 排查各类 Crash —— 常见类型 + 方法库（全部真机实测）

> **配套**：[`INTERVIEW-ANR-Perfetto排查方法.md`](INTERVIEW-ANR-Perfetto排查方法.md)（ANR 版）、
> [`NATIVE_CRASH_FORMAT.md`](NATIVE_CRASH_FORMAT.md)（自研 native 报告格式）
> **工具**：[`tools/perfetto/record-crash.sh`](../../../../../../../tools/perfetto/record-crash.sh) + [`crash-trace.cfg`](../../../../../../../tools/perfetto/crash-trace.cfg)
>
> ✅ **本文与 ANR 那篇的最大不同：三类崩溃我都真机造出来了，trace 与现场证据都在磁盘上**。
> 每条结论都标了**实测出处**，可以自己复验。样本落在 `tools/perfetto/out/crash/`（gitignore）。

---

## 0. 最重要的一句话：Perfetto **抓不到崩溃本身**

先说清楚边界，否则方法会用错。

**Perfetto 没有 crash 数据源。** 它不知道你崩了、不知道 signal、不知道 Java 异常。
（自研信号 handler 也救不了这点 —— 它落的是**自己的文件**，不是 trace。）

所以正确的心智模型是**两个证据源、靠时间对齐**：

```
Perfetto trace            →  死之前发生了什么（线程状态 / slice / CPU / GC / 阻塞）
logcat + tombstone + ExitInfo  →  怎么死的（signal / 异常栈 / 代码行号）
                    ↘  用时间戳对齐  ↙
```

这跟 ANR 那套方法是**同一个套路**（ANR 也是 trace + ANR 全文对齐）。

### 那 Perfetto 在崩溃排查里到底有什么用？

它回答四个**别的工具回答不了**的问题：

| # | 问题 | 为什么只有 Perfetto 能答 |
|---|---|---|
| 1 | 崩溃**前几百毫秒**主线程在干什么？ | logcat 只有日志，没有执行时间线 |
| 2 | 是不是 **GC 抖动**把它逼死的？ | 需要 GC 切片 + 堆曲线（见 §2） |
| 3 | 崩溃是**孤立事件**还是**长期劣化**的终点？ | 主线程状态聚合看趋势 |
| 4 | 是不是**被别人拖死**的（binder / 抢占 / IO）？ | 跨进程 flow 箭头 |

---

## 1. 三个实测样本（本文全部数据来源）

| 样本 | 触发 | trace | 现场证据 |
|---|---|---|---|
| **① Java 崩溃** | 「⚠主线程崩」 | `20261018-121404-crash.perfetto` (20.9 MB) | logcat 全栈 |
| **② Native 崩溃** | 「⚠空指针(SEGV)」 | `20261018-121459-crash.perfetto` (21.3 MB) | tombstone + 自研报告 290 KB |
| **③ JVM OOM** | 「⚠真OOM+快照」 | `20261018-121532-crash.perfetto` (27.0 MB) | **hprof 299 MB** + GC 曲线 |

设备：Redmi K40（`alioth`）/ Android 12 / **user build，无 root** / MIUI V13.0.7.1。

---

## 2. Crash 常见类型（按"从哪一层死"分）

### 2.1 总览表

| 类型 | 典型信号/异常 | 平台证据落点 | Perfetto 能帮什么 |
|---|---|---|---|
| **Java 未捕获异常** | `FATAL EXCEPTION: main` | logcat / dropbox `data_app_crash` | 看崩溃前时间线 |
| **Native 信号崩溃** | `SIGSEGV / SIGABRT / SIGBUS / SIGILL / SIGFPE` | tombstone / dropbox `SYSTEM_TOMBSTONE` | 看崩溃前 + 全线程死法 |
| **JVM OOM** | `OutOfMemoryError` | logcat + hprof | ⭐ GC 曲线 + 堆增长 |
| **Native OOM / 分配失败** | `abort` / `SIGABRT` in allocator | tombstone | 看 RSS 增长 |
| **LMK 被杀** | 无信号、进程突然消失 | `ApplicationExitInfo.REASON_LOW_MEMORY` | RSS/水位曲线 |
| **ANR 导致被杀** | —— | ExitInfo `REASON_ANR` | 见 ANR 那篇 |
| **栈溢出** | `SIGSEGV` on guard page | tombstone（`stack overflow`） | 线程栈深度 |
| **Watchdog/自杀** | 主动 `abort()` | tombstone | 自定义 slice 标记 |
| **被系统杀（后台限制）** | 无异常 | ExitInfo `REASON_OTHER` | 存活时长 |

### 2.2 三类的**关键区别**（这决定了去哪找证据）

| | Java 崩溃 | Native 崩溃 | JVM OOM |
|---|---|---|---|
| 谁死 | **只有该进程** | 只有该进程（但可能连带 native 资源） | 通常**进程不死**（可被 catch） |
| 进程要重启吗 | 是 | 是 | 否（本仓实测：catch 住了） |
| 有异常栈吗 | ✅ Java 栈 | ❌ 只有寄存器+地址 | ✅ 但栈指向分配点，非根因 |
| 需要符号化吗 | 否（行号全） | ✅ 必须（模块+偏移） | 否 |
| 真正的根因在哪 | 栈顶那行 | 符号化后的地址 | **堆里谁占着**（要 hprof） |

> 🔑 **一句话**：Java 崩看**栈**，Native 崩看**符号**，OOM 看**堆**。
> Perfetto 对三者的价值是**递减**的 —— OOM 的价值最高（因为 GC/堆数据只有它有），
> Java 崩溃的价值最低（logcat 已经全说完了）。

---

## 3. 实测：Java 崩溃怎么排查

### 3.1 现场证据（logcat，✔实测）

```
10-18 12:14:16.518 21536 21536 E AndroidRuntime: FATAL EXCEPTION: main
10-18 12:14:16.518 21536 21536 E AndroidRuntime: Process: com.example.myapplication, PID: 21536
10-18 12:14:16.518 21536 21536 E AndroidRuntime: java.lang.RuntimeException: 主线程故意崩溃（必须死）
    at StabilityLabActivity$onCreate$20$1.invoke(StabilityLabActivity.kt:350)
    at StabilityLabActivity.mainHandlerPost$lambda$0(StabilityLabActivity.kt:530)
    at android.os.Handler.handleCallback(Handler.java:938)
    at android.os.Looper.loop(Looper.java:299)
```

**栈里直接给出文件:行号** ⇒ 定位到此结束，**不需要 Perfetto**。

### 3.2 Perfetto 的增量价值：看"死之前"（✔实测）

**这条查询是本次最有价值的发现** —— 主线程状态出现 `Z`（僵尸/已死）：

```sql
select round((ts.ts-(select min(ts) from slice))/1e9,3) at_s,
       ts.state, round(ts.dur/1e6,3) ms
from thread_state ts join thread t on ts.utid=t.utid
join process p using(upid)
where p.pid = 21536 and t.is_main_thread = 1
order by ts.ts desc limit 6
```

**实测输出**：
```
at_s     state  ms
11.533   Z      -0.000     ← 死了
11.533   Running 0.200
11.531   D       1.820     ← 死之前有 IO 等待
```

**再看最后几个 slice**（✔实测）：
```
11.531  binder transaction async          0.000
11.496  binder transaction               34.000   ← 死前最后一个较长操作
11.475  Lock contention on Jit code cache (owner tid: 2154x)   9.300
11.471  DetachCurrentThread               0.000   ← ART 开始拆线程
11.471  Choreographer#onVsync 10339937    0.020
```

> 🔑 **`DetachCurrentThread` + `Lock contention on Jit code cache` + `Z`**
> 这三件套就是 **ART 正在拆除进程**的指纹。它们本身是**结果不是原因**
> （崩溃已经发生了，ART 在收尾）—— **别误读成"JIT 锁把它锁死了"**。
> 这正是"时间对齐"的重要性：logcat 说 12:14:16.518 崩，trace 里 11.533s 出现 `Z`，
> 换算后是同一时刻，于是 `DetachCurrentThread` 被正确归类为**崩溃后的收尾**。

### 3.3 Java 崩溃排查流程

```
① logcat 找 FATAL EXCEPTION → 拿到栈和文件:行号 → 【根因已定】
② 若要解释「为什么会走到那一步」→ 用 trace 看崩溃前状态
③ 任务线程崩（非主线程）→ 本仓实测：进程不死，靠 CrashJournal 遗嘱补报
```

> 💡 **本仓的"崩溃遗嘱"机制**（`CrashJournal.kt`）：主线程崩时来不及后台上报，
> 于是崩溃前先落一行遗嘱，下次启动补报。**这是 Java 崩溃特有的问题**（进程必死）——
> Native 崩溃同理（见 §4.4）。

---

## 4. 实测：Native 崩溃怎么排查

### 4.1 现场证据（tombstone，✔实测，adb logcat 里就能拿到）

```
F DEBUG   : signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
F DEBUG   : Cause: null pointer dereference
F DEBUG   : pid: 21691, tid: 21691, name: e.myapplication
F DEBUG   : backtrace:
F DEBUG   :   #00 pc 0000000000004fd4  .../base.apk!libnativecrash.so
F DEBUG   :       (Java_com_interview_nativecrash_NativeCrashHandler_nullDerefNative+24)
F DEBUG   :   #01 pc .../libart.so (art_quick_generic_jni_trampoline+148)
F DEBUG   :   #02 pc .../libart.so (art::ArtMethod::Invoke(...)+192)
```

> 🔑 **`#00` 就是凶手**，且这里**已经符号化**（因为有 BuildId 且 so 在设备上）。
> 生产环境 Release 包无 `.symtab` 时只有 `模块+偏移`，需要
> `tools/native-mem-symbolize.sh` 事后还原。

### 4.2 关键：崩溃时刻在 trace 里长什么样（✔实测）

```sql
select t.tid, t.name, round((max(ts.ts)-(select min(ts) from slice))/1e9,3) last_s, ts.state
from thread_state ts join thread t on ts.utid=t.utid join process p using(upid)
where p.name like '%example%'
group by t.tid order by last_s desc limit 6
```

**实测输出**：
```
tid    name              last_s   state
21715  app-main-sample   12.244   X     ← X = 已退出
21771  Binder:21691_5    12.221   X     ← 全部线程在 12.221s 集体死
21730  HWC release       12.221   X
21722  app-frame-metri   12.221   X
```

> 🔑 **`X` 状态 + 所有线程同一时刻集体消失** = Native 崩溃的指纹。
> 对比 Java 崩溃是**主线程先 Z**、其他线程还在（因为 ART 逐个 detach）。
> **这个差异可以在 trace 上一眼区分两种崩溃。**

### 4.3 注意：Perfetto **看不到 signal 事件**（✔实测）

我试过从 ftrace 里找 SIGSEGV：
```sql
select substr(a.string_value,1,90)
from ftrace_event fe join args a on fe.arg_set_id=a.arg_set_id
where fe.name='print' and a.string_value like '%SEGV%' limit 8
```
**实测结果：空**（返回的都是 `HWC release fence ...`）。

> ⚠️ **这是必须记住的边界**：`sched/signal_generate` 这类事件**没订阅也没在允许列表**，
> 且 perfetto 无权读 debug 节点。**signal 只能从 logcat / tombstone 拿。**

### 4.4 Native 崩溃的完整证据链（✔实测，四条都在）

| 证据 | 路径 | 实测大小 | 说明 |
|---|---|---|---|
| tombstone | `adb logcat` / dropbox `SYSTEM_TOMBSTONE` | 727 KB | **有完整符号**，最权威 |
| 自研报告 | `files/native-crash/report-21691-*.txt` | 290 KB | fd 表 / 全线程栈 / status，含寄存器 |
| Perfetto trace | `20261018-121459-crash.perfetto` | 21.3 MB | 崩溃前时间线 + 线程集体死亡 |
| ExitInfo | `ApplicationExitInfo` | 运行时 | 兜底（自研 handler 万一死锁） |

> 🔑 **本仓的设计取舍**（`native_crash_handler.cpp`）：**不顶掉系统 handler**，
> 而是"抓完现场 → 链式交回"。所以 **tombstone 照旧产生**——
> 这一点我在本次实测里**验证了**：logcat 里既有自研的
> `NativeCrash: 接管信号 11(SIGSEGV)`，也有系统的 `crash_dump64` + 完整 tombstone。

---

## 5. 实测：JVM OOM 怎么排查（Perfetto 价值最高的一类）

### 5.1 现场证据（logcat，✔实测）

```
W e.myapplicatio: Throwing OutOfMemoryError "Failed to allocate a 1048588 byte allocation
    with 209816 free bytes and 204KB until OOM, target footprint 268435456, growth limit 268435456"
I OomSnapshot: probe: 命中签名 = DumpHeap(const char*, int, bool) —— 本机 Android 12 实测命中
I OomSnapshot: forkDump 成功：ok=1 how=exited_ok exit=0 size=299120374 suspendMs=3 forkMs=7 totalMs=1551
E OomSnapshot: 结果：✅ 成功  触发时刻水位：255.86 MB / 256.00 MB
```

**hprof 产出实测**：`299,120,374` 字节（285 MB），`forkMs=7`、`suspendMs=3`、总 1551 ms。

### 5.2 ⭐ 堆增长曲线（✔实测）—— 这条只有 Perfetto 能给

```sql
select round((c.ts-(select min(ts) from slice))/1e9,1) at_s,
       round(max(c.value)/1024,1) heap_mb
from counter c
join counter_track ct on c.track_id=ct.id
join process_counter_track pct on ct.id=pct.id
join process p using(upid)
where p.pid = 21860 and ct.name = 'Heap size (KB)'
group by round((c.ts-(select min(ts) from slice))/1e9,1)
order by at_s
```

**实测输出（关键片段）**：
```
at_s    heap_mb
 6.9      1.9      ← 起点
 7.9     98.9      ← 1 秒内暴涨到 ~100MB
 8.1    116.2      ← 首次 GC 触发点
 8.9    116.6
 9.1      6.9      ← ⭐ GC 回收掉，掉回 6.9MB
 ...（稳定 7MB 左右）
17.2      7.4
17.3    257.8      ← ⭐⭐ 瞬间到顶（分配爆发）
17.4    256.1
18.1    255.9      ← 顶在 256MB 附近徘徊
19.1    256.2
19.3      3.9      ← ⭐ OOM 被 catch + 释放压力后掉回
```

**怎么读这条曲线**：

| 现象 | 含义 |
|---|---|
| 1 秒内 1.9→98.9 MB | **无节制的分配**（不是泄漏，是突发） |
| 冲到 116 MB 后 GC 成功回收到 6.9 MB | GC **能**回收 ⇒ **不是真泄漏** |
| 17.3s 直接跳到 **257.8 MB** 顶死 | 分配速度 > GC 速度，**打满堆** |
| 顶在 256 MB（= `growth limit`） | 到 **ART 上限**，下一次分配必抛 OOM |

### 5.3 ⭐ GC 抖动指纹（✔实测）

```sql
select round((s.ts-(select min(ts) from slice))/1e9,2) at_s,
       round(s.dur/1e6,1) ms, substr(s.name,1,55) n
from slice s join thread_track tt on s.track_id=tt.id
join thread t on tt.utid=t.utid join process p using(upid)
where p.pid=21860 and s.name like '%GC%' order by s.ts
```

**实测输出（OOM 前的 GC 风暴）**：
```
17.28  "Background concurrent copying GC"      17.1ms
17.28  "GC: Wait For Completion Alloc"         16.1ms  ← ⭐⭐ 关键
17.30  "Background young concurrent copying GC" 5.6ms
17.31  "Alloc concurrent copying GC"           11.4ms
17.32  "Alloc young concurrent copying GC"      4.3ms
17.34  "Background young concurrent copying GC" 5.8ms
18.38  "Background young concurrent copying GC" 6.3ms
19.06  "Background concurrent copying GC"      17.9ms
19.09  "Lock contention on GC barrier lock (owner tid: 21872)"
```

> 🔑🔑 **`GC: Wait For Completion Alloc` 就是 OOM 的教科书指纹**。
> 它的含义是：**分配方（Alloc）被 GC 逼停，等 GC 完成才能继续**。
> 出现它 ≈ **分配速度已经追上 GC 速度**，离 OOM 只剩一步。
> 后面紧跟着 `Lock contention on GC barrier lock` —— 多个线程抢 GC 屏障锁，**互相拖慢**。
>
> **反过来**：如果只见零散 GC、没有 `Wait For Completion Alloc`，
> 说明 GC 跟得上，**内存问题还没到致命程度**。

### 5.4 OOM 排查流程

```
① logcat 找 OutOfMemoryError（是 JVM 还是 native？看分配大小/栈）
② Perfetto 看堆曲线  → 是「突发打满」还是「缓慢泄漏」？
      ┌─ 突发打满（本次样本）：√ 找那次大分配的业务代码
      └─ 缓慢爬升：√ 泄漏，需要 hprof 对比两个时间点
③ Perfetto 看 GC     → 有 `GC: Wait For Completion Alloc`？
      └─ 有 ⇒ 分配速度 > GC 速度，必须减少分配，不是加大堆
④ 拉 hprof（本仓 fork 快照，见 §6）
⑤ hprof 分析：谁占了那 285MB（用 Android Studio / MAT / shark）
```

> 💡 **判断"泄漏 vs 打满"的快捷法**：
> 曲线**掉得回去** ⇒ 打满；**掉不回去、只涨** ⇒ 泄漏。
> 本次实测：8.9s 到 116MB，9.1s 掉回 6.9MB ⇒ **立刻可判"不是泄漏"**。

---

## 6. 缺 hprof 怎么办？—— 本仓的 fork 快照（✔实测成功）

**问题**：OOM 那一刻堆已经 256MB 满了，`Debug.dumpHprofData()` 需要**再分配内存**，
**它自己会 OOM**。所以"先 OOM 再 dump"走不通。

**本仓解法**（`oom_snapshot.cpp`）：**fork 一个子进程去 dump**。
子进程继承父进程内存的**写时复制**视图，且父进程被 **ScopedSuspendAll 冻结**（heap 一致）。

**本次实测数据**：

| 指标 | 实测值 |
|---|---|
| 触发水位 | **255.86 / 256.00 MB** |
| fork 耗时 | 7 ms |
| 冻结（suspend）耗时 | **3 ms** |
| dump 总耗时 | 1551 ms |
| hprof 大小 | **285 MB**（299,120,374 B） |
| 对象数 | 557,768 个 |
| 解析符号 | `_ZN3art5hprof8DumpHeapEPKcib` 命中 |

**拉回本机**（debuggable 构建下可直读）：
```bash
adb shell "run-as com.example.myapplication cat \
  files/oom-snapshots/oom-real_oom-<ts>.hprof" > oom.hprof
head -c 18 oom.hprof        # → JAVA PROFILE 1.0.3 （校验是合法 hprof）
```

**分析**：Android Studio Profiler 直接打开（`.hprof` 无需转换，因为已是 ART 格式）。

> ⚠️ **已知未验证风险**：`oom_snapshot.cpp` 在 fork 前会触碰 `Locks::alloc_tracker_lock_`，
> **在多线程 fork 场景下有死锁风险**。本样本（单次、可复现）没触发，
> 但**不能据此认为安全** —— 需要压力测试才能结论。

---

## 7. 数据源对照表：想要什么，就得订阅什么（全部实测）

这是本文最实用的一张表。**本机实测**（Redmi K40 / Android 12 / perfetto v15.0）：

| 想查 | 需要订阅 | 本机实测结果 |
|---|---|---|
| 线程状态 / Running / S / D | 默认就有 | ✅ |
| **崩溃后线程消失（Z / X）** | 默认就有 | ✅ 关键信号 |
| **GC 切片** | **`atrace_categories: "dalvik"`** | ✅ 加了才有 |
| **堆曲线 `Heap size (KB)`** | **`atrace_categories: "dalvik"`** | ✅ 加了才有 |
| 帧时间线 | `android.surfaceflinger.frametimeline` | ✅ |
| 内核阻塞原因 | `sched/sched_blocked_reason` | ⚠️ **有事件但函数名解析不出**（见下） |
| CPU 频率 | `power/cpu_frequency` | ✅ 2400 条 |
| 进程命名 | `task/task_rename` | ✅ |
| **logcat 进 trace** | `android.log` | ❌ **收不到**（`android_log_num_total=0`） |
| **signal / tombstone** | **无此数据源** | ❌ **必须外挂 logcat** |

### 两个必须知道的坑（都是我实测踩出来的）

**坑① `android.log` 在 user build 上收不到日志。**
`stats.android_log_num_total = 0`，`android_logs` 表为空。原因：读 logd 需要 `READ_LOGS`（特权级）。
**结论：logcat 必须单独用 `adb logcat` 抓**，不要指望塞进 trace。
（`record-crash.sh` 已按此实现，产物 `<trace>.logcat.txt`。）

**坑② `sched_blocked_reason` 采到了，但函数名解析不出来。**
实测 20,542 条事件，但 `caller` 字段是**整数**（1/2/3…）而非符号名，
`thread_state.blocked_function` 全 NULL。原因：符号解析需要读内核 `format` 文件，
而 SELinux 拒绝了 `traced_probes`（logcat 里满是 `avc: denied { read } ... format`）。
**结论：ANR 那篇里我写的 `blocked_function` 查询，在本机 user build 上依然拿不到结果** ——
不是配置没开，是**权限拿不到符号**。要它，得 root。

---

## 8. 抓取脚本用法（本次新建）

```bash
# Java 崩溃
tools/perfetto/record-crash.sh \
  --activity com.interview.稳定性监控.StabilityLabActivity \
  --tap-text "⚠主线程崩" --tap-delay 4 -d 18

# Native SEGV
tools/perfetto/record-crash.sh --tap-text "⚠空指针(SEGV)" --tap-delay 4 -d 18

# JVM OOM + hprof（耗时长，堆要大）
tools/perfetto/record-crash.sh \
  --activity com.interview.内存.MemoryLabActivity \
  --tap-text "⚠真OOM+快照" --tap-delay 5 -d 30
```

**产物（一次抓三样，因为单一证据源不够）**：

| 文件 | 内容 |
|---|---|
| `<ts>-crash.perfetto` | Perfetto trace |
| `<ts>-crash.perfetto.logcat.txt` | 同期 logcat（含异常栈/tombstone） |
| `<ts>-crash.perfetto.procstat.txt` | pid 前后对比（**证明真的死了**） |

**脚本的三个设计点**（都是踩坑后的选择）：
1. **按文字找按钮**（`--tap-text`），不硬编码坐标 —— 换机型/换布局不失效
2. **先 force-stop → 起 perfetto → 再拉起 App** —— `atrace_apps` 是进程启动时读的，顺序反了埋点全丢
3. **一次抓 logcat + pid** —— 因为 trace 抓不到 signal，必须互补

---

## 9. 三类崩溃的 trace 指纹速查

| 指纹 | Java 崩溃 | Native 崩溃 | JVM OOM |
|---|---|---|---|
| **主线程终态** | `Z`（先死） | `X`（集体死） | 通常**不死** |
| **其他线程** | 还在（ART 逐个 detach） | **同一刻全变 `X`** | 正常存活 |
| **崩溃前特征** | `DetachCurrentThread` + JIT code cache 锁竞争 | 瞬间全灭，无预告 | `GC: Wait For Completion Alloc` + 堆顶死 |
| **堆曲线** | 无关 | 无关 | ⭐ **核心证据** |
| **GC 风暴** | 无 | 无 | ⭐ 有 |
| **真相在哪** | logcat 栈 | tombstone 符号 | **hprof** |

> 🔑 **一句话记法**：
> **`Z` = Java 崩；`X` 集体 = Native 崩；堆顶死 + Wait For Completion = OOM。**

---

## 10. ⚠️ 诚实边界

1. **本文三类崩溃均为真机实测**（Redmi K40 / Android 12 / user build），
   trace 与现场证据都在 `tools/perfetto/out/crash/`，可复验。
2. **未覆盖的类型**（本仓造不出样本，**未验证**）：
   Native OOM、LMK 被杀、栈溢出、Watchdog 自杀、多进程连带崩溃。
   它们的排查思路（§2.1 表）来自通用经验，**没有实测支撑**。
3. **`android.log` 收不到 / `blocked_function` 拿不到** 是**本机 user build 的权限天花板**，
   已实测确认。换 root 或 userdebug 设备可能不同 —— **未验证**。
4. **OOM 快照的 fork 死锁风险未验证**（见 §6），单样本成功不等于安全。
5. **hprof 只拉到本机、未做实际分析**（299 MB，未在 Android Studio 打开验证内容正确性）。
   "285MB / 557768 objects" 是**设备日志里的数字**，非我独立统计。
6. **本文的"崩溃后收尾"判读**（`DetachCurrentThread` 等）依赖人工时间对齐 ——
   trace 里**没有崩溃时刻的标记事件**。想省这事，让 App 在崩溃前写 `trace_marker` 打锚。
