# ASM 线程插桩 · 阅读顺序

> 这份文档解决一个问题：**这个模块只有两个类、不到 150 行，但新人上手会卡住。**
> 卡住的地方不是代码量，而是「不知道自己在看什么」——
> ASM 的 Visitor 没有 main 函数，AGP 的调用栈也看不见。
>
> 所以下面按「先建立心智模型，再看代码」的顺序编排，每一步都给出**可以自己跑出结果**的命令。

---

## 0. 先回答：这段代码到底在解决什么问题

一句话：**把 `new Thread` 这件事，从「运行时不可控」变成「编译期可改写」。**

为什么需要它？因为线程治理有四层，各层能覆盖的范围不同：

| 层 | 手段 | 能治谁 | 治不了谁 |
|---|---|---|---|
| 1 | 自定义 Lint（`thread-lint/`） | 有源码的自有代码 | **三方 SDK（二进制依赖，看不到源码）** |
| 2 | 监控（`ThreadMonitor.kt`） | 只能看见，不能改变 | — |
| 3 | **ASM 插桩（本模块）** | **编译期改写字节码，含依赖里的 class** | 加固/加壳的 SDK |
| 4 | Native Hook（`cpp/thread_hook.cpp`） | 运行时所有线程创建 | 成本高、无编译期信息 |

**本模块 = 第 3 层。** 它的存在理由就是那句「Lint 看不到 SDK 的源码」。
如果只想跑通 Demo，`enableNaming` 也是实际生效的（D13 已用实测数据确认）。

### 它做的两件事（默认只开第一件）

```kotlin
// app/build.gradle.kts
configure<com.interview.thread.plugin.ThreadMonitorExtension> {
    enableNaming.set(true)    // A：给匿名线程补名字（默认开，低风险）
    enableUnify.set(false)    // B：把 new Thread 收敛到统一池（默认关，改变运行时语义）
}
```

| 开关 | 字节码层面做什么 | 风险 |
|---|---|---|
| `enableNaming` | 在 `<init>` 前插一条 `LDC`，并改描述符加一个 `String` 参数 | 低：只多一个参数 |
| `enableUnify` | 把 `NEW`/`<init>` 的 owner 从 `java/lang/Thread` 换成 `UnifiedThread` | 高：线程不再真正新建 |

---

## 1. 阅读顺序（照这个顺序看，不要跳）

### 第 1 站：先看「产物」，再看「代码」

**不要一开始就读 `ThreadClassVisitor.kt`。** 先建立「插桩前后字节码长什么样」的直觉，
否则你会看不懂那 40 行 `transform()` 在干什么。

先构建一次（生成插桩产物）：

```bash
./gradlew :app:transformDebugClassesWithAsm
```

然后用 `javap` 做 A/B 对照 —— **这是理解整个模块最快的方式**。
`app/build/tmp/kotlin-classes/debug/` 是插桩**前**（Kotlin 编译原始产物），
`app/build/intermediates/classes/debug/transformDebugClassesWithAsm/dirs/` 是插桩**后**：

```bash
# 插桩前：注意 21 行是 (Ljava/lang/Runnable;)V —— 只有一个参数
javap -c -p -classpath app/build/tmp/kotlin-classes/debug \
  com.interview.thread.ThreadMisuseScenarios | grep -B4 'Thread."<init>"'

# 插桩后：多了 ldc_w 传类名，描述符变成 (Ljava/lang/Runnable;Ljava/lang/String;)V
javap -c -p -classpath app/build/intermediates/classes/debug/transformDebugClassesWithAsm/dirs \
  com.interview.thread.ThreadMisuseScenarios | grep -B4 'Thread."<init>"'
```

本仓库实测输出（已跑通）：

```java
// ── 插桩前 ──
12: new           java/lang/Thread
15: dup
16: invokedynamic run:()Ljava/lang/Runnable;
21: invokespecial java/lang/Thread."<init>":(Ljava/lang/Runnable;)V      // ← 无 name
24: invokevirtual java/lang/Thread.start:()V

// ── 插桩后 ──
12: new           java/lang/Thread
15: dup
16: invokedynamic run:()Ljava/lang/Runnable;
21: ldc_w         "com.interview.thread.ThreadMisuseScenarios"             // ← 新增：调用者类名
24: invokespecial java/lang/Thread."<init>":(Ljava/lang/Runnable;Ljava/lang/String;)V  // ← 多一个参数
27: invokevirtual java/lang/Thread.start:()V
```

**看到这两段的差异，你就已经理解这个模块 80% 的价值了。**

