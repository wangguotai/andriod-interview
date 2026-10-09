# 稳定性监控（ANR / Crash / 卡顿）实验模块

本目录是一个**可跑的**线上稳定性监控实验模块，配套的面试知识文档见
[`INTERVIEW-稳定性监控.md`](./INTERVIEW-稳定性监控.md)。

⚠️ **采集之后的那一半（上传管道 / 服务端聚合 / 告警 / 成本）另见
[`INTERVIEW-线上ANR监控方案.md`](./INTERVIEW-线上ANR监控方案.md)** ——
本文讲"怎么采到"，那篇讲"采到之后怎么办"，并在其中审计出**本模块当前的 4 处接线缺口**：
① `ExitInfoCollector.collect()` 的结果从未进入 `StabilityReporter`
（线上拿不到唯一来自系统判定的"真 ANR"证据）；
② `exit-seen.txt` 的去重台账会被 Set 顺序截断；
③ 崩溃遗嘱无回收驱动 → 每次冷启动重复上报；
④ `CrashJournal.beginSession` 的注释与实现不符（注释称"累计不干净退出次数"，实现拷的是上次会话开始时间）。

实验页入口：`StabilityLabActivity`
（`com.example.myapplication/com.interview.稳定性监控.StabilityLabActivity`），
已登记在首页目录。所有 `TAG`：`StabilityMonitor / Stability / AnrMonitor / JankMonitor / MainThreadSampler / CrashMonitor`。

---

## 一、为什么是这三个、以及它们的本质区别

| | 谁发现 | 事后能看到 | 根本难点 |
|---|---|---|---|
| Crash | 你的进程 | 完整堆栈 | 原生崩溃拦不到；进程直接死 |
| ANR | **系统** | 系统抓的 trace | 进程死了，内存态全失效，只能下次启动回捞 |
| 卡顿 | **用户** | 只有你自己埋的数据 | 没有任何系统信号，不量就是 0 |

⚠️ 最值钱的一条实测结论（本机 Redmi K40 / Android 12 验证）：

> **ANR 的判据是"系统在等你"，不是"你卡了多久"。**

主线程 `Thread.sleep(15_000)` 且不碰屏幕 → 系统**不报** ANR
（`dumpsys window | grep lastanr` → `<no ANR has occurred since boot>`）；
同样阻塞 15 秒并持续注入触摸 → 系统报
`ANR in ... Waited 5004ms for MotionEvent(action=DOWN)`。

所以实验页的「⚠真ANR(15S)」按钮**必须**一边阻塞、一边注入触摸，
否则你只会看到"我们的哨兵报了 13 轮无响应、但系统毫无反应"。

---

## 二、采集通道

```
通道1  ApplicationExitInfo 回捞   ← 权威 + 带 trace，但延迟（只能下次启动拿）+ 可能没有 trace
通道2  哨兵看门狗                  ← 及时，但只是估算（实测 13000ms vs 真实 15000ms）
通道3  Looper 旁听                 ← 便宜，只有单条消息粒度
通道4  拦截 SIGQUIT（native）      ← 当次当场 + 系统判据，但**会抢系统行为**，默认不装（见 2.5）
```

通道 1/2/3 在 `AnrMonitor.kt` 里是**并列**的，`StabilityMonitor.installOnCreate` 会把它们都装上。
通道 4 是**实验通道**，只在实验页手动安装。

### 通道 1 的两个关键坑（实测）

**坑① 是有界环形缓冲。** 本机实测一次返回 **16 条**，会被环形覆盖
⇒ **每次冷启动都要回捞**，并按 `(timestamp, pid)` 去重落库。

**坑② 不要按 `reason` 过滤 trace。** 实测现象：
制造一次真 ANR（进程未被杀）→ 随后 `force-stop` → 下次启动回捞，
那条记录的 `reason` 是 **`用户主动退出`**，却**带着 ANR trace**
（trace 里 main 线程正停在 `Thread.sleep`）。
因为系统**在 ANR 那一刻**就把 trace 挂到该进程的 ExitInfo 上了。

