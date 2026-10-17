# JVM OOM 现场取证（fork 子进程 dump 快照）技术考证

考证时间：2026-10。所有结论均带来源；查不到确证的点集中在最后一节。

约定：`_ZN...` 为 Itanium ABI mangled 名，可用 `llvm-cxxfilt` 还原。

---

## 1. 快手 KOOM 的 Java OOM 监控怎么做

### 结论要点

- KOOM 把「fork dump」核心抽成独立模块 **`koom-fast-dump`**（Java 监控/分析在 `koom-java-leak`）。
- 流程：`Suspend VM → fork → Resume VM(父) → 子进程 dump hprof → 子进程 _exit`。
  父进程只暂停 **fork 那一瞬间**，README 称冻结从 ~20s 降到 <20ms。
- 子进程里 **不是自己写 hprof writer**，而是 `dlsym` 调 **libart 的 `art::hprof::DumpHeap`**。
- 没有 `-Wl,--wrap`。绕开 fork 后 `SuspendAll` 卡死的做法是：
  1. **fork 前在父进程先 `SuspendAll`**（这样子进程继承到的 `ThreadList` 认为所有线程已挂起）；
  2. 用 dlsym 拿到的 **全局锁指针手动加/解锁**（`Locks::mutator_lock_` / `Locks::thread_list_lock_`），避免子进程里锁被「已不存在的线程」持有而死锁；
  3. 本地重实现 `ScopedSuspendAll` / `ScopedGCCriticalSection` 这类小类（用超尺寸占位 buffer），只把 ctor/dtor **函数指针**指向 libart。
- 超时：子进程 `alarm(60)`（SIGALRM 默认动作 = 杀进程）；父进程 `waitpid` 并处理 `EINTR`。**父进程不主动 kill**。

### 证据

Java 入口（`koom-fast-dump/src/main/java/com/kwai/koom/fastdump/ForkJvmHeapDumper.java`）：

```java
boolean dumpRes = forkDump(path, true);   // waitPid=true
private native void nativeInit();
private native boolean forkDump(@NonNull String path, boolean waitPid);
```

JNI 桥（`koom-fast-dump/src/main/cpp/native_bridge.cpp`）：

```cpp
auto pid = HprofDump::GetInstance().SuspendAndFork();
if (pid == 0) {
  HprofDump::GetInstance().DumpHeap(file_name.c_str());
  FastExit(0);                     // _exit(0)
} else if (pid > 0) {
  dump_success = JNI_TRUE == wait_pid
      ? HprofDump::GetInstance().ResumeAndWait(pid)
      : HprofDump::GetInstance().Resume();
}
```

`FastExit`（`include/hprof_dump.h`）：`inline void FastExit(int c) { _exit(c); }` —— 明确用 `_exit` 跳过 atexit / C++ 析构。

fork / 等待 / 超时（`koom-fast-dump/src/main/cpp/hprof_dump_impl.cpp`，2025 重构版）：

```cpp
pid_t HprofDumpImpl::SuspendAndFork() {
  if (!Suspend()) return -1;
  pid_t pid = Fork();
  if (pid == 0) {
    alarm(60);                              // 子进程超时自杀
    prctl(PR_SET_NAME, "forked-dump-process");
  }
  return pid;
}
bool HprofDumpImpl::ResumeAndWait(pid_t pid) {
  if (!Resume()) return false;
  for (;;) {
    if (waitpid(pid, &status, 0) != -1) {
      if (!WIFEXITED(status)) { ALOGE(...); return false; }
      return true;
    }
    if (errno == EINTR) continue;           // 被信号打断则重试
    return false;
  }
}
```

**ART 调用方式是 dlsym，不是自带 hprof writer**。三套实现按 API 分派
（`hprof_dump_impl.cpp::HprofDumpImpl::GetInstance`）：

```cpp
if (android_api < __ANDROID_API_R__)       return HprofDumpBelowRImpl::GetInstance();  // API<30
else if (android_api < __ANDROID_API_V__)  return HprofDumpBelowVImpl::GetInstance();  // 30..34
else                                       return HprofDumpVImpl::GetInstance();       // >=35
```

**(a) API < 30（`hprof_dump_below_r_impl.cpp`）** —— 直接用 ART 调试器挂起：

```cpp
suspend_vm_fnc_ = dlsym(handle, "_ZN3art3Dbg9SuspendVMEv");   // art::Dbg::SuspendVM()
resume_vm_fnc_  = dlsym(handle, "_ZN3art3Dbg8ResumeVMEv");    // art::Dbg::ResumeVM()
dump_heap_func_ = dlsym(handle, "_ZN3art5hprof8DumpHeapEPKcib");
```

**(b) API 30–34（`hprof_dump_below_v_impl.cpp`）** —— 手动 SuspendAll + 解 mutator 锁：

