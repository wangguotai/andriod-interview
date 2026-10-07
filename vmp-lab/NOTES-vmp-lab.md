# VMP 加固 Lab NOTES

本 module 把一个**真实可跑的 VMP（Virtual Machine Protect）加固**做出来，用于面试讨论
「加固到底保护了什么、代价是多少」。它不是一个 PPT 式的示意：三个图像算子被编译成
**自定义字节码并以 ChaCha20 加密**存放，运行期由一个薄解释器执行，且结果与未加固的
原生实现**逐位一致**（有设备端测试与宿主机测试双重对拍）。

> 一句话立场：**本 Lab 用可复核的证据说明 VMP 能挡住什么、挡不住什么，以及它要花多少钱。**

> 想读代码：先看 [`GUIDE-vmp-code-reading.md`](GUIDE-vmp-code-reading.md) ——
> 按依赖顺序排的 8 站阅读路线，含每站该看的行号与自测题。

---

## 1. 它保护的是什么（以及不是什么）

先说清楚，因为这是面试里最容易被夸大的地方。

**原文**：`rust/imagepipeline` 里的三个算子以正常机器码编进 `libimagepipeline.so`。
拿到 `.so` 的人可以 `objdump` 直接读出算法，或把函数签名、常量、循环结构看得一清二楚。

**加固后**：三个算子的**算法**不再以机器码存在，而是变成一段加密字节码。
在 `libvmp_android.so` 里，它们是三块**看不出结构**的密文。

| 攻击者的动作 | 加固前 | 加固后 |
|---|---|---|
| `objdump` 反汇编算法本体 | 直接看到 | **看不到**（只有解释器的 `match`） |
| `strings` 找算法痕迹 | 能看到常量/字符串 | 看不到（密文 + 少数必要的 JNI 名字） |
| 静态读出「有哪些算子」 | 一眼看出 | **能看出**（明文头里有名字与长度，见 §3） |
| 运行期把明文 dump 出来 | 不需要 | **能拿到**（这是 VMP 的根本弱点，见 §8） |
| 有目的地改字节码 | — | 改完重算 FNV 校验和即可绕过（见 §8） |

**边界**：VMP 提高的是**静态分析成本**，不是「不可破解」。它把「读代码」变成
「读代码 + 逆 ISA + 写解密器 + 动态脱壳」。对愿意花这个成本的人，它只是减速带。

---

## 2. 模块结构：三层，两个入口

```
rust/vmp/                纯 Rust 虚拟机内核（无 Android 依赖，可 cargo test）
  build.rs               构建期：调生成器 → ChaCha20 加密 → 嵌入密文
  src/format.rs          VMP-1 容器格式 / 操作码 / LEB128 / zigzag / FNV-1a   ← 与 ISA.md 对齐
  src/crypt.rs           ChaCha20（手写，无新依赖，对过 RFC 8439 §2.4.2 向量）
  src/asm.rs             汇编器（**不进 .so**，仅构建期与本机测试）
  src/vm.rs              解释器 + 分页惰性解密            ← `.so` 里的核心
  src/algorithms/        三个算子的字节码生成器（**不进 .so**）
  src/selftest.rs        内建自检：VM 字节码形式的算术断言
  src/key.rs             主密钥与各程序 nonce（**不进 .so**，构建期用完即弃）
  ISA.md                 指令集规范（汇编器/VM/生成器三者的唯一契约）
rust/vmp-android/        JNI 绑定层 → libvmp_android.so
vmp-core/                 ★ android library：上面两件的构建接线 + VmpBridge + UI + 布局
vmp-lab/                  宿主 A：独立可安装 Demo App（LAUNCHER）+ androidTest
app/                      宿主 B：面试实验室首页入口（HomeCatalog 一条）
```

关键点：**算法在字节码里，不在 Rust 代码里**。`src/algorithms/` 看着像算法实现，
但它的角色是「构建期的字节码生成器」—— `build.rs` 调它产出字节码、加密、再把密文嵌进
`.so`。发布的 `.so` 里既没有 `algorithms/`，也没有 `asm.rs`。这一点在 §7 用 `nm` 验证。

