# Native 崩溃现场格式契约（v1）

> 本文件是**三方共用**的接口定义，任何一方改动都必须同步另外两方：
>
> | 角色 | 位置 | 职责 |
> |---|---|---|
> | 写入方 | `app/src/main/cpp/native_crash_handler.cpp` | 信号上下文里按本格式落盘（只允许 async-signal-safe 调用） |
> | 解析方 | `app/src/main/java/com/interview/稳定性监控/NativeCrashReport.kt` | 冷启动时读回、解析、映射成 `StabilityReporter.Event` |
> | 符号化 | `tools/native-mem-symbolize.sh` | **不需要改动** —— 脚本第 155 行的正则 `[A-Za-z0-9_.+-]+\.so\+0x[0-9a-f]+` 会自动抓取本格式里的所有帧 |

## 为什么是「行文本」而不是 protobuf / JSON

1. **信号处理器里不能分配内存。** JSON 序列化要建 map/string builder，protobuf 要 alloc —— 在
   `SIGSEGV` 上下文里调 `malloc` 是死锁与二次崩溃的头号来源。本格式只需要
   `write()` 一次循环 + 定长栈缓冲。
2. **要能被人在设备上直接看。** `adb shell run-as <pkg> cat files/native-crash/report-*.txt`
   就能读，不需要任何工具。protobuf 做不到这一点。
3. **沿用仓库既有约定。** `memtrace.cpp` 的归因报告同样是行文本 + `module+offset`，
   符号化脚本因此可以直接复用（见上表）。

## 逐行格式

文件按段落拼接，**空行与 `--- XXX ---` 行是段落分隔符**，解析方按状态机读取。

```
NCREPORT v1
pid=12345
tid=12346
signal=11 SIGSEGV
code=1 SEGV_MAPERR
fault_addr=0x0
thread_name=RenderThread
abort_message=Native crash from Lab
sdk=31
abi=arm64-v8a
android_release=12
uptime_ms=123456
version_name=1.0
version_code=1
process_name=com.example.myapplication

--- BACKTRACE #0 ---
  #00 0x0000007f12345678 libnative_crash.so+0x1a3c
  #01 0x0000007f12345600 libnative_crash.so+0x1a00

--- BACKTRACE #12347 ---
  #00 0x0000007f98765432 libart.so+0x2b1c

--- MAPS ---
7f0000000000-7f0000100000 r-xp 00000000 fd:00 1234 /system/lib64/libc.so

--- FD ---
0 r /dev/null
12 rw /data/user/0/com.example.myapplication/files/x

--- STATUS ---
Name:	com.example.myapplication
State:	S (sleeping)
Tgid:	12345
Pid:	12346
Threads:	18
SigQ:	0/18000
SigPnd:	0000000000000000
voluntary_ctxt_switches:	1234

--- CMDLINE ---
com.example.myapplication

--- END ---
```

### 字段说明

| 字段 | 说明 |
|---|---|
| `signal` | `si_signo` 数值 + 名字（`11 SIGSEGV` / `6 SIGABRT` / `7 SIGBUS` / `4 SIGILL` / `8 SIGFPE`） |
| `code` | `si_code` 数值 + 名字（`1 SEGV_MAPERR` / `2 SEGV_ACCERR` / `-6 SI_TKILL`）。**这是归因的关键**：`SEGV_MAPERR`（解引用了未映射地址，通常是空指针/野指针）与 `SEGV_ACCERR`（地址已映射但无权限，通常是越界写只读段）的修复方向完全不同 |
| `fault_addr` | `si_addr`。**必须单独占一行**：`si_addr == 0` 与 `si_addr` 落在 VMA 边缘是两种不同的 bug |
| `uptime_ms` | 用 `CLOCK_MONOTONIC`，**不用 `gettimeofday`** —— 前者是 vDSO 且不需要锁 |
| `time` | epoch millis（来自 Java 侧启动时缓存的 `System.currentTimeMillis() + elapsedRealtime` 偏移，见下） |

### 时间与版本字段（写入方实现要点）

**为什么不写 `time=`（epoch millis）**：在信号处理器里换算墙钟时间需要缓存
`System.currentTimeMillis() - elapsedRealtime()` 的偏移量（因为 `gettimeofday` 不是
async-signal-safe，`CLOCK_REALTIME` 的读取也要走 syscall 且有被 `settimeofday` 影响的风险）。
这个偏移在 Java 侧启动时算一次、存成一个**静态原子变量**是可接受的，但更省事的做法是：

