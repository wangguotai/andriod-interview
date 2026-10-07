# 线上稳定性监控：ANR / Crash / 卡顿

> 起因：`readme.md` day 2.22 记录了一场面试，明确写着薄弱点是
> 「**ANR 的概念、检测机制、线上发生 ANR 如何监控**」。
> 本文是这个薄弱点的正面回答，配套一个可跑的实验页 `StabilityLabActivity`。
>
> 全文口径：**只写本机真机实测过的**。凡是"业界普遍这么说"但我没验的，
> 会显式标 `[未验证]`；凡是实测**推翻了**常见说法的，会显式标 `⚠️实测`。

---

## 〇、先立一个总纲：三种问题的本质区别

面试里最容易露怯的地方，是把三个东西当成"同一类线上问题、换个名字"。
它们是**三种不同的可见性缺陷**：

| | 谁发现了它 | 你事后能看到什么 | 根本难点 |
|---|---|---|---|
| **Crash** | 你的进程自己（`UncaughtExceptionHandler`） | 完整堆栈、线程名 | 原生崩溃、被系统直接杀，你的 handler 根本没机会跑 |
| **ANR** | **系统**（AMS），不是你的进程 | 一条信号 `SIGQUIT` → 系统抓的堆栈；进程通常直接死 | 进程死了 → 你的内存态监控代码全部失效，只能靠"下一次启动去问系统" |
| **卡顿** | **用户**，且没有明确信号 | 只有你**自己埋的**帧耗时/消息耗时数据 | 没有任何系统事件，你不主动量就等于没有 |

一句话记住三者的采集模型：

```
Crash  → 进程内拦截（同步，当场就能拿到）
ANR    → 进程内探测（近似）+ 进程外回捞（精确，但只能下次启动拿到）
卡顿   → 纯主动度量（没人告诉你，你不量就是 0）
```

**这张表是整篇的骨架。** 下面每一节都回到"它属于哪一格、为什么只能这么做"。

---

## 一、ANR

### 1.1 什么是 ANR（概念层面最容易答错的地方）

标准答案会说"主线程被阻塞超过 5 秒"。**这个答案是错的**，或者说不完整，
它解释不了「我的 App 卡了 15 秒，为什么没有 ANR 报告」。

⚠️ **本机实测（Redmi K40 / Android 12，配套实验页的「⚠真ANR(15S)」按钮）**：

| 做法 | 系统是否登记 ANR |
|---|---|
| 主线程 `Thread.sleep(15_000)`，**不碰屏幕** | ❌ 无。`dumpsys window \| grep lastanr` → `<no ANR has occurred since boot>` |
| 同样阻塞 15 秒，**同时持续注入 `MotionEvent`** | ✅ 有。`ANR in ... Waited 5004ms for MotionEvent(action=DOWN)` |

两次实验里，我们自己的哨兵看门狗都**如实报了** 13 轮无响应（估算阻塞 13000ms）。
也就是说：

> **ANR 的判据不是「你卡了多久」，而是「系统有一个事务在等你，且等超时了」。**

ANR 的准确定义是：**AMS 期望某个组件在 `timeout` 内响应系统消息，但它没响应。**
"主线程阻塞"只是**最常见的一种成因**，不是定义本身。

#### 官方超时阈值（这张表要背）

| 触发方 | 前台 | 后台 |
|---|---|---|
| Input 事件分发（按键/触摸） | **5s** | 5s |
| `BroadcastReceiver.onReceive` | **10s** | **60s** |
| `Service`（`onCreate`/`onStartCommand`/`onBind` 等生命周期） | **20s** | **200s** |
| `ContentProvider` publish | **10s** | 10s |

> ⚠️ 注意 200s 那一格：后台 Service 卡 3 分钟都不算 ANR。
> 这解释了为什么"后台任务把主线程堵住"常常**没有** ANR 报告，只有下次启动的一个谜团。

#### 归因判据：看 main 线程状态，而不是找"耗时函数"

拿到 ANR trace 后，**不要**去找"哪个函数最慢"。先看 `"main"` 线程的 `state`：

