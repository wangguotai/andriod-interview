# 线程治理方案 · 工程实现

配套笔记：[../线程治理/线程滥用与治理.md](../线程治理/线程滥用与治理.md)

本目录是那套四层防线的**可运行实现**，四条防线都在 API 36 模拟器上实测通过。

---

## 目录结构

```
app/src/main/java/com/interview/thread/
├── ThreadPools.kt              第1步 规范层：治理入口 + 四条隔离泳道 + 命名工厂
├── ThreadMisuseScenarios.kt    第1步 对照：故意复现的 4 种线程失控劣习
├── ThreadMonitor.kt            第2步 监控层：线程快照 / 命名率 / 前缀聚类
├── NativeThreadHook.kt         第4步 Native：Hook 的 Java 侧入口
├── ThreadDefense.kt            第4步 兜底：栈压缩 + 限流降级
├── UnifiedThread.kt            第3步 收敛层：ASM 替换的目标类
├── ThreadGovernanceActivity.kt Demo 宿主（所有验证入口）
└── ../../../../../cpp/thread_hook.cpp   第4步 Native：GOT Hook 实现

build-logic/thread-plugin/       第2/3步 ASM Gradle 插件（独立构建）
├── ThreadMonitorPlugin.kt       插件入口
├── ThreadAsmVisitorFactory.kt   AGP AsmClassVisitorFactory 接入
├── ThreadClassVisitor.kt        指令级变换核心
└── ThreadMonitorExtension.kt    配置项
```

---

## 实测结果（API 36 模拟器）

| 防线 | 验证方式 | 结果 |
|---|---|---|
| 第1步 规范层 | 打印各池配置 | ✅ 5 个统一池就绪，命名工厂生效 |
| 第1步 对照 | 制造失控场景 | ✅ 裸Thread×10 / 未关池×5 / 多SDK池×4 / HandlerThread×5 |
| 第2步 监控 | 线程快照 | ✅ 45 个线程，按前缀聚类，堆栈可追到调用点 |
| 第2步 命名率 | 对照实验 | ✅ 统一池 `app-bg-N` 可溯源；默认工厂 `pool-5-thread-M` 不可溯源（20.0%）|
| 第3步 收敛 | 开启 enableUnify | ✅ **线程总数 45 → 24，业务线程 29 → 8** |
| 第4步 Native | GOT Hook libart.so | ✅ 累计捕获 **139 次**线程创建 |
| 第4步 栈压缩 | 50 个线程对照 | ✅ 默认栈与 256KB 栈均创建成功（64 位未撞 OOM，符合预期）|
| 第4步 限流 | 提交 30 个任务 | ✅ 拒绝 22 个，活跃数守住上限 8 |
| **泳道隔离** | 4×800ms 慢任务 + 1×20ms 快任务 | ✅ **快任务等待 839ms → 3ms** |

**最硬的一条证据 —— 泳道隔离对照**（`1.泳道隔离对照` 按钮实测输出）：

```
场景：先提交 4 个 800ms 的磁盘任务，再提交 1 个 20ms 网络任务

A. 单一 IO 池（core=4 max=16 无界队列）
   网络任务等待 = 839ms  ← 被 4 个慢任务堵住
   注：max=16 不会救场，无界队列使扩容条件永不成立

B. 泳道隔离（disk 泳道跑慢任务，net 泳道跑网络任务）
   网络任务等待 = 3ms    ← 网络泳道空闲，立即执行

结论：并发上限相同（4 vs 4），但泳道把「无关任务互相拖累」消灭了。
```

实测指标（`1.统一池` 按钮）：

```
net    core=4 max=8 实际=1 队列=0/32   提交=1 拒绝(队列/配额)=0/0 waitP99=1ms
disk   core=2 max=4 实际=2 队列=0/16   提交=4 拒绝(队列/配额)=0/0 waitP99=808ms
db     core=1 max=1 实际=0 队列=0/64   提交=0 拒绝(队列/配额)=0/0 waitP99=0ms
bg     core=1 max=2 实际=0 队列=0/128  提交=0 拒绝(队列/配额)=0/0 waitP99=0ms
legacy-io core=4 max=16 实际=4 队列=无界 提交=5 waitP99=838ms   ← 反面教材
```

`waitP99` 直接量化了问题：`disk=808ms`（被自己的慢任务排队）vs `net=1ms`（无阻塞）。


**ASM 插桩的字节码证据**（`javap` 反汇编实际产物）：

```java
// 插桩前
21: invokespecial  java/lang/Thread."<init>":(Ljava/lang/Runnable;)V

// 插桩后（变换 A：命名）
21: ldc_w          "com.interview.thread.ThreadMisuseScenarios"
24: invokespecial  java/lang/Thread."<init>":(Ljava/lang/Runnable;Ljava/lang/String;)V

// 插桩后（变换 B：收敛，NEW 与 <init> 同步改写）
12: new            com/interview/thread/UnifiedThread
24: invokespecial  com/interview/thread/UnifiedThread."<init>":(Ljava/lang/Runnable;Ljava/lang/String;)V
```