- native 只写 **`uptime_ms`**（`CLOCK_MONOTONIC`，vDSO 快且无锁）；
- **上传时**由 Java 侧换算成墙钟：`epochMillis = uploadTimeBase + (uptimeMs - uploadUptimeBase)`。

这样解析方的 `NativeCrashReport` 拿到的 `uptime_ms` 是**原始值**，换算责任在
Java 侧（那里可以放心地调 `System.currentTimeMillis()`）。

**版本字段是符号化的前提**：`version_name` / `version_code` 决定该报告要配哪一份
`mapping.txt` 与哪一份未 strip 的 `.so`（符号化脚本用 `--libdir` 指定）。
少了它们，服务端拿到一堆 `libxxx.so+0x1234` 却不知道该用哪个构建的符号表 ——
**对不上的符号表比解析不出来更危险**（会给出看起来合理的错误函数名）。
`version_name`/`version_code` 由 native 侧的 `install()` 参数从 Java 传进来，
不依赖 JNI 调用（见「禁止」清单）。

### 关于 `process_name` 与 `thread_name`

- `process_name`：从 `/proc/self/cmdline` 读（`open`+`read`，async-signal-safe），
  用于识别**多进程 App** 里崩的是哪个进程（`:remote` / `:push` 等）。
- `thread_name`：崩溃线程名，从 `/proc/self/task/<tid>/comm` 读。
  ⚠️ `pthread_getname_np` 也可以，但它不是 async-signal-safe 的（内部可能加锁），
  所以走 `/proc` 路径。

### 段落的可缺失性（**解析方必须容忍缺失**）

| 段落 | 什么时候会缺 | 解析方的正确行为 |
|---|---|---|
| `--- BACKTRACE #<crashed tid> ---` | 极少（崩溃线程自己的栈总是优先抓） | 缺失 → 报告标 `⚠️无崩溃线程栈` |
| `--- BACKTRACE #<其他 tid> ---` | 常见。超时/线程已在退出/被阻塞在不可中断状态 | **不报警告**，只记「抓到 N/M 个线程」 |
| `--- MAPS ---` | 极少 | 缺失 → 报告标 `⚠️无 maps，无法判断 fault_addr 落在哪个区间` |
| `--- FD ---` | 常见（遍历 `/proc/self/fd` 本身要出 syscall，且限时） | 静默 |
| `--- STATUS ---` | 极少 | 静默 |
| `--- END ---` | 说明**报告被截断了**（进程在写完之前被杀，或落盘缓冲区满） | 必须标 `⚠️报告不完整`，不要当成完整报告解析 |

## 两条硬约束（写入方必须遵守）

### 1. 信号处理器内只允许 async-signal-safe 调用

允许：`write()` `open()` `close()` `readlinkat()` `fstat()` `snprintf`(无 locale) `_exit()`
`clock_gettime()` `getpid()` `gettid()` `sigaction()` `tgkill()`（**按内核 tid 发信号**；`pthread_kill` 收的是 pthread_t 句柄，见下文 ③）
`_Unwind_Backtrace`（本仓库 `memtrace.cpp:271` 已在用，实测可行）

**禁止**：`malloc`/`free`、`printf`/`fopen`、`std::string`/`std::vector`、任何加锁操作、
`dlopen`/`dladdr`（会重入动态链接器锁）、`Local::GetEnv`（JNI 调用）、任何 ART 调用。

> ⚠️ **落盘缓冲区必须是静态预分配的定长数组**，不能用 `new`/`malloc`。
> 本设计用 `static char g_buf[kBufSize]`（1 MB，`.bss` 段，不与崩溃现场的内存争抢）。

### 2. 抓栈不能在本线程解符号

线上 APK 里没有 `.symtab`（Release 只带 `.dynsym`），`dladdr` 又不可用（见上）。
所以报告只携带 **`module+offset`**：

- `module` = 该地址所属的 VMA 的路径 `basename`（从 `/proc/self/maps` 查，**纯内存操作**）；
- `offset` = `addr - vma.start`。

符号化在事后由 `tools/native-mem-symbolize.sh` 完成（它已经支持这个格式）。
这与平台自身的做法一致（`ndk-stack`、perfetto 的 symbolize 同理）。

## 跨线程抓栈的机制（写入方实现要点）

**不给「其他线程」做远端解栈**（`unw_init_remote` + `ptrace` 那一套：慢、需要暂停、
在信号上下文里不可用）。改用 **Crashpad 的做法**：