- **`Native`** → 概率最大的一类。主线程在 native 里等（Binder 调用、`synchronized` 膨化、
  文件 IO），Java 层看不出问题。
- **`Waiting` + `held mutexes=`** → **锁竞争**。这条 trace 的价值在于同时能看到
  **持有锁的那个人在干嘛**，所以抓 ANR trace 时系统会把所有线程都抓下来。
- **`Runnable`** → 真的在算（死循环 / 超大计算 / 密集布局）。
- **`Sleeping` / `TimedWaiting`** → ⚠️ 注意这是本机实测踩到的坑：
  主线程自己调 `Thread.sleep` 造成的 ANR，在 trace 里是 `Sleeping`。
  也就是说**看到 Sleep 不要跳过**——那可能正是凶手。

⚠️ 实测佐证：本机 force-stop 一个刚 ANR 过的进程后回捞到的 trace，
`main` 线程正是 `state=S` 停在 `at java.lang.Thread.sleep(Thread.java:451)`。

### 1.2 检测机制：三条通道，缺一不可

线上 ANR 监控是**三条通道**，不是一条。它们的关系是：

```
通道1  ExitInfo 回捞   ← 权威，但延迟（只能下次启动拿到）+ 可能没有 trace
通道2  哨兵看门狗      ← 及时，但只是"近似"（估算，不是精确阻塞时长）
通道3  Looper 旁听     ← 便宜，量"单条消息耗时"，覆盖不到跨消息的卡顿
```

#### 通道 1：`ApplicationExitInfo` 回捞（API 30+）

```kotlin
val am = context.getSystemService(ActivityManager::class.java)
am.getHistoricalProcessExitReasons(packageName, 0, 0)  // 0 = 用系统默认条数
```

对 `REASON_ANR` 的条目，`getTraceInputStream()` 能拿到系统抓的那份 trace。

**这是唯一能拿到"真 ANR"权威证据的通道。** 它的三个坑必须知道：

**坑① 是个有界环形缓冲。**
⚠️ 实测本机 `getHistoricalProcessExitReasons(pkg, 0, 0)` 一次返回 **16 条**。
它是**环形覆盖**的——跑得久了，早期记录会被新记录挤掉。
> 所以：**每次冷启动都要回捞一次**，回捞后按 `(timestamp, pid)` 去重落库。
> 只在"怀疑出事时"才去捞，必然会丢。
> （16 这个数我实测到了，但**没有**验证它是否就是文档所说的默认上限，
> 因此代码里不写死这个数，只描述"有界环形"这个性质。）

**坑② 不是所有 ANR 都带 trace。**
Android 12+ 的 "self ANR"（应用自己发现主线程卡了、主动调 `am.notifyWatchdog`）这类，
系统侧记录**不带全量堆栈**。业界常引用「约 30% 的 ANR 缺 trace」的说法 `[未验证，未采信为结论]`。
我们的代码对这一点做了**显式区分**：

- `REASON_ANR` / `REASON_CRASH_NATIVE` 却 trace 缺失 → 打 ⚠️，是条值得追的线索
- `用户主动退出` / 低内存被杀等**本就不带 trace** 的原因 → 只说"无（正常）"，不打警告

> 为什么较真这一点：如果对所有原因都喊"self ANR / 未采集"，
> 监控就会变成**狼来了**——告警被忽略的成本，比不告警更高。

**坑③ ⚠️ 实测：不要按 `reason` 过滤 trace，要问"有没有 trace"。**
本机实测到了一个非常反直觉的现象：

1. 先制造一次真 ANR（进程没被杀）；
2. 随后 `force-stop` 掉这个进程；
3. 下次启动回捞，看到的那条 `reason` 是 **`用户主动退出`**，
   但它**带着 ANR trace**，且 trace 里 main 线程正停在 `Thread.sleep`。

原因：**系统在 ANR 那一刻就把 trace 抓下来挂到这个进程的 `ExitInfo` 上了**，
之后进程无论因为什么原因退出，这条记录里都带着它。