### 为什么是「library + 两个宿主」而不是「独立 APK」

最初的取舍是独立 module（与 `:ipc-lab` 一致）：**加固产物必须与未加固产物并存、
同时对拍**，本 Lab 产出独立的 `libvmp_android.so`，不替换 app 既有的
`libimagepipeline.so`；装/卸互不影响，加固实验失败绝不波及正在用的图片链路。

后来要把 VMP 放进**面试实验室首页**（`HomeActivity`），这就撞上一个硬约束：
`HomeEntry.activityClass` 的类型是 `Class<out Activity>` —— **首页只能直达本 app 内的
Activity**，独立 APK 的入口在这个模型里根本表达不了（`:ipc-lab` / `:scroll-event-demo`
同理，所以它们都不在首页）。

于是改成三层：把实现抽到 `:vmp-core`（library），`:app` 与 `:vmp-lab` 各自依赖：

| | 保留了什么 | 为什么 |
|---|---|---|
| `:vmp-core` | native 构建接线 / JNI / VmpBridge / VmpLabActivity / 布局 | 实现只能有一份 |
| `:vmp-lab` | LAUNCHER + Manifest + androidTest | 隔离性 + 测试归属 |
| `:app` | `HomeCatalog` 一条 + Manifest 里一条 Activity | 首页可达 |

**两条路都保留是有意的**：

1. `:app` 那份让面试时可以**从首页一路点进去**，这是「放进实验室页面」的诉求；
2. `:vmp-lab` 那份保留了**可单独安装、单独跑、单独测**的隔离性 —— 加固实验
   （cargo 交叉编译、JNI 符号、`.so` 装载）失败时，不该影响日常使用的主 app APK。
   而 androidTest 也放在这里：它们需要装一个带 instrumentation 的 APK，搁在
   library 里会随依赖传染给 `:app`。

两处共用同一份 UI 与桥接代码，因此不存在「首页那份和 Lab 那份行为不一样」的漂移
—— 已实测：两条入口都跑出同样 12 组逐位一致的对拍结果。

### ⚠️ 一个真实的代价：ABI 限制会传染

`:vmp-core` 的 native 只编 **arm64-v8a**，而 ABI 限制会**从 library 传染到宿主**，
于是 `:app` 也必须显式收口（`app/build.gradle.kts` 的 `ndk { abiFilters += "arm64-v8a" }`）。

影响面：本仓库其余 native（`imagepipeline` / `netlab` / `threadhook`）也一起只剩 arm64；
真机（arm64）与 arm64 模拟器不受影响；**32 位设备将装不上这个 APK**。

这是「把 native 加固代码放进主 app」的**真实代价**，不是可以忽略的细节。
若要恢复多 ABI：给 `rust/vmp-android` 补对应 rustup target，再删掉那行
—— `tools/cmake/build_rust_android.cmake` 本身已支持四个 ABI，不需要改工具链。

---

## 3. 保护设计

### 3.1 容器格式

```
偏移 0   : magic        u32 LE = 0x56504D31  ("1MPV")
偏移 4   : isa_version  u32 LE = 1
偏移 8   : code_len     u32 LE
偏移 12  : const_count  u32 LE
偏移 16  : checksum     u32 LE   ; FNV-1a32 覆盖 [4..16] ++ [20..]
偏移 20  : consts       const_count × u32 LE  ┐ 这一段被 ChaCha20 加密
偏移 ... : code         code_len 字节         ┘
```

**明文头是刻意的取舍，不是疏漏**：VM 在不解密的前提下必须知道「要解密多少字节、
常量池多大」，否则连 `len` 都无从谈起。代价是攻击者能看出「这里有一个多大的虚拟机
程序」—— 实测三个程序分别是 774 / 894 / 890 字节（见 §7）。把「看不清内容」说成
「完全看不见痕迹」是加固宣传里最常见的夸大。

### 3.2 分页惰性解密（本设计的核心）

VM **不**在启动时把整段字节码解到堆上，而是按 256 字节页惰性解密：

```
PAGE_SIZE        = 256 字节
PAGE_CACHE_SLOTS = 16    → 明文常驻上限 = 16 × 256 = 4KB
```