```
崩溃线程（handler 内）
  ├─ ① 先抓自己的栈（最重要，必须在任何耗时操作之前）
  ├─ ② 遍历 /proc/self/task/* 拿到所有 tid（getdents64 raw syscall）
  ├─ ③ 对每个 tid: tgkill(pid, tid, SIGRTMIN+3)   ← ⚠️ 必须 tgkill，见下
  ├─ ④ 在每个被唤醒线程的 handler 里：抓自己的栈 → 写进"自己 tid 的槽位"
  └─ ⑤ 崩溃线程等待（有限时间）→ 汇总
```

**为什么用 `SIGRTMIN+3` 而不是 `SIGUSR1`：** 实时信号可以排队，不会像普通信号那样被
合并丢失；且社区约定 `SIGRTMIN+3` 留给崩溃处理（`SIGRTMIN`/`+1`/`+2` 被 ART 与
profiler 占用）。

### ⚠️⚠️ ③ 必须用 `tgkill`，不能用 `pthread_kill(tid, …)`（实测踩坑）

`pthread_kill` 的第一个参数是 **`pthread_t` 句柄**（bionic 里是 `unsigned long`，
指向线程内部结构），**不是内核 tid**。把 tid 传进去 = 无效句柄，bionic 会在
`__pthread_internal_find` 里直接 `abort()` —— 也就是**在我们的 handler 内部**再炸一次，
防重入守卫会把控制权立刻交回系统，**报告永远落不了盘**（表现为：进程死、tombstone 有、
报告目录空）。且 `pid_t`(int) → `unsigned long` 是**隐式转换**，编译期零提示。
按内核 tid 定向发信号的正确接口是 `tgkill(pid, tid, sig)`（raw syscall，
async-signal-safe；本仓库 `anr_signal_hook.cpp` 用的也是它）。

### 槽位：**不用 TLS**，用「tid → 槽位」开放寻址表（实测踩坑）

直觉做法是 `static __thread ThreadCapture t_slot;`（无锁、无 malloc）。**在 Android 上
两条路都堵死**（真机 Redmi K40 / Android 12 与 API 36 arm64 模拟器均复现）：

| 方案 | 结果 |
|---|---|
| `-ftls-model=initial-exec`（静态 TLS） | ❌ **dlopen 直接失败**：`TLS symbol "(null)" in dlopened … using IE access model` —— bionic 不允许被 dlopen 的 .so 引用它自己的**非导出** static TLS 符号（文档没写，logcat 试出来的） |
| 动态模型 / emulated TLS（默认） | ⚠️ 能加载，但每次访问走 `__emutls_get_address` → **内部拿 mutex** → 在崩溃现场可能自己锁死自己（点崩溃按钮后 App 假死） |

⇒ 结论：**信号处理器里根本不该碰 TLS**。实际实现是全局槽位表
`g_slots[256]`，每个线程在自己的 handler 里按 `tid` 定位/占用槽位
（`open("/proc/self/task/<tid>/stat")` 取 `processor` 字段做哈希分散 +
线性探测 + **CAS 抢占**，避免两个线程抢到同一个槽位把栈混在一起）：

```c
struct Slot {
  int      tid;           // 原子 CAS 抢占：0=空
  int      count;
  uintptr_t frames[64];
};
static Slot g_slots[256];   // .bss，零 TLS、零锁
```

⚠️ 代价（如实记录）：每个被采样的线程多一次 `open`+`read`（~10µs 量级，
只在崩溃路径发生一次）；表满（>256 线程）时**放弃登记而不是覆盖**
（覆盖会产出"混在一起的错栈"，比少抓一个线程糟），并在报告的
`--- NOTE ---` 段写明丢了几个。

### 帧偏移的口径（实测踩坑）

`module+offset` 里的 offset 必须是**后备文件内偏移**：
`(addr - vma.start) + maps第4列的 file offset`，而不是"段内偏移"。
真机对照：不加 file offset 时我们输出 `libc.so+0x43a28`，tombstone 对同一帧是
`abort+168 pc 0x8aa28`，差 0x47000（= 段的 file offset）——
**偏移算错的后果不是解不出来，而是解出一个看起来合理的错误函数**。

⚠️ API 31 实测：App 自己的 .so 以 `r-xp 00610000 … base.apk` 形态映射（后备文件是
**APK**），所以模块名只能是 `base.apk`、offset 是 **APK 内偏移**。
`.so 内偏移 = 报告 offset - 段的 APK 偏移`（后者就在报告的 `--- MAPS ---` 段里），
未 strip 的 .so 从构建产物取。debuggerd 能直接写 `base.apk!libxxx.so` 是因为它
在 fork 出的 crash_dump 里解析了 ELF —— 我们刻意不在信号处理器里做这件事。