> ⇒ 判断是不是 ANR：**看有没有 trace，不要看 reason。**
> 代码里对这种情况会显式打一行 ⚠️ 提示（`AnrMonitor.ExitInfoCollector.Item.describe()`）。

### 「⚠真ANR(15S)」按钮会做什么

阻塞主线程 15s + 用 `input tap` 持续注入触摸，让 InputDispatcher 真的在等我们。
注入坐标刻意落在**触控区（按钮带）**——落在输出区不会消费触摸，也就触发不了 ANR。

### 2.5 通道 4：拦截 SIGQUIT（`AnrSigquitChannel` + `cpp/anr_signal_hook.cpp`）

**这是本仓库唯一一处主动去抢系统既有行为的探针**，因此刻意不接进
`StabilityMonitor.installOnCreate`，只在实验页手动装。

```
主线程                     signal handler                 dumper 线程（native）
  install()
   ├ 缓存 SignalCatcher tid（遍历 /proc/self/task）
   ├ 建 pipe
   ├ pthread_sigmask(UNBLOCK, SIGQUIT)   ← ① 不解屏蔽，sigaction 收不到任何信号
   └ sigaction(SIGQUIT, handler)

                         系统发来 SIGQUIT
                              │ write(pipe, 16B)     ← 只允许 async-signal-safe
                              │ tgkill(SignalCatcher)← ★ ② 不转发，AMS 等到 20s 超时
                              └─────────▶ read() 唤醒
                                          └─▶ JNI 回调 Java（判定/抓栈/上报都在这）
```

三个必须做对、少一个就是「假成功」的点：

| | 不做的后果 |
|---|---|
| ① `pthread_sigmask(SIG_UNBLOCK)` | SIGQUIT 在 zygote 就被 Block，唯一所有者是 ART 的 SignalCatcher 线程（走 `sigwait`）。**信号只投递给不阻塞它的那个线程** ⇒ handler 永远收不到 |
| ② `tgkill` 转发回 SignalCatcher | 系统 dump 堆栈的流程断掉，**AMS 一直等不到我们写 trace，直到 20s 超时才继续**（ANR 弹窗慢 20s、`/data/anr` trace 可能不完整） |
| ③ 二次判定（不是修复不完整） | 收到 SIGQUIT **≠** 发生 ANR：别的进程 ANR 时我们会被顺带 dump；任何人也能 `kill -3` 我们 |

判定纪律（**误报绝不进上报管道**，否则会污染平台的 ANR 率）：

```
确认 ANR = 本进程被 AMS 标记为 NOT_RESPONDING（getProcessesInErrorState，公开 API）
自报信号（senderPid == 自己）→ 一律否掉
同一次 ANR 的后续信号 → 按 20s 窗口复用结论，不重复上报
```

#### ⚠️ 实测发现（Redmi K40 / Android 12）：这是一个「先发信号、后打 flag」的顺序问题

**本通道最关键的一条实测结论**，也是实现上唯一无法靠读源码想当然的地方：

```
一次真 ANR 收到 5 条 SIGQUIT（system_server pid=1901，si_code=SI_QUEUE）：
  第 1 条  → 判定**非 ANR**（flag 还没打上）
  第 2 条  → 判定非 ANR
  第 3 条  → 判定非 ANR
  第 4 条  → 距第 1 条约 12s → 才第一次读到 NOT_RESPONDING → 确认 ANR ✅
  第 5+ 条 → 命中复用窗口，不重复上报
```

原因在 AMS 的流程：`appNotResponding()` 是
**先 `dumpStackTraces()`（发 SIGQUIT）→ 再 `makeAppNotRespondingLocked()`（打 flag）→ 弹框**。

