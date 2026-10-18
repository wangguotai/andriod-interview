# 线上采集方案：怎么把这些文件（trace / ANR 全文 / hprof）从用户设备拿回来

> 配套：[`INTERVIEW-ANR排查实操.md`](./INTERVIEW-ANR排查实操.md)（怎么分析）
> 与数据包 `tools/perfetto/out/anr/`（本次抓的样本）。
>
> 本文回答：**开发机上那套「adb + perfetto + dumpheap」，搬到线上用户设备后哪些还能用、哪些必须换**。
>
> 口径：只写**本机真机实测过**的；未验证的显式标 `[未验证]`。
> 设备：Redmi K40（`alioth`）/ Android 12 / API 31 / arm64 / **user（非 root）**。

---

## 0. 一句话结论

> **线上没有任何一条"一条命令搞定"的路。必须把采集拆成三类：(A) App 自己能做的、
> (B) 只能由 shell/adb 触发的、(C) 只能靠系统事后给的。**
> 而且实测发现一条**反直觉的好消息**：Traces 里最贵的那部分（系统级 Perfetto）
> **App 自己起不了**（SELinux 拒绝执行 `perfetto`），**但它能往系统 trace 里写自己的埋点**。

---

## 1. 先划清边界：App 能做到什么（⭐ 本次实测）

这是整份方案的地基。我用 `run-as`（**uid/域与线上 App 等价**）逐条实测：

| 能力 | 实测命令 | 结果 | 含义 |
|---|---|---|---|
| 执行 `perfetto` 二进制 | `run-as <pkg> /system/bin/perfetto ...` | ❌ **`Permission denied`** | **App 起不了系统级 trace** |
| 连 `traced_consumer` socket | `run-as <pkg> ls /dev/socket/traced_consumer` | ❌ `Permission denied` | App 无法直接命令 traced |
| 读 `perfetto` 相关目录 | — | ❌ 被 SELinux 挡住 | 只能走 shell |
| **写 `trace_marker`** | `run-as <pkg> sh -c 'echo X > /sys/kernel/tracing/trace_marker'` | ✅ **成功**（`tracing_mark_write: FROM_APP` 进了缓冲区） | ⭐ **App 能往系统 trace 里打点** |
| `Debug.dumpHprofData()` | 源码核对（见 §2.3） | ✅ **无 debuggable 门禁** | release 也能自 dump |
| `Debug.startMethodTracing()` | `Debug.java:1183-1370` | ✅ 无门禁 | App 可自抓 Java 方法 trace |

**⇒ 三条硬结论：**

1. **系统级 Perfetto trace（含 sched/binder/input）在线上只能由 `adb shell` 起** ⇒
   纯线上自动采集**拿不到**这份数据，必须有另一条路（§3.2 的兜底）。
2. **但 App 能写 `trace_marker`** ⇒ 可以让 App 在**系统 trace 的时间轴上打标记**，
   这与 §5「Perfetto 里没有 ANR 标记事件」正好互补（**实测发现，之前我列的那个缺口现在有解**）。
3. **hprof 与 method trace 可以完全由 App 自己做**，无需 root、无需 debuggable。

> ⚠️ `trace_marker` 实测的坑：前两次写入"成功但读不回来"，是因为
> `tracing_on` 为 0（atrace 没在跑）。**写入成功 ≠ 留下记录** —— 这条在线上设计时必须考虑。

---

## 2. 三类证据的线上获取方式

### 2.1 E1 Perfetto trace（系统级）

| 线上可行性 | 方式 |
|---|---|
| ❌ **App 自动** | 实测不可行（App 不能执行 perfetto、不能连 traced） |
| ⚠️ **`adb shell`（人工/内测）** | ✅ 完全可行，就是本次用的方式 |
| ⚠️ **厂内专项** | 用 `am` 的 trigger 机制或定制系统服务 `[未验证]` |

