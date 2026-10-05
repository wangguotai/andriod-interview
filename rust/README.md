# Rust 工作区约定

本目录是「Rust 提升性能」教学场景的 native 侧代码。**与 `app/src/main/cpp/` 下的 C++ 并存**：
C++ 那条线（`thread_hook.cpp`）负责线程 hook，Rust 这条线负责图像流水线，两者都挂在同一个
`externalNativeBuild` 上。

## 两个 crate 的边界（不要打破）

```
rust/
├── imagepipeline/   # 纯计算内核：零 Android / 零 JNI
│   └── cargo test  ← 这是日常开发的反馈回路，秒级
├── android/         # JNI 绑定：只做类型翻译 + 错误码 + panic 拦截
├── ipclab/          # 跨进程通信 Lab 的 native 侧（pipe/fifo/memfd/SCM_RIGHTS/signal/flock）
├── netlab/          # 网络传输内核：请求校验/取消/计时/线格式（纯逻辑）+ h3（quinn/rustls）
└── netlab-android/  # 上面那套的 JNI 绑定（薄层，同 android 的纪律）
```

> `ipclab` 是 `:ipc-lab` module 自己的 native 核心，与上面「图像性能」那条线**无关**，
> 只是共用同一个 workspace 以便统一 target/锁文件。它的构建由
> `tools/cmake/build_rust_android.cmake` 驱动，不走 `imagepipeline` 那套。详见
> `ipc-lab/NOTES-ipc-lab.md`。

- **算法只许写在 `imagepipeline`**。一旦它 `use` 了任何 Android/JNI 类型，本机测试就没了，
  反馈环从「秒级」退化成「装机级」——那正是这个拆分要避免的事。
- **`android` crate 不许有算法**。它应当薄到「读一遍就能确认没有逻辑」。

## 环境变量（必须显式导出）

本机 Rust 装在 `/Volumes/ext/Rust`，且没有默认 toolchain 的环境变量，所以每次都要：

```bash
export RUSTUP_HOME=/Volumes/ext/Rust/rustup
export CARGO_HOME=/Volumes/ext/Rust/cargo
export PATH=/Volumes/ext/Rust/cargo/bin:$PATH
```

## 常用命令

```bash
# 纯算法单测（宿主平台，秒级）
cargo test --manifest-path rust/imagepipeline/Cargo.toml

# JNI 层的 host 侧单测（只测 guard / 错误码，不需要设备）
cargo test --manifest-path rust/android/Cargo.toml

# 交叉编译出 Android .so（arm64-v8a）
NDK=$HOME/Library/Android/sdk/ndk/25.1.8937393/toolchains/llvm/prebuilt/darwin-x86_64/bin
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$NDK/aarch64-linux-android24-clang \
CC_aarch64_linux_android=$NDK/aarch64-linux-android24-clang \
AR_aarch64_linux_android=$NDK/llvm-ar \
cargo build --manifest-path rust/android/Cargo.toml --target aarch64-linux-android --release
```

> `rustup target add aarch64-linux-android` 已执行；若在别的机器上首次构建，需要重跑一次
> （国内网络可加 `RUSTUP_DIST_SERVER=https://mirrors.ustc.edu.cn/rust-static`）。

## 与仓库既有约束的关系

- native 计算**必须**由 Kotlin 侧经 `ThreadPools` 泳道调度，不能在 native 里自建线程，
  否则绕过全 App 的线程命名/配额治理（`thread-lint` 的三条规则也拦不到 native 线程）。
- Rust 内部若未来引入 rayon 等线程库，需要先想清楚它如何纳入既有治理协议，再开。

### netlab 的 h3 传输：如何纳入上述约束（登记）

网络传输比图像计算更容易踩这条线，因为 QUIC 库通常自带 async runtime。本仓库的处理方式：

- `netlab::h3::fetch` 是**阻塞式** API，内部用 **current-thread runtime + `block_on`**；
  runtime **不额外起 worker 线程**，UDP 收发/定时器由**调用方线程**驱动。
  `netlab/Cargo.toml` 刻意**不开** tokio 的 `rt-multi-thread` feature，从依赖层面兜住。
- ⇒ 因此 **Kotlin 侧必须在 `ThreadPools` 的 net 泳道线程上调用 `fetch`**（它是阻塞式 JNI 调用）。
  放主线程 = ANR；放自建线程 = 绕开泳道治理。两者都禁止。
- 后果是有意的：单次 fetch 的并发度受 net 泳道 core 数约束，而不是另起一套无治理的并发。
- 取消是**协作式**的：`block_on` 内每 50ms 轮询取消标志，命中即丢弃整个 future。
  它不是硬中断，不能打断底层 socket syscall —— 该缺口在 `h3.rs` 与 `NOTES-netlab.md` 里明确标注。

> 未做（若要做需再登记一次）：QUIC **连接复用**需要长驻 runtime，届时应由**单条**在
> `ThreadPools` 登记过的任务驱动，而不是让 runtime 自己起线程。
