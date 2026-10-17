# JVM OOM 现场取证：fork 子进程 dump 冻结堆

> 本文是**设计说明 + 实测记录 + 面试答题稿**，不是可行性清单。
> 所有数字都来自本仓库在真机（Redmi K40 / alioth / Android 12 / API 31 / arm64 / **user 版**、无 root）上的实跑。

---

## 0. 一句话

`OutOfMemoryError` 那一刻 **fork 一个子进程**：子进程只复制页表（COW），父进程被挂起毫秒级后立刻恢复，
由子进程把**fork 那一瞬间的堆**写进 hprof。父进程不必先腾出内存，快照也天然是"现场原样"。

```
父进程：SuspendAll ─(毫秒)→ fork ─→ ResumeAll ─→ 继续活着（堆还满着）
                                    │
                            子进程：继承的是一份页表副本
                                    · 改写 SuspendAll/StartGC 为 ret
                                    · 调 art::hprof::DumpHeap 写 hprof
                                    · fsync → _exit(0)
```

---

## 1. 实测结果（先看结论）

| 场景 | 堆水位 | 快照大小 | 耗时 | **父进程被挂起** | 校验 |
|---|---|---|---|---|---|
| 手动（水位 6.4MB） | 6.45 / 256 MB | **37.97 MB** | 1114 ms | **0 ms** | shark 读完 |
| **真 OOM** | **255.9 / 256 MB** | **285.00 MB** | 1550 ms | **0～4 ms** | **shark 逐记录读到 EOF，788,588 条无错** |

真 OOM 那次 shark 的记录分布（这就是"文件是真的"的证据）：

```
totalRecords=788588
  ClassDumpRecord    = 20676     ← 类元数据
  ByteArrayDump      = 94276     ← 包含压测用的 250 个 1MB byte[]（256.30 MB）
  InstanceDumpRecord = 195408
  GcRootRecord       = 287785
  StringRecord       = 107025
RESULT: shark parsed every record to EOF without error
```

> 为什么必须让 **shark**（LeakCanary 的独立解析器）来读，而不是只看"文件有 285MB"：
> 这类方案最典型的假成功就是**留下一个长度不对、中间错位的 hprof** —— 字节数看着不小，
> 但没有任何工具打得开。第三方实现能顺序读到 EOF，才算证明"结构自洽"。

**冻结语义**（单独验证）：一个 1MB 的 `byte[]` 填 `0xAB`，fork 后由父进程线程在 200ms 后覆写成 `0xCD`；
结果 hprof 里是 **1,048,576 个 `0xAB`、0 个 `0xCD`** —— 子进程看到的确实是 fork 那一刻的页。

---

## 2. 为什么不能简单地"在本进程里 dump"

`Debug.dumpHprofData()` 在本进程里做的是 stop-the-world：堆越大停得越久，
在 OOM 现场（堆已经满）这是最不该发生的事。fork 方案要解决的正是这个，
但它自己带出三个必须正面回答的问题 —— 三个都是**实测撞出来的**，不是推演。

### 2.1 拿不到 `DumpHeap` 的地址：dlopen 被 link namespace 挡死

`art::hprof::DumpHeap` 在 AOSP 里是 `namespace art HIDDEN`（release 版即 `visibility("hidden")`），
且 libart.so 早已不含 `.symtab/.strtab`。常规两条路都断：

| 尝试 | 结果 |
|---|---|
| `dlopen("/apex/com.android.art/lib64/libart.so")` | 被拒：`classloader-namespace` 不允许加载 |
| 留一个未定义符号让 linker 解析 | `UnsatisfiedLinkError: cannot locate symbol "_ZN3art5hprof8DumpHeapEPKcib"` |
| lldb attach 到卡住的子进程 | 超时 |
| debuggerd / tombstone | 非 debuggable build 需要 root |

**唯一跑通的路**：`dl_iterate_phdr` 找到 libart.so 的 load bias，再**自己解析 ELF**：
`PT_DYNAMIC` → `DT_SYMTAB` / `DT_STRTAB` / `DT_GNU_HASH` / `DT_STRSZ`，
按候选 mangled 名逐个 `strcmp`。

