# Rust 工作区约定

本目录是「Rust 提升性能」教学场景的 native 侧代码。**与 `app/src/main/cpp/` 下的 C++ 并存**：
C++ 那条线（`thread_hook.cpp`）负责线程 hook，Rust 这条线负责图像流水线，两者都挂在同一个
`externalNativeBuild` 上。

## 两个 crate 的边界（不要打破）

```
rust/
├── imagepipeline/   # 纯计算内核：零 Android / 零 JNI
│   └── cargo test  ← 这是日常开发的反馈回路，秒级
└── android/         # JNI 绑定：只做类型翻译 + 错误码 + panic 拦截
```

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
