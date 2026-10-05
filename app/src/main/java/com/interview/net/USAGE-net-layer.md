# 网络层使用指引

面向「要在这个 App 里发请求 / 加接口 / 做降级 / 查问题」的人。
设计取舍与依据见 [NOTES-net-layer.md](NOTES-net-layer.md)，本文只讲**怎么用**。

---

## 0. 一句话总览

```
业务代码
   │  只用两个入口：NetClient.shared（API）/ NetClient.downloads（大文件）
   ▼
NetClient ── 唯一 http 客户端工厂
   ├── InterviewDns         DNS 缓存 / 多 IP / 坏 IP 熔断
   ├── NetEventListener     分阶段耗时（DNS/TCP/TLS/首包）→ NetMetrics
   └── AdaptiveRetryInterceptor  自适应超时 / 幂等重试 / 退避抖动

读：NetClient.networkQuality()   → 该不该降级
读：NetMetrics.summary()         → 现在网络怎么样
```

**两条纪律**：① 不要自己 `new OkHttpClient`；② 网络任务提交到 `ThreadPools.network`。

---

## 1. 接入（新模块怎么用）

已完成接入、你什么都不用做：`MyApplication` 启动感知层，`ImageDownloader`、Retrofit 已用共享 client。

新模块要用网络，只做三件事：

```kotlin
// 1) 拿 client —— API 用小包请求，大文件下载用 downloads
val client = NetClient.shared

// 2) 请求放进网络泳道（不要在 Activity / 回调里直接 execute）
ThreadPools.network.execute("你的模块名") {
    // 3) 在这里发请求
    client.newCall(request).execute().use { resp -> /* ... */ }
}
```

`ThreadPools.network.execute` **返回 Boolean**：`false` 表示被背压拒绝（配额满 / 队列满）。
不要忽略它 —— 忽略等于让任务静默消失，而且泳道的拒绝指标涨了却没人降级（参考
[ImageDownloader](../image/ImageDownloader.kt) 的处理：首次被拒延迟重试一次，仍拒则回调失败）。

---

## 2. 日常用法

### 2.1 发一个 API 请求

共享 client 已装好 DNS / 度量 / 重试，带上 `User-Agent` 即可：

```kotlin
val request = Request.Builder()
    .url("https://api.github.com/zen")
    .header("User-Agent", "your-module")   // 强烈建议：便于服务端与你自己归因
    .build()
val code = NetClient.shared.newCall(request).execute().use { it.code }
```

Retrofit 接口直接 `gitHub.xxx()` 即可（[Common.kt](../retrofit/Common.kt) 已接好 `NetClient.shared`），
不需要额外配置。

### 2.2 下载大文件

```kotlin
// 下载档：超时给足、不自动重试（大文件重下比传完更浪费）
NetClient.downloads.newCall(request).execute().use { resp ->
    resp.body?.byteStream()?.use { input -> /* 写文件 */ }
}
```

⚠️ 下载档**不做重试**，弱网中断需要你自己做断点续传（见 §5，尚未内置）。

### 2.3 按网络质量做降级

```kotlin
when (NetClient.networkQuality().level) {
    NetworkLevel.OFFLINE  -> showOfflineCache()      // 用本地缓存
    NetworkLevel.WEAK     -> loadFirstPageOnly()      // 只拉首屏 / 低清图 / 关预加载
    NetworkLevel.MARGINAL -> loadNormalPage()         // 可以正常，但别预取
    NetworkLevel.GOOD     -> loadWithPreload()        // 全量 + 预加载
}
```

`level` 是**唯一判据**，不要再自己去读 `ConnectivityManager`。
四个档的语义与判定规则见 [NetworkLevel.kt](NetworkLevel.kt)。

想实时跟随切换（比如切后台回前台重判）：

```kotlin
val listener = { s: NetworkQuality.Snapshot -> updateUiFor(s.level) }
NetworkQuality.addListener(listener)
// onDestroy / 页面退出时务必移除，否则泄漏
NetworkQuality.removeListener(listener)
```

### 2.4 读指标（分阶段耗时 / 分位）

```kotlin
val s = NetMetrics.summary()
// s.p50TotalMillis / p90 / p99      总耗时分位（弱网问题看 p90/p99）
// s.avgDnsMillis / avgConnectMillis / avgTlsMillis   各阶段均值（-1=该阶段被复用跳过）
// s.p90FirstByteMillis              首包 P90
// s.connectionReuseRate / cacheHitRate

NetMetrics.recent(20)   // 逐条记录：状态码、协议、各阶段、是否复用、错误信息
```

### 2.5 查 DNS 缓存 / 熔断状态

```kotlin
Log.i("X", NetClient.dnsDebugState())
// 输出每个域名的解析结果 + 剩余 TTL + 当前熔断的 IP
```

---

## 3. 常见需求怎么做

| 我想… | 怎么做 |
|---|---|
| 加一个 http 接口 | Retrofit 里加方法即可，client 已共享，无需配置 |
| 加一个新域名的请求 | 直接发，DNS 缓存与度量自动生效（首次会走解析） |
| 只对某个接口放宽/收紧超时 | 单独设置该 Call 的超时：`val c = client.newCall(req); c.timeout().timeout(5, TimeUnit.SECONDS); c`（不要改全局）；或用 `NetClient.build(自定义 NetConfig)` |
| 新增一个度量维度 | 改 [NetEventListener](NetEventListener.kt) 的 per-call 状态 + [NetMetrics.Record](NetMetrics.kt) 字段（见 §4） |
| 上报指标到服务端 | 定时读 `NetMetrics.summary()`/`recent()`，走 `ThreadPools.background` 批量上报（不要逐条实时发） |
| 从 JSON 换 Protobuf | 换 Retrofit converter 即可，与 client 无关 |
| 换 HTTPDNS | 替换 [NetClient.dns](NetClient.kt) 的 `servers` 或换一个 `Dns` 实现（见 §5） |