### 2.2 子进程会死锁在 ART 的锁上：三个动作缺一不可

在 fork 出来的子进程里直接调 `DumpHeap`，它会卡在三个不同的地方。
逐项关掉开关做过**对照实验**（`flags` 是位掩码，见 `OomSnapshotNative.Flags`）：

| flags | ① 父进程 SuspendAll | ② 子进程 patch SuspendAll | ③ 子进程 patch GC | 结果 |
|---|---|---|---|---|
| `0x04` | – | – | ✅ | 卡在 `InternTable::VisitRoots` → `Mutex::ExclusiveLock`，超时 |
| `0x06` | – | ✅ | ✅ | 卡在 `SuspendAll`，超时 |
| `0x05` | ✅ | – | ✅ | 卡在 GC 等待（`WaitForGcToCompleteLocked`），超时 |
| **`0x07`** | ✅ | ✅ | ✅ | **成功** |

为什么是这三件事：

1. **父进程必须先 `SuspendAll`**：fork 那一刻子进程继承的 `ThreadList` 写着"这些线程都存在"，
   但子进程里**它们并不存在**。父进程若先挂起所有线程，子进程继承到的锁状态就是"无人持有"。
   调用的是 `art::ScopedSuspendAll::ScopedSuspendAll(const char*, bool)`（`_ZN3art16ScopedSuspendAllC2EPKcb`）——
   它**内部自己取** `Runtime::instance_ → thread_list_`，不读 `this`，所以传一个 64 字节的哑对象就行。
2. **子进程里 `ThreadList::SuspendAll/ResumeAll` 改成 `ret`**：否则它会去挂起一批不存在的线程，永久等下去。
3. **子进程里禁止 GC**：`DumpHeap` 会走 GC 临界区，而"等一次 GC 完成"在子进程里永远不会发生。

> ⚠️ **ABI 坑（实测 SEGV）**：不要直接调 `ThreadList::SuspendAll` —— 它是成员函数，
> 通过普通函数指针调用会把字符串当 `this` 传，直接 `SIG_ACCERR` 崩在 `SuspendAllInternal`。
> 必须用上面那个**不读 `this`** 的 `ScopedSuspendAll` 构造函数。

### 2.3 子进程只能做 async-signal-safe 的事

POSIX 规定 `fork` 之后子进程只应调用 async-signal-safe 函数（否则可能死锁在父进程 fork 时正持有的锁上）。
所以子进程侧：

- 日志自己写（`write(STDERR_FILENO)` + 手写 `append_str/append_int/append_hex16`），
  **不用** `snprintf` / `__android_log_print` / `strerror`（它们可能拿 liblog / locale 的锁）；
- `_exit(0)` 而**不是** `exit`（后者会跑 atexit / flush stdio 的锁）；
- 输出 fd 在**父进程**先 `open` 好，子进程继承；
- 子进程 `setpgid(0,0)`，且**刻意不设 `PR_SET_PDEATHSIG`** —— 设了就是"父死子必死"，
  而 OOM 时父进程很可能马上被系统杀掉，子进程必须能活下来把快照写完。

> 诚实说明：调 `DumpHeap` 本身就是**违反**"只用 async-signal-safe"的（KOOM、Perfetto 也一样）。
> 它之所以能用，是因为 ①②③ 把子进程里那些"父进程可能在 fork 时正持有"的 ART 锁，
> 要么变成空操作、要么变成无人持有。这是**用约束换来的**，不是这条规则不适用。

---

## 3. 踩过的坑（都是静默错误，都已修复并留档）

这一节的价值比"它能跑"更高 —— 每条都是**实测撞出来的**，且都不是编译期能发现的。

### 3.1 GNU_HASH 解析漏了 bloom filter → 找不到符号时崩掉**父进程**