**所以线上的现实选择是两条腿**：

- **腿 1（内测/厂内复现）**：`adb shell` + 本文配套 cfg，与开发机完全一样。
- **腿 2（线上兜底）**：采不到系统 trace 时，靠 §2.2 的 ANR 全文 + §2.3 的 hprof/method trace。
  **这也解释了为什么"ANR 全文"必须是线上采集的主力** —— 它是唯一在纯 App 环境下拿得到的
  「全线程 + 对端进程」证据（见 `INTERVIEW-ANR排查实操.md` §5）。

### 2.2 E2 ANR 全文（⭐ 线上主力）

**方式 A：`ApplicationExitInfo`（App 自己能做，推荐）** — API 30+

```kotlin
val am = context.getSystemService(ActivityManager::class.java)
am.getHistoricalProcessExitReasons(packageName, 0, 0)   // 每次冷启动都要捞
// 对带 trace 的条目：info.traceInputStream
```

- ✅ **无需 root、无需 debuggable、无需 adb** —— 纯 App 能力。
- ⚠️ 三个实测坑（既有结论，复验）：
  1. **有界环形**（实测 16 条覆盖 2h41m）⇒ **必须每次冷启动捞**，不能攒批等网络；
  2. **不是所有 ANR 都带 trace**（self ANR 常没有）；
  3. **⚠️ 不要按 `reason` 过滤** —— 实测见过 `reason=USER REQUESTED` 却**带 ANR trace** 的条目。
     **判断标准是"有没有 trace"**。

**方式 B：`dumpsys dropbox --print`（仅 adb/内测）**
本次数据就是从这来的（27,766 B / 2,736 行，含 system_server 段）。
线上 App 拿不到，只作人工排查。

> ⚠️ **本仓现存缺口（我审计确认）**：`AnrMonitor.ExitInfoCollector` 的结果
> **从来没有进过上报管道** —— 只赋给内存里的 `StabilityMonitor.lastExitInfo` + 打日志，
> `StabilityReporter.Event` 里没有它。而且 trace 字节被解析成摘要后**原始字节直接丢弃**。
> ⇒ **线上要采 ANR 全文，第一件事是把这个缺口接上。**

### 2.3 E3 hprof / method trace（App 自己能做）

**hprof —— 实测无需 debuggable**（源码核对，这是关键）：

```c
// art/runtime/native/dalvik_system_VMDebug.cc
static void VMDebug_dumpHprofData(...) {   // :219 — 无任何权限检查
  ...
  hprof::DumpHeap(filename.c_str(), fd, false);
}
// 对比：同文件里有门禁的两个
if (!Dbg::IsJdwpAllowed())        ThrowSecurityException("...not debuggable");  // :442 nativeAttachAgent
if (!runtime->IsJavaDebuggable()) ThrowSecurityException("...not debuggable");  // :464 类豁免
```

⇒ 全文件只有 **442 / 464** 两处 debuggable 检查，**都不在 `dumpHprofData` 的路径上**。

**调用方式（两条，本仓两个都已实现）**：

| 方式 | 特点 | 本仓现状 |
|---|---|---|
| `Debug.dumpHprofData(path)` | 简单，但**会冻结进程**（stop-the-world） | 已有 |
| **fork 子进程 dump** | 不冻结，copy-on-write | ✅ 已实现 `oomdump/OomSnapshotNative.forkDump` |

**method trace（App 也能自抓，无需权限）**：
`Debug.startMethodTracing(path)` / `startMethodTracingSampling(...)`（`Debug.java:1183-1370`）。

⚠️ **hprof 体积实测**：本仓文档记录 **~285 MB**（`OomSnapshotMonitor.kt:105`），
本次 `am dumpheap -g` 实测 **35.7 MB**（App 较空时）。⇒ **线上绝不能无条件 dump 并上传**。

---

## 3. 线上落地方案（按可行性分三档）