直接映射缓存，`page_no % SLOTS` 定位槽位 + tag 比对（伪关联，避免整表失效）。
效果是：即使有人 dump 堆内存，任一时刻也只有 **≤4KB** 明文；要拿完整字节码，
必须钩住取指路径逐页收集，而不是简单 dump 一块内存。

**实测有效性**（256×192 → 64×48 的一次降采样）：

```
解密 4 页 / 命中 10,759,249 次（命中率 100.00%）
```

一次调用里只发生 **4 次**解密，之后 1075 万次取指全部命中缓存。
⇒ 结论：**惰性解密几乎免费**，加固的代价不是解密，而是解释执行本身（见 §6）。

### 3.3 为什么密钥「不进 `.so`」是个近似说法

`key.rs` 里的 `MASTER_KEY` 通过 `build.rs` 的 `include!` 在构建期使用，
但**运行时解密需要同一把密钥**，所以它必然也在 `.so` 里（以字节数组形式）。
`key.rs` 不在符号表中 ≠ 密钥不在二进制里。

真实加固里这一步是**白盒密码学 / 密钥混淆**的主战场（把密钥与大量假密钥、查表网络
混在一起，使提取需要专门的逆向工作）。本 Lab **没有**做这件事，只做了
「把它写成一个数组」。这是与商业加固器**最大的差距**，面试时必须主动说出来。

---

## 4. ISA 设计要点

完整规范在 [`rust/vmp/ISA.md`](../rust/vmp/ISA.md)。这里只记三条与「加固」直接相关的。

### 4.1 栈机 + 变长操作数

- 两个栈：主操作数栈（4096 格）+ 辅助数组 `aux`（16384 格，给直方图/行累加器）。
- 操作数三种编码：无 / `U8`（1 字节）/ `Leb`（**1~N 字节无符号 LEB128**）。
- **跳转偏移先 zigzag 再 LEB128**：`rel` 对循环回边是负数，直接塞进 LEB128 会占 10 字节；
  zigzag 让 ±1 都落在 1 字节量级。
- **变长是反编译摩擦的来源**：同一条 `JMP` 在小程序里 2 字节、长程序里 3 字节，
  静态扫描无法按固定步长切分指令流，必须先完整实现 LEB128 解码 + 控制流分析。
  而变长偏移本身是**自指**的（偏移编几个字节取决于偏移值，偏移值又取决于前面所有
  跳转的长度）—— 汇编器用全局不动点迭代求解。

### 4.2 复合指令：为「不透明」而非「性能」

`PACKRGBA`(46) / `MAD`(47) / `AUXADDU`(48) / `PUSHMD`(49) 把图像处理里高频出现的
3~4 条操作融合成一条。这**不是**为了性能（VM 里省下的只是几次 `match` 派发），
而是为了**降低字节码的可读性与体积**：一条 `PACKRGBA` 比四条独立的移位/或指令
更难一眼看出「这是在打包像素」。

`AUXADDU` 值得单独说：`aux[a] += v` 是直方图与行累加器的**唯一**热模式。
用「读-算-写回」三条指令表达会让字节码体积翻倍，并把「地址要压两次」这个易错点
暴露给每一处手写序列 —— 而对拍测试只会告诉你「结果差一点」。

### 4.3 步数上限：加固**自己引入**的攻击面

`MAX_STEPS = 200_000_000`，超限返回 `STEP_LIMIT = -7`。

为什么必须有：**字节码就是被保护对象，也就是可以被篡改的对象**。一个把回边偏移改坏
的补丁就能让解释器永远转下去 —— 在 Android 上表现为「某后台线程 100% CPU」，
不崩、不打日志，极难归因。加上限后最坏情况变成「一次调用返回错误码」，可上报、可降级。

**这是加固本身带来的新风险**，必须由加固方案自己闭环。面试里这是个加分点。

状态码：`0 OK` / `-1 BAD_OPCODE` / `-2 BAD_OPERAND` / `-3 DIV_ZERO` /
`-4 OOB` / `-5 RET_UNDERFLOW` / `-6 BAD_HEADER` / `-7 STEP_LIMIT`。