> ⇒ **判断一条退出记录是不是 ANR，看它有没有 trace，不要看它的 reason。**
> 这条如果面试时讲出来，是很强的区分度——因为它只能靠实测踩到。

#### 通道 2：哨兵看门狗（进程内，及时）

原理：一个后台线程往主线程 `Handler` 上 `postAtFrontOfQueue` 一个探测 `Runnable`，
然后自己 park 一会儿；如果醒来时那个 `Runnable` 还没被执行，说明主线程**没走到队首**。

时间线是这样的（这是本方案的核心，务必画准）：

```
t=0    哨兵 post 探测 R
t=0    哨兵 park(interval=1000ms)          ← ⚠️ 注意 park 的时长是 interval，不是 timeout
t=1000 哨兵醒来，检查 R 是否跑过
       ├─ 跑过 → 正常
       └─ 没跑 → 再等 (timeout - interval) = 1500ms
                 t=2500 还没跑 → 本"轮"判为无响应
```

**关键点：单轮阈值 2500ms 是"醒来后再等 1500ms"的总和，不是 park 1000ms。**
写成"park(2500) 后检查"是错的——那样每次探测都会白白让主线程多担 2500ms 的队列延迟。

我们的实现细节（都在 `AnrMonitor.kt`）：

- `PROBE_INTERVAL_MS = 1000`，`PROBE_TIMEOUT_MS = 2500`，`CONSECUTIVE_ROUNDS = 2`
- **连续 2 轮**才判定：`CONSECUTIVE_ROUNDS` 是为了过滤 GC 长暂停造成的单轮假阳性
- 探测线程用 `LockSupport.parkNanos` 而不是 `Thread.sleep`：
  无锁、不参与 monitor 竞争，且中断语义干净
- `edgeTriggered`：一次卡死只报**一次**（边沿触发），避免每轮都报一遍刷爆上报通道

**诚实边界（必须主动说）：**

- 哨兵量到的是「按 interval 粒度**估算**的阻塞时长」，**不是**精确值（实测报 13000ms 实为 15000ms）
- 哨兵**抓不到 native 卡死**：如果主线程卡在 native 且一直持有 ART mutex，
  我们抓堆栈的调用会**一起卡住**——此时只能拿到"卡了"这个事实，拿不到堆栈
- 堆栈抓取用 `Thread.getStackTrace()`，**会触发 safepoint 检查**。
  在"已经卡住"时它相对便宜（线程本来就停着），但在正常路径高频调用会**制造**卡顿。
  所以**只能在探测线程上调用，绝不能在主线程**。

#### 通道 3：Looper 旁听（`Looper.setMessageLogging`）

```kotlin
Looper.getMainLooper().setMessageLogging { log -> /* 解析 msg 耗时 */ }
```

能拿到**单条消息**的执行耗时，够便宜（只在 debug 场景或低频采样）。

**诚实边界**：它只能覆盖"单条消息内部慢"。
而**真正的卡顿常发生在两条消息之间**（例如消息 A 阻塞了 3 秒）——
严格说 A 自己也会被记到，但跨消息的等待（`sync barrier`、异步消息插队）它无能为力。
所以它是通道 2 的**补充**，不是替代。

#### ⚠️ 一条被实测否掉的经典方案：`FileObserver` 监听 `/data/anr/traces.txt`

网上流传的经典做法是：`FileObserver` 盯 `/data/anr/traces.txt`，变化就读出来。

⚠️ **本机实测结论：这条路在 Android 12 上已经死了。**
`/data/anr` 目录对本 App **自己的进程**也是 `Permission denied`——
历史上那个"App 的 uid 和 system 共享同一个 AID 可以读"的前提已经不存在了。

> 本仓库 xshell 模块里遗留的 `ANRFileObserver` 就是这一路的产物，**已过时**。
> 保留它的价值只剩"证明此路不通"。
> 正确姿势就是本节的通道 1（`ApplicationExitInfo`）。

### 1.3 关于"ANR 率"这个指标

⚠️ 一个容易在面试里加分的点：**Google Play 后台的 ANR 率指标，
现在同样依赖 `ApplicationExitInfo` 的上报路径。**
所以：