---

## 泳道设计：为什么不是「全 App 一个 IO 池」

第一版实现是单一 IO 池，重构为「**一个治理入口 + 四条隔离泳道**」。理由是单一池有三处硬伤：

1. **队头阻塞**：`LinkedBlockingQueue` 是 FIFO，前面堵 4 个 30s 的文件扫描，
   后面所有 50ms 的配置请求一起等 30s（实测 839ms vs 3ms 就是这个）。
2. **下游资源异构**：网络受带宽约束、磁盘随机 IO 队列深度低、SQLite 单写者。
   用一个并发数服务三种资源，对每种都是错的。
3. **全局故障面**：一个 SDK 失控提交慢任务，全 App 的 IO 一起瘫痪 ——
   等于在另一个层面重建了「线程滥用」本身。

**治理能力（命名/配额/监控）收在入口层，执行能力按下游资源分道。**
「收口」收的是治理，不是物理池。

### 泳道大小的依据

**不是**服务器公式 `核数 × (1 + W/C)` —— 那是追求 CPU 利用率最大化的算法，
移动端的目标函数是「延迟 + 功耗」，故意不这么算。真实依据：

| 泳道 | core/max | 依据 |
|---|---|---|
| `net` | 4 / 8 | 移动端带宽是共享管道，加并发只是均分；射频 race-to-sleep 要求集中突发。对齐 OkHttp `maxRequestsPerHost=5` |
| `disk` | 2 / 4 | 手机 UFS/eMMC 随机 IO 队列深度低，加并发只推高**所有**请求延迟，不增加吞吐 |
| `db` | 1 / 1 | SQLite 单写者（WAL 下亦然），>1 纯粹在 DB 锁上排队 |
| `bg` | 1 / 2 | 后台预取/上报，低优先级 + 大配额，可牺牲延迟换吞吐 |

精确值应由 **Little's Law** 从低端机实测推出（并发数 = 到达率 λ × 任务时长 W），
eMMC 机型的 λ 和 W 会同时变差。上表是保守起点，不是终点。

### 三处对旧设计的修正

**修正 1：`maximumPoolSize` 从死配置变成生效。** 旧版 `LinkedBlockingQueue()`
容量是 `Integer.MAX_VALUE`，而 `execute()` 第 3 步要求「队列满才扩容」——
永不成立，实际并发上限恒为 core=4，写 16 是自欺。现在队列有界（16/32/64/128），
扩容条件真正可达。

**修正 2：`CallerRunsPolicy` → `AbortPolicy`。** 旧注释写「降级为调用者线程执行，
避免 OOM」，但有两个问题：它对无界队列引发的堆积**无效**（那是真 OOM 来源），
而且泳道可以被**主线程**提交——饱和时让主线程去跑 IO 任务 = 直接 ANR。
现在改成 Abort，把背压显式抛回调用方（`execute` 返回 `false`），
由调用方决定丢弃/降级/上报。

**修正 3：优先级改用 `Process.setThreadPriority`。** 在 Android 上
`Thread.setPriority(NORM_PRIORITY - 1)` 效果很弱；真正影响调度的是
`android.os.Process.setThreadPriority`，它会切换调度 cgroup、影响 CPU 配额。
⚠️ 有个陷阱：`setThreadPriority` 作用于**当前线程**，若在 `newThread()` 里调用
只会改到创建者线程，必须包一层在新线程的 `run` 里设置。

### 可观测性

`Lane.metrics()` 暴露 `queueDepth` / `waitP99Millis` / 拒绝计数。
**`queueDepth` 才是「任务是否堆积」的直接证据**，`poolSize` 看不出来；
`waitP99`（入队到开始执行的等待）比线程数更能反映真实压力。
另外每条泳道有 `quotaPerCaller`，挡住「一个模块吃满整条泳道」。

---

## ⚠️ 两个真实的平台坑（本 Demo 踩过并修复）

这两处是写这套方案时最容易翻车的地方，也是本实现相较于网上简易示例的主要价值。

### 坑 1：只改 `<init>` 的 owner 会抛 VerifyError

变换 B 把 `java/lang/Thread` 换成 `UnifiedThread` 时，**必须同时修改两条指令**：

```java
NEW com/interview/thread/UnifiedThread   // ← 必须改
DUP
INVOKESPECIAL com/interview/thread/UnifiedThread.<init>   // ← 必须改
```

字节码校验器要求「`NEW` 出来的未初始化类型」与「所调构造函数的 owner」一致。
只改其中一条 → 类加载时 `VerifyError`。

### 坑 2：Android 的 linker 不重定位 `.dynamic` 段指针

这是 **Android 与 glibc 的真实差异**，写 Native Hook 必踩：