```cpp
ssa_constructor_fnc_ = dlsym(handle, "_ZN3art16ScopedSuspendAllC1EPKcb");
ssa_destructor_fnc_  = dlsym(handle, "_ZN3art16ScopedSuspendAllD1Ev");
sgc_constructor_fnc_ = dlsym(handle,
    "_ZN3art2gc23ScopedGCCriticalSectionC1EPNS_6ThreadENS0_7GcCauseENS0_13CollectorTypeE");
sgc_destructor_fnc_  = dlsym(handle, "_ZN3art2gc23ScopedGCCriticalSectionD1Ev");
mutator_lock_ptr_    = dlsym(handle, "_ZN3art5Locks13mutator_lock_E");
exclusive_lock_fnc_  = dlsym(handle, "_ZN3art17ReaderWriterMutex13ExclusiveLockEPNS_6ThreadE");
exclusive_unlock_fnc_= dlsym(handle, "_ZN3art17ReaderWriterMutex15ExclusiveUnlockEPNS_6ThreadE");
dump_heap_func_      = dlsym(handle, "_ZN3art5hprof8DumpHeapEPKcib");

bool HprofDumpBelowVImpl::Suspend() {
  void *self = __get_tls()[TLS_SLOT_ART_THREAD_SELF];       // <bionic/tls.h>
  sgc_constructor_fnc_(sgc_instance_.get(), self, kGcCauseHprof, kCollectorTypeHprof);
  ssa_constructor_fnc_(ssa_instance_.get(), LOG_TAG, true);
  exclusive_unlock_fnc_(*mutator_lock_ptr_, self);          // 关键：避免与子进程死锁
  sgc_destructor_fnc_(sgc_instance_.get());
  return true;
}
bool HprofDumpBelowVImpl::Resume() {
  void *self = __get_tls()[TLS_SLOT_ART_THREAD_SELF];
  exclusive_lock_fnc_(*mutator_lock_ptr_, self);
  ssa_destructor_fnc_(ssa_instance_.get());
  return true;
}
```

源码里的注释原话：`// avoid deadlock with child process`。

**(c) API ≥ 35（`hprof_dump_v_impl.cpp`）** —— 增加 `thread_list_lock_`，且 **fork 时持锁**：

```cpp
thread_list_lock_ptr_ = dlsym_elf(handle, "_ZN3art5Locks17thread_list_lock_E");
exclusive_lock_fnc_   = dlsym_elf(handle, "_ZN3art5Mutex13ExclusiveLockEPNS_6ThreadE");
exclusive_unlock_fnc_ = dlsym_elf(handle, "_ZN3art5Mutex15ExclusiveUnlockEPNS_6ThreadE");
dump_heap_func_       = dlsym_elf(handle, "_ZN3art5hprof8DumpHeapEPKcib");

pid_t HprofDumpVImpl::Fork() {                 // 持 thread_list_lock_ 再 fork
  void *self = __get_tls()[TLS_SLOT_ART_THREAD_SELF];
  MutexLock lk(self, thread_list_lock_ptr_);
  return HprofDumpImpl::Fork();
}
void HprofDumpVImpl::DumpHeap(const char* filename) {
  Resume();                                    // 注释：Resume before DumpHeap
  dump_heap_func_(filename, -1, false);
}
```

注意 `dlsym_elf`（不是普通 `::dlsym`）——见下一节。另有注释：
`// hprof_constructor_fnc_ symbol not exists on Android 14`，
以及 `defines.h` 里为 Android 15 手抄枚举：
`kGcCauseHprof = 15` / `kCollectorTypeHprof = 15`（注释说明旧系统上可能是 14/13…）。

### 「patch / wrap」问题的直接回答

- **没有** `-Wl,--wrap`、**没有**改 ART 源码、**没有**自带 hprof writer。
- 「替换实现」限定在这些**本地薄封装类**（`hprof_dump_v_impl.cpp`）：

```cpp
class ScopedSuspendAll {                       // 只做转发
 public:
  ScopedSuspendAll(const char *cause, bool long_suspend) {
    HprofDumpVImpl::GetInstance().ssa_constructor_fnc_(this, cause, long_suspend);
  }
  ~ScopedSuspendAll() { HprofDumpVImpl::GetInstance().ssa_destructor_fnc_(this); }
 private:
  char placeholder_[64] = {0};                 // Over size for device compatibility
};
// 同理 ScopedGCCriticalSection、MutexLock
```

- `MutexLock` 是**手动** `ExclusiveLock/Unlock` 一个 dlsym 出来的全局锁指针。
- `art::Thread*` 通过 `__get_tls()[TLS_SLOT_ART_THREAD_SELF]` 取（bionic TLS 槽位）。

### 符号解析：kwai-linker

`native_bridge.cpp` include `<kwai_linker/kwai_dlfcn.h>`；实现见
`koom-common/kwai-android-base/src/main/cpp/kwai_linker/{kwai_dlfcn.cpp,elf_reader.cpp}`。

- `DlFcn::dlsym`：Android N(24/25) 走**手写 ELF 解析**（因 `::dlsym` 受限），其它版本走 `::dlsym`。
- `DlFcn::dlsym_elf`：**总是**手写 ELF 解析，查找顺序
  **`.dynsym`（GNU/ELF hash）→ `.symtab` → `.gnu_debugdata`（LZMA 解压后再查）**：