---

## 5. 正确性：逐位对拍，以及它抓到的两个真问题

### 5.1 红线

**加固后算错，比不加固更糟。** 所以验收红线是：三个算子在两条路径下**逐字节相等**。
不是「看起来一样」、也不是「误差很小」—— 图像算法一旦有末位偏差，下游
（缓存 key、去重、占位色）都会跟着漂，而且漂得毫无规律，最难查。

对拍在两个层面做：

| 层面 | 位置 | 覆盖 |
|---|---|---|
| 宿主机 `cargo test` | `rust/vmp/tests/crosscheck.rs`（7 条） | 手算用例 + 合成图 + 真实尺寸 + 边界 |
| 设备端 `androidTest` | `vmp-lab/…/VmpGoldenTest.kt`（7 条） | 上面同一批用例，走真正的 JNI 路径 |

设备端用例刻意包含 `8x8→3x2` —— 原生实现的注释记着，早期用「可分离两趟」时它出现过
`120 vs 参考 119` 的逐位差异。**老 bug 的回归点是最便宜的测试。**

### 5.2 问题一：JNI 包名与符号名不一致（真机才暴露）

Kotlin 侧把 `VmpNative` 放在 `com.interview.vmp.nativebridge` 子包，而 Rust 侧导出的
是 `Java_com_interview_vmp_VmpNative_*`（**漏了 `nativebridge`**）。

- 编译：通过。Kotlin 编译通过、Rust 交叉编译通过、APK 打包通过。
- 运行：**全部** `UnsatisfiedLinkError`。

这正是 JNI 的经典失败模式：**没有编译期检查**。修法是把符号统一为
`Java_com_interview_vmp_nativebridge_VmpNative_*`，并在两侧注释里写死这条纪律。

### 5.3 问题二：**逐位对拍本身有盲区**（更值得讲的一个）

设备端对拍报错：`blur 1x1 r=2: VM rc=-1` —— VM 路径拒绝，原生路径成功。

根因：VM 路径有一道 `radius > max(w,h)` 的守卫（超限直接拒绝），
**原生路径没有**。两边的输入域不一致。

为什么这个值得单独讲：**当时「逐位对拍」是全绿的**。因为对拍只比较
「两边都成功时的输出」，而 `1x1 r=2` 落在「一边成功一边失败」的**差额**里 ——
那不是交集，是无人区。

修法不是「把守卫删掉」，而是把 VM 的正确性前提**提升成两条路径共用的输入校验**
（`exec::check_image_dim` / `exec::check_blur_radius`），并补一条**同域测试**
（`both_paths_reject_the_same_inputs` / `bothPathsRejectTheSameInputs`）。

> **结论（值得记住）**：逐位对拍必须配一条「同域」测试。
> 否则它给出的绿灯只覆盖输入域的交集，差额部分是无人区。

顺带被这条测试钉住的还有：`radius as u32` 会把 `-1` 变成 `4294967295` ——
**静默**把非法输入变成巨量工作量，比报错危险得多，所以在 JNI 边界显式挡住负数。

---

## 6. 代价是多少（实测数字）

### 6.1 方法（照抄不过时就别信数字）

- **同一个 `.so`、同一次运行、同一份输入**。原生对照刻意编在 `vmp_android` 里
  （见 `rust/vmp-android/src/exec.rs` 的 `native` 模块），所以两条路径的差异**只**
  来自「解释字节码」vs「直接跑机器码」，不含编译选项/LTO/库加载的差别。
  若分属两个 `.so`，测出来的就不是「加固的代价」。
- **暖机 5 次**（JNI 首次调用有符号解析、页缓存为空、分支预测未收敛）。
- **单线程**：native 的 VM 执行器是 `thread_local`，跨线程会重新分配
  —— 这既是线程纪律，也是「测的是不是同一件事」的前提。
- **报中位数 + 最小 + p90**。CPU-bound 的 microbenchmark 里**最小值**最接近
  「无干扰」的一次执行（见 §6.2 的抖动说明）。