- 如果你在 `Application` 里**吞掉了** `REASON_ANR` 的处理（或提前标了 ack），
  Play Console 的 ANR 数据会**同样失真**——你和平台的观测口径是绑定的。
- ⇒ 这解释了"为什么 Play 面板的 ANR 数比我们自己埋点少很多"这类反复出现的困惑。
  `[Play 侧的具体采集口径未逐条验证，方向性结论]`

### 1.4 ANR 的采样成本与"不该做的事"

| 做法 | 评价 |
|---|---|
| 高频抓所有线程堆栈 | ❌ 抓取本身触发 safepoint，反而制造卡顿 |
| 只在"卡住"的边沿抓一次 | ✅ 线程本来就停着，这个时刻最便宜、信息量最大 |
| 主线程自检（自己 post 自己） | ❌ 主线程都卡住了，它根本没机会执行自检 |
| 在崩溃 handler 里做 IO | ❌ 见第四节 |

---

## 二、Crash

### 2.1 Java 未捕获异常：进程内拦截

```kotlin
val prev = Thread.getDefaultUncaughtExceptionHandler()
Thread.setDefaultUncaughtExceptionHandler { t, e ->
    report(t, e)
    prev?.uncaughtException(t, e)   // ⚠️ 必须委托，否则会改变平台默认行为
}
```

**要点① 必须保留并委托 `prev`。**
不委托的后果：Android 12 上默认 handler 是
`com.android.internal.os.RuntimeInit$KillApplicationHandler`（本机实测打出来的类名），
它负责"打印 FATAL + 记录 + 杀进程"。你自己吞掉，进程就不会按平台预期退出。

**要点② ⚠️ 实测：`FATAL EXCEPTION` 日志行 ≠ 进程死了。**
Android 12 的 `RuntimeInit$KillApplicationHandler` 会**先**打 FATAL 日志。
本机实测：装了我们的 handler 后，一条主线程崩溃的 logcat 里
**同时**有 `FATAL EXCEPTION: main` 和我们的 `主线程异常，交回原 handler（进程将结束）`，
但真正判断死没死的唯一可靠信号是 **PID 变了**：

```
adb shell pidof com.example.myapplication
```

> 面试里如果问"怎么判断崩溃后进程是否真的退出了"，答"看 PID，不要看日志"。

### 2.2 主线程崩溃 vs 后台线程崩溃，处理策略完全不同

| | 主线程崩溃 | 后台线程崩溃 |
|---|---|---|
| 语义 | 用户正在用的界面没了，必须让进程退出（否则是"僵尸界面"） | 可能只是一次非关键任务失败 |
| 策略 | 记录 → **交回原 handler** 让它死 | 记录 → 可吞（但要上报）→ 进程继续 |
| 实测 | PID 由 26696 → 消失 ✅ | PID **不变**（26696 → 26696）✅ |

后台线程崩溃吞掉的风险是"静默失败"——用户功能坏了但你毫不知情。
所以**吞掉的每一例都必须上报**，这是唯一的补偿。

### 2.3 崩溃遗嘱（last will）：解决"进程直接死，来不及上报"

问题：崩溃发生后，你的上报需要网络，但进程马上就死了，来不及发。

方案：**崩溃时只写本地，下次启动再发。**

```
崩溃那一刻：  写一个 pending 文件（含堆栈）→ fd.sync() 落盘 → 进程死
下次启动：    读 pending 文件 → 补报 → 上报成功后才删文件（acknowledge）
```

三个必须踩对的点：

**① 写完要 `fd.sync()`。**
只 `flush()` 不够——数据还在 page cache 里，进程被 `SIGKILL` 时不会落盘。
这是"遗嘱"能否生效的分界线。

**② 重启后要靠"会话标记"区分"崩溃死"和"正常退出"。**
`CrashJournal.beginSession()` 在启动时写一个 `running` 标记，正常退出时清掉。
下次启动如果**标记还在** → 上次是异常死亡。

**③ ⚠️ 只有"上报真的成功"才能清账（这是最容易写出 bug 的地方）。**
本仓库在实现过程中**真的踩到了这个 bug**，值得作为教训记录：