### 3.1 档 1：**纯 App 自动采集**（✅ 线上可行，推荐先做）

```
触发（哨兵判定卡死 / ExitInfo 捞到真 ANR）
   ↓
① App 写 trace_marker 打标记        ← ✅ 实测可行（给系统 trace 留锚点）
② 采集 ApplicationExitInfo 全文      ← ✅ 纯 App 能力
③ 需要时 Debug.dumpHprofData / fork dump  ← ✅ 实测无 debuggable 门禁
   ↓
④ 落盘（有界队列 + fsync）→ ⑤ 后台上报
```

**能拿到**：ANR 全文（全线程 + 对端进程段）、Java 堆快照、method trace、自己打的 trace_marker。
**拿不到**：系统级 Perfetto（sched / binder flow / input 分发链）。

### 3.2 档 2：**+ 内测/厂内 adb 专项**（✅ 可行）

保持本文配套的 `adb shell perfetto` 流程不变，只在**内测包 / 厂内设备**上用。
⇒ 得到完整的 Perfetto trace（本次那 15.9 MB 那种）。

### 3.3 档 3：**<profileable> + 系统工具**（⚠️ 需产品决策）

`<profileable android:shell="true"/>` 让 **release 包**能被 shell 侧工具剖析
（官方文档明确说该标签**就是为 production 构建设计的**、可以长期留着）。

| 好处 | 代价 |
|---|---|
| release 上可用 Perfetto / Simpleperf / Studio Profiler 剖析 | 稍微放宽剖析入口（官方认为风险最小） |
| 让 heapprofd 能分析 release 进程 | 需要产品/安全评审 |

⚠️ **本仓 manifest 目前没有这个标签**（实测 grep 无），且 release 构建的配置我**未验证**。

---

## 4. 上报通道（线上必须新建的部分）

**审计结论：现有传输通道撑不住二进制大文件。**

| 现状（已审计） | 细节 |
|---|---|
| 唯一的真实网络出口 | `CrashUploader.upload()`：OkHttp **JSON POST**（`CrashUploader.kt:280-297`） |
| ⚠️ **默认根本不发** | 未配置 URL 时走 `simulatedUpload`，是个**直接 `return true` 的 lambda**（`StabilityMonitor.kt:383-399`） |
| URL 来源 | 编译期 `BuildConfig.CRASH_UPLOAD_URL`（来自 `local.properties`），**仓库里是注释掉的** |
| 载荷类型 | 全是 `String/Long/Int/Boolean`；**无 multipart / 无文件体 / 无流式** |
| 超时预算 | ~10 s（`CrashUploader.kt:118`） |
| 落盘队列 | 只有 `CrashJournal`（**20 条上限**、逐行文本、有 `fd.sync()`） |
| 上报驱动 | `ReportDriver`：`scheduleWithFixedDelay` 默认 **30 s** + 进后台时触发 |
| 内存环形缓冲 | 320 条 / 每 kind 96 |
| **远端配置 / 开关** | ❌ **完全不存在** |

### 4.1 需要新建的三件事

1. **文件型传输**：multipart 或「申请上传 URL → PUT」两段式。
   ⚠️ 几百 MB 的 hprof 直接 POST 不现实 ⇒ 建议**先压缩**（hprof 可 gzip）
   或**只上传服务端需要的切片**。
2. **跨进程死亡的持久队列**：`CrashJournal` 只有 20 条且是文本；大文件需要
   独立的「待上传文件清单 + 状态机（未传/传输中/已传）」，且要**有界 + 可清理**
   （本仓 `KEEP_DUMPS = 2` 已在做本地淘汰，可复用这套思路）。
3. **远端开关（必须）**：现在**一个都没有**。采全量 trace/hprof 是**重操作**，
   没有开关就意味着无法在事故时**远程关掉**。建议：
   `perfettoEnabled / hprofEnabled / sampleRate / maxBytesPerDay`，
   且**拉取失败时用保守默认值**（沿用既有 `traceLevel` 的设计原则）。

