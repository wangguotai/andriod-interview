# 线上 ANR 监控方案（端到端设计稿）

> 起因：`readme.md` day 2.22 那场面试的薄弱点是「**ANR 的概念、检测机制、线上发生 ANR 如何监控**」。
> 同目录的 [`INTERVIEW-稳定性监控.md`](./INTERVIEW-稳定性监控.md) 已经回答了前两问，
> 并且把**采集侧**做成了可跑的 [`StabilityLabActivity`](./StabilityLabActivity.kt)（三通道）。
>
> 本文回答第三问里还没答的那一半：**采到了之后怎么办**——
> 上传管道、远程配置、服务端聚合口径、告警，以及成本。
>
> 全文口径同前：**只写本机真机实测过的**。本次新测的数字统一带 `M1~M9` 编号，
> 原始记录在 [附录 A](#附录-a真机测量原始记录)；凡沿用既有文档的结论，标注"复验"或"沿用"；
> 未验证的显式标 `[未验证]`；实测**推翻/修正**了既有说法的，标 `⚠️实测`。

**设备**：Redmi K40（`alioth`）/ Android 12 / `SKQ1.211006.001`，包 `com.example.myapplication` v1(1.0)，uid 10357。

---

## 〇、一句话定位：本文与既有文档的分工

```
采集（已完成）                上报与决策（本文）              消费（本文的服务端段）
─────────────                ──────────────────             ──────────────────────
三通道 + 统一出口       →     批量 / 分级 / 退避 / 配额   →   聚合口径 / ANR 率 / 告警
INTERVIEW-稳定性监控.md        ★ 当前最大缺口                ★ 目前完全空白
```

一句话结论（也是面试可以先抛的那句）：

> **线上 ANR 监控的难点不在"怎么抓到 ANR"，而在"这套东西自身的成本与口径"**——
> 一条未裁剪的 ANR trace 未压缩约 **200 KB**（实测 M4），
> 未压缩时相当于 **约 2000 条**普通埋点事件（100 B/条）的带宽；而 ExitInfo 的环形窗口
> 在正常使用下**约 2~3 小时就被覆盖一轮**（实测 M2），根本没有"攒久一点再传"的空间。
> 于是"上报预算"和"证据完整性"天生对立，方案的每一个设计点都是在这两者之间做取舍。

---

## 一、现状审计：采集侧做完了，接线只完成了一半

先把"已经有什么"钉死，避免把设计意图当成已实现。以下**逐行核对过代码 + 真机落盘文件**，
共审出 **4 处缺口（A/B/C/D）**：

| 环节 | 状态 | 证据 |
|---|---|---|
| 三通道采集 | ✅ 完成 | `AnrMonitor`（哨兵 + Looper 旁听 + ExitInfo 回捞） |
| 统一上报出口语义 | ✅ 完成 | `StabilityReporter.drainTo(upload)`：**返回 true 才移除事件** |
| 崩溃遗嘱落盘 | ✅ 完成（但有重复上报风险） | 真机 `files/stability/crash-journal.pending` **1412 B**，内含一条真实主线程崩溃栈（实测）；⚠️见 §1.3 |
| 会话标记 | ✅ 完成（有瑕疵） | 真机 `session.state` = `running / 1791347303885 / 1791347230168`；⚠️见 §1.4 |
| ExitInfo 去重台账 | ✅ 完成（有瑕疵） | 真机 `exit-seen.txt` = **15 条**（去重键 `pid@timestamp`）；⚠️见 §1.2 |
| **周期性上报驱动** | ❌ **不存在** | 全仓 **没有任何** 定时器/`WorkManager`/`AlarmManager` 调用 `flush`；`installOnCreate` 里也没有 |
| **真实 upload 出口** | ❌ **不存在** | 全仓 `StabilityMonitor.flush { … }` **只有 1 处调用**，就是 `StabilityLabActivity` 的「模拟上报」按钮（`upload = { true }`，纯 demo 假回执）；另一处 `flushJank` 是帧报告，与上传无关 |
| **ExitInfo 结果进上报管道** | ❌ **未接** | 回捞结果只赋给 `StabilityMonitor.lastExitInfo`（仅用于总览文案）与打日志，**从不进 `StabilityReporter`** |
| 远程配置 | ❌ 不存在 | `jankSampleRate` 是本地写死的 `5` |

### 1.1 ⚠️实测 缺口 A：**ExitInfo 的权威证据从没进过上报管道**

这是本次审计发现的最严重问题，且很容易被"三通道都装了"的表象掩盖：

```
AnrMonitor.ExitInfoCollector.collect()  →  StabilityMonitor.lastExitInfo（内存里）
                                        →  Log.i("ExitInfo 回捞完成…")
                                        →  formatOverview() 里的文案
                                        ✗  StableReporter.Event  ← 从来没有这一步
```

后果：**通道 1（唯一来自系统判定的"真 ANR"证据）在线上是拿不到的**，
它只活在当前进程的内存里和 `adb logcat` 里；而 `lastExitInfo` 是 `@Volatile` 的普通字段，
**下次冷启动就被覆盖**。如果只按现有接线发版，线上会表现为
"ANR 事件有（哨兵的近似判定）、真 ANR 证据没有"，而这恰恰是最难排查的那种缺失——
它不是报错，是**静默的**。

> 面试价值：这正是"**装好了三个探针 ≠ 接好了监控**"的具体证据。
> 我是在核对 `collect()` 的返回值去向上发现的，不是靠读设计文档。

### 1.2 ⚠️实测 缺口 B：`exit-seen.txt` 的去重台账会被截断，导致**重复上报或漏报**

`AnrMonitor.ExitInfoCollector.saveSeen()` 的写入逻辑是：

```kotlin
f.writeText(ids.toList().takeLast(200).joinToString("\n"))   // AnrMonitor.kt:677
```

这里有两个坑，第二个更隐蔽：

1. 上限 200 远大于 ExitInfo 环形缓冲的 16（实测 M1），**正常不会触发**；
2. 但 `ids` 来自 `loadSeen()` 的 `readLines().toSet()`，**是 Set —— 顺序不确定**。
   一旦真的超过 200 条，`takeLast(200)` 截掉的可能**不是最旧的**，而是任意 200 条；
   被截掉的那条如果其实是"已上报过的"，下次回捞就会被判成 `isNew = true` → **重复上报**；
   反之若误留，则会**漏报**。
3. ⚠️ 真机还观察到一个次要现象：台账文件末尾**缺少换行**（15 行内容、`wc -l` 也报 15），
   写的是 `joinToString("\n")`。这对 `readLines()` 无影响（它容忍无尾换行），
   属**无害但脆**的写法——换成按行追加的解析器就会丢最后一条。

> 正确做法：台账用 `ArrayDeque` 语义（显式保序）或改用 SQLite；
> 去重键也应更稳（`pid@timestamp` 在 pid 复用时有理论碰撞，加 `reason` 更稳）。

### 1.3 缺口 C：崩溃遗嘱只记录、不回收 —— 在"无上报驱动"下会**重复上报**（真机现场）

真机上 `crash-journal.pending` 里躺着一条 2026-10-07 的主线程崩溃遗嘱，
时间戳对应 pid=496，而 `exit-seen.txt` 里**已经有** `496@1791344376133`。

也就是说：这条崩溃**已经由 ExitInfo 通道确认过**了（同一事件在两条通道里各有一份证据），
但遗嘱文件**仍在**——因为按设计，只有 `StabilityMonitor.flush(upload)` 返回 true 才清账，
而线上**没有 flush 驱动**（与缺口 A 同一病根）。

⇒ 这不是 bug，是**设计正确但接线缺失**的表现。但它有个真实代价：
`CrashMonitor.recoverPending()` 用 `forceReport()`（`CrashMonitor.kt:240`）把遗嘱
**每次冷启动都重新塞回事件队列**，在"永远没有 flush"的线上环境里
→ **同一条崩溃会被重复上报 N 次**（N = 冷启动次数），且遗嘱文件永不消失。
→ 接线时必须一并处理（见 §2.6）。

⚠️ 另外区分一个容易写错的因果：遗嘱文件本身**不影响** `session.state` 的
"上次未正常收尾"判定（那是两个文件、两套机制），后者是**独立**的缺口 D（见 §1.4）。

### 1.4 ⚠️实测 缺口 D：`session.state` 的会话计数**代码与注释不符**（且我第一版判据错了，见下）

修正记录（把一次**自己的误判**如实留下，因为它比结论更有价值）：
本节初稿我据「App 重启后 `session.state` 的 mtime/内容未变」判定它"从未写回"。
**该判据是错的**——采样时 App 其实还没重启，文件本就不该变。复核源码后更正如下：

`CrashJournal.beginSession`（[CrashJournal.kt:214-231](file:///Volumes/ext/workspace/projects/android/interview/android-interview/app/src/main/java/com/interview/稳定性监控/CrashJournal.kt)）写的是：

```kotlin
listOf(
    STATE_RUNNING,
    System.currentTimeMillis().toString(),
    lines.getOrNull(1) ?: "0",   // ← 注释写的是「累计不干净退出次数」
).joinToString("\n")
```

⇒ **第三字段实际拷的是上一次的"会话开始时间"（`lines[1]`），不是任何计数；
源码里从头到尾没有 `+1`。** 所以：
- 正确结论：**第三字段是"上次会话开始时间"**（真机 `1791347230168` 即是），
  `beginSession` 的**注释与实现不符**（注释说计数、代码拷时间戳）——这是一处**真实的代码缺陷**；
- 我初稿说的"累计不干净次数恒等于 0""从未持久化"**不成立**，已删除。

⚠️ 之所以保留这段：`session.state` 在三种读法下会被解出三个不同的值
（第 1 行状态 / 第 2 行本次开始时间 / 第 3 行上次开始时间），
**只靠观察文件无法判断语义，必须回读源码**——这正是我第一版踩的坑。

---

## 二、第 1 层：客户端上传管道

### 2.1 为什么"现成的平台 SDK 直接调"不够

崩溃平台的 SDK 是商品，但本项目的场景有两个特殊性，决定了必须有**自己的中间层**：

1. **数据源不是"异常"，而是"系统记录"**。ANR 的权威数据是 `ApplicationExitInfo`
   回捞出来的（含一条 trace 字节流），不是一次 `throw`。多数 SDK 的 ANR 能力
   （如果有）是"自己判定 + 自己抓栈"，与"系统判定 + 系统抓栈"**不是同一份证据**，
   而且我们实测到系统那份 trace 的**结构分层极不均匀**（见 §3.2）——
   直接整条扔给 SDK 既贵又难聚合。
2. **清账语义必须唯一**。`drainTo` 的"true 才移除"是这份设计的核心正确性保证
   （见 `INTERVIEW-稳定性监控.md` §2.3 那次的真实 bug）。
   第三方 SDK 的网络层不提供这个语义，硬接会把"确认已送出"和"确认已收到"混在一起。

所以管道的位置是：**`StabilityReporter`（唯一出口）→ `StabilityUploader`（本文新增）→ 平台/自建。**

### 2.2 分级：三类数据的预算完全不同（实测锚点）

用本次实测的字节数把"预算"这件事量化，这是整份设计里最硬的一块依据：

| 类别 | 单条体积（实测） | 策略 | 依据 |
|---|---|---|---|
| 崩溃（Java） | ≈ 2 KB（`CrashMonitor` 已 `take(2048)` 截断）+ 遗嘱 8 KB 上限 | **必报**，且**立即**（遗嘱已有兜底） | 稀有，每一条都值钱 |
| ANR（哨兵） | ≈ 数十 KB（含 `dumpMainThread()`，≤4000 字符 Looper dump） | **必报**（低延迟，近似判定） | 是"用户可感知卡顿"的即时信号 |
| ANR（ExitInfo + trace） | **200 KB 未压缩 / 28 KB gzip**（实测 M4） | **必报，但 trace 分级** | 权威证据；未压缩时成本 ≈ 普通事件的 2000 倍 |
| jank 明细 | 每条数百字节 | 1/50 会话 | 量大、同质 |
| 卡顿聚合报告 | ≈ 数 KB | 1/5 | 聚合后信息密度高 |
| 主线程采样直方图 | ≈ 数 KB | 1/20 会话 | 靠聚合 |

> ⚠️ 关键设计点：**ANR trace 必须走独立配额桶，不能和普通事件共用一个限流器。**
> 否则一次 ANR 风暴（OOM 前兆时常见）会把配额打满，
> 结果**真正的崩溃反而被限流掉**——这与 `Kind` 枚举顺序即优先级的意图正好相反。

### 2.3 trace 分级（本文的核心设计）

⚠️ **先纠正我第一版的一个严重口径错误**：我最初把这份 196,794 B 的报告当成
"本 App 一条 ANR 的 trace"来分层。**实际上它含两个进程的线程栈**：

```
$ grep -n "^----- pid " /tmp/anr_full.txt
151:----- pid 27275 at ...   ← ① 本 App（app 段，声明 DALVIK THREADS (26)）
827:----- pid 1901  at ...   ← ② system_server（声明 DALVIK THREADS (237)）
线程头总数 = 124（app 26 + system_server 98）
```

⇒ **修正后最重要的结论，是那条 71.7%——而不是原先说的 89%：**

| 级别 | 内容 | 字节（实测） | gzip-6 | 占全文 | 价值 |
|---|---|---|---|---|---|
| **A 元信息** | `Subject` 超时原因（含 `Waited 5004ms for MotionEvent(action=DOWN)`）、`Foreground`、Activity、Build、CPU 使用表、内存压力 | 8,722 | 2,564 | 4.4% | 回答"**是不是我卡了、等谁**"；**含 system_server 抢占 CPU 的证据** |
| **A′ app 段头** | `----- pid …` / `Cmd line` / fingerprint / ABI / class loader / ART 内部指标 | 8,520 | — | 4.3% | 与日志侧对齐、确认符号化前提 |
| **B main 线程栈** | main 线程 25 行（`state` / `held mutexes` / 帧） | 1,589 | — | 0.8% | **回答"卡在哪一行"——单位字节价值最高** |
| **精简合计 A+A′+B** | | **18,831** | **6,407** | **9.6%** | 够定位绝大多数"卡在业务代码"的 ANR |
| **C app 其余 25 个线程块** | 只在锁竞争时才需要（找 `held by thread N` 的对手） | 36,914 | — | 18.8% | app 侧全量 = 55,745（28.3%） |
| **S system_server 98 个线程块** | ⚠️ **不是我们的进程，与本次 ANR 的定位几乎无关** | 141,049 | — | **71.7%** | 唯一的用途是佐证"**整机在忙**"（证据在 A 的 CPU 表里，A 已够） |
| 全量 | | 196,794 | 28,328 | 100% | |

⇒ 三条设计结论（按重要性）：
1. **最大的浪费不是"线程太多"，而是"把 system_server 的 98 个线程块也传了"——它独占 71.7%。**
   客户端解析时**必须按 `----- pid` 分段**，只取自己进程的那一段。
2. 默认上报 **A+A′+B = 18,831 B（gzip 后 6,407 B，占全量压缩后的 22.6%）**。
3. 判"要不要升到 app 全量"的**判据在 A 里**：`main.state ∈ {Blocked, Waiting}`、或 `held mutexes=` 非空、
   或 A 的 CPU 表显示**某个 app 内的线程**高占用。（system_server 高占用**不属于**"需要 app 全量"的理由——
   那种情况下 A 表已经给出了结论。）
⚠️ 口径提示：**A 也可独立上报**（"级别 0"，gzip 后仅 2,564 B = 全量的 9.0%），
`A 的 4.4%` 与 §4.2 里另一个 `4.4×`（压缩后的 22.6% 的倒数）**是两回事**，别混。

| 级别 | 何时上报 | 触发方式 |
|---|---|---|
| **0 = A** | 永远（兜底） | gzip 后 2.5 KB，极便宜，用于算"ANR 率"分子分母 |
| **1 = A+A′+B**（默认） | 永远 | 覆盖绝大多数"卡在业务代码"的 ANR |
| **2 = A+A′+B+C（app 侧全量）** | 仅当级别 1 判断为**app 内锁竞争**时 | 判据见上（**不含 system_server 段**） |

⚠️ 本机这条 ANR 是"main + `state=Sleeping` + `held mutexes=` 空 + App 自身 CPU 0%"
→ 判定为**非锁竞争**，级别 1 就够（而且 B 级直接给出了凶手：`at java.lang.Thread.sleep`）。
⇒ 修掉"错传 system_server"之后，**默认只发 app 侧 28.3% 里的一小部分**，
不存在"89% 白花"的说法（那是我第一版漏了 `----- pid` 分段导致的误算）。

> 这就是"ANR 监控的成本优化"最实在的一处：不是靠压缩算法（gzip 只到约 7×，
> `-1`→`-9` 的额外收益仅约 21%），而是靠**按进程与价值分层裁剪**。

### 2.4 管道的五个必要部件（每个都有具体理由）

```
┌─────────────── StabilityUploader ───────────────┐
│ ① 有界队列（持久化）   →  进程被杀不丢           │
│ ② 批量 + gzip         →  200KB → 28KB，再合并    │
│ ③ 指数退避 + 抖动     →  服务端雪崩时不添乱       │
│ ④ 分类配额（ANR trace 独立桶）→ 崩溃不被饿死      │
│ ⑤ 幂等键              →  重复上报可去重           │
└─────────────────────────────────────────────────┘
```

**① 有界队列必须落盘。** 现有 `CrashJournal` 只覆盖崩溃；ANR 事件（哨兵抓的）
在内存环形缓冲里，**进程被系统杀（ANR 常发生）就丢了**。
⇒ 复用 `CrashJournal` 的"遗嘱"写法（追加 + `fd.sync()` + 启动补报），
给 ANR/ExitInfo 事件开第二个文件（如 `anr-journal.pending`），**同样的 `MAX_RECORDS` 有界**。

**② 批量 + gzip：压缩是把单位从 KB 变到 100B 级的唯一手段。** 实测：全量 196,794 → 28,328 B（**6.9×**）；
只报 A+A′+B 则 18,831 → 6,407 B（压后仅 6.4 KB）。
⚠️ 但要注意：**gzip 只对"重复度高"的 trace 有效**，对短事件（<1 KB）反而增大体积
（gzip 头 + 字典约 20 B）。⇒ 设阈值：**< 1 KB 的批次不压**。

**③ 指数退避 + 抖动。** 一次 ANR 风暴往往是**同一个版本**在多台设备上同时发生
（同一段坏逻辑），无抖动的重试会把服务端打穿。退避公式用
`min(cap, base × 2^n) × random(0.5, 1.0)`，`cap` 建议 30 分钟，且**失败不丢、落回队列**。

**④ 分类配额。** 见 §2.2 的 ⚠️。

**⑤ 幂等键。** 服务端去重必须靠客户端给的稳定键，而不是"看内容像不像"。
实测落盘的 `exit-seen.txt` 用的 `pid@timestamp`（如 `27275@1791338537514`）就是现成的幂等键来源；
沿用 §1.2 的建议，**加上 `reason`** 降低 pid 复用碰撞。

### 2.5 上报驱动（本次要补的关键接线）`[设计建议，未实现]`

现状是"完全没有驱动"（§1）。生产口径建议**四个触发点**（不是单一周期），
并明确区分"**常规补报**"与"**证据类即刻直连**"：
⚠️ 下表四个触发点均为 `[未验证/建议值]`（无实测支撑；`~10 s`、`30 min` 是回避启动
ANR 预算与耗电的惯用起点，需按实际启动耗时与后台策略重标）。

| 触发点 | 时机 | 覆盖的场景 |
|---|---|---|
| **冷启动后延迟一次** | `onCreate` 后 ~10 s（躲开启动 ANR 预算） | 补报上次的遗嘱 + 捞到的 ExitInfo |
| **进后台时** | `onTrimMemory`/`onStop` 判定进后台 | 进程可能随时被回收，**这是最后的发送窗口** |
| **周期兜底** | 30 min（`WorkManager`，非 `AlarmManager`） | 长会话、后台存活 |
| **即刻直连（仅 ANR trace）** | 探针判定卡死/回捞到真 ANR 时 | 不等周期——因为**环形窗口只有几小时**（实测 M2） |

⚠️ 第四点与既有代码的教训不冲突：`README.md` §三（与 `INTERVIEW-稳定性监控.md` §2.3 的
崩溃遗嘱权责链同源）说的"**采集点（`record`）里绝不能顺手做发送**"仍然成立。区别在于：
发送由**后台泳道**独立线程发起（读队列 → 发送），而不是在 `record()` 的调用栈里做。
这正是"采集体量 vs 主线程预算"那条原则的延伸。

### 2.6 与 `flush` 的"成功才清账"如何共存

保留唯一的清账点，把新增的两类数据也纳入同一个事务边界：

```kotlin
// 唯一清账点（现有语义不变，只扩展数据源）
fun flush(upload: (List<Event>) -> Boolean): Boolean {
    val ok = StabilityReporter.drainTo(upload)
    if (ok) context()?.let {
        CrashMonitor.acknowledgeJournal(it)   // 现有
        AnrJournal.acknowledge(it)            // 新增：ANR 事件遗嘱
    }
    return ok
}
```

⚠️ 注意**不要**用"ExitInfo 已由系统保存"当作可以不清账的理由——
系统那份是**环形有界、几小时就覆盖**（实测 M2），一旦被覆盖，
我们没清账的副本就是唯一的证据了。

---

## 三、第 2 层：服务端聚合与"ANR 率"口径

### 3.1 这个指标为什么必须自己算

`INTERVIEW-稳定性监控.md` §1.3 已给出方向性判断：Play Console 的 ANR 率
**同样依赖 `ApplicationExitInfo` 这条链路**（`[Play 侧口径未逐条验证]`）。
本次实测给了一条**旁证**（M3）：本 App 的 ExitInfo 里唯一带 trace 的那条，
`reason` 是 `USER REQUESTED`。⇒ 说明"**有 trace**"与"**reason=ANR**"是**两个独立条件**。
⚠️ **不能反推**成"这条就是该次退出的 ANR"——进程可能在更早的时刻 ANR、
trace 被系统挂到后来的退出记录上（既有文档坑③描述的正是这个机制）；
也不能据此断言 Play 侧的具体统计口径（那未验证）。
可确证的只有：**"只看 reason" 会漏掉带 trace 的记录**这一事实，
⇒ 所以"我们自己算的 ANR 率"与"面板上的数"**有对不上的机制性可能**（`[未验证]`）。

### 3.2 服务端要有的是"两个视角 + 一个分母"

```
视角1（系统判定，权威）  ExitInfo 回捞 → reason/有没有 trace → ANR 次数
视角2（进程内近似，及时）哨兵事件       → 用户可感知的卡顿次数
分母（必须有，否则率无意义）           → 会话数（session） / 启动次数（app_start）
```

⚠️ **没有分母的"ANR 数"是没用的**：同一个 ANR 数，在 1 万会话和 100 万会话下含义完全相反。
本仓库已经有会话的原材料：`CrashJournal.beginSession()`（真机 `session.state` 已落盘），
把它升级成"每次冷启动上报一条 `session_start` 计数事件"即可（**这是分母，不是日志**）。
`[设计建议，未实现]`

**聚合维度**（按优先级）：
`版本 → 机型/OS（避免某 ROM 专属问题被平均掉）→ 页面/Activity（A 级 `Subject` 里就有）→ 前台/后台`。

⚠️ 本机 A 级 `Subject` 文本里直接带 Activity 与超时原因：
`Input dispatching timed out (… StabilityLabActivity (server) is not responding. Waited 5004ms for MotionEvent(action=DOWN))`
⇒ **归因到页面不需要额外埋点**，从 Subject 正则提取即可（但要防文本格式跨版本变化，
提取失败时降级为"未知"，不能丢整条事件）。

### 3.3 告警：分"率"和"绝对值"两档，且必须带分母

⚠️ **本节全部阈值为 `[未验证/建议值]`**——它们没有任何实测支撑，是按"避免告警疲劳 +
不漏报"的经验取的起点，上线后必须按实际基线重标。

| 告警 | 阈值 `[未验证/建议值]` | 为什么 |
|---|---|---|
| ANR 率突增 | 相对上周同时段 **> 2×** 且绝对会话数 > 100 | 只看相对会在大样本下误报，只看绝对会在小样本下误报 |
| 单机型 ANR 率 | 该机型 > 全局 **3×** | 抓 ROM 专属问题（本机就是 MIUI，属高风险样本） |
| 新版本 ANR 率回归 | 灰度版本 > 基线 + 阈值 | 与灰度发布联动（见 §4.4） |
| ANR trace **缺失率** 突增 | > 30% `[阈值未验证]` | 这往往意味着采集链路自己坏了，而不是业务变差了 |

⚠️ 最后一条容易被忽略：**监控系统的"数据缺失"本身就是一种告警**。
本次审计发现的缺口 A（ExitInfo 事件从不上报）就是这类——它不会报错，
只会让面板"看起来很安静"，而安静被当成"没问题"。

---

## 四、第 3 层：灰度、成本与合规

### 4.1 远程配置（客户端必须能被随时"拧小"）`[设计建议，未实现]`

⚠️ 下面整段 JSON 与默认值都是**建议**，无一行实测（唯一有实测支撑的是
"拉不回来时用保守默认值"这条**原则**本身，理由见下）。

```
{ "anr":  { "probeEnabled": true, "intervalMs": 1000, "timeoutMs": 2500,
            "traceLevel": 1, "traceLevelByLockContention": 2 },   // 0=A 1=A+A'+B 2=app 侧全量
  "jank": { "enabled": false, "sampleRate": 50 },
  "upload": { "batchSize": 20, "maxBytesPerDay": 2097152 } }      // 2 MB/天/设备
```

⚠️ 两条约束（前一条是**原则**、后一条是**建议**，均无实测）：
- **拉取失败必须用兜底默认值（且默认值要保守）**：卡顿风暴时，客户端自己不知道"此刻该降采样"，
  所以配置拉不回来时不能"全开"。默认 `jank.sampleRate = 50`、`traceLevel = 1`。
- **`traceLevel` 的降级要能"只影响新事件"**：已经落进队列的全量 trace 照发
  （否则降级动作本身会改变数据口径，让前后不可比）。

### 4.2 成本量级（用实测数字粗算，可当场演算给面试官）

以 **DAU 100 万、日活 4 会话/人、ANR 率 0.5%** 为例（**示意口径，非实测**）：

```
ANR 事件数          = 100万 × 4 × 0.5%   = 20,000 次/天
× 6.4 KB(精简,压后) = 128 MB/天          ← 若报全量: 20,000 × 28.3 KB ≈ 567 MB/天（4.4×）
```

⇒ 结论（⚠️ 这里的 **`4.4×` = 全量压缩后 ÷ 精简压缩后 = 1/22.6%**，
与 §2.3 表里"A 占**未压缩**全量 4.4%"是**两个不同的 4.4**，别混）：
**精简分级把 ANR trace 的带宽压到 1/4.4（省 77%）**，
代价是"app 内锁竞争类 ANR 需要二次拉取"（见 §2.3 的级别 2）。
再加上"仅锁竞争升到 app 全量"，长期期望值还能更低。
⚠️ ANR 率 0.5% / 4 会话 是**假设值**，真实量级要按业务基线替换。

### 4.3 隐私

ANR trace 里**天然包含**文件路径、类名、线程名、**Activity 名**（实测 A 级里就有
`/data/app/~~LVZ6WaYgN8krg7hrZqI2lA==/…/base.apk` 这类安装路径，含设备侧签名信息）。
⚠️ 上云前必须做**路径与实名信息脱敏**（本次实测的 trace 里未发现用户输入内容，
但 `description` 字段（`REASON_OTHER` 时的系统文本）**没有稳定契约**
——既有文档已警告不要写业务判断，这里追加一条：**它也不该原样上云**）。

### 4.4 与灰度发布联动

新版本灰度期间，**ANR 率必须作为回滚指标之一**，且要用同一套口径比较
（这正是为什么 §3.2 的"两个视角 + 分母"必须固定下来，不能随版本改口径）。

---

## 五、面试速答版（先背这一页）

**Q：线上 ANR 怎么监控？**
> 分三层：**采集**（三通道，见 `INTERVIEW-稳定性监控.md`）→ **上报**（分级 + 配额 + 有界落盘）→ **聚合**（统一口径 + 分母）。
> 只说"三通道"会被追问"那你怎么把它发出去、多贵"。

**Q：上报最贵的是什么？**
> **ANR trace**。实测一条未裁剪的 ANR 报告 **196 KB**，gzip 后 28 KB——
> 未压缩时相当于 **约 2000 条**普通埋点事件（按 100 B/条计）。
> 所以默认只上报 **元信息 + app 段头 + main 线程栈（18,831 B；压后 6,407 B，占全量压缩后体积的 22.6%）**，
> 只有判定为**app 内锁竞争**时才升到 app 侧全量——因为那时才需要那 25 个 app 线程块去找"持锁的对手"。
> ⚠️ 而最大的那笔浪费（**71.7%**）甚至不是"要不要发全量"的选择题：那是 system_server 的
> 98 个线程块，**根本不该传**——客户端解析前必须按 `----- pid` 分段，只取自己进程那一段。

**Q：ExitInfo 环形缓冲要注意什么？**
> 它是**有界环形**。⚠️本次实测：16 条覆盖了 **2 小时 41 分**（`dumpsys activity exit-info`），
> 即正常使用下**几小时就滚一圈**。⇒ 必须"启动即捞"，**不能攒批等网络**。
> 另外实测到一条反直觉的记录：唯一带 trace 的那条 `reason = USER REQUESTED`（用户主动退出）——
> 说明**"有 trace" 与 "reason=ANR" 是两个独立条件**，判断要看前者
> （但也别反推成"它一定是 ANR"，见边界 §6.6）。

**Q：为什么不能直接接三方崩溃平台？**
> 因为 ANR 的权威数据是**系统记录**（`ApplicationExitInfo` + 系统抓的 trace），
> 不是一次 `throw`；而且我们的清账语义（`drainTo` 返回 true 才移除）在 SDK 的网络层里没有对应物，
> 硬接会把"已送出"和"已收到"混为一谈——这正是我们之前踩过的那个 bug 的根源。

**Q：这套东西你自己会不会成为卡顿源？**
> 会，所以有三条硬约束：① 采集点零 IO（只在 `record()` 入队）；
> ② 回捞/上报都在后台泳道；③ 抓栈只在"已经卡住"的边沿做一次（线程本来停着，此时最便宜）。
> 而且**监控的缺失也要告警**——本次审计就发现 ExitInfo 数据从未进上报管道，
> 这种缺失是静默的，只会让面板变安静。

---

## 六、⚠️ 诚实边界汇总

1. `dumpsys activity exit-info` 的 16 条是本机**跨包一致**的（M1），
   但这只证明 **dumpsys 输出上限**；App 侧 `getHistoricalProcessExitReasons()` 的上限
   **仍未被直接验证**（既有文档第六节第 4 条同样未解）。两者的公共性质是"有界 + 环形覆盖"。
2. 200 KB / 28 KB 是**单个 ANR 样本**（M4）。同机 `/data/anr/` 下同时存在 **41 KB 与 1.36 MB** 的 trace，
   样本间方差 >30×，**不可当常量写死**。
3. trace 分段（A/A′/B/C/S）是**对 DropBox 版报告**测的。`ExitInfo.traceInputStream`
   返回的文本格式与之**未必逐字节一致**（`[未验证]`）——需在真机上抓 `ExitInfo` 的 trace 复核。
   ⚠️ 但**"必须先按 `----- pid` 分段"这个结论与格式无关**：它是文本协议的一部分，
   只要 trace 里含多个进程段就成立（本次实测该报告里确有 app + system_server 两段）。
4. 每台设备 2 MB/天 的配额**未经验证**，是从单条体积反推的示意值。
5. "锁竞争 → 升到 app 全量"的判据（`state ∈ {Blocked, Waiting}` / `held mutexes` 非空）
   是**设计**，未在真实锁竞争样本上验证准确率——本机那条恰是**非锁竞争**（app 段 0 个 `Blocked`）。
6. Play Console 的 ANR 采集口径**未逐条验证**；本文只给出一条**旁证**（M3）。
   ⚠️ 而且这条旁证**不能反推**"那条 trace 就是该次退出的 ANR"——
   进程可能在更早时刻 ANR、trace 被挂到后来的退出记录上（既有文档坑③描述的正是这个机制）。
   可确证的只有："存在一条 `reason≠ANR` 却带 trace 的记录"。
7. **`dumpsys dropbox --print` 能作为离线取证通道**（M7，无需 root，本机 153 条 / 上限 1000）——
   这是本文的**新发现**，但"生产设备上 dropbox 保留策略是否一致"未验证。
8. 本文**只做审计与设计，未改动任何代码**。缺口 A/B/C 是**已确认的现状事实**（缺口 D 见第 10 条），
   修复方案已给出但尚未实现、尚未实测。
9. **§3.3 的告警阈值、§3.2 的 `session_start` 上报、§4.1 的远程配置 JSON、§2.5 的四个触发点
   全部是设计建议，均标 `[未验证/建议值]`**，无一处实测。
10. ⚠️ 缺口 D（§1.4）在初稿里被我写错了，现已更正为"**代码与注释不符**"。
    保留该错误记录的原因：它是我"照注释字面解释数据、未回读源码"导致的，
    比结论本身更值得记（§1.4 有完整复盘）。
11. **单条 200 KB 里 71.7% 是 system_server 的线程栈**（M4）——这个结论的适用范围是
    "**本机型/本 ROM 的 DropBox 版报告结构**"。不同 Android 版本是否都在同一条 ANR 报告里
    附带 system_server 段，**未跨版本验证**。

---

## 七、待办 / 下一步

- [ ] **修缺口 A（最高优先）**：把 `ExitInfoCollector.Item` 映射为 `StabilityReporter.Event`
      （`kind` 需新增 `ANR_EXIT_INFO` / `EXIT_REASON`），让权威证据进入统一出口。
- [ ] **修缺口 B**：`exit-seen.txt` 改用保序结构（`ArrayDeque`）或 SQLite；去重键补 `reason`。
- [ ] **修缺口 C**：给遗嘱补上报驱动（否则每次冷启动重复上报）；报告侧按 `(pid, timestamp)` 去重兜底。
- [ ] **修缺口 D**：让 `beginSession` 第三字段的实现与注释对齐（要么真的累计、要么把注释改成"上次开始时间"）。
- [ ] **补上报驱动**：`StabilityUploader`（有界落盘队列 + gzip + 退避 + 分类配额 + 幂等键），
      四个触发点见 §2.5；配 JVM 单测（沿用 `AnrTraceParserTest` 的做法，`isReturnDefaultValues = true`）。
- [ ] **trace 分级落地**：按 `----- pid` 分段 + A/A′/B/C/S 解析器 + `traceLevel` 远程配置；
      先在 `StabilityLabActivity` 加一个按钮，把"同一条真 ANR 在各级别下的字节数"打出来
      （**用实测数字而不是估算说话**，本次已用 `/tmp` 脚本算过一遍，见 M4）。
- [ ] **取证脚本**：把 `adb shell dumpsys dropbox --print` + 本机 `adb pull` 流程写成
      `tools/` 下的脚本（M7 的落地）。
- [ ] 复核边界 3：在真机上抓 `ExitInfo.traceInputStream` 的原文，与 DropBox 版对比格式差异。

---

## 附录 A：真机测量原始记录

> 设备：Redmi K40（`alioth`）/ Android 12 / `SKQ1.211006.001` / 串号 `57d05823`
> 包：`com.example.myapplication` v1(1.0)，uid 10357
> 日期：2026-10-08　方式：全部为**只读**系统命令，测量期间未改动 App 代码

### M1 — ExitInfo 的 16 条上限（跨包一致）

```bash
$ adb shell dumpsys activity exit-info com.example.myapplication | grep -c "ApplicationExitInfo #"
16
$ adb shell dumpsys activity exit-info com.android.settings  | grep -c "ApplicationExitInfo #"
16
$ adb shell dumpsys activity exit-info com.google.android.gms | grep -c "ApplicationExitInfo #"
16
```
`com.android.systemui` → 0 条（可能该包名不在包列表里）；`com.miui.home` → 1 条（仅 1 次退出）。

### M2 — 16 条覆盖的时长窗口

最早 `2026-10-07 09:46:52.087` → 最晚 `2026-10-07 12:28:21.301`
⇒ **2 小时 41 分钟**即被 16 条滚动覆盖满。

### M3 — 唯一带 trace 的那条，reason 是 `USER REQUESTED`

```
reason=10 (USER REQUESTED)   trace=/data/system/procexitstore/anr_2026-10-07-10-01-56-994.gz
（其余 15 条 trace=null）
```
⇒ 直接现场证据，**复验**了"不要按 reason 过滤 trace、要看有没有 trace"。
⚠️ 但要与 §1.1 的缺口分开看：系统侧**有**这条 trace，而 App **没有把它报出去**。

### M4 — 真实 ANR 报告的体量（本 App 自己的 ANR，含 main 线程栈）

| 形态 | 字节 | 行 |
|---|---|---|
| 未压缩全量 | 196,794 | 2,733 |
| gzip -1 | 34,857 | |
| gzip -6 | 28,328 | |
| gzip -9 | 27,673 | |
| DropBox 自己存的（也是 gzip） | 28,242 | ← 与我的 gzip -6 相差 <0.5%，方法论自洽 |

⚠️ **分区前必须先按 `----- pid` 分段**（这是我第一版漏掉的一步）：

```
$ grep -n "^----- pid " anr_full.txt
151:----- pid 27275 at ...    ← 本 App
827:----- pid 1901  at ...    ← system_server
$ grep -n "DALVIK THREADS" anr_full.txt
311:DALVIK THREADS (26):       ← app 段声明 26 个
1052:DALVIK THREADS (237):     ← system_server 段声明 237 个（文本里 98 个线程头，见下）
```

五段字节账（**合计精确闭合 = 196,794**）：

| 段 | 内容 | 字节 | 占全文 | gzip-6 |
|---|---|---|---|---|
| **A** | ANR 元信息：`Subject` / `Foreground` / Activity / Build / CPU 使用表 / 内存压力 | 8,722 | 4.4% | 2,564 |
| **A′** | 空行 + app 段头（`----- pid` / `Cmd line` / fingerprint / ABI / class loader / ART 指标） | 8,520 | 4.3% | — |
| **B** | main 线程块（25 行） | 1,589 | 0.8% | — |
| **精简合计 A+A′+B** | | **18,831** | **9.6%** | **6,407**（全量压缩后的 22.6%） |
| **C** | app 其余 25 个线程块 | 36,914 | 18.8% | — |
| **S** | **system_server 的 98 个线程块** | 141,049 | **71.7%** | — |
| 全量 | | 196,794 | 100% | 28,328 |

app 侧全量（A+A′+B+C）= **55,745 B（28.3%）**；S 段单独 = **141,049 B（71.7%）**。

⚠️ **线程数的真实口径**（解释第一版为何写错）：
- 全报告线程头 **124** 个 = app **26** + system_server **98**；
- app 段的 `DALVIK THREADS (26)` 与实测的 26 个线程头**一致**；
- system_server 段声明 `(237)` 但文本里只有 98 个线程头 —— 该段在中途被
  `[[TRUNCATED]]` 截断，**这个差是截断造成的，不是内容缺失**；
- 第一版把它写成"125 个线程块""其余 124 个"，是两个口径混用导致的错误，已修正。

> A 级里的 CPU 使用表（前几行，实测）：
> `48% 1901/system_server`、`14% 615/logd`、`14% 3053/com.android.systemui`、
> `0% 27275/com.example.myapplication`
> ⇒ 这张表**用 4.4% 的体积换到了最关键的一句结论**："本次 ANR 期间 App 自己 0% CPU，
> 是系统侧（system_server 48%）在忙"——所以那 71.7% 的 system_server 线程栈
> 对本次定位**毫无必要**。这是"分级"最划算的一块。

### M5 — 复验：trace 里 main 线程是 `Sleeping`

```
"main" prio=5 tid=1 Sleeping
  | held mutexes=
  at java.lang.Thread.sleep(Native method)
  - sleeping on <0x05c43aca> (a java.lang.Object)
  at com.interview.稳定性监控.StabilityLabActivity$onCreate$12$2.invoke(StabilityLabActivity.kt:227)
  …
  at android.os.Looper.loop(Looper.java:299)
```
`Subject: Input dispatching timed out (… StabilityLabActivity (server) is not responding. Waited 5004ms for MotionEvent(action=DOWN))`

⇒ **复验**既有结论：主线程自己 `Thread.sleep` 造成的 ANR 在 trace 里就是 `Sleeping`，
看到 Sleep 不要跳过——它正是凶手；且这条堆栈与 `StabilityLabActivity.kt:216-229`
的「⚠真ANR(15S)」实现（阻塞 + 注入触摸）完全对得上。

### M6 — `/data/anr` 权限（复验）

```bash
$ adb shell run-as com.example.myapplication ls /data/anr
ls: /data/anr: Permission denied
$ adb shell ls -ld /data/anr
drwxrwxr-x 2 system system 8192
```
`/data/system/procexitstore/`、`/data/system/dropbox/` 同样对 `shell` 与 `run-as` 均 `Permission denied`。

### M7 — DropBox 可作离线取证（无需 root）

```bash
$ adb shell dumpsys dropbox --print          # 全部条目
$ adb shell dumpsys dropbox --print --file   # 附带条目文件路径
```
本机输出：`Drop box contents: 153 entries` / `Max entries: 1000`，
含本 App 的 `data_app_anr (compressed text)` 与 `data_app_crash (text)` **全文**，
比 `logcat` 的 `FATAL EXCEPTION` 单行完整得多。

### M8 — 客户端落盘状态（缺口 C 的现场）

```bash
$ adb shell run-as com.example.myapplication ls -l files/stability
-rw------- 1412  crash-journal.pending     ← 一条未清账的真实主线程崩溃遗嘱
-rw-------  313  exit-seen.txt             ← 15 条去重台账
-rw-------   35  session.state             ← running / 1791347303885 / 1791347230168
```
遗嘱内容（截断）：`1791344376126  CRASH  java.lang.RuntimeException  main  1  1  496 …`
去重台账里**已有** `496@1791344376133` ⇒ 该崩溃已由 ExitInfo 通道确认，遗嘱却仍在（因无 flush 驱动）。

> ⚠️ 并且 `CrashMonitor.recoverPending()` 用的是 `forceReport()`（`CrashMonitor.kt:240`，
> 走 `record()`、绕过采样），所以每次冷启动都会把这份遗嘱**再报一次**——
> 在"永远没有 flush"的线上环境里就是**重复上报 N 次**。

### M9 — `session.state` 第三字段的真实语义（缺口 D 现场；**含一次自我纠错**）

```bash
$ adb shell pidof com.example.myapplication
8591                                              ← App 运行中
$ adb shell "date"
Thu Oct  8 09:51:53 CST 2026
$ adb shell run-as com.example.myapplication cat files/stability/session.state
running
1791347303885       ← 第 2 行：本次会话开始时间
1791347230168       ← 第 3 行：**上一次会话开始时间**（不是计数）
$ adb shell run-as com.example.myapplication ls -l files/stability/session.state
-rw------- 35  ... 2026-10-07 12:28
```

❌ **我的第一版结论（错误）**：我据"PID 8591 在跑 + mtime 未变"判定第三字段
"从未写回"、把它当成"累计不干净退出次数恒等于 0"。

✅ **回读源码后更正**：`CrashJournal.beginSession`（[CrashJournal.kt:214-231](file:///Volumes/ext/workspace/projects/android/interview/android-interview/app/src/main/java/com/interview/稳定性监控/CrashJournal.kt)）
第三字段写的是 `lines.getOrNull(1) ?: "0"`，即**拷贝上一版文件的第 2 行（上次开始时间）**；
源码里**没有 `+1`**。所以：
- 第三字段**本来就该是"上次开始时间"**，其值是合理的；
- 本次采样时 App 尚未重启（PID 8591 是旧进程），**文件不变是预期行为**，不构成证据；
- 真正的缺口是：**注释写"累计不干净退出次数"，实现却拷时间戳**——代码与注释不符。

⚠️ 教训（比结论值钱）：`session.state` 三行的语义（状态 / 本次开始 / 上次开始）
**无法靠观察文件推断**，必须回读源码；我第一版正是照着注释字面去解释，才得出错误结论。