- 最初的写法是在崩溃 handler 里顺手把缓冲 drain 掉（"顺便把数据发出去"）；
- 结果：① 在**刚出错、状态未知的线程上**做 IO，把一个崩溃放大成"崩溃 + IO 卡顿"；
  ② 更糟的是**清账语义混乱**——`recoverPending` 和崩溃 handler 都去"确认"了遗嘱，
  导致同一份崩溃在设备上**打印了两遍**（同一条 `[CRASH]` 出现 2 次，实测发现）。

修正后的单一权责：

```
CrashMonitor        只记录 + 只写遗嘱，绝不 drain、绝不 acknowledge
recoverPending()    只把遗嘱读进内存环形缓冲，不 acknowledge
StabilityMonitor.flush(upload)   ← 唯一清账点
    drainTo(upload) 返回 true（真的发出去了）→ 才 acknowledgeJournal()
```

> **总结成一句可复用的原则**：
> 「崩溃路径上不做 IO」和「崩溃后尽快把数据送出去」并不矛盾，
> 两者之间应该隔着一个**一跳**（handler → 队列 → 后台线程）。
> 但"尽快"的实现**不能**顺手把"确认收到"一起做掉——
> **确认必须且只能发生在"确认已经送出"之后。**

### 2.4 原生崩溃：你只能看到"上次死了"

Java 层**完全拦不到** native crash（SIGSEGV 等）——`UncaughtExceptionHandler` 是 ART 层的机制。
能拿到的只有 `ApplicationExitInfo` 里 `REASON_CRASH_NATIVE` 这一条记录。

⚠️ 注意：`REASON_CRASH_NATIVE` 的 `getTraceInputStream()` 返回的是**protobuf 二进制**
（不是 ANR 那种文本）。本仓库的处理是**记录它的存在、大小、hexdump 前十来字节**，
**不手工解析 tombstone**——

> 理由：手写 protobuf 解析器去解一个**跨版本都不稳定**的内部格式，
> 是典型的"投入产出比极差且极易解析错"的活。
> 正确的做法是用 `ndk-stack` / `addr2line` 离线符号化，或者直接交给平台。
> 我们只回答"有没有、多大、是不是 native"这三个问题。

`AnrTraceParser` 里因此有一个 `looksBinary()`：靠首字节判断是不是 protobuf，
文本 ANR 走 `parseText`，二进制走 `parseBinaryStructure`（只做浅层结构探测）。

---

## 三、卡顿（Jank）

### 3.1 为什么必须自研/自采：卡顿没有任何系统信号

Crash 有异常、ANR 有系统记录，而**卡顿什么信号都没有**。
用户说"你这有点卡"，你不能去 `logcat` 里找——那里什么都没有。
⇒ 卡顿是三者中**唯一一个"你不主动量就等于 0"**的指标。

### 3.2 两种采集口径

**口径 A：帧级（`FrameMetrics` / `Choreographer`）**

每帧的耗时分解：`TOTAL_DURATION`、`LAYOUT_MEASURE`、`DRAW`、
`SYNC_AND_DRAW`、`COMMAND_ISSUE`、`SWAP_BUFFERS`、`DELAY`…

判"这帧算不算卡"不能拍脑袋，要对着**刷新率**算：

```
帧预算(frameBudget) = 1000ms / refreshRate
  60Hz → 16.67ms      90Hz → 11.1ms      120Hz → 8.3ms
```

⚠️ **实测踩到的坑：`FrameMetrics` 的字段在"没有值"时会给 -1 或异常大值。**
我们最初的实现直接把 `-1` 当耗时参与统计，结果算出"有一帧耗时 -1ms"这种鬼数据，
百分比分布全歪。修正：**任何负值、或大于 ~60s 的值，一律视为"未指定"，丢弃该帧**，
并用 `unspecifiedFrames` 计数器单独记一笔（丢弃要**可见**，不能静默吞掉）。

