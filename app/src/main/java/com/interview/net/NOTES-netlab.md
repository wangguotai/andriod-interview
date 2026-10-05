# B · netlab 说明（含 M2：QUIC 接入 / M3：pin 通道 + 回退 + 取消桥接 + 实验页）

> 前置：[DESIGN-rust-transport.md](DESIGN-rust-transport.md)（A）、[CRONET-FEASIBILITY.md](CRONET-FEASIBILITY.md)（C）。
> 本文说明**实际落地了什么、没落地什么**，以及如何验证 —— 只有跑过的结论写在这里。

---

## 0. 里程碑

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1 | 协议边界与控制面：校验 / 取消 / 计时 / 线格式 + 薄 JNI | ✅ |
| M2 | 真实 HTTP/3（QUIC）传输：kernel `h3` + `fetch` JNI + Kotlin 接缝 | ✅ |
| **M3** | **证书固定的 JNI 配置通道 + 运行期回退 + 取消桥接 + 实验页补齐** | ✅ 本文 |

ABI 由 2 → **3**（M3 给 `fetch` 增加 `pins` 参数）。
（更早：ABI 1 → 2 是 M2 改线格式：headers 由 map 改为**有序可重复** + 新增 `spki` 字段。）

---

## 1. 落地了什么

```
rust/
├── netlab/               ← 传输 kernel：校验/取消/计时/线格式（纯逻辑）+ h3（quinn/rustls）
│   └── cargo test        ← 秒级反馈回路（55 个用例）
├── netlab-android/       ← 薄 JNI：类型翻译 + 错误码 + panic 拦截 + 句柄管理
│   └── cargo test        ← host 侧 3 个用例（guard / ABI 对齐）
app/src/main/java/com/interview/net/
├── RustTransportConfig.kt        ← 路由判定（纯逻辑）+ 配置（默认关）
├── RustTransportInterceptor.kt   ← **接缝**：应用拦截器（放最后）接管传输、合成 Response
└── nativebridge/                 ← NetLabNative / NetLabBridge（fetch + 线格式 + SPKI）
```

### `netlab`（kernel，63 tests）

| 模块 | 职责 | 关键测试 |
|---|---|---|
| `request` | 请求准入（方法白名单、**拒绝明文 http**、代理明确拒绝） | 明文被拒、POST/PATCH 被拒、畸形 URL、拒绝码稳定不重复 |
| `cancel` | 取消状态机（CAS 语义） | 已完成后的取消必须被忽略、并发取消恰好一个胜出 |
| `timing` | 阶段耗时（字段对齐 `NetMetrics.Stage`）+ 单调计时器 | `lap` 未 start 返回 `None` 而非 0；QUIC 无独立 TLS 段 |
| `wire` | 线格式编解码 + 头部行 + **pin 行** | 往返一致、**header 值中的 `:` 不截断**、**重复头全部保留**、body 长度不符两侧都报错、未知字段前向兼容、编码确定性；pin 行：长度/非十六进制/大小写/`*` 与空 host 语义（8 例） |
| **`h3`** | **quinn + rustls 的阻塞式 HTTP/3 客户端** | 明文/方法在联网前被拒、取消码契约、**取消探针是条件完成而非周期完成**、pin 不匹配可与链校验失败区分、**SPKI 提取与 openssl 独立计算值一致** |

### `netlab-android`（薄 JNI，11 个符号）

```
abiVersion / versionString
validateRequest
cancelTokenNew / cancelTokenFree / cancelTokenCancel / cancelTokenIsCancelled
wireDecode / probeHandleRoundTrip
fetch            ← M2 新增：真实 HTTP/3（阻塞式）；M3 增加 pins 参数
spkiSha256Hex    ← M2 新增：与 OkHttp CertificatePinner 对拍用
```

所有对外函数走 `guard`/`catch_unwind`，**panic 不跨 FFI**；句柄的创建/释放也各自包了 `catch_unwind`。