```cpp
void *ElfReader::LookupSymbol(const char *symbol, ElfW(Addr) load_base, bool only_dynsym) {
  ElfW(Addr) sym_vaddr =
      has_gnu_hash_ ? LookupByGnuHash(symbol) : LookupByElfHash(symbol);
  if (sym_vaddr != 0) return reinterpret_cast<void *>(load_base + sym_vaddr);
  if (only_dynsym) return nullptr;
  for (int index = 0; index < symtab_ent_count_; index++) {   // .symtab
    if (ELF_ST_TYPE(symtab_[index].st_info) != STT_FUNC &&
        ELF_ST_TYPE(symtab_[index].st_info) != STT_OBJECT) continue;
    if (!strcmp(strtab_ + symtab_[index].st_name, symbol))
      return reinterpret_cast<void *>(load_base + symtab_[index].st_value);
  }
  std::string decompressed_data;
  if (DecGnuDebugdata(decompressed_data)) {                    // .gnu_debugdata (LZMA)
    ElfReader elf_reader(std::make_shared<MemoryElfWrapper>(decompressed_data));
    if (elf_reader.Init()) return elf_reader.LookupSymbol(symbol, load_base);
  }
  return nullptr;
}
```

这条「dynsym → symtab → gnu_debugdata」的降级链，正是下面第 2 节「符号是否可见」的工程答案。

来源：
- https://github.com/KwaiAppTeam/KOOM/blob/master/koom-fast-dump/README.md
- https://github.com/KwaiAppTeam/KOOM/blob/master/koom-fast-dump/src/main/cpp/native_bridge.cpp
- https://github.com/KwaiAppTeam/KOOM/blob/master/koom-fast-dump/src/main/cpp/hprof_dump_impl.cpp
- https://github.com/KwaiAppTeam/KOOM/blob/master/koom-fast-dump/src/main/cpp/hprof_dump_below_r_impl.cpp
- https://github.com/KwaiAppTeam/KOOM/blob/master/koom-fast-dump/src/main/cpp/hprof_dump_below_v_impl.cpp
- https://github.com/KwaiAppTeam/KOOM/blob/master/koom-fast-dump/src/main/cpp/hprof_dump_v_impl.cpp
- https://github.com/KwaiAppTeam/KOOM/blob/master/koom-common/kwai-android-base/src/main/cpp/kwai_linker/elf_reader.cpp

---

## 2. ART hprof dump 的对外入口符号，按版本

### 结论要点

- 定义在 `art::hprof`，**签名从 android11 到 main 一直没变**：
  `void DumpHeap(const char* filename, int fd, bool direct_to_ddms);`
  mangled = `_ZN3art5hprof8DumpHeapEPKcib`。
- 函数体也一直没变：内部 `ScopedGCCriticalSection(kGcCauseHprof,kCollectorTypeHprof)` +
  `ScopedSuspendAll(__FUNCTION__, true)` + `Hprof(...).Dump()`。
- **未查到**「Android 12/13/14/15 改成 `std::string` / `DumpHeap(std::string,...)`」这种签名变化。
  各 release 分支都是 `const char*, int, bool`。
- **可见性在 Android 15 变了**：`hprof.h` 从 `namespace art {` 改为
  `namespace art HIDDEN {`（android15-release 起），`HIDDEN` 在 release（`NDEBUG`）下 =
  `__attribute__((visibility("hidden")))`。
- 另外 libart.so 早已**去掉 `.strtab/.symtab`**（commit `c7f5eb76`，
  "Remove strip.keep_symbols from libart defaults"，2023-07-23），改为依赖 `.gnu_debugdata`。
- 因此：**不能假定所有 ROM 上 `::dlsym(libart, "_ZN3art5hprof8DumpHeapEPKcib")` 都能成功**。
  KOOM 的工程做法 = 手写 ELF 解析，依次查 dynsym / symtab / gnu_debugdata。

### 证据

定义（`runtime/hprof/hprof.h`）：各分支逐版本核对结果：

| ref | 声明 |
|---|---|
| android11-release | `namespace art {` … `void DumpHeap(const char* filename, int fd, bool direct_to_ddms);` |
| android12-release | 同上 |
| android13-release | 同上 |
| android14-release | 同上 |
| android15-release | **`namespace art HIDDEN {`**，签名同上 |
| main | `namespace art HIDDEN {`，签名同上 |

函数体（`runtime/hprof/hprof.cc`，android14-release 与 android15-release 完全一致）：

```cpp
void DumpHeap(const char* filename, int fd, bool direct_to_ddms) {
  CHECK(filename != nullptr);
  Thread* self = Thread::Current();
  gc::ScopedGCCriticalSection gcs(self, gc::kGcCauseHprof, gc::kCollectorTypeHprof);
  ScopedSuspendAll ssa(__FUNCTION__, true /* long suspend */);
  Hprof hprof(filename, fd, direct_to_ddms);
  hprof.Dump();
}
```

`HIDDEN` 定义（`libartbase/base/macros.h`）：

```cpp
#ifdef NDEBUG
#define HIDDEN __attribute__((visibility("hidden")))
...
#else
#define HIDDEN
```

引入该可见性属性的提交：`runtime/hprof/hprof.h` 的历史里，最早一条 2024-01-18 的
"Add visibility attributes in runtime/h*"（commit `b887d813e36296e45bdb350bdc8089b10a4466a3`）。

去符号表提交（`c7f5eb76`）：描述原文「With this change libart.so will no longer contain
`.strtab` and `.symtab` sections. Symbol debug info should still be available from
`.gnu_debugdata` section.」