### 4.2 成本量级（用本次实测数字）

| 证据 | 单条实测 | 日增（按 1 万次 ANR/天估） |
|---|---|---|
| ANR 全文 | **28 KB** | ~280 MB |
| Perfetto trace（30s） | **15.9 MB** | **~159 GB** ← 不可接受 |
| hprof | 35.7 MB ~ 285 MB | 天文数字 |

⇒ **分级策略是硬要求**：
- **ANR 全文**：每次真 ANR 都报（便宜、权威）；
- **Perfetto trace**：**只在白名单/内测**，或极端情况下按样本上报；
- **hprof**：**仅手动触发**，或只在 OOM 快照时保留本地、**默认不上传**（本仓现在就是这样）。

---

## 5. ⭐ 一个可用的小技巧：用 trace_marker 给 trace 打锚点

**解决的问题**：我在《排查实操》§9 记的缺口 ——
**Perfetto trace 里没有稳定的「ANR 标记事件」**，只能靠人工对时间窗。
本次实测发现 App **能写 `trace_marker`** ⇒ 可以让 App 自己打锚点：

```
App 判定卡死那一刻 → 写 trace_marker("ANR_DETECTED pid=... reason=...")
                  → 事后在 Perfetto UI 里搜 "ANR_DETECTED"，直接跳到现场
```

⚠️ 两个实测注意点：
1. 写入**成功不等于留下记录** —— `tracing_on=0` 时写进去也没有 buffer（我实测踩到）。
2. 所以只有在「perfetto 正在跑」时打锚点才有意义 ⇒ 与 §3.2 的 adb 专项配合使用。
   `[未验证]` 在真正的线上（无 trace 运行）打锚点的收益。

---

## 6. 落地顺序建议

| # | 动作 | 线上可行 | 备注 |
|---|---|---|---|
| **P1** | **接上 ExitInfo → 上报管道**（修既有缺口 A），并**保留原始 trace 字节** | ✅ | 最高优先：这是线上唯一能拿到的权威证据 |
| **P2** | **新建文件型传输 + 待传队列 + 远端开关** | ✅ | 见 §4.1 |
| **P3** | **App 侧 trace_marker 打点**（§5） | ✅ | 小改动，给 trace 加锚点 |
| **P4** | **加 `<profileable android:shell="true"/>`** 或明确只在 debuggable 构建采 | ⚠️ 需决策 | 见 §3.3 |
| **P5** | **内测专项流程**（`adb shell perfetto`，即本文配套流程） | ✅ | 内测/厂内用 |

---

## 7. ⚠️ 诚实边界

1. **档 1（纯 App 采系统 trace）实测不可行** —— App 不能执行 `perfetto`、不能连 `traced_consumer`；
   **这是 SELinux 强制的结果，不是配置问题**。我未测试"厂商定制系统服务"这条路。
2. **`trace_marker` 可用是我实测的**，但**线上收益未验证**（需要 trace 同时在跑才有意义）。
3. **未在真正的 release APK 上验证**：本机 App 是 **debuggable**。
   `dumpHprofData` 无门禁是**源码核对**得出的，**未在 release 包上实测**。
4. **`<profileable>` 的效果未实测**，只是官方文档 + 搜索结果支持；本仓 manifest 里没有该标签。
5. **服务端容量从未验证** —— §4.2 的 159 GB/天是按 1 万 ANR/天粗算的示意值，不是实测。
6. **上报通道的审计基于当前未提交的工作区**（`CrashUploader.kt` 是 untracked、
   `StabilityMonitor.kt` 是 modified），可能与你仓库里的最终形态有差异。
7. **未验证**：Android 13/14 上这些门禁是否一致；不同 ROM（尤其 MIUI 这类定制）是否额外收紧。
8. 本文**未改动仓库任何代码**。