```cpp
// 错误：直接当绝对地址用
strtab = (const char*) d->d_un.d_ptr;   // 拿到 0x13db0，是个偏移！

// 正确：Android linker 刻意不重定位 .dynamic（保持该段只读），
// 需要自己加 load_bias
strtab = (const char*)(base + d->d_un.d_ptr);
```

实测数据（libart.so，base=`0x6f2f800000`）：

```
strtab=0x13db0  symtab=0x2f8  jmprel=0x39e50    ← 未重定位的偏移
```

配套的两个连带错误：
- 定位 `PT_DYNAMIC` 要用 **`p_vaddr`** 而非 `p_offset`（非首个 PT_LOAD 段中两者不等）
- 校验时别检查 `.dynstr` 首字节非空——**ELF 规范里首项就是空字符串**，首字节必然为 0

### 其他要点

- **目标模块是 `libart.so` 而非 `libc.so`**：libart 通过自己的 PLT/GOT 调 `pthread_create`，改 libc 的 GOT 拦不到。
- **`libart.so` 在 Android 10+ 无法 `dlopen`**（linker namespace 限制），必须用 `dl_iterate_phdr` 遍历已加载模块定位。
- **REL 与 RELA 结构体大小不同**（REL 无 addend），用错会导致重定位表遍历错位，需按 `DT_PLTREL` 分支。
- **Hook 点绝不能做重活**：本实现只计数 + 一次轻量 JNI 回调；若在 Hook 点抓全量堆栈会拖慢线程创建本身。

---

## 能力边界（诚实的部分）

**Native Hook 拿不到创建者的 Java 堆栈。** 原因是 Hook 触发时新线程刚创建、尚未 attach 到 JVM，
此时 `Thread.getAllStackTraces()` 里还没有它。

所以实践中三者是互补的，不能相互替代：

| 手段 | 能拿到什么 | 局限 |
|---|---|---|
| ASM 插桩 | 编译期就确定「哪个类的哪一行创建了线程」 | 加固/加壳的 SDK 插不进去 |
| Java 采样 | 运行期完整堆栈 | 采样时刻才知道，且 `getAllStackTraces` 有开销 |
| Native Hook | **所有**线程创建事件（含三方 SDK），不会漏 | 拿不到 Java 堆栈，只有事件计数 |

**栈压缩的收益视位宽而定**：省的是虚拟地址空间而非物理内存，所以对 32 位设备意义最大；
64 位下 50 个线程远未触及地址空间上限（实测全部创建成功）。

---

## 关于 `enableUnify` 默认关闭

收敛层会**改变程序运行时语义**（线程不再真正新建），有真实风险：

- 依赖 `Thread.start()` 真实语义的代码可能行为异常（如 `ThreadLocal` 隔离、线程专属上下文）
- 被收敛的 Runnable 若依赖「在自己线程里执行」的假设（如 Looper 相关），会出问题

所以默认 `false`，仅作演示与验证。生产环境若要开启，建议：
1. 先在**单模块**小范围收口（如只收敛 OkHttp 的调度线程池），而非全局
2. 白名单排除系统关键路径
3. 灰度 + 崩溃率监控

---

## 如何运行

```bash
# 装到设备后，从桌面启动「线程治理 Demo」，或：
adb shell am start -n com.example.myapplication/com.interview.thread.ThreadGovernanceActivity

# 观察日志
adb logcat -s ThreadDemo:D ThreadMonitor:D ThreadMisuse:D ThreadDefense:D ThreadHook:D UnifiedThread:D
```

按钮对应关系：

| 按钮 | 作用 |
|---|---|
| 1.统一池 | 打印四条泳道 + 其他池的配置与实时指标（含 queueDepth / waitP99）|
| 1.泳道隔离对照 | 单一池 vs 泳道的队头阻塞对照实验 |
| 1.制造失控 | 投放 4 类线程失控场景（后续所有验证的靶子）|
| 2.线程快照 | 全量线程 + 状态 + 堆栈 + 命名率 |
| 2.命名率对照 | 统一池 vs 默认工厂的命名率差异 |
| 4.栈压缩对照 | 不同栈大小的创建成功率 |
| 4.限流降级 | 超并发上限的拒绝行为 |
| 4.Native Hook | 安装 pthread_create Hook |
| Hook 统计 | 查看 Native 捕获次数 |

## 构建说明

插件位于独立构建 `build-logic/`，通过 `settings.gradle.kts` 的 `includeBuild` 接入：

```kotlin
// app/build.gradle.kts
plugins { id("com.interview.thread.monitor") }
configure<com.interview.thread.plugin.ThreadMonitorExtension> {
    enableNaming.set(true)    // 默认开：低风险，只加个参数
    enableUnify.set(false)    // 默认关：改变运行时语义，有风险
}
```

> ⚠️ AGP 的 `transformClassesWith` 接收 Kotlin 的 `Function1<ParamT, Unit>`，
> **Java lambda 无法满足该签名**（编译报 "void 无法转换为 Unit"），插件必须用 Kotlin 编写。