`GNU_HASH` 的表头不是 `[nbuckets][symoffset][buckets...]`，中间还夹着
**bloom filter（`Elf64_Xword[bloom_size]`）**。第一版把 bloom 的字当 bucket 读，
算出"符号总数 = **4,026,643,504**"（真实是 **5993**）。

后果分两级，第二级致命：

- ① 好符号**仍能命中**（命中即 return，走不到表尾）—— 所以 `probe()` 看起来一切正常；
- ② 一旦某个名字**没找到**，扫描就冲过 `.dynsym` 末尾解引用假地址，
  在 `phdr_cb` 的 `strcmp` 上 **SIGSEGV，整个父进程崩掉**。

实测就是这样崩的：`probe` 全绿（7 个符号全命中），而 `forkDump` 里一个**拼错的符号名**
把 App 带走 —— tombstone 显示 `SIGSEGV / SEGV_ACCERR in __strcmp_aarch64` ← `phdr_cb`。

**修复**（两道闸门，缺一不可）：

1. 按 `bloom_size` 正确算出 buckets 的偏移；32 位 ABI 下 bloom 元素是 4 字节，
   故未实现 32 位时**直接早退返回 0**（干净降级，而不是算出垃圾）。
2. 更根本的一道：**用 `.dynsym` 所在 `PT_LOAD` 的段大小算索引上界**
   （`k * sizeof(Sym) < seg_size`）—— 这是"表物理上装得下多少项"，
   在读任何一项**之前**就夹死。`DT_STRSZ` 校验保留为第二道（它挡的是"偏移合法但越界"）。

> 这条的教训值得单拎出来：**"找不到就走查表末尾"必须被硬约束住**。
> 一个"找不到"的路径能崩溃整个进程，而它在正常设备上（符号都在）永远不会被走到 ——
> 属于典型的"测试全绿、线上必崩"。

### 3.2 「预防性快照」把「真 OOM 现场」挤掉了

第一版 `最小间隔` 节流对两种触发一视同仁。实测：手动拍了一张（预防性），
**14 秒后真的 OOM 了**，结果被"最小间隔 300s"挡住 —— **最该留下的那一次现场被丢了**。

**修复**：区分语义。

- **真 OOM（`REAL_OOM`）→ 无视最小间隔**：它是更强的信号，
  任何此前的预防性快照都不能成为"不拍它"的理由；
- **预防性（`WATERMARK`，非手动）→ 受节流**：它是"有总比没有好"的补充，越级抖动时连拍只白占磁盘；
- **并发闸门（`running` CAS）对两者都生效**：绝不允许两次 dump 并行
  （会同时挂起父进程两次、抢磁盘 IO）。**间隔可以放宽，互斥不能。**

配套：磁盘保留 2 份时，**真 OOM 快照优先**（`cleanupOldDumps(protect=)`），
否则两次水位快照就能把它挤出去。

### 3.3 取证任务在共享泳道里被**饿死**

第一版把 dump 提交到 `ThreadPools.background`（`coreSize=1`）。页面按钮提交一个"等结果"的任务后占住了那个
线程，而 OOM 取证排在它**后面** —— 于是：`受理=true`（闸门过了），但 `doSnapshot` **一行日志都没打**。

后果极具迷惑性：日志显示"已受理"，现场却没有文件，**像 dump 失败又像没执行**。

**修复**：OOM 取证起**专用线程**，不进共享池。理由不是洁癖：
共享池可能正忙（一次 OOM 常伴随多线程连环抛，大家都在抢池子）、
优先级是 `LOWEST`（取证要的是"尽快落盘"，不是"让路"）；
最坏情况池子被占满且全阻塞 —— 那取证就永远发不出去。

### 3.4 在 OOM 现场**解析** hprof → 解析器自己 OOM

第一版在 `doSnapshot` 里顺手就把 hprof 解析了。真 OOM 实测：

```
E OomSnapshot: 解析 OOM 快照失败
⚠️ 未解析：OutOfMemoryError: Failed to allocate a 1048592 byte allocation
           with 92176 free bytes and 90KB until OOM
```

