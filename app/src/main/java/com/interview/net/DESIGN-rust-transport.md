# A · Rust 传输栈接入 OkHttp 设计（RustTransportInterceptor）

> 前置：已读 [CRONET-FEASIBILITY.md](CRONET-FEASIBILITY.md)。
> 本文只出**设计**，不含实现；实现见 B（`netlab` crate 骨架）。

---

## 0. 一处对上一轮的修正（有证据）

上一轮我说「缝在 `Call.Factory`」。看完 Cronet 官方 README 后要**修正**：

| 缝合点 | 保留 Java 拦截器？ | 证据 |
|---|---|---|
| `Call.Factory` | ❌ **全不保留** | 官方 README: "OkHttpClient configuration is unavailable and **bypassed completely**"；CallFactory 模式连 app 拦截器都不跑 |
| **`Interceptor`（应用层，放最后）** | ✅ **保留它之前的拦截器** | 官方 README 推荐「重度使用拦截器时用 interceptor 模式」；"Add the Cronet interceptor last" |

**结论：自研 Rust 的正确缝合点是「应用层 Interceptor」，不是 CallFactory。**
用 CallFactory 会让我们的 [AdaptiveRetryInterceptor](AdaptiveRetryInterceptor.kt) **静默失效**——
重试、退避、幂等判断全部丢失，而且不会有任何报错，属于典型的「假绿」。

> 只有在「某个第三方库强制要求 `Call.Factory`」时，才补一个内部包一层 OkHttpClient 的
> CallFactory 适配器；主路径仍走 Interceptor。

**socketFactory 方案：不可行，先排除。** OkHttp 文档明确 `SocketFactory.createSocket()` 产出的是
**TCP socket**；QUIC 跑在 UDP 上、且握手融合在连接建立里，塞不进 `SocketFactory` 的抽象。
这条最容易被误当成捷径，实际是死路。

---

## 1. 目标 / 非目标

**目标**
- G1：Retrofit 接口与业务代码**零改动**，传输在弱网/特定请求下改走 Rust QUIC。
- G2：**可回退、可 A/B**：同一接口能在 OkHttp 与 Rust 两条路径间切换并对拍。
- G3：度量口径统一——Rust 路径的阶段耗时回灌进既有 [NetMetrics](NetMetrics.kt)。
- G4：安全不降级——**证书固定必须在 rustls 里等价实现**。

**非目标（第一版明确不做）**
- N1：不做 WebSocket（Cronet 亦不支持，非必要）。
- N2：不做流式上传/SSE（跨 JNI 背压暂不碰）。
- N3：不替换全部流量（默认只对弱网/白名单 host 生效）。
- N4：不追求「比 OkHttp 快」的结论——是否更快由 A/B 数据说话。

---

## 2. 架构与分层

```
业务 / Retrofit 接口                       ← 不变（G1）
   │
OkHttpClient
   ├─ [日志/自定义 header 等 app 拦截器]      ← 保留
   ├─ AdaptiveRetryInterceptor                ← 保留（关键！）
   └─ RustTransportInterceptor（放最后）       ← 新增：缝合点
           │  决定：这次请求走 Rust 吗？
           ├── 否 → chain.proceed(request) → OkHttp 原生（含 DNS/连接池/EventListener）
           └── 是 → RustBridge.fetch(...) → 合成 okhttp3.Response 返回（不调 proceed）
                       │
                   JNI 薄层（rust/android 风格：类型翻译 + 错误码 + panic 拦截）
                       │
                   netlab kernel（quinn + rustls）  ← 纯传输，host 可测部分下沉
```

**路由判据**（可配置，默认保守）：`NetworkQuality.level == WEAK` 且 host 在白名单内，
且 URL 是 `https`。其余一律走 OkHttp 原生 —— 保证任何异常都能退回已知可用的路径。

---

## 3. 需要实现的接口面（精确清单）

`okhttp3.Call`（4.12.0 源码实测）共 **8 个方法**；但走 Interceptor 方案时，
我们**不实现 Call**，只实现 `Interceptor`（1 个方法）。这才是 Interceptor 方案的额外好处：
接口面小、无 `clone()/timeout()` 语义模仿成本。

```kotlin
class RustTransportInterceptor(
    private val bridge: RustBridge,          // JNI 桥
    private val router: (Request) -> Boolean // 路由判据
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!router(req)) return chain.proceed(req)      // 走 OkHttp（G2 回退）

        val reqId = startMetric(req)                     // 阶段计时起点
        return try {
            val native = bridge.fetch(                   // 阻塞式 JNI 调用
                url = req.url.toString(),
                method = req.method,
                headers = req.headers.toMultimap(),
                body = req.body?.let { it.toByteArraySafe() }, // N2：第一版限小 body
                timeoutMillis = chain.callTimeoutMillis().takeIf { it > 0 }
                    ?: NetConfig.DEFAULT.baseCallTimeoutMillis,
            )
            synthResponse(req, native, reqId)            // 合成 Response
        } catch (e: IOException) {
            endMetric(reqId, error = e)
            throw e
        }
    }
}
```