**口径 B：消息级（`Looper` printer）**
单条 `Message` 耗时 > 阈值（本仓库用 `SLOW_MESSAGE_MS = 500`）记一条。
它和帧级的区别：一帧里可能跑了好几条消息，一条消息也可能横跨好几帧。
**两者是不同粒度的正交视图，都要有。**

### 3.3 主动采样：把"卡在哪"问出来

只报告"卡了 200ms"是没用的，要回答**卡在哪**。
`MainThreadSampler` 周期性抓主线程堆栈做聚合，关键在**分类**：

```
CPU 热点（Runnable/真正在算）   ← 这才是"代码问题"
WAIT（等在锁/IO/Binder）        ← 这是"环境/架构问题"
IDLE（等消息，正常）            ← ⚠️ 必须排除，否则占比全被它淹没
```

⚠️ **不把 IDLE 排除掉的采样报告基本不可读**：主线程大部分时间本来就在等消息，
它会占 50%+ 的样本，把真正的热点挤到看不见。

**采样在卡顿时加密**：`AnrMonitor` 判定卡死的边沿会调 `MainThreadSampler.noteStuck()`，
采样器切到 50ms 一次——**卡住那一刻的堆栈信息量最大**，
因为此时线程停在"等谁"上，而不是在正常打转。

### 3.4 `JankStats` vs 自研：为什么本仓库没引它

`androidx.metrics:metrics-performance`（JankStats）是官方方案，但它在本仓库**不可用**：

- 依赖目录（`gradle/libs.versions.toml`）里没有它
- Gradle 离线缓存里也没有

**更重要的判断：即使能引，它也不够。** 对比：

| 能力 | JankStats | 本仓库自研 |
|---|---|---|
| 帧耗时 + 卡顿归因窗口 | ✅ | ✅（`FrameMetrics`） |
| 把卡顿关联到"哪个界面/什么状态" | ✅（有 `State` 机制） | ⚠️ 需自己做（我们按页面 tag） |
| 主线程**在哪等**（锁/IO/Binder） | ❌ | ✅（`MainThreadSampler`） |
| 消息级耗时 | ❌ | ✅（Looper printer） |
| 与 ANR 归因打通 | ❌ | ✅（`noteMainThreadBlockSite`） |

> 结论：`JankStats` 解决的是"帧数据采集 + 归因窗口"这一层，
> 但**回答不了"卡在哪"**。真正的线上方案是
> **"JankStats 式帧采集"为底 + 自研堆栈采样为刃"**。
> 本仓库因为依赖不可得，底和刃都自己写了一遍——这反而把原理看得更清楚。

⚠️ 一个诚实边界：`jankSampleRate` 的取值要小心。
本 Demo 的默认值是 **`1/5`**（`StabilityReporter.jankSampleRate = 5`，实测设备上打印的正是
`采样率：JANK/SLOW_MESSAGE 1/5`）。这意味着**一次孤立的长卡顿可能恰好落在 4/5 的未采样区间**——
本机实测踩到过：制造一次 3 秒主线程卡顿后，`JANK` 计数仍为 0。
**这是采样率设计的必然结果，不是丢数据**：卡顿靠聚合（分位/率）看趋势，
ANR/Crash 才靠"每一条必报"。但**前提是分母足够大**——如果一次会话只有个位数帧事件，
1/5 的采样会让统计失去意义。生产上这个值要跟会话长度、上报配额一起定。

---

## 四、三者的交汇处分

### 4.1 统一上报出口

三种事件最终应该汇入**同一条出口**，而不是各自上报。理由：

1. **限流与优先级**：崩溃/ANR 必报，卡顿可采样。放在一条通道里才能统一裁决。
   本仓库 `Kind` 的**枚举顺序即优先级**：`CRASH > ANR > JANK`。
2. **背压下的取舍**：环形缓冲满了丢最旧——但**不能丢崩溃**。
3. **字段归一**：采集源（帧、Looper、handler）不应该知道三家平台的字段格式。

本仓库的出口：`StabilityReporter.Sink`（接口）+ `drainTo(upload)` 的**成功才清账**语义。

### 4.2 与"线程治理"子系统的接缝