⇒ **只采样一次的实现，会在每一次真 ANR 上都漏掉那条最关键的信号**
（恰恰是系统 dump 我们的那一条）。若某 ROM 只发一次 SIGQUIT，单次采样就是 100% 漏报。
所以必须**有界轮询等 flag**：窗口 `POLL_WINDOW_MS = 20s`
（依据是源码常量 `dumpStackTraces()` 里的 `remainingTime = 20 * 1000`，不是猜的），
实测 **22 次轮询 ≈ 5.5s** 就等到了。代价写在这里：**上报天生滞后最多 20 秒**
——「及时」是通道 2 哨兵的职责，「权威确认」才是本通道的职责。

**实测已确认的整条链路**（真机，同一台）：

| 环节 | 证据 |
|---|---|
| handler 收到系统信号 | `收到 SIGQUIT：sender pid=1901 code=-1(SI_QUEUE(sigqueue))` |
| 转发 SignalCatcher 生效 | logcat：`Signal Catcher … reacting to signal 3` + `Wrote stack traces to tombstoned`，**无 20s 停顿** |
| 系统真的判定 ANR | `ActivityManager: ANR in com.example.myapplication`；系统弹出「没有响应」框；`dumpsys window lastanr` 有记录 |
| 二次判定正确 | 5 条信号 → **1 条** `Kind.ANR`（另 4 条复用），事件流水里 `ANR 2`（通道 2 一条 + 通道 4 一条） |
| 误报过滤 | `run-as … /system/bin/kill -3 <pid>`（外部进程发、当时无 flag）→ 判为**非 ANR**，不进上报管道 |

⚠️ 另有两个**只有真机才能发现**的陷阱（都已固化在代码里）：

1. **`/proc/<pid>/task/<tid>/comm` 读出来是 `Signal Catcher`（带空格）。**
   按资料里常见的写法只匹配 `SignalCatcher` 会**找不到线程** —— 而后果是
   「有拦截、没转发」，会拖慢系统取证最多 20s。现在按「去掉空白后比较」匹配。
2. **adb shell 的 uid 发不了 `kill -3`**（`Operation not permitted`），
   验证必须用 `adb shell run-as <pkg> /system/bin/kill -3 <pid>`（App 自己的 uid）。

#### 已知缺口（会漏报）

AM 侧 `SilentAnr`（后台 ANR）与部分 ROM 的「闪退 ANR」**直接 kill、不打 `NOT_RESPONDING` 标记**，
本通道判不出来（微信 Matrix 用「20s 窗口轮询 flag」也解决不了这一类）。
⇒ **权威口径仍然是通道 1**，通道 4 是补充。

**实现踩到的两个坑（都已固化在代码注释里）**：

1. **结构体成员不能叫 `si_pid` / `si_uid` / `si_code`。** 它们是
   `<asm-generic/siginfo.h>` 里的宏（`#define si_pid _sifields._kill._pid`），
   写成成员后 `p.si_pid` 会被展开成 `p._sifields._kill._pid` → 编译报
   `member reference base type 'int32_t' is not a structure or union`。
   （编译期就暴露，但错误信息指向 siginfo.h，容易误判成头文件用法有问题。）
2. **JNI 边界必须在 ASCII 包下。** 导出符号形如
   `Java_<包名下划线化>_<类名>_<方法名>`，而 C++ 标识符不允许非 ASCII ——
   所以 JNI 声明层落在 `com.interview.anrsignal.AnrSigquitHook`，
   业务层仍在中文包 `com.interview.稳定性监控`，中间由这一个类隔开。

**怎么验（实验页按钮 + adb）**：

| 操作 | 预期 |
|---|---|
| 「通道4:SIGQUIT」 | 显示读数：catcherTid 是否找到、转发开关状态 |
| 「⚠真ANR(15S)」 | 读数 `handler 命中` 增加；`dumpsys window \| grep lastanr` 有记录；系统弹「无响应」框；**事件流水只多 1 条 ANR** |
| `adb shell run-as <pkg> /system/bin/kill -3 <pid>` | 判为**非 ANR**（外部发送但当时无 flag），**不出现在事件流水** |
| 「事件流水」 | `ANR` 计数 = 通道 2 哨兵 1 条 + 通道 4 **1 条**（证明没被 5 条信号放大） |