> 若将来确需 `Call.Factory`（例如某库强制），再补一个 `RustCallFactory`，实现那 8 个方法：
> `request()/execute()/enqueue(Callback)/cancel()/isExecuted()/isCanceled()/timeout(): Timeout/clone()`。
> 其中 `timeout(): Timeout` 需要返回一个能映射到 Rust deadline 的 okio.Timeout；
> `clone()` 语义是「复制一个未执行的 Call」——这两处最容易实现得似是而非，**非必要不做**。

**合成 `Response` 时拿不到、必须显式置空/标注的字段**（与 Cronet 同样的坑，见 C 文档 3.4）：
`handshake`、`networkResponse`、`cacheResponse`、`sentRequestAtMillis/receivedResponseAtMillis`
——rustls 层其实**有** TLS 信息（协议版本、cipher、证书链），应尽量填 `handshake` 而不是放弃。

---

## 4. 线程与生命周期（本项目最硬的一条）

### 4.1 关键设计：**让 Rust 跑在我们的泳道线程上**

quinn 是 async 的，默认方案会起 tokio 多线程 runtime → **自带 event loop/reactor 线程，
绕过 `thread-lint` 与 [ThreadPools](../thread/ThreadPools.kt)**，违反本仓库既有纪律
（见 [rust/README.md](../../../../../../rust/README.md) 的显式约定）。

**推荐做法**：用 **current-thread runtime + `block_on`**，由调用方线程驱动：

```rust
// 调用发生在 ThreadPools.network 的线程上；runtime 不额外起线程。
// reactor（UDP 收发、定时器）由 block_on 期间在同一线程驱动。
let rt = tokio::runtime::Builder::new_current_thread()
    .enable_all()
    .build()?;
rt.block_on(async { quinn_fetch(...).await })
```

这样：**网络任务全部落在我们的 net 泳道线程上**，命名/配额/背压全部继续生效。
代价是并发度受泳道 core 数约束（core=4）——但这本来就是既有设计，
且泳道注释里已论证「RTT 受限下 n=1→4 有线性收益」。**这是一个真实的架构收益，不是妥协。**

> 进阶：若需要 connection 复用与多路复用，一个 client 共享一个 current-thread runtime，
> 由插桩的**单条** `ThreadPools` 长驻任务驱动——需在 `rust/README.md` 显式登记
> 「该线程如何纳入治理协议」，不得静默绕开。

### 4.2 `enqueue` 回调 → 主线程
`RustBridge.fetch` 在泳道线程阻塞返回后，Intercept 返回 Response，OkHttp 的正常回调链
会把它送到调用方。**不需要** native→JAVA 反向回调（第一版刻意规避 N2，正是为了躲开
`AttachCurrentThread` 带来的生命周期/死锁风险）。

### 4.3 `cancel()`（诚实标注为难点）
阻塞式 `block_on` 无法被外部线程硬中断。第一版用 **协作式取消**：
Java 侧置 `Arc<AtomicBool>` 取消标志 + `quinn::Connection::close()`，Rust 侧在各阶段边界检查。
**已知缺口**：无法打断正在进行的单次 socket 读；表现为「取消有延迟」——
与 Cronet 拦截器模式的已知行为一致（"Call cancellation signals are propagated with a delay"）。

---

## 5. 度量回灌（G3）——让两条路径同口径

Rust 侧在给 `NetMetrics.Record` 的字段上**逐项对齐**（字段定义见 [NetMetrics.kt](NetMetrics.kt)）：

| `NetMetrics.Stage` | OkHttp 路径（现有 EventListener） | Rust/quinn 路径（需采集） |
|---|---|---|
| `dnsMillis` | `dnsStart/End` | **Java 侧先解析**：复用 [InterviewDns](InterviewDns.kt)，把 IP 传给 Rust → 耗时由此侧计时 |
| `connectMillis` | `secureConnectStart` 截断 | QUIC 无独立 TCP 握手 → **记 UDP 建连+TLS 融合段**，语义需在文档标注「与 TCP 路径不可直接比」 |
| `tlsMillis` | `secureConnectEnd - secureConnectStart` | QUIC 把 TLS 融进握手 → **并入 connect 段或记 0**，并标注 |
| `firstByteMillis` | `responseHeadersEnd - requestHeadersEnd` | 首帧/响应头到达时刻 |
| `totalMillis` | `callEnd - callStart` | JNI 进入→返回（含跨语言开销，须单独标注） |
| `reusedConnection` | `connectionAcquired` | quinn `Connection` 是否复用 |
| `protocol` | OkHttp `Protocol` | 固定 `"HTTP_3"`（h3） |