仓库里已有的 `thread/` 模块有一套线程池异常收集（`ThreadErrorReporter`、`CrashGuard`）。
本模块通过 `StabilityReporter.bindThreadGovernance()` 把它们**汇入同一条出口**。
这样解决了一个老问题：线程池 `submit()` **吞异常**导致线上"线程池里的崩溃看不见"。

### 4.3 装崩溃 handler 的时机：一个实测到的冲突

本仓库最初的设想是不自动安装，但**最终落地的代码是自动安装的**
（`MyApplication.attachBaseContext` → `StabilityMonitor.installEarly`，
`swallowBackground = true`）。于是在真机上实测到了这个决策的**副作用**：

`thread/ThreadGovernanceActivity` 的「⚠无防护对照」实验，
前提是"后台线程抛异常 → 进程真的挂掉"。实测：

```
点击前 PID=28026
点击后 PID=28026          ← 进程没死，实验失效
W CrashMonitor: 后台线程异常已捕获，等待上报出口发送：pool-3-thread-1
```

而 `thread/README.md` 记录的历史结果是"进程被杀"（emulator-5554 / API 36）。

⇒ 一个"更早、更全"的监控安装，**静默地改变**了另一个模块的实验语义，
让一个本该变红的对照实验变成**假绿**。

**处理方式**：不动监控（`swallowBackground = false` 会牺牲线上能力），
而是给**实验**一个「临时卸下 / 用完装回」的开关：

- `CrashMonitor.uninstallForExperiment()` —— 还原成平台默认 handler，让异常杀进程
- `CrashMonitor.reinstallForExperiment()` —— 装回**我们自己**的 handler（不是 `previousHandler`）

**修复后实测**：点「⚠无防护对照」→ PID **31787 → 消失**（实验恢复有效）；
下次启动后点「后台线程崩」→ PID **32370 → 32370**（监控已自动装回）。
详情见 `README.md` 第五节。

> 面试加分点：能讲清"我加了一个监控，它让另一个实验失效了——
> 我怎么**发现**（真机 PID 对比，而不是看日志）、怎么**修**（隔离而非削弱监控）、
> 怎么**验证修复**（两个方向的 PID 实测）"——
> 这比"我会用 `UncaughtExceptionHandler`"值钱得多。
> 核心认知：**监控组件的安装时机本身就是个设计决策，装早了会改变别人的行为。**

---

## 五、面试速答版（先背这一页）

**Q：ANR 是什么？**
> AMS 期望某组件在超时内响应系统消息但没有。**判据是"系统在等你"，不是"你卡了多久"**。
> 阈值：Input 5s / Broadcast 10s(前) 60s(后) / Service 20s(前) 200s(后) / Provider 10s。
> 实测：主线程阻塞 15s 且无输入 → 系统**不报** ANR；有输入在等 → 报 `Waited 5004ms for MotionEvent`。

**Q：线上怎么监控 ANR？**
> 三条通道：①`ApplicationExitInfo` 回捞（权威，延迟，是有界环形，每次启动都要捞，
> 且**不要按 reason 过滤、要看有没有 trace**）；②哨兵看门狗（及时，近似）；
> ③Looper 旁听（便宜，粒度粗）。`FileObserver` 监听 `/data/anr` 这条路在 Android 12 已死。

**Q：Crash 怎么监控？**
> Java 层 `UncaughtExceptionHandler`，**必须保留并委托 prev**；主线程崩要交回原 handler
> 让进程死，后台崩可吞但**必须上报**；原生崩溃拦不到，只能靠 `ExitInfo`。
> "进程是否真死"看 PID，别看 `FATAL EXCEPTION` 日志。

**Q：卡顿怎么监控？**
> 卡顿没有系统信号，必须自采。帧级（`FrameMetrics`，按刷新率算预算，负值/超大值要丢弃）
> + 消息级（Looper printer）+ **主线程堆栈采样**（回答"卡在哪"，必须排除 IDLE）。
> `JankStats` 有帧采集但回答不了"卡在哪"。

