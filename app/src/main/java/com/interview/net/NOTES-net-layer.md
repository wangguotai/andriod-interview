# 网络层优化 NOTES（OkHttp 实践）

本目录把「网络优化清单」落到 OkHttp 上，按**感知 → 度量 → DNS → 策略 → 收口**分层。
每层都是可独立测试/观测的，避免把优化做成「一堆散落的 if」。

## 目录结构

| 文件 | 层次 | 职责 |
|---|---|---|
| `NetworkLevel.kt` | 策略（纯逻辑） | 质量打分规则，零 Android 依赖，可 JVM 单测 |
| `NetworkQuality.kt` | 感知 | ConnectivityManager 事件 + 请求结果回喂的滚动窗口 |
| `NetMetrics.kt` | 度量 | 分阶段耗时 + P50/P90/P99 + 逐条记录 |
| `NetEventListener.kt` | 度量采集 | 挂在 OkHttp 网络栈内部的阶段钩子 |
| `DnsResolver.kt` | DNS | 纯 JDK 的 UDP 报文解析（A/AAAA） |
| `InterviewDns.kt` | DNS | TTL 缓存 + 多 IP 排序 + 坏 IP 熔断 |
| `AdaptiveRetryInterceptor.kt` | 策略 | 自适应超时 + 幂等重试 + 指数退避抖动 |
| `NetConfig.kt` | 配置 | 所有可调参数（API 档 / 下载档 / 实验档） |
| `NetClient.kt` | 收口 | 唯一的 OkHttpClient 工厂与共享实例 |
| `NetLabActivity.kt` | 实验 | 真机证据链（文本：逐条记录 / 路由判定 / pin / 取消回退） |
| `NetDashboardActivity.kt` + `dashboard/` | 实验 | 图形仪表盘：阶段堆叠 / 长尾分位 / 切网事件 / 弱网模拟器 |

## 与优化清单的对应（已落地 / 未落地）

| 清单项 | 状态 | 落点 |
|---|---|---|
| 分阶段耗时监控 | ✅ | `NetEventListener`（DNS/TCP/TLS/首包/总耗时） |
| 耗时 P50/P90/P99 | ✅ | `NetMetrics.summary()` |
| 弱网单独统计 | ✅ | `NetworkQuality`（按 transport + RTT/成功率分档） |
| 网络质量感知 / 动态超时 | ✅ | `NetworkScoring` + `adaptiveTimeoutMillis()` |
| DNS 缓存 / 多 IP | ✅ | `InterviewDns`（TTL 缓存 + 多 IP + 熔断） |
| 幂等重试 + 退避抖动 | ✅ | `AdaptiveRetryInterceptor` |
| 连接池 / Dispatcher 治理 | ✅ | `NetClient.build()` |
| HTTPDNS / DoH（防劫持 + 精准调度） | ❌ | 见下「诚实的缺口」 |
| 连接复用度量 | ✅ | EventListener `connectionAcquired` + `connectStart` |
| 大文件断点续传（Range） | ❌ | TODO（下载档已留位） |
| HTTP/3 / QUIC / BBR | ❌ | 需 Cronet 或 Rust(quinn) —— 超出 OkHttp 能力 |
| Brotli / Protobuf | ❌ | OkHttp 默认 gzip；Brotli 需 `okhttp-brotli` 依赖 |
| 请求合并 / 批量上报 / 降级开关 | ❌ | 属业务调度层，见 `ThreadPools` 泳道 |

## 诚实的缺口（别把这些当成已解决）

1. **本 DNS 不是 HTTPDNS**。走 UDP 明文 53，能拿多 IP 与 TTL、能熔断坏 IP，
   但**不能防劫持/投毒**，也不能按用户位置精准调度。真正的 HTTPDNS 需要
   DoH/DoT（HTTPS/TLS 传输）+ 服务端调度 —— 是另一个模块的量级。
2. **DNS 解析未读取服务端 TTL**，统一用 60s 策略值（见 `InterviewDns.DEFAULT_TTL_MILLIS`）。
3. **无断点续传**。下载档 `maxAttempts=1`，弱网下大图中断即从头再来。
   正解是 Range 分片 + 本地记录已下载偏移。
4. **参数（阈值/系数/超时）多为经验值**，代码里已逐条标注「经验/惯例/待实测」。
   上线前应结合 `NetMetrics` 的真实分位分布回调。

## 验证方式

```bash
# 纯逻辑单测（秒级，无需设备）
./gradlew :app:testDebugUnitTest --tests "com.interview.net.NetLayerTest"

# 真机：安装后从 Launcher 进入「网络优化 Lab」
#   1 网络质量 → 3 单次API → 5 指标分位 → 6 逐条记录
#   4 并发10次：观察连接复用率从 0% 升起来
#   7 对照：系统 DNS vs 自定义 DNS
# logcat 过滤：NetLab / NetMetrics / NetworkQuality / InterviewDns / NetRetry
```

## 设计纪律（与仓库既有约定一致）

- **收口**：`NetClient` 是全 App 唯一允许 `OkHttpClient.Builder()` 出现的地方。
  `ImageDownloader` 与 Retrofit 都已改为取用共享 client —— 每多一个自建 client，
  就碎掉一份连接池与 DNS 缓存。
- **执行分档**：API 档（RTT 受限）与下载档（带宽受限）是两个 client，参数不同，
  但共享同一份 DNS 与 EventListener。这与 `ThreadPools`「治理收口、执行分道」同源。
- **策略与平台隔离**：能算错的逻辑抽成纯函数（`NetworkScoring`、`percentile`、
  DNS 报文编解码），用 JVM 单测钉死；需要设备的部分交给 NetLab。
- **不裸线程**：NetLab 的请求全部提交到 `ThreadPools.network`，不在 Activity 里 new Thread。