也就是说**解析器自己 OOM 了** —— 此刻父进程的堆还是满的（255.9/256MB），
而解析 285MB 的 hprof 要建几万条类名/字符串映射，这个分配必然失败。

更值得警惕的是它的**腐蚀性**：一次失败无关紧要，但每来一次 OOM 就在"已经很紧张"的堆上再砸一次大分配，
**等于让观测行为加重被观测的问题**。

**修复**：OOM 路径上**只落盘、不解析**；解析挪到"内存已经恢复"的时刻
（页面的「解析最近快照」按钮 → `OomSnapshotMonitor.analyze()`）。
这也和本仓库 `HeapDumpGate` 的纪律一致：**重活必须在显式动作下发生**。

顺带修掉一个"解析上限"的死结：默认上限 64MB，而 OOM 快照实测 285MB ——
固定用它等于"最该看的那份文件永远看不了"。
现在默认入口行为**不变**，只有明确知道"文件天生就大"的 OOM 路径传
`MAX_ANALYZE_BYTES_FOR_OOM`（512MB）。

### 3.5 其它修复

| 问题 | 后果 | 修复 |
|---|---|---|
| `patch_entry_to_ret` 写完不回读 | 补丁没打上 → 子进程死锁 → 只看到 `timeout_sigkilled`，和"ART 本来就卡"**长得一模一样** | 写后**回读比对**，不一致即失败 |
| 子进程忽略 patch 返回值、恒 `_exit(0)` | 补丁失败无法与超时区分 | 失败 `_exit(10)` → 父进程报 **`child_patch_failed`** |
| 子进程日志走 stderr | Android 上 stderr 通常不可见，父子间无通信渠道 | 关键事件用 exit code 回传 |
| `timeout_ms <= 0` | deadline 立刻到期 → 一次都不轮询 → 直接 SIGKILL + 删文件 | 入口拒绝 `bad_timeout_ms` |
| Kotlin 侧 `toULongOrNull(16)` 不接受 `0x` 前缀 | 每个地址都解析成 0 → 页面同时显示"可用 ✅"和"7 个符号全缺"，**自相矛盾** | 先 `removePrefix("0x")`；并在描述里显式指出这种矛盾是本地 bug 而非设备问题 |
| `dumpSym` 恒打印候选表第 0 条 | 命中其它候选时报错签名 | 记录实际命中的那一条 |
| 解析上限 64MB vs OOM 快照 285MB | 见 3.4 | 见 3.4 |

---

## 4. 代码结构

遵循本仓库的既有约定：**一个关注点一个 `.so`**，JNI 声明层在 ASCII 包，业务逻辑在中文包。

| 层 | 文件 | 职责 |
|---|---|---|
| native | [`app/src/main/cpp/oom_snapshot.cpp`](../app/src/main/cpp/oom_snapshot.cpp) | ELF 符号解析、挂起、fork、子进程 patch、有界等待 |
| 构建 | [`app/src/main/cpp/CMakeLists.txt`](../app/src/main/cpp/CMakeLists.txt) | `add_library(oomsnapshot SHARED ...)` + `dl` |
| JNI 声明 | [`com/interview/oomdump/OomSnapshotNative.kt`](../app/src/main/java/com/interview/oomdump/OomSnapshotNative.kt) | `probe()` / `forkDump()` / `Flags`，加载失败即降级 |
| 业务 | [`com/interview/内存/OomSnapshotMonitor.kt`](../app/src/main/java/com/interview/内存/OomSnapshotMonitor.kt) | 触发、去重闸门、落盘、清理、上报、事后解析 |
| 演示 | [`com/interview/内存/MemoryLabActivity.kt`](../app/src/main/java/com/interview/内存/MemoryLabActivity.kt) | 「OOM 现场取证」按钮组 |

**为什么单独一个 `.so`**：它动的是 ART 的运行时同步原语（`SuspendAll` / GC 临界区），
**用错会冻结整个 App**。失败的后果与 `memtrace`（采样偏差）完全不同量级，
所以必须能**独立降级** —— 不能和别的关注点共用一个库。