来源：
- https://gitgud.io/aosp/platform/art/-/raw/android14-release/runtime/hprof/hprof.h
- https://gitgud.io/aosp/platform/art/-/raw/android15-release/runtime/hprof/hprof.h
- https://gitgud.io/aosp/platform/art/-/raw/android14-release/runtime/hprof/hprof.cc
- https://gitgud.io/aosp/platform/art/-/raw/main/libartbase/base/macros.h
- https://gitgud.io/aosp/platform/art/-/commit/c7f5eb7654a9689f03dc0f038ad802758ed24623
- https://android.googlesource.com/platform/art/+/master/runtime/hprof/hprof.cc

### 其它可用入口（同一族）

- `_ZN3art3Dbg9SuspendVMEv` / `_ZN3art3Dbg8ResumeVMEv`（`art::Dbg`，KOOM 用于 API<30）。
- `_ZN3art16ScopedSuspendAllC1EPKcb` / `D1Ev`。
- `_ZN3art2gc23ScopedGCCriticalSectionC1EPNS_6ThreadENS0_7GcCauseENS0_13CollectorTypeE` / `D1Ev`。
- 全局锁对象：`_ZN3art5Locks13mutator_lock_E`、
  `_ZN3art5Locks17thread_list_lock_E`。
- 注意：`ScopedSuspendAll` 在 `runtime/thread_list.h` 里带
  `EXPORT`（`EXPORT explicit ScopedSuspendAll(...)` / `EXPORT ~ScopedSuspendAll()`），
  `Locks` 类也是 `class EXPORT Locks` —— 这类是**显式导出**，比 `HIDDEN` 的更可能在 dynsym。

---

## 3. fork 之后能做什么、不能做什么：权威依据

### 3.1 POSIX / Linux / bionic 对「fork 后只能 async-signal-safe」的规定

- POSIX / Linux man：多线程程序 fork 后，**exec 之前**子进程只能调用 async-signal-safe 函数。
  - https://man7.org/linux/man-pages/man7/signal-safety.7.html
  - fork(2) NOTES：子进程只保留调用 fork 的那个线程；「the entire virtual address space
    of the parent is replicated in the child, including the states of mutexes…」
    https://man7.org/linux/man-pages/man2/fork.2.html
  - 转述明确版：https://github.com/libuv/libuv/issues/998
    （"After a fork(2) in a multithreaded program, the child can safely call only
    async-signal-safe functions…"）
- `pthread_atfork(3)` 语义：handlers 在调用 fork 的线程里执行；prepare 逆序、parent/child 正序。
  https://man7.org/linux/man-pages/man3/pthread_atfork.3.html

### 3.2 bionic 实现（`pthread_atfork` + `fork`）

`bionic/libc/bionic/fork.cpp`（main）：

```cpp
int fork() {
  __bionic_atfork_run_prepare();
  int result = _Fork();                 // __clone_for_fork(): clone(CLONE_CHILD_SETTID|...|SIGCHLD)
  if (result == 0) {
    android_fdsan_set_error_level(ANDROID_FDSAN_ERROR_LEVEL_DISABLED);
    android_fdtrack_set_globally_enabled(false);
    __set_stack_and_tls_vma_name(true);
    __bionic_atfork_run_child();
  } else {
    __bionic_atfork_run_parent();
  }
  return result;
}
```

`bionic/libc/bionic/pthread_atfork.cpp`（main）关键语义：

```cpp
void __bionic_atfork_run_prepare() {
  pthread_mutex_lock(&g_atfork_list_mutex);
  g_atfork_list.walk_backwards(...prepare...);     // POSIX: prepare 逆序
}
void __bionic_atfork_run_child() {
  g_atfork_list_mutex = PTHREAD_RECURSIVE_MUTEX_INITIALIZER_NP;  // 子进程重置该锁
  pthread_mutex_lock(&g_atfork_list_mutex);
  g_atfork_list.walk_forward(...child...);
  pthread_mutex_unlock(&g_atfork_list_mutex);
}
void __bionic_atfork_run_parent() {
  g_atfork_list.walk_forward(...parent...);
  pthread_mutex_unlock(&g_atfork_list_mutex);
}
```

- 列表锁是 `PTHREAD_RECURSIVE_MUTEX_INITIALIZER_NP`；子进程里被**重新初始化**（大致是为避免
  「其它线程正持有该锁」时死锁）。
- bionic 自己会注册 handler：`libc_init_common.cpp::__libc_init_fork_handler()` →
  `pthread_atfork(arc4random_fork_handler, _thread_arc4_unlock, _thread_arc4_unlock);`
  （保护 arc4random 锁）
- bionic 导出 `_Fork`（`libc.map.txt` 有 `_Fork;`，introduced=29）——**绕过所有 atfork handler**
  的裸 fork，正是系统组件用来「fork 但不想跑 atfork handler」的手段。

来源：
- https://gitgud.io/aosp/platform/bionic/-/raw/main/libc/bionic/fork.cpp
- https://gitgud.io/aosp/platform/bionic/-/raw/main/libc/bionic/pthread_atfork.cpp
- https://gitgud.io/aosp/platform/bionic/-/raw/main/libc/bionic/libc_init_common.cpp
- https://gitgud.io/aosp/platform/bionic/-/raw/main/libc/libc.map.txt

### 3.3 ART 是否注册 atfork handler？