---

## 4. 扩展点

### 4.1 换 DNS 后端（HTTPDNS）

`InterviewDns` 的构造参数是「上游 DNS 服务器」：

```kotlin
// NetClient.kt 里
InterviewDns(servers = listOf(InetSocketAddress("你的httpdns-host", 53)))
```

⚠️ 现在的实现走 **UDP 明文 53**，能多 IP / 拿 TTL / 熔断坏 IP，但**不防劫持**。
真 HTTPDNS（DoH/DoT + 服务端就近调度）需要另写一个 `Dns` 实现替换掉它 —— 接口就是
`okhttp3.Dns`，改动点只有 [NetClient.dns](NetClient.kt) 一处。

### 4.2 加一个指标字段

1. `NetEventListener`：在对应回调里记字段（per-call，无需同步）；
2. `NetMetrics.Record`：加字段；
3. `NetMetrics.summary()`：加聚合；
4. [NetLabActivity](NetLabActivity.kt)：在「逐条记录」里展示。

### 4.3 调整策略参数

所有可调参数在 [NetConfig.kt](NetConfig.kt)：超时、重试次数、退避基数、连接池、Dispatcher。
分三档：`DEFAULT`（API 档）/ `DOWNLOAD`（下载档）/ `WEAK_NETWORK_EXPERIMENT`（实验档）。
每个参数旁都标了「推导 / 惯例 / 经验 / 待实测」，改之前先看注释。

---

## 5. 目前**没有**的能力（别踩空）

| 能力 | 现状 | 正解 |
|---|---|---|
| DNS 防劫持 / 精准调度 | UDP 明文，无 | 换 DoH/DoT 的 `Dns` 实现 |
| 断点续传（Range） | 无，下载档不重试 | 分片 + 本地记录偏移 |
| HTTP/3、QUIC、BBR | 无（OkHttp 不支持） | Cronet，或 Rust(quinn) 实验模块 |
| Brotli 压缩 | 无（只有默认 gzip） | 加 `okhttp-brotli` 依赖 |
| 请求合并 / 批量上报 / 降级开关落地 | 无 | 业务调度层，参考 `ThreadPools` 泳道 |

---

## 6. 排障

### 6.1 logcat 过滤 TAG

| TAG | 看什么 |
|---|---|
| `NetLab` | 实验页请求失败 |
| `NetMetrics` | 指标相关 |
| `NetworkQuality` | 网络切换 / 质量分档变化 |
| `InterviewDns` | 熔断坏 IP、缓存、回退系统解析 |
| `NetRetry` | 重试与退避 |
| `NetClient` | 客户端构造 |

### 6.2 现象 → 原因对照

| 现象 | 可能原因 |
|---|---|
| 首次请求慢、后续快 | 正常：首次含 DNS+TCP+TLS；后续 `reused=Y`，各阶段为 `-1` |
| 某域名请求反复超时 | DNS 拿到坏 IP → 看 `InterviewDns` 是否在熔断；或链路本身不通 |
| `successRate` 掉但系统显示有网 | 假连接，`NetworkQuality` 会判 `WEAK` |
| 请求「凭空消失」 | `ThreadPools.network.execute` 返回了 false 没处理（被背压拒绝） |
| 弱网下 P99 极高 | 看 `recent()` 里每条的各阶段，定位是 DNS / 建连 / 首包哪一段 |
| 大图下载中途失败 | 下载档不重试，需自行做续传（§5） |

### 6.3 真机实验页

从 Launcher 进「网络优化 Lab」，建议顺序：
`1 网络质量 → 3 单次API → 5 指标分位 → 6 逐条记录`；
`4 并发10次` 看连接复用率从 0% 升起（第二次起 `reused=Y`）；
`7 对照` 看自定义 DNS 热缓存（≈0ms）。

---

## 7. 测试

```bash
# 纯逻辑单测（秒级，无需设备）：打分 / 退避 / 幂等 / 分位 / DNS 编解码
./gradlew :app:testDebugUnitTest --tests "com.interview.net.NetLayerTest"
```

新增「会算错但不会崩」的逻辑（策略、算法），请抽成纯函数放进去加用例 ——
这是本仓库对 native kernel 的同一套纪律（可测的与需设备的隔离）。

---

## 8. 红线（这几个别做）

1. **不要 `new OkHttpClient`** —— 每多一个自建 client，就碎掉一份连接池与 DNS 缓存。
   需要不同参数就 `NetClient.build(NetConfig(...))`。
2. **不要在 Activity 里裸执行网络** —— 一律 `ThreadPools.network.execute`。
3. **不要忽略 `execute` 的返回值** —— 那是背压信号。
4. **不要把非幂等 POST 配成可重试** —— 除非服务端支持 `Idempotency-Key`（拦截器按此判定）。
5. **不要自己读 `ConnectivityManager` 做策略** —— 用 `NetworkQuality.level`，那里融合了真实请求质量。