### 6.2 宿主基准（macOS host，release，计时 101 次，两次运行）

| 算子 | native 最小 | VM 最小 | **最小比值** | VM 指令数 | ns/指令 |
|---|---:|---:|---:|---:|---:|
| dominant 256×192 | 131.5 µs | 127.7 ms | **971x** | 7,399,843 | 17.3 |
| downscale 256×192→64×48 | 54.1 µs | 46.9 ms | **867x** | 5,383,889 | 8.7 |
| blur 256×192 r=2 | 75.1 µs | 438.3 ms | **5834x** | 45,518,629 | 9.7 |

> 两次运行的最小比值分别是 974.5/866.6/5857.5 与 971.4/867.2/5833.8 ——
> 复现性在 1% 以内。
>
> ⚠️ 中位比值更飘（dominant 808~960x、blur 5889~5946x）。原因：native 侧只要
> 几十 µs，**中位数容易被机器上的其它负载抬高**。所以文档以最小比值为准，
> 并且不把任何精确倍数写成断言。

### 6.3 设备端基准（模拟器 API 36 / arm64-v8a / ranchu，两次运行）

| 算子 | 运行 | native 中位 | VM 中位 | 比值 | VM 指令数 | ns/指令 |
|---|---|---:|---:|---:|---:|---:|
| dominant 256×192 | #1 | 126.0 µs | 133.8 ms | 1062x | 6,312,503 | 21.2 |
| | #2 | 124.0 µs | 121.8 ms | 982x | 6,312,503 | 19.3 |
| downscale 256×192→64×48 | #1 | 55.0 µs | 85.4 ms | 1553x | 5,383,889 | 15.9 |
| | #2 | 59.0 µs | 87.5 ms | 1483x | 5,383,889 | 16.3 |
| blur 256×192 r=2 | #1 | 510.0 µs | 705.5 ms | 1383x | 45,518,629 | 15.5 |
| | #2 | 517.0 µs | 631.2 ms | 1221x | 45,518,629 | 13.9 |

**同一个模拟器上两次运行就有 10~25% 的比值波动**（VM 中位从 133.8ms 到 121.8ms）。
这不是噪声可以忽略不计的那种：所以本节只给出**量级**（~1000x）与稳定得多的
**指令数**，不给出「加固使性能下降 N 倍」这类精确主张。
指令数两次完全一致 —— 这也说明指令数是比耗时更可靠的横向量。

**怎么读这两张表**：

1. **模拟器上的 ~1000x 不是模拟器伪影** —— 宿主基准（真实 CPU）也给出 ~900x。
   所以这是解释器的**真实**开销量级。
2. **两条路径的比值随算子变化**，因为 native 侧的复杂度不同
   （downscale 有 `O(sw·sh)` 的聚合，blur 是 `O(w·h·r)`）。加固代价 ≈ 恒定 ~10 ns/指令
   × 该算子的指令数 ⇒ **指令数多的算子吃亏更大**（blur 4500 万条指令 = 0.44s）。
3. **指令数依赖输入内容**：dominant 在宿主图片上是 7,399,843 条、在设备测试图上是
   6,312,503 条 —— 同尺寸不同像素内容，走的分支不同。
   而 downscale 两次都是 **5,383,889**（分毫不差），因为它的控制流与像素值无关。
   这个对比本身就是一个有用的性质：**数据相关的控制流会让指令数（进而耗时的可预测性）
   变差**。
4. **ns/指令是唯一与设备无关的量**：host ~9~17 ns、模拟器 ~15~21 ns。
   它把「VM 慢」拆成两个可分别改进的问题：**字节码太长**（写笨了）还是
   **解释器太慢**（派发开销大）。

### 6.4 这个代价该怎么评价

诚实地说：**对图像处理，这几乎不可用**。256×192 的模糊从 0.5 ms 变成 0.7 秒，
没有任何交互场景能接受。

VMP 适用的位置是：**调用频次低、单次计算量小、但算法本身是核心资产**的场合 ——
例如许可证校验、签名/授权逻辑、关键参数推导、反调试判定。
把「每帧都跑」的算子拿去虚拟化，是选错了对象。

