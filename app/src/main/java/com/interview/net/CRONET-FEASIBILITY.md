# C · Cronet 接入可行性与体积评估（对照基线）

> 目的：在决定「Rust 自研传输栈」之前，先建立一条**不写 Rust 就能统一到 OkHttp 并跑 HTTP/3**
> 的对照基线。C 阶段只做评估，不改动业务代码。
>
> 数据来源：AAR 实际下载解包 + 官方 README + Google Maven 元数据。**标注为「实测」的是本机下载
> 后量出来的数字，不是估算。**

---

## 一、可行性结论：可行，且有官方成品

[google/cronet-transport-for-okhttp](https://github.com/google/cronet-transport-for-okhttp)
做的就是这件事——**把另一套 native 网络栈（Chromium 的 QUIC/HTTP3）统一到 OkHttp/Retrofit 之下**。
它证明「统一到 OkHttp」在工程上成立，且提供了两个缝合点：

| 缝合点 | 用法 | 保留什么 | 失去什么 |
|---|---|---|---|
| **`CronetInterceptor`** | `OkHttpClient.Builder().addInterceptor(...)`（**必须放最后**） | OkHttp 应用拦截器链（含我们的 `AdaptiveRetryInterceptor`） | 网络配置、EventListener 中间阶段 |
| **`CronetCallFactory`** | `Retrofit.Builder().callFactory(...)` | Retrofit 接口不变 | `OkHttpClient` 配置**整体旁路**；`Call.tag()` 抛异常 |

依赖（实测 pom）：
- `com.google.net.cronet:cronet-okhttp:0.1.1` —— **最新版就是 0.1.1**（仅 0.1.0/0.1.1）
- 适配器自身很薄：AAR **99.5 KB**（实测）
- 但它拉 **OkHttp 3.12.13 + Okio 2.10.0 + cronet-api 98.4758.101**（实测 pom）

⚠️ **重要顾虑：适配器已陈旧**。0.1.1 依赖的是 2023 年的 cronet-api 98，而本项目已升到
OkHttp 4.12.0。引入时必然要 `exclude`/`resolutionStrategy` 强制对齐，否则会把 OkHttp 降级到 3.x。
这是「用现成库」方案里最容易踩的坑，**不是零成本**。

---

## 二、体积评估（实测）

### 本工程基线（实测）

| 项 | 值 |
|---|---|
| debug APK | 13.1 MB |
| release APK | 9.2 MB |
| 打包的 ABI | **仅 arm64-v8a** |
| 现有 native 库 | `libimagepipeline_android.so` 327 KB + `libthreadhook.so` 30 KB ≈ **357 KB** |

### 方案 ①：Play Services Cronet 提供方（推荐用于「只想用 HTTP/3」）

| 项 | 值（实测） |
|---|---|
| `play-services-cronet:18.1.1` AAR | **90.2 KB** |
| 传递依赖 | play-services-base 18.5.0 / basement 18.9.0 / tasks 18.2.0 / cronet-api 141.7340.3 |
| 增量的 cronet native（arm64） | **≈ 0**（native 由设备上的 Play Services 提供，不打进 APK） |
| 桌面/无 GMS 设备 | 不可用 → **必须回退 OkHttp** |

→ APK 增量在**百 KB 量级**（适配器 + play-services 基础件 + cronet-api 胶水），native 体积外置。
这正是我们必须保留「Cronet 不可用时回退 OkHttp」的原因——这台设备上 Cronet 不是我们的代码。

### 方案 ②：`cronet-bundled` 自带 native（无 GMS 也能用）

`org.chromium.net:cronet-bundled:500.1.0` AAR = **13.31 MB**（实测，13,956,135 B），内含 4 个 ABI：

| ABI | 解包后 .so |
|---|---|
| arm64-v8a | **7.00 MB** |
| armeabi-v7a | 4.16 MB |
| x86 | 6.91 MB |
| x86_64 | 7.90 MB |

本工程只打包 arm64-v8a → **净增约 7 MB（+77%，相对当前 9.2 MB release）**，且这是压缩前；
装上后再叠加 DEX/资源。对当前这个「教学 Lab」体量来说，是**数量级的膨胀**。

⚠️ 命名有坑：`cronet-embedded` 现在是**空壳**（只转发到 `cronet-bundled`），别被旧粒度误导。

---

## 三、能力得失（官方 README 明确列出，非推测）

这几条直接决定了我们上一轮做的网络层**哪些会失效**：

### 3.1 通用——OkHttp 核心整体旁路
> "The entirety of OkHttp core is bypassed. This includes **caching, retries, authentication,
> and network interceptors**."

即：连接池、`Dns`（我们的 [InterviewDns](InterviewDns.kt)）、缓存、网络拦截器全部**不再生效**，
需在 `CronetEngine.Builder` 上重配。**证书固定（pinning）也必须配到 Cronet 上**（安全红线）。

### 3.2 度量——`EventListener` 中间阶段不回调 ⚠️
> "**Intermediate `EventListener` stages are not being reported.**"

→ 我们的 [NetEventListener](NetEventListener.kt)（DNS/TCP/TLS/首包拆分）**直接失效**。
分阶段耗时只能改从 Cronet 的 `UrlRequest` 状态回调（`onResponseStarted` 等）重新采集，
且粒度更粗——Cronet 不暴露 DNS/TLS 分段。

### 3.3 重试——`AdaptiveRetryInterceptor` 的命运
拦截器模式下**可以保留**（它是应用拦截器）——这是选拦截器而非 CallFactory 的主要理由之一。

### 3.4 其他必须知道的缺口
- `Response.handshake` / `networkResponse` / `cacheResponse` / `sentRequestAtMillis` / `receivedResponseAtMillis` **均为空**；
- 同一 header 的**多值**会丢，只保留最后一个；
- `Accept-Encoding` 由 Cronet 接管，自定义被忽略；
- **WebSocket 不支持**；
- 拦截器模式下 **`Call.cancel()` 传播有延迟**；
- CallFactory 模式下 **`Call.tag()` 必抛异常**（本项目 [ImageDownloader](../image/ImageDownloader.kt) 未用 tag，暂不受影响）。

---

## 四、C 阶段结论

1. **「统一到 OkHttp 并跑 HTTP/3」有官方解，且不写 Rust** —— 用适配器 + Play Services 提供方，
   APK 增量百 KB 级；但适配器陈旧（依赖 OkHttp 3.12.13），需处理依赖对齐。
2. **代价是 OkHttp 核心（DNS/连接池/缓存）与我们的 EventListener 度量失效**，
   要在 Cronet 侧重配 —— 尤其是**证书固定不能漏**。
3. **`cronet-bundled` 自带的代价是 +7 MB（arm64）**，对教学工程过重；能用 GMS 就别自带 native。
4. 因此若走 Cronet 路线，务必**保留回退**：`CronetProviderInstaller` 失败 → 用 `NetClient.shared`。
   这与我们「可回滚、可 A/B」的纪律一致。

### 与自研 Rust 的对比（决定 A/B 是否值得）

| 维度 | Cronet（C 基线） | Rust 自研（A/B） |
|---|---|---|
| HTTP/3、连接迁移、BBR | ✅ 现成 | 需自己实现 |
| 代码量 | 百行胶水 | 数千行 + JNI 桥 |
| 度量/证书固定 | 需在 Cronet 重配，**分段度量做不到** | 完全自控，可做分段 |
| 依赖陈旧风险 | ✅ 有（0.1.1 / api 98） | 自己控版本 |
| APK 体积 | +百 KB（GMS）/ +7 MB（bundled） | quinn+rustls+tokio 预计**数 MB** |
| 线程治理 | Cronet 自带线程，同样需纳入协议 | 同样需纳入协议 |
| 学习/掌控价值 | 低 | 高 |

**判据**：如果目标是「产品拿收益」→ 停在 C。如果目标是「自己拥有传输栈并做分段度量对拍」→ 继续 A/B，
但必须接受上面 3.1–3.2 在自研方案里同样存在（**只要传输换掉，OkHttp 的 DNS/连接池/EventListener 就都没了**），
区别仅在于自研时你**能**重建它们，而 Cronet 给不了分段。

---

## 五、下一步

- **A**：`RustCallFactory` 接入设计（`Call` 接口需实现的方法清单、与 `NetMetrics` 的度量回灌点、证书固定对齐点）。
- **B**：`netlab` crate 骨架（窄 JNI + host 单测 + CMake 接线）。