**必须诚实的两点**：
1. QUIC 把 TCP+TLS 握手融合，所以 `connectMillis/tlsMillis` 与 TCP 路径**语义不同**，
   汇总统计时要分开打标，否则会把「分段口径不同」误读成「Rust 连接更快」。
2. JNI 往返有固定开销，`totalMillis` 会天然比同条件纯 Java 略高——**这正是要对拍量出来的**。

---

## 6. 安全对齐（G4，红线）

| OkHttp 能力 | Rust 侧必须等价实现 | 若遗漏的后果 |
|---|---|---|
| `certificatePinner` | rustls 自定义 `ServerCertVerifier`，按 SPKI SHA-256 校验 | **TLS 校验被降级，静默且无报错** |
| 系统信任库 / Network Security Config | 加载 Android CA store（经 JNI 或打包 roots） | 自签/企业 CA 环境下请求失败或被错误信任 |
| 明文例外（`usesCleartextTraffic`） | 路由层直接拒绝非 https 走 Rust | 绕开平台明文策略 |
| SNI / 主机名校验 | rustls 默认校验，**不得关** | 中间人 |
| 代理/VPN | 第一版**不支持代理则明确报错**，不静默直连 | 企业内网用户请求失败或绕过代理审计 |

→ 建议：**证书固定一致性与明文策略**做成 B 阶段的自动化对拍用例（同一证书，两条路径行为必须一致）。

---

## 7. 第一版范围（收敛）

| 能力 | 第一版 | 说明 |
|---|---|---|
| GET / HEAD | ✅ | 主力场景 |
| 无 body 的 POST | ✅ | |
| 带小 body 的 POST（≤64KB，完整读入内存） | ✅ | 避免流式背压 |
| 流式上传/下载、SSE、WebSocket | ❌ | 第二版，需解决跨 JNI 背压与反向回调 |
| 重定向 | ❌（交回 OkHttp） | 路由层遇到 3xx 直接回退，不在 Rust 里跟随 |
| 代理 | ❌ 明确报错 | 见 §6 |
| HTTP 缓存 | ❌ | 交回 OkHttp 或后续 |

---

## 8. 验收标准（B 阶段结束时必须能证明）

1. **回退可用**：Cronet 不可用 / Rust 未初始化 / 路由判否 → 请求 100% 走 OkHttp，功能不变。
2. **拦截器存活**：日志、自定义 header、`AdaptiveRetryInterceptor` 在 Rust 路径下**仍有日志证明被调用**。
3. **度量同口径**：Rust 路径的记录出现在 `NetMetrics.recent()`，字段齐全且打标 `HTTP_3`。
4. **安全对齐**：证书固定的对拍用例通过（错误指纹必须两条路径都失败）。
5. **A/B 报告**：同接口、同网络下 OkHttp(h2) vs Rust(h3) 的 P50/P90/P99，**含「无显著差异」这一可能的诚实结论**。
6. **线程治理**：线程快照中不出现游离的 tokio/reactor 线程（或在 README 登记豁免理由）。

---

## 9. 风险清单

| 风险 | 等级 | 缓解 |
|---|---|---|
| rustls 证书校验实现不当 → 安全降级 | 🔴 高 | §6 对拍用例；复述「不得关校验」 |
| 阻塞式取消不彻底 | 🟠 中 | 文档标注；必要时降级为「不可取消但会超时」 |
| QUIC 分段口径与 TCP 不可比 → 错误结论 | 🟠 中 | §5 打标 + 报告分开呈现 |
| tokio runtime 绕过线程治理 | 🟠 中 | §4.1 current-thread + block_on |
| JNI 往返开销抵消传输收益 | 🟡 低 | 正是 A/B 要量的量（§5 第 2 点） |
| APK 体积（quinn+rustls） | 🟡 低 | 按 ABI 分包；与 C 的 +7MB 对比 |

---

## 10. 下一步（B）

按 [rust/README.md](../../../../../../rust/README.md) 既有分层搭 `netlab`：
- `rust/netlab/`：纯传输 kernel，**不依赖 JNI**，可 host `cargo test` 的部分尽量下沉
  （帧编解码、阶段计时结构、取消标志状态机）；
- `rust/android/`：沿用现有 `imagepipeline_android` 的纪律——**薄 JNI，只做类型翻译 +
  错误码 + panic 拦截 + `ABI_VERSION` 对拍**；
- CMake 复用 [rust_build.cmake](../../../../../../app/src/main/cpp/rust_build.cmake)；
- 先只暴露一个窄接口：`fetch(url, method, headersJson, bodyBytes, timeoutMs) -> NativeResponse`。