> 💡 什么时候这一步会「看不到变化」？
> `app/build/` 是构建缓存，**旧产物可能残留**。如果发现插桩前后一模一样：
> ① 确认 `./gradlew :app:transformDebugClassesWithAsm` 真的执行了（EXIT 0）；
> ② 直接比对文件哈希 `md5 <插桩前> <插桩后>`，相同就说明该类确实没被改写（见下一站）。

---

### 第 2 站：`ThreadAsmVisitorFactory.kt` —— 先搞清「框架怎么调用我」

这个类只回答两个问题，但**必须看懂，否则后面所有代码都没有上下文**：

1. **哪些类进得来？** → `isInstrumentable(classData)`
2. **进来之后谁处理？** → `createClassVisitor(classContext, next)`

关键认知：**这不是你调用框架，是框架回调你。** 没有 main、没有显式调用点。

```kotlin
override fun isInstrumentable(classData: ClassData): Boolean
```

`classData` 由 AGP 提供（源码见 `ClassDataImpl.kt`），四个字段：

| 字段 | 内容 | 本项目用到了吗 |
|---|---|---|
| `className` | **JVM 内部名**，如 `com/interview/thread/Foo`（斜杠，不是点） | ✅ 唯一的判断依据 |
| `classAnnotations` | 类上的注解 | ❌ |
| `interfaces` | 自己 + 父类实现的所有接口 | ❌ |
| `superClasses` | 自己 + 父类的所有父类 | ❌ |

> ⚠️ 这解释了 `currentClass.replace('/', '.')` —— `className` 是**斜杠形式**，
> 但 `LDC` 出来的线程名要给人看，所以转成点号。

本项目的过滤策略是**「放行」而非「拦截」**（排除 android/java/kotlin 等基础包，
其余全进），理由写在代码注释里。这个取舍是**正确**的 —— 但有个前提没成立，见 D13「已知问题」。

---

### 第 3 站：`ThreadClassVisitor.kt` —— 指令级变换核心

**这是唯一有真正技术含量的一站。** 读之前先接受三个事实（代码注释里有，这里给直觉）：

#### 事实 1：为什么要 `DUP`？

```
NEW java/lang/Thread      // ① 在栈上放一个「未初始化的对象引用」
DUP                       // ② 复制一份：一份给 <init> 消耗，一份留给后面用
INVOKESPECIAL <init>      // ③ 消耗栈顶那份，把对象「初始化」
ASTORE n                  // ④ 剩下那份存进局部变量表
```

`INVOKESPECIAL` 调用构造函数时会**隐式消耗**栈顶的引用。所以 `DUP` 不是多余的，
没有它就凑不出「调用完还能拿到对象」的效果。
**这也是为什么不能简单地「在 <init> 之后插入代码」——栈的平衡是精确的。**

#### 事实 2：为什么不能整体替换 Thread？

因为 `ASTORE n` / `ALOAD n` 的下标 `n` **依赖上下文**：

- 实例方法里 `0` 号槽是 `this`
- 静态方法里 `0` 号槽是第一个参数

编译时无法预知每个 `Thread` 对象被存到哪个槽、被怎么用。
所以「把 Thread 全换成 MyThread」会踩坑。

**→ 这直接推导出本项目的核心洞察：「不统一 Thread，而是统一 Runnable 的执行环境」。**
Runnable 是干净的突破口（`Thread` 的各种构造函数都能接 Runnable）。

#### 事实 3：改 owner 必须同时改 NEW（否则 `VerifyError`）

这是**最关键的坑**，代码里也标了 ⚠️：

```java
NEW com/interview/thread/UnifiedThread          // ← 必须改
DUP
INVOKESPECIAL com/interview/thread/UnifiedThread.<init>   // ← 必须改
```

字节码校验器要求「`NEW` 出来的未初始化类型」与「所调构造函数的 owner」**必须一致**。
只改其中一条 → 类加载时 `VerifyError`。

对应代码：[`ThreadClassVisitor.rewrite()`](src/main/kotlin/com/interview/thread/plugin/ThreadClassVisitor.kt)
里把两行绑在一起：

```kotlin
if (enableUnify) {
    newInsn?.desc = UNIFIED_THREAD     // 改 NEW
    ctor.owner = UNIFIED_THREAD        // 改 <init>  —— 两行必须在一起
}
```

#### 然后看「延迟 emit」这个设计

`visitMethod` 返回的不是 `mv`（直接透传），而是一个 `MethodNode`：

```kotlin
override fun visitMethod(...): MethodVisitor? {
    val mv = super.visitMethod(...) ?: return null
    return object : MethodNode(Opcodes.ASM9, ...) {
        override fun visitEnd() {
            transform(this)   // ← 指令全部收齐后，才做变换
            accept(mv)        // ← 变换完再往下游吐
        }
    }
}
```