---

## 2. 关键设计（三条，都是踩过坑才写下的）

### 2.1 线程治理：current-thread runtime + `block_on`

`h3::fetch` 用 **current-thread runtime**，runtime **不额外起 worker 线程**，
IO 由**调用方线程**驱动。Cargo.toml 刻意**不开** `rt-multi-thread` feature，从依赖层面兜住。

⇒ 网络任务落在 `ThreadPools` 的 net 泳道线程上，命名/配额/背压继续生效。
**因此 Kotlin 侧必须在泳道线程上调用 `fetch`**（它是阻塞式 JNI 调用）。
代价：并发度受 net 泳道 core 数约束 —— 这是既有设计，且泳道注释已论证其合理性。

### 2.2 接缝：应用拦截器（放最后），不是 `Call.Factory`

见 A §0。用 `Call.Factory` 会让 OkHttp「bypassed completely」，**静默干掉**
`AdaptiveRetryInterceptor`；`socketFactory` 装不下 QUIC（UDP + 握手融合）。
应用拦截器放最后，是唯一既保留既有拦截器、又能接管传输的缝。

### 2.3 ⚠️ 防双记（本接缝最隐蔽的坑）

OkHttp 对每个 Call 都会在**最外层**触发 `EventListener.callEnd`（`RealCall` 源码实测）。
所以应用拦截器合成 Response、**不调 `chain.proceed()`** 时，`NetEventListener`
**仍会记一条** code=-1、阶段全空的脏记录 —— 与合成者的记录叠加 = **双记**，
分位数/成功率/复用率被静默污染、不报错。

处理：[`NetEventListener`](NetEventListener.kt) 只在**收到过真实响应头**
（`responseHeadersEnd`/`cacheHit`，即 `sawResponse`）时才记账。合成响应不经过
`ConnectInterceptor`/`CallServerInterceptor`，因此不会触发这些回调 → 自动跳过。
有专门的集成测试钉住（见 §4）。

> 曾考虑用「把 `sentRequestAtMillis` 置负」当标记，最终选 `sawResponse` 更简单且语义直接。
> 无论哪种，**关键是必须有测试**：这条路径纯逻辑单测盖不住。

---

## 3. **没**落地什么（诚实清单 —— 读之前先看这里）

| 项 | 状态 | 说明 |
|---|---|---|
| 证书固定的**配置通道** | ✅ **M3 已接** | pin 经 `fetch(pins=...)` → JNI `JByteArray` → `wire::parse_pin_lines` → rustls 自定义 verifier，在握手里生效。两侧指纹一致性用 `NetLabBridge.decodePinToSpki` 与 `CertificatePinner` 解析对拍；错误指纹必被拒（`SPKI-PIN-MISMATCH`，标记为安全事件）有集成测试钉住 |
| **运行期回退** | ✅ **M3 已接** | Rust 传输**已开始但失败**时交回 OkHttp 重试一次，但**仅限幂等方法**，且 **pin 失败 / 取消绝不回退**（安全语义优先于可用性）。裁决在 [RustTransportInterceptor] `shouldFallback`，有正反例集成测试 |
| **取消桥接** | ✅ **M3 已接** | OkHttp **没有** public 的 onCancel 回调，故在拦截器内起一个**只轮询内存布尔**的看门狗线程（非 IO，不绕开泳道），把 `Call.isCanceled()` 桥接到 Rust 取消标志；native 每 50ms 查一次即中止飞行中的请求 |
| QUIC 连接复用 | ❌ | 每次调用新建 endpoint/connection，`reusedConnection` 恒为 false。复用需在 Kotlin 侧持有连接池 + 一个纳入治理的长驻 runtime |
| 流式 body / 上传 / SSE / WebSocket | ❌ | v1 只支持「已完整读入内存」的小 body（≤64KB） |
| Android 系统 CA / Network Security Config | ❌ | v1 用 `webpki-roots`（Mozilla 根集合），覆盖公网服务，**覆盖不到企业自签 CA** |
| `handshake` 完整重建 | ❌ | quinn 未暴露 cipher/TLS 版本，合成 `Response` 的 `handshake` 置 null（**相对 OkHttp 是信息降级**）；补偿：回传叶证书 SPKI |
| 硬中断取消 | ❌ | 取消是协作式：select 每 50ms 查标志，命中即丢弃 future；**不能打断底层 socket syscall**（M3 已把「何时置标志」接对，但机制仍是协作式） |
| 重定向 | ❌ | 交回 OkHttp；Rust 路径不跟随 3xx |
| HTTP 缓存 | ❌ | 交回 OkHttp |
| 代理 | ❌ | 明确拒绝（不静默直连） |

