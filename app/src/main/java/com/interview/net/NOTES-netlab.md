# B · netlab 骨架说明

> 前置：[DESIGN-rust-transport.md](DESIGN-rust-transport.md)（A）、[CRONET-FEASIBILITY.md](CRONET-FEASIBILITY.md)（C）。
> 本文说明 B 阶段**实际落地了什么、没落地什么**，以及如何验证。

---

## 1. 落地了什么

严格沿用 [rust/README.md](../../../../../../rust/README.md) 的两层拆分：

```
rust/
├── netlab/               ← 纯逻辑 kernel：零 Android / 零 JNI / 零依赖
│   └── cargo test        ← 秒级反馈回路（本阶段 36 个用例）
└── netlab-android/       ← 薄 JNI：类型翻译 + 错误码 + panic 拦截 + 句柄管理
    └── cargo test        ← host 侧 3 个用例（guard / ABI 对齐）
```

### `netlab`（kernel，36 tests）

| 模块 | 职责 | 关键测试 |
|---|---|---|
| `request` | 请求准入校验（方法白名单、**拒绝明文 http**、代理明确拒绝） | 明文被拒、POST/PATCH 被拒、畸形 URL、拒绝码稳定不重复 |
| `cancel` | 取消状态机（`cancel`/`complete`/`settle` 的 CAS 语义） | **已完成后的取消必须被忽略**、并发取消恰好一个胜出、cancel 与 complete 不得同时胜出 |
| `timing` | 阶段耗时（字段对齐 Kotlin `NetMetrics.Stage`）+ 单调计时器 | `lap` 未 start 时返回 `None` 而非 0；**QUIC 路径不得有独立 TLS 段** |
| `wire` | 线格式编解码（跨 FFI 回传 + 对拍） | 往返一致、**header 值中的 `:` 不被截断**、body 长度不符在编/解码两侧都报错、未知字段前向兼容、编码确定性 |

### `netlab-android`（JNI 薄层，3 tests + 交叉编译验证）

导出 9 个符号，对应 Kotlin [NetLabNative](../nativebridge/NetLabNative.kt)：

```
abiVersion / versionString
validateRequest
cancelTokenNew / cancelTokenFree / cancelTokenCancel / cancelTokenIsCancelled
wireDecode
probeHandleRoundTrip
```

- 所有对外函数走 `guard`（catch_unwind → 错误码），**panic 不跨 FFI**；句柄的创建/释放也各自包了 `catch_unwind`（析构 panic 同样不能跨边界）。
- 句柄借用/所有权契约写在函数注释里：`cancelTokenNew` 转移所有权给 Kotlin，`free` 只能调一次。

---

## 2. **没**落地什么（诚实清单）

### 2.1 没有 `fetch` —— 这是有意的

真正的 QUIC 传输（quinn/rustls）**尚未接入**。原因不是"来不及"，而是设计文档 §4.1 那个前提问题还没解：

> quinn 需要 async runtime，而 runtime 会起自己的 event loop 线程，绕过 `thread-lint`
> 与 `ThreadPools` 的命名/配额治理。

在解决"runtime 线程如何纳入泳道治理"之前就塞一个 `fetch` 进去，只会得到一个名字唬人、
实际上要么空转、要么偷偷起游离线程的实现。**所以本阶段刻意不提供该符号** ——
宁可边界清晰，也不要一个看起来能发请求的假绿。

### 2.2 其余缺口

| 项 | 状态 |
|---|---|
| QUIC / HTTP-3 实际收发 | ❌ 未实现（见 2.1） |
| rustls 证书固定实现 | ❌ 未实现（设计见 A §6，安全红线） |
| Kotlin 侧 `RustTransportInterceptor` | ❌ 未实现（设计见 A §3） |
| 流式 body / 上传 / SSE / WebSocket | ❌ 第一版范围外（A §7） |
| 度量回灌到 `NetMetrics` | ❌ 未实现（设计见 A §5） |