**为什么要这样？** 因为要「配对 `NEW java/lang/Thread` 和它对应的 `<init>`」——
必须拿到**完整指令链**做前后文分析，流式处理（边读边改）做不到。
代价是每个方法都要在内存里存一份指令列表。

> 这是 ASM 里最经典的取舍：**`ClassVisitor` 流式处理省内存但没全局视野，
> `MethodNode` 树式处理有全局视野但吃内存。**

#### 最后看「精确匹配」如何避免误伤

```kotlin
node.opcode == Opcodes.NEW && node is TypeInsnNode && node.desc == THREAD
```

只认 `NEW java/lang/Thread` 这一种。子类（如 `class SpThread : Thread()`）的构造函数里
对 `super()` 的调用**前面没有** `NEW java/lang/Thread`，所以不会被误伤。

这个设计经实测验证是**正确**的（D13 记录）：`class AsmProbe2(r: Runnable) : Thread(r)`
这类子类，`super()` 会被正常补上 name，**不会**出现参数类型错配。

---

### 第 4 站：`UnifiedThread.kt` —— 变换 B 的运行时落点

看完变换核心再来看这个类，就很好理解了：

```kotlin
override fun start() {
    val r = task ?: return          // 无 Runnable 就直接跳过（无参构造）
    try {
        val accepted = ThreadPools.converged.execute(CALLER, r)
        if (!accepted) r.run()      // 池满 → 降级原地执行，业务不中断
    } catch (e: Throwable) {
        r.run()                     // 异常也要降级，不能吞掉业务
    }
}
```

**设计要点：不真正 `start()`，而是把 Runnable 丢进 `ThreadPools.converged` 泳道。**
所以它必须**兼容所有被替换的 Thread 构造函数签名**（代码里有 8 个构造重载），
且 `start()` 里绝不能做阻塞操作。

注意这里的降级策略：**收敛层是「尽力而为」的优化，不是「必须成功」的依赖** ——
池满了就原地跑，保证业务不中断。这个取舍值得学习。

---

### 第 5 站（可选）：`ThreadMonitorPlugin.kt` —— 入口与接线

最后回头看插件入口，就一目了然了。真正有用的信息是**接入链路**：

```
app/build.gradle.kts:4        id("com.interview.thread.monitor")
        ↓
settings.gradle.kts           includeBuild("./build-logic")        ← 把插件当独立构建引入
        ↓
ThreadMonitorPlugin.apply()   onVariants { variant.instrumentation.transformClassesWith(...) }
        ↓
ThreadAsmVisitorFactory       ← AGP 回调 isInstrumentable / createClassVisitor
        ↓
ThreadClassVisitor            ← 指令级变换
```

⚠️ 一个容易踩的坑（代码注释里也写了）：AGP 的 `transformClassesWith` 接收的是
Kotlin 的 `Function1<ParamT, Unit>`，**Java lambda 无法满足该签名**
（编译报 "void 无法转换为 Unit"），所以这个插件**必须用 Kotlin 写**。

---

## 2. 推荐的自学节奏

| 步骤 | 做什么 | 耗时 |
|---|---|---|
| 1 | 跑第 1 站的 `javap` A/B 对照，看懂前后差异 | 15 min |
| 2 | 读 `ThreadAsmVisitorFactory.kt`，搞清「放行 vs 拦截」 | 10 min |
| 3 | 读 `ThreadClassVisitor.kt`，重点看三个「事实」和延迟 emit | 40 min |
| 4 | 读 `UnifiedThread.kt`，理解「统一 Runnable 执行环境」 | 15 min |
| 5 | 亲手改一次：把 `disabledPackage` 加进去，验证该类不再被插桩 | 20 min |

**第 5 步最重要。** 只看不写会产生「我懂了」的错觉。改一个过滤条件、
用 `javap` 验证产物变化，才算真正走通一遍。

---

## 3. 动手实验：三个能自己跑的验证

### 实验 A：验证「命名」变换生效

```bash
./gradlew :app:transformDebugClassesWithAsm
javap -c -p -classpath app/build/intermediates/classes/debug/transformDebugClassesWithAsm/dirs \
  com.interview.thread.ThreadMisuseScenarios | grep -B3 'Thread."<init>"'
# 期望：看到 ldc_w "com.interview.thread.ThreadMisuseScenarios"，
#       且描述符是 (Ljava/lang/Runnable;Ljava/lang/String;)V
```

### 实验 B：验证「已有的 String 参数不会被重复添加」

看 `ThreadMisuseScenarios` 里已经写死名字的那两处：