---

## 3b. 实验页覆盖（哪些能力**在页面上能看到**）

> 动机：一度出现「代码里有、页面上看不到」的认知落差 —— 页面只证明了
> 「传输能跑」，没证明 M2/M3 真正的主题（接缝 / 开关 / 防双记 / pin / 取消 / 回退）。
> 下面这张表把「能力 ↔ 上屏按钮」显式对齐，避免再次误读。

| 能力 | 页面上怎么看 | 类型 |
|---|---|---|
| 网络感知 / DNS / 度量 / 重试 | 按钮 1–7 | 既有 |
| 路由判定（为什么走/不走） | 按钮 8（`explainRoute`） | 展示 |
| **传输能跑**（低层原生桥直连） | 按钮 9 —— 调 `NetLabBridge.fetch`，**刻意绕过**拦截器 | 演示 |
| **接缝真的接管 OkHttp** + **防双记护栏** + 字段交接 | 按钮 10 —— 经 `buildWithRustTransport`，断言 `protocol=QUIC` 且 `NetMetrics` 恰好 +1 | **核心** |
| **证书固定**：指纹对拍 + 命中 + 拒绝 | 按钮 11（`spkiSha256Hex` vs `CertificatePinner`；正确命中 / 错误必拒） | **核心** |
| **取消**（协作式、飞行中）+ **回退边界** | 按钮 12（看门狗桥接取消；错误 pin 做「绝不回退」反例） | **核心** |
| **A/B**：h3 vs h2 各自端到端 + 同口径入库 | 按钮 13 | 对照 |

两条边界必须写清：
① 按钮 9 走**低层桥**，天然不含路由/防双记/回退 —— 证明接缝要看按钮 10；
② 按钮 13 是**跨 host**（`cloudflare-quic.com` 有 QUIC，`api.github.com` 没有），
   **不得**据此下「h3 比 h2 快/慢」的结论；且 QUIC 的 connect 段含融合 TLS，口径与 TCP 不同。
③ 「普通网络失败 → 回退成功」的正例在页面上**难以稳定复现**，该正例以**单测**为准
   （`RustTransportInterceptorIntegrationTest`）；页面只呈现「取消不回退」「pin 失败不回退」两条负例。

---

## 4. 验证方式（本节全部实测过）

```bash
# 1) kernel + JNI 秒级单测（不需设备、不需网络）
export RUSTUP_HOME=/Volumes/ext/Rust/rustup CARGO_HOME=/Volumes/ext/Rust/cargo PATH=/Volumes/ext/Rust/cargo/bin:$PATH
cargo test -p netlab -p netlab_android
#   => netlab 63 passed；netlab_android 3 passed

# 2) 真实 HTTP/3 冒烟（需外网；不放进常规单测，避免污染秒级回路）
cargo run -p netlab --example h3_fetch --release -- cloudflare-quic.com --verify-pin

# 3) Kotlin 纯逻辑 + 集成单测（MockWebServer 跑真实 OkHttp 栈）
./gradlew :app:testDebugUnitTest          # 全量：71 passed

# 4) 交叉编译 + 进 APK
./gradlew :app:assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | grep '\.so$'
```