- 我逐一检索了 `runtime/runtime.cc`、`runtime/thread_list.cc`、`runtime/thread.cc`、
  `runtime/os_linux.cc`、`runtime/signal_catcher.cc`、`sigchainlib/sigchain.cc`、
  `runtime/jit/jit.cc`、`runtime/java_vm_ext.cc`：**均未发现 `pthread_atfork`/`__register_atfork` 调用**。
  → 结论：**未查到确证 ART 注册 pthread_atfork handler**（倾向「没有」）。
- 但 ART 有一套 **Zygote fork 专用钩子**（由 zygote 主动调用，不是 atfork）：
  `Runtime::PreZygoteFork()` / `PostZygoteFork()` / `InitNonZygoteOrPostFork()`、
  `Jit::PostForkChildAction()`、`Heap::PreZygoteFork()`。
  （`runtime/runtime.cc`、`runtime/jit/jit.cc`）
- **关键后果**：App 里自己 `fork()` 时，上述钩子**不会**被执行 → 子进程继承的是「旧的」
  `ThreadList`（里面还有已经消失的线程）和一堆可能被持有/半持有的全局锁。
  这正是 KOOM / perfetto 必须「fork 前先 SuspendAll + 拿 thread_list_lock_」的原因。
- 可能被「父进程其它线程持着」的 ART 全局锁（`runtime/base/locks.h`）：
  `Locks::mutator_lock_`（`MutatorMutex*`）、`Locks::thread_list_lock_`（`Mutex*`）、
  `Locks::thread_suspend_count_lock_`、`Locks::heap_bitmap_lock_`、
  `Locks::jni_libraries_lock_`、`Locks::jit_mutator_lock_` 等；加上 JIT、ClassLinker、
  GC 线程各自的锁。`thread_list_lock_` 的注释原文：
  「The thread_list_lock_ guards ThreadList::list_. It is also commonly held to stop threads…」

来源：
- https://gitgud.io/aosp/platform/art/-/raw/main/runtime/runtime.cc
- https://gitgud.io/aosp/platform/art/-/raw/main/runtime/thread_list.cc
- https://gitgud.io/aosp/platform/art/-/raw/main/runtime/base/locks.h
- https://gitgud.io/aosp/platform/art/-/raw/main/runtime/jit/jit.cc

### 3.4 「App 里不要 fork / fork 只用于 exec」有没有官方文档？

- **未查到** Android developer docs 有「App 内禁止 fork」这类明文条款（这点不要替官方发言）。
- 但 AOSP 系统代码里存在强烈的**工程惯例证据**：
  `system/core/debuggerd/handler/debuggerd_handler.cpp` 原注释：

  ```cpp
  // Don't use fork(2) to avoid calling pthread_atfork handlers.
  pid_t crash_dump_pid = _Fork();
  ```

  即：**官方 crash 处理器刻意用 `_Fork()` 而不是 `fork()`，以跳过 atfork handler。**
- 另外 system/core 历史提交里有
  `const int result = pthread_atfork(nullptr, nullptr, &UnblockSigterm);`
  （"register a fork handler to unblock SIGTERM in the child processes"）——
  说明 AOSP 对「fork 后信号掩码残留」也要专门处理。
  来源：https://android.googlesource.com/platform/system/core/+/b9ee9abebe631b02f312f1d2517305cf6ead159a

---

## 4. 「fork 子进程 dump 原始内存镜像」的替代方案与取舍

### 4.1 ART 原生：Perfetto `android.java_hprof` 数据源（**最权威的同款实现**）

`art/perfetto_hprof/perfetto_hprof.cc` —— 这就是「OOM 时刻 fork 快照」的官方版本：

```cpp
pid_t ForkUnderThreadListLock(art::Thread* self) {
  art::MutexLock lk(self, *art::Locks::thread_list_lock_);
  return fork();
}

void ForkAndRun(art::Thread* self, ResumeParentPolicy policy,
                const std::function<void(pid_t)>& parent_runnable,
                const std::function<void(pid_t,uint64_t)>& child_runnable) {
  // 必须在 fork 之前做：否则会死锁等 GC（其它线程被 clone 掉但锁没释放）；
  // 也要避免子进程里走 logd 日志（有 mutex）。
  std::optional<art::gc::ScopedGCCriticalSection> gcs(std::in_place, self,
      art::gc::kGcCauseHprof, art::gc::kCollectorTypeHprof);
  std::optional<art::ScopedSuspendAll> ssa(std::in_place, __FUNCTION__, /*long_suspend=*/true);
  pid_t pid = ForkUnderThreadListLock(self);          // 持 thread_list_lock_ 再 fork
  if (pid != 0) { /* parent: 按 policy 决定先 resume 还是先等 */ return; }
  // 子进程：
  sigaction(kJavaHeapprofdSignal, &g_orig_act, nullptr);  // 卸掉自己的信号 handler
  child_runnable(parent_pid, ts);
  art::FastExit(0);                                     // 不跑 atexit
}
```

OOM 触发路径 `DumpPerfettoOutOfMemory()` 的两个关键设计（**对你的方案直接可抄**）：

1. `ResumeParentPolicy::DEFERRED`：**不立即 resume 父进程**。源码注释解释：
   若 fork 后立刻让原进程继续，「它多半会因 OOME 立刻退出」，系统随后会清理整个
   cgroup（把刚 fork 的子进程一起杀掉），所以必须**等 dump 完成再让父进程继续**。