⚠️ 本机 `ndk.abiFilters = arm64-v8a` ⇒ 32 位设备/非 arm64 模拟器上通道 4
必然不可用（`loadLibrary` 失败→优雅降级），此时通道 1/2/3 完全不受影响。

---

## 三、崩溃处理：三条不能踩错的规则

1. **必须保留并委托 `prev` handler。** 不委托会改变平台默认行为
   （Android 12 默认是 `RuntimeInit$KillApplicationHandler`）。
2. **主线程崩 → 交回原 handler 让进程死**（实测 PID 26696 → 消失）；
   **后台线程崩 → 可吞但必须上报**（实测 PID 26696 → 26696 不变）。
3. ⚠️ **`FATAL EXCEPTION` 日志 ≠ 进程死了。** 判断死没死**只看 PID**：
   `adb shell pidof com.example.myapplication`

### 崩溃遗嘱（last will）的权责链

崩溃时只写本地并 `fd.sync()` 落盘，下次启动补报，**上报成功才清账**：

```
CrashMonitor                    只记录 + 写遗嘱；绝不 drain、绝不 acknowledge
CrashJournal.recoverPending()   只读进内存环形缓冲；不 acknowledge
StabilityMonitor.flush(upload)  ← 唯一清账点：只有 drainTo 返回 true 才 acknowledgeJournal
```

> 这条链子的由来是一段**真实的 bug 修复**：最初在崩溃 handler 里顺手 drain，
> 导致同一条 `[CRASH]` 在设备上打了**两遍**，且在刚出错、状态未知的线程上做了 IO。
> 教训：
> **「崩溃路径上不做 IO」和「崩溃后尽快送出去」不矛盾**——
> 中间隔着一跳（handler → 队列 → 后台线程）即可；
> 但**"确认收到"必须且只能发生在"确认已经送出"之后**。

为此**采集点（`record`）里绝不能顺手做发送**。
⚠️ 这里记录一个**实测踩到的坑**：曾经在 `record` 里挂过一个"合并式自动 flush"，
本意是让崩溃/ANR 尽快可见，但它带一个硬编码的 `upload = { true }`
（demo 没有真上传出口），语义于是变成「记下 → 立刻宣称发送成功 → 从环形缓冲删掉」：

- 事件在几毫秒内被清空 → 实验页「事件流水」永远是空的
- 「模拟上报」按钮恒报 **0 条**（要发的东西已经被"发"掉了）
- 批量化彻底失效（等于一条一发）

⇒ **"尽快发送"必须由真正的 upload 出口实现（生产是定时器），
不能在采集点上用一个假的成功回执来"模拟"。**
采集点的原则是：**只入队、只计数、零 IO，发送是别人的职责**。
（崩溃遗嘱另有 `CrashJournal` 兜底到下次启动，所以即使当次没有 flush 也不会丢。）

---

## 四、卡顿：帧级 + 消息级 + 堆栈采样

- **帧级**：`FrameMetrics`，按刷新率算帧预算（60Hz→16.67ms / 90Hz→11.1ms / 120Hz→8.3ms）。
  ⚠️ 实测坑：字段"没有值"时会给 `-1` 或极大值，**必须丢弃**（否则会算出"某帧耗时 -1ms"）。
- **消息级**：`JankMonitor` 用 `Looper.setMessageLogging` 记单条消息耗时（阈值 `SLOW_MESSAGE_MS = 500`）。
- **堆栈采样**：`MainThreadSampler` 回答"卡在哪"，把样本分成
  `CPU 热点 / WAIT / IDLE`——**必须排除 IDLE**，否则主线程"等消息"的样本会淹没真正的热点。
  卡顿边沿会自动加密采样（`noteStuck()` → 50ms/次）。

