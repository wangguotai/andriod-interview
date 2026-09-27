# 线程治理方案 · 工程实现

配套笔记：[../线程治理/线程滥用与治理.md](../线程治理/线程滥用与治理.md)

本目录是那套四层防线的**可运行实现**，四条防线都在 API 36 模拟器上实测通过。

---

## 目录结构

```
app/src/main/java/com/interview/thread/
├── ThreadPools.kt              第1步 规范层：统一线程池 + 命名 ThreadFactory
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
| 第2步 命名率 | 对照实验 | ✅ 统一池 `app-io-N` 可溯源；默认工厂 `pool-5-thread-M` 不可溯源（20.7%）|
| 第3步 收敛 | 开启 enableUnify | ✅ **线程总数 45 → 24，业务线程 29 → 8** |
| 第4步 Native | GOT Hook libart.so | ✅ 累计捕获 **139 次**线程创建 |
| 第4步 栈压缩 | 50 个线程对照 | ✅ 默认栈与 256KB 栈均创建成功（64 位未撞 OOM，符合预期）|
| 第4步 限流 | 提交 30 个任务 | ✅ 拒绝 22 个，活跃数守住上限 8 |

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
| 1.统一池 | 打印 5 个统一池配置 |
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