2. 子进程 `ArmWatchdogOrDie()`：`timer_create(CLOCK_MONOTONIC, SIGEV_SIGNAL, SIGKILL)`，
   `kWatchdogTimeoutSec = 120`。子进程还会 `daemon(0,0)` 再干活，用
   `BusyWaitpid(child, kWatchdogTimeoutSec*1000)` 兜底。
3. 子进程拒绝重入：`g_oome_triggered` 加锁防「OOM 可重入导致 fork 多次」。

**取舍**：perfetto 的 ART heap dump **只含引用图（retention graph），不含对象内容**，
不是标准 hprof；想要完整 hprof（含 field 值）还得走 `art::hprof::DumpHeap`（KOOM 路线）。

来源：
- https://gitgud.io/aosp/platform/art/-/raw/main/perfetto_hprof/perfetto_hprof.cc
- https://perfetto.dev/docs/data-sources/java-heap-profiler
- https://perfetto.dev/docs/getting-started/local-android-trace-recording （`--wait-for-oom`）

### 4.2 AOSP debuggerd：tombstone + core dump（「fork 出内存快照给外部进程读」的官方范式）

`system/core/debuggerd/handler/debuggerd_handler.cpp`：

- **伪线程**：`clone(debuggerd_dispatch_pseudothread, pseudothread_stack,
  CLONE_THREAD|CLONE_SIGHAND|CLONE_VM|CLONE_CHILD_SETTID|CLONE_CHILD_CLEARTID, …)`
  —— 注释「Essentially pthread_create without CLONE_FILES, so we still work during
  file descriptor exhaustion.」（伪线程共享地址空间但不共享 fd 表，可安全关掉 fd）。
- **fork+exec 调 crash_dump 用 `_Fork()`**（见 3.4），exec 前只做 async-signal-safe 操作
  （`dup2`、`async_safe_format_buffer`、`execle`、`write`）。
- **权限准备**：crash **之前**就 `prctl(PR_SET_DUMPABLE, 1)`、
  `prctl(PR_SET_PTRACER, PR_SET_PTRACER_ANY)`，让 crash_dump 能 ptrace 自己。
- **保存内存快照**：`create_vm_process()` 用**双 `clone(nullptr, nullptr, CLONE_FILES, ...)`**，
  造出一个「停止的、孤儿」的地址空间副本，供 crash_dump 读取；
  期间 `setsid()` 避免 SIGHUP，`drop_capabilities()`。
- crash_dump 侧（`debuggerd/crash_dump.cpp`）全程用 `ptrace(PTRACE_SEIZE/INTERRUPT/…、
  GETREGSET)` 读寄存器/内存；`core_dump.cpp` 生成 ELF core（NT_PRSTATUS / PT_LOAD 等）。

来源：
- https://gitgud.io/aosp/platform/system/core/-/raw/main/debuggerd/handler/debuggerd_handler.cpp
- https://gitgud.io/aosp/platform/system/core/-/raw/main/debuggerd/crash_dump.cpp
- https://android.googlesource.com/platform/system/core/+/aebebeb/debuggerd/handler/debuggerd_handler.cpp

### 4.3 crashpad：fork/exec 独立 handler + ptrace broker

- **handler 是独立可执行**（`crashpad_handler`），客户端 `SpawnSubprocess()` 里
  `fork()` + `execv(e)`（`client/crashpad_client_linux.cc`）。
- crash 时 `LaunchAtCrashHandler::HandleCrashImpl()`：`ScopedPrSetPtracer` → `fork()` →
  子进程 `ClearCloseOnExec(preserve_fds_)` → `execve/execv` handler，父进程 `waitpid`。
- **ptrace broker**：handler 通过 socket 请求客户端再 `fork()` 一个 broker
  （`util/linux/exception_handler_client.cc`，`kTypeForkBroker`）：

  ```cpp
  case ExceptionHandlerProtocol::ServerToClientMessage::kTypeForkBroker: {
    Signals::InstallDefaultHandler(SIGCHLD);
    pid_t pid = fork();
    if (pid == 0) {
      PtraceBroker broker(server_sock_, getppid(), am_64_bit);
      _exit(broker.Run());            // broker 子进程只做 ptrace/读内存，然后 _exit
    }
    waitpid(pid, &status, 0);
  }
  ```

- broker 读内存（`util/process/process_memory_linux.cc`）：**优先开 `/proc/<pid>/mem` 用
  `pread64`**，失败再退回 ptrace `ReadUpTo`：

  ```cpp
  snprintf(path, sizeof(path), "/proc/%d/mem", connection->GetProcessID());
  mem_fd_.reset(open(path, O_RDONLY | O_NOCTTY | O_CLOEXEC));
  read_up_to_ = [this](VMAddress a, size_t s, void* b) {
    return HANDLE_EINTR(pread64(mem_fd_.get(), b, s, a));
  };
  ```

- broker 打开文件路径也**预先拼好字符串**（`/proc/<pid>/…` 写进 `file_root_buffer_`），
  即「预先准备、避免 fork 后做重活」。
- 客户端在 fork 之后要重设 ptracer：`pthread_atfork(nullptr, nullptr, SetPtracerAtFork)`
  + `prctl(PR_SET_PTRACER, pid, 0,0,0)`（`crashpad_client_linux.cc` 383/411 行）。