**为什么 JNI 声明类在 ASCII 包**：`Java_com_interview_内存_...` 里中文包段没有合法的导出符号
（ART 把非 ASCII 字节转义成 `_0XX`，与 clang 导出的原始 UTF-8 字节永远对不上）。
这条约束与 `memtrace` / `anrsignal` 一致，那次 `UnsatisfiedLinkError` 留档在
[`app/proguard-rules.pro`](../app/proguard-rules.pro) 第 3 节；本库的 `-keep` 也加在同一处。

### 用法（线上怎么接）

```kotlin
// 安装（装 OOM 通道 + 可选的水位预防通道）
OomSnapshotMonitor.install(allowWatermark = true)

// 关键：在**每一个** catch(OutOfMemoryError) 里都调一次
try { ... } catch (e: OutOfMemoryError) {
    OomSnapshotMonitor.onOutOfMemoryError(context, e, "sceneName")  // 不阻塞
    throw e   // 取证不等于吞掉异常
}
```

> ⚠️ 为什么不能只挂全局 `UncaughtExceptionHandler`：业务代码**通常自己 catch 了 OOM**
> （本仓库 [`com/interview/thread.ThreadDefense`](../app/src/main/java/com/interview/thread/ThreadDefense.kt) 就是），
> 只在全局 handler 里埋点，线上看到的 OOM 率会**严重偏低**。

---

## 5. 能力边界（诚实标注，别拿它当"万能 OOM 方案"）

| 边界 | 说明 |
|---|---|
| **只覆盖 Java 堆 OOM** | native OOM 不抛 Java 异常，本通道抓不到 |
| **只在 Android 12 / arm64 实测过** | 其它 ROM/版本**必须先看 `probe()`** 再下结论 |
| **依赖 libart 具体符号** | `DumpHeap` 签名随 ART 版本变（本机命中的是 `void DumpHeap(const char*, int, bool)`）；候选表 + 干净失败，绝不静默 |
| **patch 是 arm64 专属** | 把入口写成 `0xD65F03C0`（`ret`）；其它 ABI 必须**降级**而不是硬来（32 位已在解析层早退） |
| **依赖 COW** | 冻结语义靠 fork 的页表副本；语义已验证（`0xAB`/`0xCD` 实验） |
| **子进程调了非 async-signal-safe 的 ART 代码** | 与 KOOM / Perfetto 同样的取舍，靠"三件事"把风险锁住（见 §2.3） |
| **真 OOM 时 fork 可能 ENOMEM** | 页表 + 子进程写缓冲都要内存；这正是**水位预触发通道**存在的原因 —— 给"真拍不出来的那一次"留个参照 |
| **磁盘成本高** | 实测单份 **285MB**，保留 2 份 ≈ **570MB**；生产上应"上报成功后立即删"，本仓库留 2 份是为了能对比两次 OOM |
| **没有引用链** | 端内只有"类 → 数量/字节"（直方图）。要看"谁持有它"必须用 shark/MAT 打开支配树 |
| **没有分配栈信息**（= 打开后没有 trace） | 实测：本仓库产出的 hprof 里 `STACK_FRAME = 0`，42 万个对象的 `stack_trace_serial` **全是 0**。原因见下节 —— 这是 **ART 的默认行为**，不是本功能的缺陷 |
| **固定符号名逐个 `strcmp`** | 换 ROM 后若符号名变了，走"符号未命中"干净失败，不会 hang |

### 5.1 为什么 hprof 打开后"只有内存信息、没有 trace"

用 Android Studio 打开会看到只有类/实例/字节数、没有分配调用栈。**这不是本方案的问题**，
是 ART 这几年的既定行为 —— 三条证据：