反过来，**加固强度与成本可以调**，这也是面试里值得展开的点：

- 只虚拟化**关键几条指令**（如校验结果的那次比较）而不是整个算法；
- 混合方案：热点用原生、关键判定用 VM；
- 代价可以用「ns/指令 × 指令数」事先估算，不必先做再测。

---

## 7. 静态取证：攻击者到底能看到什么

以下命令与输出都是**实测**（用 NDK 的 `llvm-nm`/`llvm-strings`/`llvm-readelf`）。

⚠️ 注意分析对象：**cargo 的 release 产物（532,648 B，未 strip）** 才有符号表可查；
Gradle 打进 APK 的是它的 **strip 后副本（374,224 B）**（`stripDebugDebugSymbols`）。
下面第 1、2 条（符号是否存在）依赖符号表，要在 **未 strip 的那个**上跑；
第 4 条（字符串扫描）与密文核对在两者上结论相同 —— 因为 stripping 去掉的是名字，
不会改变 `.rodata` 里密文的可读性。

```bash
NM=$ANDROID_NDK/toolchains/llvm/prebuilt/*/bin/llvm-nm
SO=rust/target/aarch64-linux-android/release/libvmp_android.so

# 1) 生成器/汇编器**不在**里面 —— 这是「算法不在机器码里」的直接证据
$NM -C --defined-only $SO | grep -E 'vmp::(algorithms|asm|format)'   # → 空

# 2) 解释器**在**里面
$NM -C --defined-only $SO | grep 'vmp::'
# → vmp::vm::Vm::run / Code::leb / Code::byte / Code::konst / vmp::find
# → vmp::crypt::keystream_block
# → vmp::programs::{DOMINANT,DOWNSCALE,BLUR}   （三块密文数据）

# 3) 明文对照路径在（有意为之）
$NM -C --defined-only $SO | grep imagepipeline
# → imagepipeline::dominant::dominant_color / blur::blur_box / downscale::downscale_area

# 4) 没有任何操作码助记符 / 程序文本泄成可读字符串
$STRINGS -n 6 $SO | grep -iE '^(HALT|PUSHI|AUXADDU|HIST|VPM1)$'      # → 空
```

出现的 `dominant` / `blur` / `downscale` 字符串全部是**预期**的：
JNI 方法名（`Java_…_blurVm`）、程序查找键（`vmp::find(name)`）、
`imagepipeline/src/*.rs` 的 panic 路径字符串。**没有一条指令、一个操作码名字泄出来。**

### 密文本身的核对

在 `.rodata` 里按魔数扫描，**恰好 3 处**命中（`"1MPV"` = `0x56504D31` LE）：

| 程序 | 容器总长 | code_len | const_count | 偏移 |
|---|---:|---:|---:|---:|
| blur | 774 B | 750 | 1 | `0xa922` |
| dominant | 894 B | 698 | 44 | `0xac3a` |
| downscale | 890 B | 858 | 3 | `0xafc6` |

每个都满足 `20 + const_count×4 + code_len == 总长`，且**存储的 checksum 与重算的
FNV-1a32(密文) 一致** —— 说明容器完好、密封正确。
用各自的 key+nonce 解密后，载荷是**合法**的操作码流（逐字节落在已定义操作码范围内）；
用别的程序的 nonce 解密则不是。⇒ **三个程序确实是各自独立加密的字节码。**

### 大小归因

| 段 | 大小 | 说明 |
|---|---:|---|
| `.text` | 261,672 | 代码（imagepipeline 原生 + VM + JNI） |
| `.strtab` | 82,264 | 符号字符串（未 strip 时才在） |
| `.symtab` | 75,992 | 符号表（→ §7 的取证前提） |
| `.eh_frame` | 35,532 | 栈展开表 |
| `.rodata` | 23,328 | **三块密文（共 2,558 B）在这里** |

**能立刻改进的一点**：`.eh_frame`（35 KB）与符号表对加固产物没有价值，
release 里可以关掉（`-C panic=abort` + strip）。本 Lab 保留了它们以便取证，
这是**为了可讲解而做的取舍**，真实发布不该这样。

---