- **注意**：crashpad 的「fork」是**立即 exec 到另一个进程**，随后由外部进程 ptrace 读内存，
  而不是「子进程在自己地址空间里写快照」。这点和 KOOM 语义不同。
- 你列举的 `personality(ADDR_NO_RANDOMIZE)`、预 mmap scratch buffer 等：**在 Linux/Android
  的 crashpad 实现里未查到**（`ADDR_NO_RANDOMIZE` 是 macOS/Linux ASLR 相关，crashpad 主要
  在 mac 侧用；Android/Linux 用的是 ptrace + `/proc/pid/mem` + socket broker）。

来源：
- https://fastly.jsdelivr.net/gh/chromium/crashpad@main/client/crashpad_client_linux.cc
- https://fastly.jsdelivr.net/gh/chromium/crashpad@main/util/linux/exception_handler_client.cc
- https://fastly.jsdelivr.net/gh/chromium/crashpad@main/util/process/process_memory_linux.cc
- https://fastly.jsdelivr.net/gh/chromium/crashpad@main/util/linux/ptrace_broker.cc
- https://github.com/chromium/crashpad/blob/main/doc/overview_design.md

### 4.4 libunwindstack / simpleperf / MAT

- **libunwindstack**（KOOM 也 vendor 了一份）：`Memory.cpp` 里
  `Memory::Create(pid)` → `MemoryRemote(pid)`，`MemoryRemote::Read` 调
  `process_vm_readv(pid, ...)`；`Maps.cpp` 解析 `/proc/<pid>/maps`。
  这是「用 syscall 读另一个进程内存 + maps」的最小实现。
  （KOOM: `koom-common/kwai-unwind/src/main/cpp/libunwindstack/{Memory.cpp,Maps.cpp}`）
- **simpleperf**：有 `dump`（dump 一个 record file）等功能；**但我未能抓到
  `--dump-regions` 之类精确 flag 的官方文档** → 标为未确证（见第 6 节）。
  文档入口：https://android.googlesource.com/platform/system/extras/+/refs/heads/main/simpleperf/doc/executable_commands_reference.md
- **MAT 直接分析 Android ELF core**：**未查到确证**。MAT 支持的是标准 JVM hprof；
  Android 的「core」由 crashpad/debuggerd 产出，通常用 gdb/lldb + 符号 分析。

---

## 5. 已知「OOM 时刻 fork 快照」项目 / 文章

| 项目 / 文章 | URL | 结论 / 坑 |
|---|---|---|
| KOOM | https://github.com/KwaiAppTeam/KOOM | fork dump 主线实现（第 1 节）。 |
| KOOM Issue #302 | https://github.com/KwaiAppTeam/KOOM/issues/302 | 用户报告：master 适配 Android 15 后 **fork dump 很大概率 crash**，集中在**华为 Android 12/14**；维护者问「崩主进程还是 fork 进程」，**未给出结论**。→ 说明 ROM 差分（华为）是真实风险。 |
| KOOM Issue #289 | https://github.com/KwaiAppTeam/KOOM/issues/289 | 「koom 支持 Android 15 监控吗」，Open 无回复。 |
| KOOM 源码解读（掘金） | https://juejin.cn/post/7267418197745270847 | 梳理 Tracker→dumpAndAnalysis→ForkJvmHeapDumper 调用链；确认「fork 前挂起 JVM 是为让子进程的 ThreadList 认为线程已挂起」。 |
| KOOM 深度源码解析（掘金） | https://juejin.cn/post/7537339432291385344 | 明确「为什么 SuspendAll 必须放主进程」：子进程只剩 fork 线程，子进程里 SuspendAll 永远等不到其它线程返回 → 卡死；「先 SuspendAll 再 fork，共享父进程 ThreadList 全局变量 `_list`，**欺骗虚拟机**」。 |
| 字节 MARS-APMPlus / heapdump.cn | https://heapdump.cn/article/3224735 | 头条/抖音线上 Java OOM 归因：注册 `UncaughtExceptionHandler` 判断 OOM；**内存触顶子进程 dump = fork 创建子进程做耗时 dump**；同时承认「OOM 后再 dump 容易失败」「dump 会卡顿」；也提到用 xHook 在 native 层 hook dump 边 dump 边裁剪（Tailor 路线）。 |
| 七猫免费小说内存优化实践 | https://tech.qimao.com/qi-mao-mian-fei-xiao-shuo-appnei-cun-you-hua-shi-jian-yi/ | 子进程里 `Debug.dumpHprofData(path)` 然后 `exitProcess()`，父进程 `resumeVM()` + 等子进程。即「Java 层 dumpHprofData + fork」路线（与 KOOM 的 native dlsym 路线不同）。 |
| Matrix ResourceCanary 2.0.8 | https://github.com/Tencent/matrix/releases | Release note：「Upgrade ResourceCanary to support HPROF 'fork dump'」；`ForkAnalyseProcessor` 在 native 层 fork。 |
| 从 Matrix-ResourceCanary 看内存快照生成 | https://juejin.cn/post/7273435095913840640 | 拆解 ForkAnalyseProcessor 的 dumpAndAnalyse。 |

---

## 6. 未能确证的点 + 你实现时应自测验证的项

### 6.1 未查到确证

1. **ART 是否注册 `pthread_atfork` handler**：我检索的 ART 源文件里没有；但**不能说 100% 没有**
   （没有全仓库 grep 的可靠手段）。倾向「没有」。