1. **官方 `Debug.dumpHprofData()` 的结果一模一样**（本机 A/B 实测）：

   | 产出方式 | STACK_FRAME | 有分配栈的对象 |
   |---|---|---|
   | 本方案（fork 子进程 + `art::hprof::DumpHeap`） | **0** | 0 / 428,389 |
   | `Debug.dumpHprofData()`（官方，同机同版本） | **0** | 0 / 425,247 |

   也就是说：换成官方 API，打开后**同样没有 trace**。如果这是本功能的缺陷，官方也一起"坏"了。

2. **根因在 AOSP 源码**（`art/runtime/hprof/hprof.cc`，android12-release，第 444-452 行）：

   ```cpp
   void Dump()
     REQUIRES(Locks::mutator_lock_) ... {
     {
       MutexLock mu(Thread::Current(), *Locks::alloc_tracker_lock_);
       if (Runtime::Current()->GetHeap()->IsAllocTrackingEnabled()) {   // ← 闸门在这里
         PopulateAllocationTrackingTraces();
       }
     }
     ...
   ```

   分配栈只有在**分配跟踪（allocation tracking）已被开启**时才会被写进文件。
   否则 `LookupStackTraceSerialNumber()` 一律返回 `kHprofNullStackTrace = 0`
   （同一文件第 156、627-633 行），落盘就是"每个对象都没有栈"。

3. **这个开关不受 dump 的参数控制**：`DumpHeap(filename, fd, direct_to_ddms)` 的
   `direct_to_ddms` **只决定输出走 DDMS socket 还是走文件 fd**（`DumpToDdmsDirect`），
   与"要不要记录分配栈"无关。所以**无论怎么调用 `DumpHeap` 都拿不到 trace**。

**为什么这个选择是对的（不只是"能用就行"）**：分配跟踪一开，
ART 会给**每一次分配**记录调用栈（`AllocRecordObjectMap`，默认上限
`kDefaultNumAllocRecords = 512*1024` 条记录、`kMaxSupportedStackDepth = 128` 帧，
见 `allocation_record.h`），并给所有快速分配入口点插桩（`InstrumentQuickAllocEntryPoints`）。
这对一个 App 是**长期的性能与内存惩罚**，代价远大于一次事后分析的便利。
所以 AOSP 默认关闭；而 Google 官方做 OOM 时刻快照那套（Perfetto `android.java_hprof`）
也**只 dump 堆内容、不 dump 分配栈** —— 我们的取舍与它一致。

**那"这个对象是谁分配的"到底怎么查**（按可靠性排序）：

| 手段 | 前提 | 说明 |
|---|---|---|
| 端内**两次直方图差分** | 无 | 本仓库已有（「压力前后差分」按钮）。定位"哪类对象在涨"，**不需要 trace** |
| shark / MAT 看**支配树 + 引用链** | 无 | 定位"谁持有它"。这是分析泄漏的主路径，也不需要 trace |
| 借助带分配跟踪的工具抓 dump | **得先开分配跟踪** | 只有先开启分配跟踪、再 dump，文件里才会带栈。AS Memory Profiler 抓的 dump 通常带（它自己会去开）；但受 512K 条记录上限约束，超出就丢最老的，所以**它也未必查得到** |
| 想在 App 内自带 | 需 `VMDebug.setAllocTrackerStackDepth()` 等内部 API | 可行但**代价写清楚**：长期插桩 + 每对象记录栈。不建议为"偶尔的 OOM 取证"常开 |

> ⚠️ 上面第三行里"AS 会自己去开分配跟踪"是**通常做法**，我**未在本次实测中直接验证**
>    （本次只硬验证了"官方 `dumpHprofData` 与我们的文件都没有栈"这一条）。
>    要确认请自行在 AS 里对同一进程抓一次，再做同样的 `STACK_FRAME` 计数。

**一句话**：fork 快照解决的是"**OOM 那一刻堆里有什么、各占多少**"（保真度与低停顿）；
"**谁分配的**"是另一个问题，靠差分与支配树解决，两者不冲突、也不该混为一谈。

---

## 6. 面试如何讲（可背诵版）

**问：线上 OOM 现场怎么抓？**