## 8. 诚实的缺口（别把这些当成已解决）

1. **运行期动态 dump 能破**。这是 VMP 的根本弱点：密钥在二进制里，解释器必然要
   解密才能执行。攻击者只需 hook 取指函数（Frida hook `Code::byte`）就能
   逐页收集明文。分页缓存把「一次性 dump 一整块内存」变成「按页收集」，
   提高了门槛但**没有改变可破性**。真正的对抗手段是 RASP / 反调试 / 完整性自校验，
   本 Lab 都没有做。
2. **校验和不是抗篡改**。FNV-1a32 覆盖**密文**，好处是不解密即可校验，能抓出
   意外损坏与版本错配；但**有意的攻击者改完重算校验和即可**。抗篡改需要
   **私钥签名 + 运行期验签**（签名私钥在厂商手上，设备端只有公钥）—— 本 Lab 不做。
3. **密钥就是 `.so` 里的一个数组**（§3.3）。没有白盒密码学、没有密钥拆分、
   没有假密钥混淆。这是与商业加固器最大的差距。
4. **ISA 用的是 64 位宽操作 `u64`**，而真实加固 VM 的 ISA 通常是 **32 位**
   （为了更小的字节码体积与更快的派发，也为了在两套指令的事后混淆上有更大空间）。
   本 Lab 选 64 位是为了让「无符号/有符号语义」的坑更容易讲清楚。
5. **算法现在存在两份**（原生 + 字节码生成器），这是 VMP 的**真实维护成本**：
   改一次算法要改两处，且两边必须逐位一致。本 Lab 用对拍测试把「漂移」变成红灯，
   但成本本身消不掉 —— 这也是为什么纯 VMP 化整条图像链路不现实。
6. **没有反调试、没有完整性自校验、没有多线程硬化**。`thread_local` 的执行器
   只为「每线程一份状态」的语义正确，不构成任何防护。
7. **只编 arm64-v8a**。教学场景够用；四 ABI 会显著拖慢构建，且对结论无影响。
8. **基准只覆盖一种尺寸与一个半径**。没有扫尺寸曲线，也没有覆盖
   `MAX_DIM` 边界附近（4096）的行为 —— 那里会有缓存局部性与页缓存压力的变化。

---

## 9. 一个失败的设计（留作负面教材）

**初版页缓存是「单页」的**（只缓存最近用到的 1 页）。后果：

```
downscale 256×256 → 32×32，release：
  单页缓存      ~46 秒        （解密被反复触发，成为绝对瓶颈）
  16 槽缓存      0.08 秒
```

原因：downscale 的**循环体横跨 4 页**，单页缓存下每轮迭代都要把 4 页来回换出换入 ——
每次取指都是未命中，ChaCha 解密彻底主导了耗时。实测未命中次数达 **15 万**级别。

修法：改为 `PAGE_CACHE_SLOTS = 16` 的**直接映射**缓存（`page_no % SLOTS` + tag 比对）。
之所以是「直接映射」而不是全关联 / LRU：槽位定位是**一次取模**，
而全关联要比较 16 个 tag；取指是每个指令都要走的热路径，这里不能有搜索。

修完后：单次调用解密 **4 页**、命中 1075 万次（§3.2）。

> 教训：**惰性解密的成本几乎全部由「缓存命中率」决定，而不是由 ChaCha 本身决定。**
> 评测一个惰性解密设计时，第一件该测的就是未命中次数，而不是加密算法的速度。

这个坑现在被两条测试钉住：`vm_path_decrypts_lazily`（Rust）与
`vmDecryptsLazilyWithBoundedPlaintextWindow`（设备端），
断言「每页最多解密一次」且「命中数 ≫ 未命中数」。

---

## 10. 怎么跑

### 宿主机（最快，不需要设备）

```bash
export RUSTUP_HOME=… CARGO_HOME=…          # 本机 Rust 环境在 /Volumes/ext/Rust
cd rust
cargo test -p vmp                           # 14 单测 + 7 对拍
cargo test -p vmp_android --release         # 10 单测（含同域测试）

# 宿主机的「解释 vs 直接」基准（必须 release）
cargo test -p vmp_android --release --test host_bench -- --ignored --nocapture
```