### 为什么没引 `JankStats`

`androidx.metrics:metrics-performance` 在本仓库**依赖目录与离线缓存里都没有**。
但更本质的理由是：**即使能引，它也回答不了"卡在哪"**
（它做帧采集 + 归因窗口，没有堆栈采样与消息级耗时）。
详见 `INTERVIEW-稳定性监控.md` 的对比表。

---

## 五、生产接线（`MyApplication` 已接线，但这带来一个**实测到的冲突**）

### 现状（已接线）

`MyApplication.attachBaseContext` → `StabilityMonitor.installEarly(this)`；
`onCreate` → `StabilityMonitor.installOnCreate(this)`。

```kotlin
class MyApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        StabilityMonitor.installEarly(this)   // 装 handler + 接线程治理 sink
        // StabilityReporter.sink = { event -> platform.send(event) }  // 接三方平台时注入
    }
    override fun onCreate() {
        super.onCreate()
        StabilityMonitor.installOnCreate(this)
        // ThreadPools.background.execute("stability.flush") {
        //     StabilityMonitor.flush { batch -> platform.upload(batch) }  // true 才清账
        // }
    }
}
```

### ⚠️ 但它曾与 `thread/` 的「无防护对照」实验**冲突（已实测并已修复）**

`ThreadGovernanceActivity` 的「⚠无防护对照」按钮的**实验前提**是
"后台线程抛异常 → 进程真的挂掉"。而 `installEarly` 装的是
`swallowBackground = true` 的全局 handler，会**吞掉**后台异常。

**修复前的真机实测（Redmi K40 / Android 12）：**

```
点击前 PID=28026
点击后 PID=28026      ← 进程没死，实验变成"假绿"
E AndroidRuntime: FATAL EXCEPTION: pool-3-thread-1
E AndroidRuntime: java.lang.IllegalStateException: 未防护的线程池任务异常 → 应导致进程被杀
W CrashMonitor: 后台线程异常已捕获，等待上报出口发送：pool-3-thread-1
```

对比 `thread/README.md` 记录的历史结果（emulator-5554 / API 36）：那次进程**被杀**。

#### 修复方案：给实验一个「临时卸下 / 用完装回」的开关

不动监控本身（`swallowBackground = false` 会牺牲线上能力），
而是让**实验自己**在触发前卸下全局 handler：

```kotlin
// ThreadGovernanceActivity「⚠无防护对照」按钮内
val uninstalled = CrashMonitor.uninstallForExperiment()   // 还原成平台默认 handler
// ... 提交会抛异常的裸池任务：进程被杀 = 实验结论成立
// 兜底：若本 ROM 没杀（有别的 handler 接管），delayed 任务把监控装回，
//       确保"实验副作用不泄漏成常态"
```

`CrashMonitor` 为此提供两个方法：

| 方法 | 作用 |
|---|---|
| `uninstallForExperiment(): Boolean` | 把默认 handler 还原成**我们安装之前的那个**（通常是 `RuntimeInit$KillApplicationHandler`），让异常按平台默认行为杀进程。未安装过则返回 `false` |
| `reinstallForExperiment(): Boolean` | 装回**我们自己**的 handler（不是 `previousHandler`，否则一次实验就永久失去监控） |

**修复后真机实测：**

| 场景 | 实测结果 |
|---|---|
| 点「⚠无防护对照」 | PID **31787 → 消失**（进程被杀，实验恢复有效）✅ |
| 下次启动后点「后台线程崩」（稳定性实验页） | PID **32370 → 32370**（handler 已自动装回，后台异常重新被吞）✅ |

> 因为 `MyApplication.attachBaseContext` 每次启动都会 `installEarly`（幂等），
> 所以"进程被杀"导致的卸下状态**不会跨启动残留**。

