# 跨进程通信 Lab NOTES

本 module 把「IPC 有哪些机制」拆成**可独立跑、且各自能自证跨进程**的演示。
目标不是「调通 API」，而是**留下可复核的证据**：每个演示的结果里都带 pid / 进程名 /
内核回传的身份（如 `SO_PEERCRED`），让「这是真的两个进程在说话」不靠嘴说。

## 为什么是独立 module

演示需要**第二个进程**（`android:process=":ipc_remote"`）、AIDL、ContentProvider、
以及一个 Rust `cdylib`。放进 `:app` 就要动 app 的 Manifest 与 native 构建，风险外溢到
与 IPC 无关的既有实验。独立 module 把这些影响面收在自己的构建脚本里。

因此本 module **自带 LAUNCHER，是一个可单独安装的 Demo App**（applicationId
`com.interview.ipclab`），不挂到主 app 的 `HomeCatalog` —— 与仓库里同样自带的
`:scroll-event-demo` 保持一致的做法。

## 两条正交的轴

| 轴 | 取值 | 含义 |
|---|---|---|
| **层级 IpcLayer** | `ANDROID` / `LINUX` / `BRIDGE` | 机制来自 Android framework、Linux 内核、还是两者的桥 |
| **模型 IpcModel** | `RPC` `PUBSUB` `DATA_ACCESS` `BYTE_STREAM` `PIPE` `SHARED_MEMORY` `SIGNAL` `LOCK` | 通信的语义模型（怎么用） |

「汇总对比」按钮就是按 `IpcModel` 分组打印**静态选型指引**：什么场景该用哪种模型。
它是**选型向导**，不是运行结果 —— 真正的运行证据在上面的逐个演示日志里。

## 演示清单与证据点

### Android framework（Binder 家族）

| 演示 | 机制 | 关键证据 |
|---|---|---|
| `binder_sync` | AIDL 同步调用 | 返回的 `pid`/`proc` 与客户端不同 ⇒ 真跨进程 |
| `binder_threadpool` | Binder 线程池 | 6 次并发「耗时」调用落在 6 个**不同** binder 线程、总耗时 ≈ 单次而非累加 |
| `binder_async_callback` | oneway + 反向回调 | `computeAsync` 立即返回（≈0ms）；进度/结果经客户端传入的 `IRemoteCallback` 反向送回 |
| `binder_death` | linkToDeath | `crashRemoteProcess` 后收到 `binderDied()`；重连得到**不同**的新 pid |
| `messenger` | Messenger | `msg.replyTo` 双向；应答 pid ≠ 客户端 pid |
| `provider` | ContentProvider | `query` 元信息行 / `call("sum")` / `openFile` 返回 fd |
| `broadcast` | 显式广播 | 接收方 pid/proc 自报，再发回执广播形成闭环 |

### Linux 原生（Rust，syscall 级）

| 演示 | syscall | 关键证据 |
|---|---|---|
| `native_pipe` | `pipe2` | 写 8 字节原子；关闭写端后 read=0（EOF）；写已关读端=EIPE |
| `native_fifo` | `mkfifo` + `open` | 两端 `fstat` 的 `st_ino` 相同 ⇒ 同一内核管道；无读者时 `O_NONBLOCK` 打开=ENXIO |
| `native_shm` | `memfd_create` + `mmap` + `fork` | 子进程看到父写的数据、父进程看到子进程改的字节 ⇒ 同一物理页 |
| `native_shm`（fd 传递） | `socketpair` + `sendmsg(SCM_RIGHTS)` | 接收端 fd 与发送端 fd 的 `st_ino` 相同 ⇒ 同一内核对象 |
| `native_signal` | `kill` + `SA_SIGINFO` | 子进程收到实时信号，`siginfo.si_pid` = 父进程 pid ⇒ 来源可辨 |
| `native_flock` | `flock` | 父持锁时子进程 `LOCK_EX\|LOCK_NB`=EWOULDBLOCK ⇒ 跨进程互斥 |

### 桥 / 字节流