**Q：上报可靠性怎么保证？**
> 崩溃遗嘱（写本地 + `fd.sync()`，下次启动补报）；
> **只有"发送真的成功"才清账**，确认点必须唯一。

---

## 六、⚠️ 诚实边界汇总（本模块**做不到**什么）

面试里主动说出边界，比硬吹能力更能建立信任。以下每一条都是实测或代码事实：

1. **哨兵的阻塞时长是估算**（13000ms vs 真实 15000ms），不是精确值。
2. **哨兵抓不到 native 卡死**——主线程卡在 native 且持 ART mutex 时，抓堆栈的调用一起卡住。
3. **`REASON_CRASH_NATIVE` 的 trace 是 protobuf**，本模块**不解析**，只记存在/大小。
4. **无法确认 `ExitInfo` 环形缓冲的确切默认容量**——只实测到一次返回 16 条，
   因此代码不写死容量，只说"有界、会被覆盖、每次启动都要捞"。
5. **采样堆栈 ≈ `Thread.getAllStackTraces()`**，是**近似**，
   不等同于系统 SIGQUIT 抓的那份全量 trace（后者由系统在 ANR 时抓取）。
6. **`ANRFileObserver`（xshell 遗留）已过时**——`/data/anr` 在 Android 12 上禁止访问（实测）。
7. **`MainLooperWatcher`（xshell 遗留）有缺陷**——它把 `Choreographer` 消息过滤掉了，
   而**那正是卡顿发生的地方**（帧调度消息）。
8. ~~ASM 插件的 `excludedPackages` 失配~~ **已实测证伪（本次差点被误导）**：
   反编译构建产物，`ThreadPools$NamedThreadFactory.newThread` 的 `NEW` 仍是
   `java/lang/Thread`、`ThreadDefense` 零改写，说明排除**确实生效**；
   未被排除的类则被改写成 `UnifiedThread`。
   ⚠️ 教训：插件源码里「className 是斜杠形式」的注释与行为不符，
   **推导字节码插桩行为必须看构建产物，不能看注释**。
9. **本模块的 cell/泳道依赖 `ThreadPools`**：因为 ASM 的 `enableUnify=true`
   会把裸 `new Thread` 改写成 `UnifiedThread`，
   所以看门狗这类"需要常驻独占"的线程必须走
   `ThreadPools.dedicatedThread(...)` 这个正规出口。
10. **Java 8 核心库脱糖未开启** → 禁用 `java.time`，本模块用 `SimpleDateFormat`。
11. ~~本模块的自动安装让 `thread/` 的「无防护对照」实验失效~~ **已修复并双向实测**：
    给实验加了 `CrashMonitor.uninstallForExperiment()` / `reinstallForExperiment()`，
    实测「无防护对照」进程重新被杀（PID 31787 → 消失），
    且下次启动监控自动装回（后台崩 PID 32370 → 32370）。详见 4.3。

---

## 七、待办 / 下一步

- [x] `AnrTraceParser` 的 JVM 单测 —— 已落地
      `app/src/test/java/com/interview/稳定性监控/AnrTraceParserTest.kt`，
      6 个用例全绿（`testDebugUnitTest`），覆盖三个分支 + 空/垃圾输入 + 帧数截断：
  - 文本 trace → `isBinary=false`、能解出 main 线程状态、能解出锁 holder
  - protobuf 头 → `isBinary=true`、不误报堆栈
  - 被裁剪的 trace → `有堆栈=false`、`subject=null`
- [x] ~~`excludedPackages` 点号/斜杠不匹配~~ → **实测证伪，排除生效**（见第六节第 8 条）。
      附带发现：插件源码里那条关于「斜杠形式」的注释是**误导性的**，值得单独修掉。
- [x] ~~跨模块冲突：自动安装 handler 使「无防护对照」失效~~ →
      已用 `uninstallForExperiment/reinstallForExperiment` 隔离并双向实测（见 4.3）。
- [ ] 插件源码 `ThreadAsmVisitorFactory.kt` 的误导性注释修正（把 className 的真实形式写对）
- [ ] `JankStats` 引入后的对比实验（当前因依赖不可得，只做了原理层对比）