**遗留提醒**：`thread/README.md` 里那条"进程被杀"的历史记录，
是在**没有稳定性监控**时的结果；现在它的有效性由上面这个开关保障 ——
若哪天这个开关被移除，该结论会立刻再次失效。

**接入自己的上报出口**只需两步：

1. `StabilityReporter.sink = { ... }` —— 只做观察/旁路（可选；`drainTo` 本身已会打 logcat，
   再挂一个"打 logcat"的 sink 会让同一条事件**打印两遍**，实测过）。
2. 在 `StabilityMonitor.flush(upload)` 里给出真正的 `upload`，
   **返回 `true` 表示已发送**——只有 `true` 才会清掉崩溃遗嘱。

### 页面挂帧监控

```kotlin
override fun onResume() { super.onResume(); StabilityMonitor.watchJank(this) }
override fun onPause()  { StabilityMonitor.flushJank("本页"); JankMonitor.stop(); super.onPause() }
```

---

## 六、实验页按钮

| 按钮 | 作用 | 对应的面试点 |
|---|---|---|
| 接入监控 / 总览 / 清空 | 装探针、看 `StabilityMonitor.formatOverview()`、清内存态 | 全局视角 |
| 卡顿:主线程3S / 卡顿:忙等4S | 两种卡顿形态（sleep vs 忙等） | 帧监控 + 采样分类 |
| 帧报告 / 采样报告 / 采样开关 | 帧分布、`MainThreadSampler` 热点、开关采样 | "卡在哪" |
| LOOPER慢消息1.5S | Looper printer 的粒度 | 消息级监控 |
| ANR探针说明 / 自报ANR | 看门狗原理；self ANR（**无全量 trace**） | ANR 三通道 |
| ⚠真ANR(15S) | 真 ANR（阻塞 + 注入触摸） | **ANR 判据** |
| 通道4:SIGQUIT / 通道4读数 | 安装 native SIGQUIT 拦截 + 二次判定读数 | 通道 4（2.5 节） |
| EXITINFO回捞 | `ApplicationExitInfo` 回捞 + trace 摘要 | 通道 1 |
| TRACE解析(样本) | `AnrTraceParser` 三个分支（文本 / 二进制 / 被裁剪） | trace 解析 |
| 崩溃遗嘱 | 看 `CrashJournal` 的 pending 状态 | 上报可靠性 |
| 后台线程崩 | 后台崩溃（PID **不变**） | 崩溃策略 |
| 池任务崩(SUBMIT) | 线程池 `submit()` 吞异常 → 汇入统一出口 | 与线程治理的接缝 |
| ⚠主线程崩 | 主线程崩溃（PID **变化**，进程结束） | 崩溃策略 |
| 事件流水 / 事件流水导出 / 模拟上报 | 环形缓冲、JSONL 落盘、`flush` 语义 | 统一上报出口 |

---

## 七、⚠️ 诚实边界

本模块**做不到**的事（详见 `INTERVIEW-稳定性监控.md` 第六节，此处列要点）：

1. 哨兵的阻塞时长是**估算**（实测 13000ms vs 真实 15000ms）。
2. 哨兵**抓不到 native 卡死**（主线程卡在 native 且持 ART mutex 时，抓堆栈的调用一起卡住）。
3. `REASON_CRASH_NATIVE` 的 trace 是 **protobuf**，本模块**不解析**，只记存在/大小/hexdump。
4. **未确认 `ExitInfo` 环形缓冲的确切默认容量**——只实测到一次返回 16 条，
   因此代码不写死容量，只依赖"有界、会被覆盖、每次启动都捞"这个性质。