> 分两层。第一层是**别漏记**：OOM 通常被业务代码自己 catch，所以我在每个
> `catch(OutOfMemoryError)` 里都埋点，而不是只挂全局 handler —— 只挂全局的话，
> 线上 OOM 率会严重偏低。

> 第二层是**怎么把现场留下来**。常规 `dumpHprofData` 是 stop-the-world，
> 堆越大停得越久，而 OOM 现场恰恰是堆最满的时候，用它在生产上是不可接受的。
> 我的做法是 **fork 一个子进程**：子进程只复制页表（COW），所以
> **父进程只被挂起毫秒级**（实测 0～4ms），然后子进程去写 hprof，
> 写出来的就是 **fork 那一瞬间的堆**。父进程不需要先腾出内存 ——
> 这是它和"本进程 dump"最本质的区别。

**问：fork 之后子进程直接 dump 行不行？**

> 不行，会死锁，而且我实测过**卡在三个不同的地方**。要同时做三件事：
> ① fork 前在父进程 `SuspendAll`，让子进程继承到的锁状态是"无人持有"；
> ② 子进程里把 `ThreadList::SuspendAll/ResumeAll` 改成 `ret`，否则它会去挂起一批不存在的线程；
> ③ 子进程里禁止 GC，因为"等一次 GC 完成"在子进程里永远不会发生。
> 我是逐项关掉 flags 做对照实验验证的：少任何一个都会超时，只有三个全开才成功。

**问：`DumpHeap` 是 hidden 符号，怎么拿到地址？**

> `dlopen` 会被 linker namespace 挡掉（`classloader-namespace`），
> 留未定义符号也会 `UnsatisfiedLinkError`。所以我自己解析 ELF：
> `dl_iterate_phdr` 拿 load bias，再走 `PT_DYNAMIC` → `DT_SYMTAB/DT_STRTAB/DT_GNU_HASH`。
> 这里我踩了一个很深的坑：GNU_HASH 表头中间夹着 **bloom filter**，
> 我第一版漏了它，把符号总数算成 40 亿。
> 后果特别阴险 —— 符号都在的时候一切正常，**一旦某个名字没找到，扫描就冲过 `.dynsym` 末尾，
> 在 `strcmp` 上把整个父进程崩掉**。所以我最后的做法是**用段大小算索引上界**，
> 在读任何一项之前就夹死，而不是靠"找不到就 break"。

**问：怎么证明快照是真的、不是半成品？**

> 两步。**冻结语义**：我放了一个 1MB 填 `0xAB` 的数组，fork 后让父进程线程
> 200ms 后覆写成 `0xCD`，hprof 里读出来是 1,048,576 个 `0xAB`、0 个 `0xCD`。
> **结构完整性**：不看"文件有多大"，而是让 **shark**（第三方解析器）
> 逐记录读到 EOF —— 285MB / 788,588 条记录，无错。

**问：这个方案的代价是什么？**

> 三条，我都会主动说：**磁盘**单份 285MB；**磁盘**上要配合"上报后立即删"；
> 还有一条更微妙的 —— **不能在 OOM 现场解析**。我实测过，
> 解析器自己会 OOM，因为父进程的堆还是满的。所以我把解析挪到内存恢复之后。
> 另外真 OOM 时 fork 本身可能 ENOMEM，所以才需要一条
> **水位 85% 的预防性快照**通道作为补充。
> 而且这两条通道的节流规则**不能一样** —— 我实测遇到过"预防性快照把真 OOM 现场挤掉"，
> 所以真 OOM 必须无视最小间隔。

---

## 7. 相关文档

- [`docs/jvm-oom-fork-forensics-research.md`](jvm-oom-fork-forensics-research.md) —— 技术考证：
  KOOM 源码、AOSP ART/bionic/crashpad 逐版本比对、Perfetto `android.java_hprof` 的同款实现、
  以及"未确证的点 + 必须自测的项"
- 线程滥用与治理：`app/src/main/java/com/interview/线程治理/线程滥用与治理.md`