### 实测结果

| 项 | 结果 |
|---|---|
| `cargo test -p netlab -p netlab_android` | **63 + 3 passed** / 0 failed |
| 真实 HTTP/3（host，cloudflare-quic.com） | ✅ **200**，125959 B，QUIC 建连 285~796ms |
| SPKI 提取 vs openssl 独立计算 | ✅ 一致（`d8a2…b663`，单测钉住） |
| pinning 端到端（真实服务器） | ✅ 正确 pin 成功 / 错误 pin 被拒且标记为 `SPKI-PIN-MISMATCH` |
| pin 配置通道（Kotlin → JNI → rustls） | ✅ 指纹对拍一致；错误指纹必拒；页面上按钮 11 可复现 |
| 运行期回退 | ✅ 幂等失败回退成功 / pin 失败与取消**绝不**回退（集成测试）；页面按钮 12 演示负例 |
| Kotlin 单测 | ✅ `RustTransportTest` 23 + `RustTransportInterceptorIntegrationTest` 14 + `NetLayerTest` 17 + 图像 16 + 示例 1 = **71 passed** |
| 交叉编译 | ✅ `libnetlab_android.so` **2.63 MB**（stripped，arm64） |
| APK 内 native 库 | ✅ `libnetlab_android.so` 2.63 MB；`libimagepipeline_android.so` 仍 327 KB（CMake 未破坏图像侧） |
| APK 体积 | 13.5 → **17.73 MB**（debug；增量 ≈ netlab QUIC 栈，**对比 Cronet 单 ABI 7 MB 更省**） |
| 导出符号 | ✅ 11 个（含 `fetch` / `spkiSha256Hex`，`fetch` 已带 `pins` 参数） |

---

## 5. 与 Cronet 的体积对照

| 方案 | 单 ABI native | 说明 |
|---|---|---|
| Cronet bundled | ≈ **7.0 MB** | 成熟、Java↔Java 适配器现成 |
| 本方案 netlab(quinn+rustls) | ≈ **2.57 MB** | 需自建 Rust→JNI→Kotlin 桥；v1 功能面窄 |

即 **netlab 在体积上有明显优势，代价是功能覆盖面与自建成本**（见 C 文档）。
这是"能选 Rust 而非 Cronet"在本项目的**主要量化理由**。

---

## 6. 下一步（若继续）

1. ~~**证书固定的 JNI 配置通道**~~ —— ✅ **M3 已接**（见 §3 表）。
2. **QUIC 连接复用**：一个 client 共享一个 current-thread runtime，由**单条**登记过的
   `ThreadPools` 长驻任务驱动（须在 `rust/README.md` 显式登记治理协议）。
3. 流式 body（跨 JNI 背压）—— 难度最高，建议最后做。
4. A/B 报告（同接口、同网络下 OkHttp(h2) vs Rust(h3) 的 P50/P90/P99），**允许结论是"无显著差异"**。
   ⚠️ 页面上的按钮 13 是**跨 host** 对照，**不能**当这份报告用。

---

## 7. 一句总结

M2 把设计文档 A 里那条"缝在应用拦截器"的路线**跑通了真实 QUIC**；
M3 补齐了它周边最容易「有代码、无证据」的四块：**证书固定的 JNI 配置通道、
运行期回退（幂等限定 + pin/取消绝不回退）、取消桥接、以及实验页的覆盖**。
kernel 63 个秒级测试、真实服务器 200 OK 与 pinning 端到端验证；
Kotlin 71 个测试（含 14 个用真实 OkHttp 栈的集成测试）证明接缝、防双记、回退与 pin 链有效；
`.so` 进 APK 且体积（2.63 MB）比 Cronet 单 ABI（≈7 MB）更省。
**仍未做的复用/流式/系统 CA** 已在上表逐条列明，不含糊。