5. 采样堆栈是 `Thread.getAllStackTraces()` 的**近似**，不等同系统 SIGQUIT 抓的全量 trace。
6. **未验证** Play Console 的 ANR 采集口径细节（方向性结论：与 `ApplicationExitInfo` 绑定）。
7. ~~未验证 ASM 插件 `excludedPackages` 的匹配~~ **已实测，结论：排除生效，无缺陷。**
   验证方法：反编译构建产物 `ThreadPools$NamedThreadFactory.newThread`，
   其 `NEW` 仍指向 `java/lang/Thread`（不是被改写后的 `UnifiedThread`），
   且 `ThreadDefense` 同为零改写 —— 说明排除列表里的
   `com.interview.thread.ThreadPools` / `ThreadDefense` **确实匹配上了**。
   对照：未被排除的 `ThreadMisuseScenarios` / `NetLabActivity` 等已被改写（全产物共 8 个类）。
   ⚠️ 但插件源码里「className 是斜杠形式」的注释与实际行为**不符**，
   属**误导性注释**（容易让后来者据此推导出"排除失效"的错误结论，本次就差点如此）。
8. ~~通道 4（SIGQUIT）未做真机验证~~ **已实测（Redmi K40 / Android 12）**：
   handler 能收到系统发的 SIGQUIT（`sender=system_server(1901), SI_QUEUE`）、
   转发后 ART 正常 `Wrote stack traces to tombstoned`（**无 20s 停顿**）、
   系统侧确实记了 `ANR in com.example.myapplication`、5 条信号只产出 **1 条** `Kind.ANR`。
   ⚠️ 但仍有未验证项：① 只有这一台设备/这一个 ROM（MIUI 12，Android 12）；
   ② **没有验证「不转发时是否真的会慢 20s」的对照实验**（该结论来自微信 Matrix 原文，
   本仓库只验证了「转发后一切正常」这一半）；③ 多 ABI 未覆盖（见第 10 条）。
9. **通道 4 会漏报**：后台 ANR（`SilentAnr`）与部分 ROM 的「闪退 ANR」不打
   `NOT_RESPONDING` 标记，本通道判不出来。**上报还会滞后最多 20s**
   （等 flag 的有界窗口，见 2.5 节）。权威口径仍是通道 1。
10. **通道 4 本机只有 arm64-v8a**（`ndk.abiFilters` 收口所致），
   非 arm64 设备上 `loadLibrary` 失败 → 优雅降级，通道 1/2/3 不受影响。
11. **`Signal Catcher` 的线程名不可想当然**：实测 `/proc/<pid>/task/<tid>/comm`
   读出来是 **`Signal Catcher`（带空格）**，按资料里常见的 `SignalCatcher` 只匹配一种
   会找不到线程 → 「有拦截没转发」→ 拖慢系统取证最多 20s。
   现在按「去掉空白后比较」匹配，但这属于**跨 ROM 需复核**的实现细节。

### 依赖与环境约束

- `compileSdk = 34`、`minSdk = 24`、`targetSdk = 33`；`ApplicationExitInfo` 需要 **API 30+**，
  低版本设备上通道 1 自动降级（只剩通道 2/3）。
- **Java 8 核心库脱糖未开启** → 禁用 `java.time`，本模块统一用 `SimpleDateFormat`。
- ASM 插件 `enableUnify = true` 会把裸 `new Thread` 改写成 `UnifiedThread`，
  所以看门狗这类"需要常驻独占"的线程**必须**走
  `ThreadPools.dedicatedThread(...)`（正规出口），不能裸 `new`。
  （通道 4 的 native dumper 线程不在这个约束内：它由 `pthread_create` 创建，
  不经过 `java.lang.Thread`。）

### 遗留的过时/有缺陷实现（保留原因：证明此路不通）

| 位置 | 结论 |
|---|---|
| xshell `ANRFileObserver` | **已过时**。`/data/anr` 在 Android 12 上对本进程也是 `Permission denied`（实测），共享 AID 的前提已不存在 |
| xshell `MainLooperWatcher` | **有缺陷**。它过滤掉了 `Choreographer` 消息——而那**正是卡顿发生的地方**（帧调度） |
| xshell `ANRWatchDog` | 半成品，思路可用，实现未接入 |
