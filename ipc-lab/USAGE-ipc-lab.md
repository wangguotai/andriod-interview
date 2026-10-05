# 跨进程通信 Lab 使用说明

一个可单独安装的 Demo App，逐个演示 Android 与 Linux 上的进程间通信机制，
每个演示都留下**可复核的跨进程证据**（pid / 进程名 / 内核身份）。

## 快速开始

```bash
# 编译（会顺带用 cargo 编出 arm64-v8a 的 libipclab.so）
./gradlew :ipc-lab:assembleDebug

# 安装到已连接的设备/模拟器（真机若开了「USB 安装需确认」可能需手动允许）
adb install -r -t ipc-lab/build/outputs/apk/debug/ipc-lab-debug.apk

# 启动（自带 LAUNCHER，也可从桌面图标进）
adb shell am start -n com.interview.ipclab/com.interview.ipc.ui.IpcLabActivity
```

## 界面

- **顶部**：当前进程 `pid` 与 Rust 库描述（`native=Rust abi=1 ...`）。若这里显示
  `native=不可用`，说明 `.so` 没打进包或 ABI 不匹配 —— 先解决它，否则原生演示全废。
- **左/上：演示列表**：按 `IpcLayer` 分组（Android framework / Linux 原生 / 桥）。
- **右/下：证据面板**（等宽字体）：点击某个演示后，结果**追加**到面板，不清屏，
  方便把多个演示的证据对着看。
- **汇总对比**：按 `IpcModel` 打印**静态选型指引**（不是本次运行结果）。
- **清空日志**：清空证据面板。

## 建议的阅读顺序

1. **AIDL · 同步调用**（`binder_sync`）—— 先建立「Binder 跨进程」的基准证据：
   返回的 pid 与客户端不同。
2. **AIDL · Binder 线程池** + **oneway + 反向回调** —— 理解服务端的并发模型与双向 Binder。
3. **AIDL · linkToDeath** —— 看客户端如何感知并自愈「对端进程没了」。
4. **Messenger / ContentProvider / Broadcast** —— Binder 之上的三种不同语义封装。
5. **LocalSocket(abstract) ↔ Rust** —— 从 framework 跨到内核原语。
6. **pipe / FIFO / shm / signal / flock** —— 逐个看 syscall 级证据。
7. **TCP loopback** —— 与 AF_UNIX 做对照，理解「本机通信为什么优先 AF_UNIX」。
8. **汇总对比** —— 收起时按「我要做什么」反查该用哪种模型。

## 每个演示在看什么

日志里每个 `✅` 都是一条**可复核的断言**，重点看这几处：

- `binder_*`：出现 `✅ 服务端 pid=… 与客户端 pid=… 不同 ⇒ **确实跨进程**`。
  进程名应是 `com.interview.ipclab:ipc_remote`，不是主进程名。
- `binder_threadpool`：`返回的独特 tid 数量=6`、`总耗时 ≈ 单次`（而非单次×6）。
- `binder_async_callback`：`computeAsync 返回耗时=0ms`（oneway 未等待）。
- `binder_death`：出现 `🔔 DeathRecipient.binderDied()`，且重连后 pid 变了。
- `native_fifo`：两端 `st_ino` 相同（同一内核管道）。
- `native_shm`：子进程看到父写、父看到子改；`SCM_RIGHTS` 两端 `st_ino` 相同。
- `native_signal`：`siginfo.si_pid` 等于发送方 pid。
- `native_flock`：子进程拿锁得到 `EWOULDBLOCK`。

## 命令行复核（不点 UI）

```bash
# 跑仪器测试：逐个演示 + 断言证据（出现 ❌ 即失败）
./gradlew :ipc-lab:connectedDebugAndroidTest

# 直接看证据原文
adb logcat -d | grep -a System.out | sed 's/.*System.out: //'

# 观察两个进程确实都存在
adb shell ps -A | grep ipclab
#   com.interview.ipclab
#   com.interview.ipclab:ipc_remote
```

## 常见问题

- **`native=不可用`**：`.so` 未打包或 ABI 不符。检查 `abiFilters` 与设备架构
  （`adb shell getprop ro.product.cpu.abi`），并确认 `assembleDebug` 里 cargo 那步真的跑了。
- **真机安装被拒（`INSTALL_FAILED_USER_RESTRICTED`）**：MIUI 等系统需在开发者选项里
  打开「USB 安装」/「安装未知来源应用」，或改用模拟器。
- **FIFO 演示报权限错**：FIFO 的对端路径必须在应用有权访问的目录（本 Lab 用自身
  filesDir）。`/data/local/tmp` 在 shell 域下会被 SELinux 拒绝 —— 这是预期行为，见 NOTES。
- **`binder_death` 之后别的 Binder 演示**：`crashRemoteProcess` 会杀掉 `:ipc_remote`，
  下一个演示会自动重新拉起它。演示在单线程执行器里串行跑，正是为了避免这种状态交叉。

## 相关文档

- 设计取舍、证据点、诚实缺口：[`NOTES-ipc-lab.md`](NOTES-ipc-lab.md)
- Rust 侧说明：`rust/ipclab/src/lib.rs` 头部注释