| 演示 | 机制 | 关键证据 |
|---|---|---|
| `local_socket_abstract` | Java `LocalSocket(ABSTRACT)` ↔ Rust `AF_UNIX` | Kotlin 客户端收到 Rust 服务端应答；双方 `SO_PEERCRED` 一致 |
| `local_socket_rust_selftest` | Rust 内自测 | abstract 与 filesystem 两种命名空间各跑一遍往返 |
| `tcp_loopback` | TCP `127.0.0.1` | 临时端口往返成功，并对照 AF_UNIX 的开销差异 |

## 诚实的缺口（别把这些当成已解决）

1. **JVM 进程内不 fork**。`pipe` / `signal` / `flock` 这些演示里，需要「另一个执行流」
   的地方用 `fork`；在跑着 ART 的进程里 fork，子进程只保留 forking 线程，且可能继承
   别人持有的 malloc 锁 —— **不安全**。本 crate 的做法是：子进程分支**严格不做分配**
   （只把状态字节写进 pipe，父进程负责格式化成人类可读的行）。`shm` 演示确实 fork 了，
   但它遵守「子分支零分配」这条纪律。真正的跨进程互斥/信号，在 `native_flock` /
   `native_signal` 里用**真实父子进程**验证；Android App 场景下若要独立进程做这些，
   应另起组件进程而不是 fork 一个 ART 进程。
2. **`mkfifo` 在 `/data/local/tmp` 被 SELinux 拒**。shell 域建不了 FIFO，所以 FIFO 的
   对端路径落在**应用自己的 filesDir**（`/data/user/0/com.interview.ipclab/files/ipc-native/`）。
   这也正是「FIFO 需要双方对同一路径有权限」的真实写照。
3. **`memfd_create` 走的是裸 syscall**。bionic 的用户态封装在某版本上把 `SFD_CLOEXEC`
   传错；本 crate 用 raw number（aarch64=279）并避免 `__INTRODUCED_IN(30)` 符号。
   在更老的设备上仍可用，但属「绕过 libc」，需知悉。
4. **只编 arm64-v8a**。教学场景够用，四 ABI 会显著拖慢构建；要上其它 ABI 需同步
   改 `abiFilters` 与 Rust target。
5. **广播接收方无返回值**。`broadcast` 的「回执」是接收方**主动再发一条广播**实现的，
   不是框架给了返回值 —— 这正是广播与 RPC 的本质区别。
6. **对比页是静态指引**。`汇总对比` 输出的选型建议不随本次运行结果变化；运行证据只在
   各演示日志里。UI 里已明确写出这一点。

## 一个曾经踩到的坑（留作警示）

组件日志最初用 `applicationInfo.processName` 取进程名，结果跑在 `:ipc_remote` 里的
Service/Provider/Receiver 全部打印 `proc=com.interview.ipclab` —— **看起来像自己调自己**，
恰好把演示要证明的事说反了。原因：`applicationInfo.processName` 返回的是
Manifest 里 `<application>` 声明的**主进程名**，与你在哪个进程调用无关。
正解是读 `/proc/self/cmdline`（见 `ProcName.kt`），它反映内核视角的**当前进程**真名。

## 验证方式

```bash
# 1) 编 APK（含 Rust .so）
./gradlew :ipc-lab:assembleDebug

# 2) 确认 .so 真的进了包、且 JNI 符号在
unzip -l ipc-lab/build/outputs/apk/debug/ipc-lab-debug.apk | grep '\.so'
unzip -p  ipc-lab/build/outputs/apk/debug/ipc-lab-debug.apk lib/arm64-v8a/libipclab.so \
  | $ANDROID_NDK/toolchains/llvm/prebuilt/*/bin/llvm-nm -D - | grep Java_com_interview_ipc

# 3) 真机/模拟器上跑全部演示并断言证据
./gradlew :ipc-lab:connectedDebugAndroidTest

# 4) 看证据原文（每个演示前有 ========== <id> ==========）
adb logcat -d | grep -a System.out
```

`androidTest` 的 `everyDemoRunsAndProducesEvidence` 会逐个跑完 `IpcLabCatalog.DEMOS`，
出现任何 `❌` 判定即失败；`binderCallCrossesProcessBoundary` 显式断言「确实跨进程」。