### 设备 / 模拟器

```bash
# 1) 编 APK。:vmp-core 会用 cargo 交叉编译出 arm64-v8a 的 libvmp_android.so，
#    两个宿主（:vmp-lab 与 :app）共用同一份产物。
./gradlew :vmp-lab:assembleDebug :vmp-lab:assembleDebugAndroidTest
./gradlew :app:assembleDebug          # 首页入口那一份

# 2) 装（真机若开着「USB 安装需确认」，MIUI 需要先在开发者选项里打开「USB 安装」）
adb install -r -t vmp-lab/build/outputs/apk/debug/vmp-lab-debug.apk
adb install -r -t vmp-lab/build/outputs/apk/androidTest/debug/vmp-lab-debug-androidTest.apk

# 3) 金标准：逐位对拍（7 条）
adb shell am instrument -w -r -e class com.example.myapplication.VmpGoldenTest \
  com.interview.vmplab.test/androidx.test.runner.AndroidJUnitRunner

# 4) 基准：结果走 System.out，用 logcat 取原文
adb shell am instrument -w -r -e class com.example.myapplication.VmpBenchmarkTest \
  com.interview.vmplab.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d | grep -a "System.out"
```

**两个实测的坑**（都记在代码注释里了）：

1. **`gradlew connectedDebugAndroidTest` 在 MIUI 真机上会被拦**
   （`INSTALL_FAILED_USER_RESTRICTED`，ddmlib 无法自行绕过）。
   对策：手动 `adb install` 两个 APK，再直接 `am instrument`。
   注意 ddmlib 失败时**会卸载主 APK**，所以顺序是「先装主 APK、再装测试 APK」。
2. **长基准会被系统冻结**。基准要跑二十多秒，期间进程没有可见界面，
   `cached_apps_freezer` 会把它放进 `freezer` cgroup：主线程变成
   `D (disk sleep)`、CPU 时间停止增长 —— **看起来和死循环卡死一模一样**。
   诊断一行就够：`cat /proc/<pid>/cgroup` 看到 `/perf/frozen` 就是被冻结。
   对策：跑之前先把 App 拉到前台。
   （试过在 `@Before` 里用 `startActivitySync` 自动拉前台，**行不通** ——
   它要等主线程 idle，在这个纯计算场景里直接 45 秒超时。见测试里的注释。）

### 两条入口，同一份实现

| 入口 | 怎么进 | 用途 |
|---|---|---|
| **面试实验室首页** | 主 app → 实验台 → 「VMP 加固 Lab」 | 面试时从首页一路点进去 |
| **独立 Demo App** | 桌面图标，或 `am start -n com.interview.vmplab/com.interview.vmp.ui.VmpLabActivity` | 隔离验证、跑 androidTest |

两者都进同一个 `VmpLabActivity`（来自 `:vmp-core`），四个按钮：
**加固状态** / **逐位对拍** / **A/B 对照** / **跑基准**，把本节的过程搬到界面上，
方便面试时当场指着屏幕讲。

---

## 11. 面试里我会怎么讲

1. **先划清边界**：VMP 是提高静态分析成本，不是不可破解；动态 dump 一定打得穿（§8.1）。
   主动说出这一点，比等对方指出来得可信。
2. **用一行数字建立直觉**：`~10 ns/指令`。任何算子都能用「指令数 × 10ns」先估出代价，
   不必先做再测（§6.3）。
3. **讲「同域」那个坑**（§5.3）：逐位对拍看起来很硬，但它有盲区 ——
   只比了交集。这是本题最有区分度的一段，因为它不是「我写对了」，
   而是「我发现我的验证方法本身有漏洞」。
4. **讲单页缓存的失败**（§9）：测量的第一指标应该是未命中次数，不是加密速度。
5. **落到取舍**：给出「什么时候该用 VMP、什么时候不该」的判断
   （低频 + 小计算量 + 核心资产），并说明强度可以调（只虚拟化关键判定）。
6. **最后承认成本**：算法存在两份，维护成本是真实的（§8.5）。