2. **`simpleperf` 的 `dump` / `--dump-regions` 精确用法**：官方文档页多次抓取失败，未确证具体 flag。
3. **MAT 是否支持直接打开 Android ELF core**：未查到确证。
4. **crashpad 在 Android/Linux 是否用 `personality(ADDR_NO_RANDOMIZE)`、预 mmap scratch buffer**：
   在 Linux 实现里未查到（那套更像 macOS/Windows 侧）。Android/Linux 走 ptrace + `/proc/pid/mem`。
5. **官方明文「App 内不要 fork」**：未查到；只有 AOSP 代码里的工程惯例（`_Fork` 跳过 atfork）。
6. **`hprof_dump_v_impl.cpp` 里 `art::hprof::DumpHeap` 在 Android 15/16 ROM 上是否真的能 dlsym 到**：
   源码显示 `namespace art HIDDEN`，但「hidden 是否真的从 dynsym 消失」取决于各厂商 ROM 的
   build 配置 —— 必须实测（见 6.2）。
7. **KOOM 历史上有无自带 `libhprof`/`jvm_dump.cpp`**：我把 KOOM 全历史文件名扫过，**只有**
   `hprof_dump*.cpp`，**没有** `jvm_dump.cpp` / `libhprof`。即 KOOM 一直是 dlsym libart 路线。
8. **是否存在把 `DumpHeap` 签名改成 `std::string` 的版本**：android11→main 全部是
   `const char*, int, bool`，未查到任何 std::string 版本。

### 6.2 你实现时必须自测验证

- **libart 符号在不同 ROM 上是否存在 / 可见**（核心风险）：
  ```bash
  # 取设备上的 libart（Android 14+ 在 APEX 里）
  adb shell ls -l /apex/com.android.art/lib64/libart.so
  adb pull /apex/com.android.art/lib64/libart.so
  # 1) dynsym 里有没有（能否 ::dlsym）
  llvm-readelf -sW --dyn-syms libart.so | grep -E 'DumpHeap|SuspendVMEv|ScopedSuspendAll|mutator_lock_|thread_list_lock_'
  # 2) symtab（若被 strip 就没有）
  llvm-readelf -sW libart.so | grep DumpHeap
  # 3) gnu_debugdata（解压后再看）
  llvm-readelf -S libart.so | grep gnu_debugdata
  ```
  在**每个目标 ROM / ABI / 版本**上跑一遍；把结果做成能力表。
- **写个最小 dlsym 探针**（App 内）：对上述每个 mangled 名分别 `dlopen_elf`+解析，
  打日志报告成功/失败；失败即降级到别的策略。
- **fork 死锁实测**：连续 fork dump 数百次，统计卡死率；覆盖 华为/小米/OPPO/三星 等 ROM
  与 Android 12/13/14/15/16。
- **子进程超时/看门狗**：验证 `alarm`/`timer_create(SIGKILL)` 在目标 ROM 上确实生效，
  父进程 `waitpid` 不会被异常信号路径破坏。
- **OOM 时父进程该不该 resume**：perfetto 的 `DEFERRED` 论证（cgroup 清理会连坐子进程）
  值得在你方案里实测——建议默认「dump 完成前不 resume」。
- **SELinux / `PR_SET_DUMPABLE` / `PR_SET_PTRACER`**：若走「外部进程读 `/proc/pid/mem`」路线，
  在 App 域（非 system）上能否 ptrace/读 mem 需实测（通常同 uid 子进程可以）。
- **`_exit` vs `exit`**：子进程务必 `_exit`，避免跑 atexit / 析构（KOOM、perfetto 都这样）。
- **ABI/页大小**：Android 15+ 16KB 页（KOOM 专门有 16KB 适配提交 `aae5a7c`）。

---

## 附：可直接抄的骨架（基于 KOOM 与 perfetto 的共识）

```
父进程（在 OOM 处理线程，非 UI）：
  1. 关闭/暂停非必要线程动作（可选）
  2. 取 art::Thread* = __get_tls()[TLS_SLOT_ART_THREAD_SELF]
  3. ScopedGCCriticalSection(self, kGcCauseHprof=15, kCollectorTypeHprof=15)   // dlsym ctor
  4. ScopedSuspendAll("oom", long_suspend=true)                                // dlsym ctor
  5. Mutex::ExclusiveLock(Locks::thread_list_lock_)                            // dlsym
  6. pid = fork()
  7. 父进程：
       - 若「继续跑」：解锁 + 逆序析构 + waitpid（带 EINTR 重试 + 超时）
       - 若「OOM 即将退出」：先 waitpid 子进程完成，再析构（避免 cgroup 连坐）
  8. 子进程：
       - timer_create(CLOCK_MONOTONIC, SIGKILL, 120s)   // 看门狗
       - prctl(PR_SET_NAME, "oom-dump")
       - 释放 3/4 继承来的挂起状态（或直接调 art::hprof::DumpHeap 内部会再 SuspendAll）
       - art::hprof::DumpHeap(path, -1, false)          // dlsym _ZN3art5hprof8DumpHeapEPKcib
       - _exit(0)                                       // 绝不 exit()
```

符号解析务必带降级链：**dynsym → symtab → .gnu_debugdata**（KOOM `ElfReader::LookupSymbol`）。