```kotlin
Thread(r, "leaked-pool-$index-thread")
Thread(r, "$sdkName-worker")
```

它们的描述符里本来就有 `Ljava/lang/String;`，`hasNameParam()` 会命中并**跳过**。
实测确认未被重复插桩 —— 这正是**幂等保护**生效的证据。

> ⚠️ 但这条幂等保护是**启发式**的（判断「描述符里有没有 String」，
> 而不是「这个 String 是不是 name 参数」）。它在本仓库的所有场景下都正确，
> 但对一个签名形如 `Thread(Runnable, String)` **且那个 String 不是 name** 的
> 自建重载（本仓库不存在这类代码），理论上会漏插。
> 属于「已知精度边界」，不是 bug。

### 实验 C：验证「子类不被误伤」

新建一个探针（**验证完请删除**）：

```kotlin
package com.interview.thread
class AsmProbe2(r: Runnable) : Thread(r)
```

```bash
./gradlew :app:transformDebugClassesWithAsm
javap -c -p -classpath app/build/intermediates/classes/debug/transformDebugClassesWithAsm/dirs \
  com.interview.thread.AsmProbe2 | grep 'invokespecial'
# 实测结果：super() 被正常补 name → (Ljava/lang/Runnable;Ljava/lang/String;)V
# 且没有出现「参数错配」，子类路径行为正确。
```

---

## 4. 这个模块最有价值的 5 个知识点（面试/复盘用）

1. **`DUP` 的语义** —— `INVOKESPECIAL` 会隐式消耗栈顶引用，栈平衡是精确的。
2. **为什么不能整体替换 Thread** —— `ASTORE`/`ALOAD` 下标依赖局部变量表上下文。
   → 由此推出「统一 Runnable 执行环境」这个核心洞察。
3. **`NEW` 与 `<init>` 必须同步改 owner** —— 否则 `VerifyError`。这是最容易翻车的点。
4. **延迟 emit（`MethodNode`）vs 流式（`ClassVisitor`）** —— 需不需要全局视野，
   决定了用哪种模式，代价是内存。
5. **插桩必须幂等 + 精确匹配** —— 否则重复构建产物不一致、子类被误伤。

---

## 5. 已知问题（阅读时请留意）

### ⚠️ D13：注释声称治理三方 SDK，但当前配置只作用于本项目

- **代码位置**：`ThreadMonitorPlugin.kt:36` 用的是 `InstrumentationScope.PROJECT`
- **注释位置**：同文件第 21 行 + `ThreadAsmVisitorFactory.kt:48` 声称
  「天然支持处理**依赖中的 class**（这正是治理三方 SDK 的关键）」
- **冲突**：这两者**矛盾**。

已通过 AGP 8.3.0 源码验证（`AsmClassVisitorsFactoryRegistry.kt`）：

```kotlin
if (scope == InstrumentationScope.ALL) {
    dependenciesClassesVisitors.add(visitorEntry)   // ← 只有 ALL 才会注册到依赖
}
projectClassesVisitors.add(visitorEntry)
```

即：**`PROJECT` 只处理本项目 class，`ALL` 才处理依赖。**

实测旁证：本次构建中，依赖里的 R 类（`androidx/*/R$*.class`、`com/google/*/R$*.class`）
被原样放进了 `transformDebugClassesWithAsm/jars/`（292 个类全是 `R` 子类），
字节码未被改写；而项目内的 `com/interview/**` 确实被改写了。

**结论**：这个模块**当前只能改写自有代码**，做的正是 Lint 已经能做的那部分。
`isInstrumentable` 里精心写的「放行而非拦截」逻辑，对三方 SDK 目前**不生效**。

**修法**：把 `InstrumentationScope.PROJECT` 改成 `InstrumentationScope.ALL`。
代价是覆盖面变大、构建变慢，且要对加固/加壳的 SDK 做防御。

> 本文档只标注问题，**未改动行为**。

---

## 6. 延伸阅读

- 方案全貌：[`app/src/main/java/com/interview/thread/README.md`](../../../README.md)
- 四层防线理论：[`线程滥用与治理.md`](../../线程治理/线程滥用与治理.md) §三 第三层
- 运行时落点：[`UnifiedThread.kt`](../../UnifiedThread.kt)、
  [`ThreadPools.kt`](../../ThreadPools.kt)
- 源码依据：`~/.gradle/caches/.../gradle-8.3.0-sources.jar`
  中的 `com/android/build/gradle/internal/instrumentation/AsmClassVisitorsFactoryRegistry.kt`
- ASM 官方手册：<https://asm.ow2.io/asm4-guide.pdf>（第 2 章讲 Visitor 模型，第 3 章讲指令）