一句话：**B 交付的是"协议边界与控制面"，不是"能跑 QUIC 的传输层"。**

---

## 3. 构建接线（CMake 的改动）

[rust_build.cmake](../../../../../../app/src/main/cpp/rust_build.cmake) 从"为 imagepipeline 写死"重构为通用形式：

- **工具链探测只做一次**（ABI→target 映射、cargo 可用性、`rustup target` 检查）；
- 新增 `add_rust_android_library(<target> <crate_dir> <lib_name> [extra...])`；
- [CMakeLists.txt](../../../../../../app/src/main/cpp/CMakeLists.txt) 现在两行注册两个库：

```cmake
add_rust_android_library(imagepipeline_android rust/android imagepipeline_android rust/imagepipeline)
add_rust_android_library(netlab_android rust/netlab-android netlab_android rust/netlab)
```

⚠️ 重构时保留了原有的三条踩坑注释（SHARED 目标而非 custom/IMPORTED、`-D` 参数不要重复加引号、
`RUST_REPO_ROOT` 的层级），并新增一条：**`netlab-android` 把 `netlab` 作为增量依赖传入**，
否则改了 kernel 源码不会触发 `.so` 重编 —— 那正是最典型的"改了不生效"假绿。

---

## 4. 验证方式（本阶段实际执行过的）

```bash
# 1) kernel 秒级单测（不需设备、不需网络）
export RUSTUP_HOME=/Volumes/ext/Rust/rustup CARGO_HOME=/Volumes/ext/Rust/cargo PATH=/Volumes/ext/Rust/cargo/bin:$PATH
cargo test -p netlab -p netlab_android
#   => netlab 36 passed；netlab_android 3 passed

# 2) 交叉编译（arm64）
NDK=$HOME/Library/Android/sdk/ndk/25.1.8937393/toolchains/llvm/prebuilt/darwin-x86_64/bin
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$NDK/aarch64-linux-android24-clang \
CC_aarch64_linux_android=$NDK/aarch64-linux-android24-clang \
AR_aarch64_linux_android=$NDK/llvm-ar \
cargo build --manifest-path rust/netlab-android/Cargo.toml --target aarch64-linux-android --release

# 3) 走 Gradle native 构建，确认 .so 进 APK（关键：这一步才证明接线对）
./gradlew :app:assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep '\.so$'
```

### 实测结果

| 项 | 结果 |
|---|---|
| `cargo test -p netlab` | **36 passed** / 0 failed（0.02s） |
| `cargo test -p netlab_android` | **3 passed** / 0 failed |
| 交叉编译 | ✅ `libnetlab_android.so` 492 KB |
| APK 内 native 库 | ✅ `libnetlab_android.so` **343 KB**（arm64-v8a） |
| 导出符号 | ✅ 9 个 `Java_com_interview_net_nativebridge_*` |
| APK 体积 | 13.1 → **13.5 MB**（debug；增量来自 netlab .so，非 Cronet 的 7 MB） |

> 顺带一个反向证据：`libimagepipeline_android.so` 在 APK 里是 319 KB —— 说明
> **重构后的 CMake 没有破坏既有的图像侧接线**（否则它会是 CMake 占位空库）。

---

## 5. 下一步（若继续）

按优先级：

1. **解决 runtime 线程治理**：确认 quinn 用 current-thread runtime + `block_on`、
   由 netlab 线程驱动，并在 `rust/README.md` 登记协议 → 这是接入 `fetch` 的前置。
2. 接入 `quinn` + `rustls`，实现窄接口 `fetch`，只支持 `request::validate` 放行的请求。
3. 实现 rustls 证书固定（A §6），并把"两条路径证书行为一致"做成对拍用例。
4. Kotlin 侧 `RustTransportInterceptor` + 度量回灌（A §3/§5）。
5. 出 A/B 报告（h2 vs h3 的 P50/P90/P99），**允许结论是"无显著差异"**。
