# VMP 实现 · 代码阅读指南

> 目标：**用 60~90 分钟，把这个 VMP 加固实现从头到尾读懂**，并且知道每一处「为什么
> 这么写」——而不是只知道「有这么个文件」。本指南按依赖顺序排，不要跳着读。
>
> 配套文档：[`ISA.md`](../rust/vmp/ISA.md)（指令集规范，**唯一的契约**）、
> [`NOTES-vmp-lab.md`](NOTES-vmp-lab.md)（设计取舍与实测数据）、
> [`USAGE-vmp-lab.md`](USAGE-vmp-lab.md)（怎么跑）。

---

## 0. 先建立三个心智模型

读代码前先把这三句话记住，后面所有细节都挂在这三根钉子上。

**① 算法不在 Rust 代码里，在字节码里。**
`rust/vmp/src/algorithms/` 里的函数**长得像算法**，但它们是**字节码生成器**，
只在构建期运行。发布出去的 `.so` 里没有这个目录，只有解释器和三块密文。
—— 这条是最容易被问倒的地方（「你的 VM 里是不是把算法又写了一遍？」），
也是 NOTES §7 要用 `nm` 证明的事情。

**② 三份代码必须逐位一致，这是验收红线不是目标。**
`imagepipeline` 的机器码 / `algorithms` 生成的字节码 / `vm` 的解释执行，
三者给出**逐字节相等**的结果。加固后算错比不加固更糟。

**③ 加固抬高的是成本，不是不可破解。**
分页惰性解密把「dump 一块内存」抬高到「写一个 tracer」。
诚实的边界写在 NOTES §8，读代码时不要产生「这样就安全了」的错觉。

---

## 1. 数据流全景（先看这张图，再进代码）

```
 构建期 (cargo build --target aarch64-linux-android)
 ────────────────────────────────────────────────────
  build.rs
    ├─ include! src/format.rs, asm.rs, crypt.rs, key.rs, algorithms/
    ├─ algorithms::dominant::build()  →  明文容器（头 20B + 常量池 + 字节码）
    ├─ ChaCha20 加密 payload（[20..]），重算 FNV-1a32
    └─ 生成 OUT_DIR/programs.rs（三个 hex 字节数组）→ 被 lib.rs include!

 运行期 (Android, libvmp_android.so)
 ────────────────────────────────────────────────────
  Kotlin  VmpBridge.dominantBytes(src, w, h, Path.VM)
    └─ VmpNative.dominantColorVm(src, w, h)          [JNI]
        └─ android_impl.rs: EXEC.with(|e| e.borrow_mut().dominant(..))
            └─ exec.rs: VmExec::dominant
                ├─ check_image_dim / check_blur_radius   ← 两条路径共用
                ├─ Memory { src, dst, work } 装配
                └─ Vm::run(blob, Some((key, nonce)), inputs, mem)
                    ├─ Code::parse      解析明文头 + 校验和
                    ├─ Code::byte(ip)   取指：页命中比较 / 未命中解密 256B
                    └─ match op { .. }  解释执行，HALT 结束
```

对照读 [`exec.rs`](../rust/vmp-android/src/exec.rs) 的 `VmExec::dominant`（L158）与
[`vm.rs`](../rust/vmp/src/vm.rs) 的 `Vm::run`（L389），这条链就通了。

---

## 2. 推荐阅读顺序

### 第 1 站：契约（`format.rs` 210 行 + `ISA.md` 201 行）— 约 15 分钟

**先读 `ISA.md`**，尤其：

| 读什么 | 为什么 |
|---|---|
| §1 执行模型 | 栈机、两个栈（主栈 + `aux`）、内存三段（SRC/DST/WORK）、输入槽 —— 没有这些后面看不懂 |
| §2 指令表 | 50 条指令，`HALT=0 .. PUSHMD=49`。**不用背**，当字典用 |
| §3 操作数编码 | `U8` / `Leb` 变长；跳转 = zigzag + LEB128。**这是反编译摩擦的来源** |
| §4 容器格式 | 20 字节明文头的字段含义；为什么头必须明文 |
| §5 状态码 | `-1..-7`，故障归因靠它 |

**再读 [`format.rs`](../rust/vmp/src/format.rs)**，重点三处：

- `MAGIC`/`ISA_VERSION`/`HEADER_LEN`（L16~20）—— 与 ISA §4 的表一一对应；
- `Op` 枚举（L56）—— 指令表的 Rust 表示，**只用于可读性与测试**，解释器里走的是裸 `u8` match（性能）；
- `zigzag`/`unzigzag`/`leb_encode`/`leb_len`（L111~153）—— 手写一遍就懂为什么负数要 zigzag；
- `fnv1a32` + `seal`（L155+）—— **注意 `seal` 覆盖的是密文**，所以 VM 不解密就能验；
  也正因如此它**不是抗篡改**（改完重算即可），只是抓意外损坏。

> 读到这里要能回答：「为什么明文头是 20 字节而不是 0 字节？」（VM 需要知道载荷多长）
> 「校验和为什么不能防篡改？」（因为它没有密钥）

### 第 2 站：VM 核心（`vm.rs` 841 行）— 约 30 分钟

这是最值钱的一站。**按这个顺序读**：

**① `Memory` 与 `VmError`（L72~120）** —— 五段内存视图 + 七个错误。
`VmError::code()`（L95~106）把枚举翻译成 ISA 的负状态码，是「内部错误 → 对外契约」的唯一收口。

**② `MAX_STEPS`（L121）与其上的长注释** —— 为什么必须有限额。
一句话：**字节码是被保护对象，也就是可被篡改对象**，一条改坏的回边就能让解释器
永远转下去（Android 上表现为后台线程 100% CPU、不崩不打日志）。
这是**加固自己引入的攻击面**，必须自己闭环。

**③ `Crypt` 类型别名（L124）与 `Code` 结构（L127~144）** ——
```rust
pub type Crypt<'a> = Option<(&'a [u8; 32], &'a [u8; 12])>;
//                          ^^^^  None = 明文模式（本机测试 / selftest）
```
这个 `Option` 是理解后面所有 `match self.crypt` 的钥匙。

**④ `Code::parse`（L151~185）** —— 解析头 + 校验和，**不碰密钥**。
注意 `expect_len` 那条长度一致性检查：它能挡住「长度字段被改」这一类损坏。

**⑤ `Code::ks_index`（L192~194）** —— 只有两行，但注释值得读：
「20 这个数字只能在本文件这一个地方出现」。**加密载荷下标 ≠ 文件偏移**，
差一个 20，这是最容易写错且最难查的一处（表现是解出一堆垃圾字节码 —— 看起来像字节码被改坏）。

**⑥ `Code::konst`（L207）与 `Code::byte`（L226~259）** —— 两个取数入口。
`byte` 是**整个 VM 的热路径**，注释里直接说了「惰性分页几乎免费」就来自这里：

```rust
let page_no = ip / PAGE_SIZE;
let slot = page_no % PAGE_CACHE_SLOTS;   // 直映：一次取模定位
if self.tags[slot] != page_no { /* 未命中：解密整页 */ }
```

> 为什么要**直映**而不是全关联/LRU？因为取指是每条指令都走的路，槽位定位必须是
> **一次取模**；全关联要比 16 个 tag。这个取舍在 NOTES §9 有失败对照（单页缓存 → 46 秒）。

**⑦ `Code::leb`（L261~281）** —— 变长整数解码。注意它**可能跨页**（一个 LEB 的后半在下一页），
所以它必须逐字节走 `byte()`，不能直接从切片读。

**⑧ `Vm::run`（L389~675）—— 重点中的重点。** 分三段读：

- **L404~450 的宏与那段 hygiene 注释**（`macro_rules!` 在 L411~449：`next`/`next_jump`/`pop`/`push`/`binop`）：`macro_rules!` 对局部变量是
  **定义处卫生**，宏体里直接写 `ip` 会去找宏定义处的作用域并编译失败。
  所以每个宏都把 `$code`/`$ip`/`$self` 作为参数收进来。你在别处见到的
  「宏里变量找不到」错误，根因就是这个。
- **L451~465 的主循环**：`steps` 计数与上限检查（L454~460，超限也要落账 `last_steps`，
  否则「步数=0」会被误读成「一步没走」）、`self.last_ip = ip` 记录故障锚点、`next!` 取指、可选 trace。
- **L466~664 的 `match op`**：50 条指令（`0 => break` 到 `other => BadOpcode`）。**不要逐条精读**，按功能分组看：
  | 组 | 指令 | 看什么 |
  |---|---|---|
  | 压栈 | `PUSHI`(1) `PUSHM`(2) `PUSHN`(3) `PUSHMD`(49) | 立即数 / 常量池 / 输入槽 / **运行时变址取常量** |
  | 访存 | `LOADB`(4) `LOADBS`(5) `LOADW`(6) `DSTOREB`(7) `DSTOREW`(8) | 三段内存 + 零/符号扩展 + 小端 |
  | 算术 | `ADD..IREM`(9~15) | **回绕语义**、除法两套（无符号 vs 有符号向零截断） |
  | 位运算 | `AND..ISHR`(16~21) | 移位量掩码 63 |
  | 比较 | `LT..CMPGTU`(22~29) | 有符号 / 无符号分开 |
  | aux | `AUXRD`(30) `AUXWR`(31) `AUXADDU`(48) | **地址在栈顶**的统一约定 |
  | 栈操作 | `POP`(32) `DUP`(33) `SWAP`(34) `OVER`(35) | |
  | 控制流 | `JMP`(36) `JZ`(37) `JNZ`(38) `CALL`(39) `RET`(40) | 相对偏移 + `next_jump!` 解 zigzag |
  | 复合 | `PACKRGBA`(46) `MAD`(47) | 为**降低可读性**而融合，不是为性能 |
- **L667~673 收尾**：把 `fetched_pages`/`cache_hits`/`steps` 落到 `self`，
  这是基准取证的数据来源（`last_steps()` 等）。

**⑨ `mem_read`/`mem_write`（L692/L716）** —— 段选择 + 越界检查。
注意 `mem_write` 里 `region::SRC => return Err(BadOperand)`：**SRC 是只读段**。

> ✅ 读完这一站的检验标准：能不看代码说出「一次 downscale 调用里，
> 字节码是怎么从密文变成一条条被执行的指令的，以及哪些步骤会被重复执行」。

### 第 3 站：构建期加固流水线（`build.rs` 196 行）— 约 10 分钟

[`build.rs`](../rust/vmp/build.rs) 很短，但要读懂它的**两个设计决定**：

1. **`#[path = "src/..."] mod format;`（L45~54）** —— 把 src 下的文件**原样搬进**
   构建脚本 crate。于是同一份源码同时属于两个 crate。
   为什么必须这样：如果 build.rs 自己抄一份格式定义或 ChaCha20，
   「构建期加密」与「运行期解密」就有两个可能漂移的实现 ——
   而漂移的后果是运行期解出垃圾字节码、报个 `BAD_OPCODE` 就完事，排查全在设备上。
   `include!` 从机制上消灭这种可能。
2. **`protect()`（L155）** —— `crypt::apply_keystream(&MASTER_KEY, &nonce, &mut blob[HEADER_LEN..])`
   然后 `format::seal(&mut blob)`。**顺序不能反**：seal 覆盖密文，所以必须在加密之后。

`bytes_literal()`（L185）把它渲染成 `[1,2,3]` 字面量写进 `OUT_DIR/programs.rs`。

> 去 `target/aarch64-linux-android/release/build/vmp-*/out/programs.rs` 看一眼生成结果。
> 你会看到三个 hex 数组 —— **这就是加固的全部秘密所在**：能看到「有三个程序、各多大」，
> 看不到任何一条指令。看它一眼比看十行注释有用。

### 第 4 站：算法是「生成器」（`algorithms/` 758 行）— 约 20 分钟

**这是最容易读错的一站。** [`algorithms/mod.rs`](../rust/vmp/src/algorithms/mod.rs)
开头的注释先说清了：这些函数**不是算法实现，是字节码生成器**。
它们长得像算法，是为了最容易与 `imagepipeline` 的原生实现逐行对照。

**建议 ：先读 `dominant.rs`（304 行）**，它最完整地演示了「一个真实算法怎么变成字节码」：

- **L1~56 的模块注释**：先把原实现的语义抄成伪代码（含整数色相的三种分支、
  `max == r` 的优先级、`hue = (hue % 360 + 360) % 360`）。
  **这段注释是逐行对照原生实现写下来的，不是凭记忆** —— 这是本文件的价值所在。
- **L63~82 的 aux 布局常量**：`WEIGHT_BASE=0`（12 个桶权重）、`LUMA_BASE=12`、
  `GRAY_SUM=24`、`GRAY_N=25`……后面是标量槽。
  为什么权重与亮度要占 aux 而不是栈？因为它们是**按桶下标随机存取**的数组。
- **调色板怎么查（L52~56 的注释 + L104~113 的代码）** —— 这一段最见功力：
  常量池布局是 `[0..8]` 标量、`[8..20]` 调色板 R、`[20..32]` G、`[32..44]` B。
  三张表**连续且各 12 项**，于是「按 best 桶号查表」就是
  `PUSHMD(8+best)` / `PUSHMD(20+best)` / `PUSHMD(32+best)` —— 三次运行期变址取常量。
  两个关键约束：
  · `konst_force`（**不去重**）是必需的。若用普通的 `konst`，重复的调色板分量会被
    折叠，下标就不再连续，「基址 + 下标」这套算法直接失效 ——
    而且失效方式是**静默读错颜色**，不是报错。
  · 之所以不把调色板放进 aux，是因为这样能让它保持成**一块数据**而不是
    十二条 if/else 控制流；反编译时前者只是一张表，后者会散开（L55~56 的原话）。
- **`DIV` 而不是 `IDIV`**：注释明说了调色板缩放 `color * luma / 255` 必须用无符号除法。
  用错 `IDIV` 在负数上会截断到不同的值 —— 这类差异极小、极难发现。
- **`BUCKET_PALETTE`（L85~98）**：常量表本身，注释要求它「与
  `imagepipeline::dominant::BUCKET_PALETTE` 逐字节相同」—— 这也是对拍的一部分。

**再快速过 `downscale.rs`（206 行）与 `blur.rs`（213 行）**，只抓各自的「特有坑」：

| 文件 | 特有的坑（都在文件头注释里） |
|---|---|
| `downscale.rs` | 累加器用 u32（所以有 `MAX_DIM=4096` 这个前提）；`SCALAR_BASE=16000` 用来把标量区与按列数组分开 |
| `blur.rs` | **两趟都必须落 u8**（中间结果是有意义的中间态，不是精度损失）；`x+k-r` 的**无符号下溢**——`min(w-1)` 挡不住，必须手写 `saturating_sub` 语义（见 `emit_clamp` L174） |

### 第 5 站：汇编器（`asm.rs` 406 行）— 约 15 分钟

**只读 `finish()`（L331~405）就够**，其余是 API 糖。它解决的是这个问题：

```text
跳转偏移编成几个字节，取决于偏移值；而偏移值又取决于它前面所有跳转的长度。
→ 这是个全局不动点，必须迭代求解。
```

读法：
1. `label()`（L54）登记标签位置；`jmp/jz/jnz` 登记 `Fixup`（待回填的偏移）；
2. `finish()` 先假设所有偏移都是 1 字节，算出初值，再反复重算长度直到收敛；
3. `debug_assert!(ats.windows(2).all(|w| w[0] < w[1]))`（L334）——
   这条断言在保护「跳转必须按位置递增发出」这个前提，破坏了收敛性假设。

> 为什么要汇编器而不是手写字节数组？手写意味着**每条跳转偏移都要人肉算**，
> 程序一改就全盘漂移。那正是「对拍测试以最贵的方式失败」的典型来源。

### 第 6 站：其余两个内核文件 — 约 8 分钟

- [`crypt.rs`](../rust/vmp/src/crypt.rs)（191 行）—— ChaCha20 手写实现，无新依赖。
  `keystream_block`（L30）是核心；`keystream_byte`（L108）是 VM 逐字节解密用的**慢路径**；
  `apply_keystream`（L96）是构建期用的快路径。测试里对过 **RFC 8439 §2.4.2** 向量。
- [`selftest.rs`](../rust/vmp/src/selftest.rs)（194 行）—— VM 的运行时自检，
  **同样是 VM 字节码但明文存放**（它不承载算法，只是一组算术断言）。
  为什么必须有：VM 语义是我们自定义的，没有现成编译器能验证它。
  如果 `IREM` 写成了「向负无穷截断」，所有把负数喂给它的算子都会静默算错。
  自检的价值在于把「VM 与规范一致」变成**可随时在设备上重放的证据**。
  注意它用了**独立编码器**（`Enc`），不是 `asm.rs` —— 这样汇编器写错时自检不会一起错。

### 第 7 站：JNI 与桥接 — 约 20 分钟

**读 `exec.rs`（528 行）比读 `android_impl.rs` 更值钱**，因为前者能在本机跑测试。

[`exec.rs`](../rust/vmp-android/src/exec.rs) 按顺序读：

1. **L10~23 的模块注释**：两条**正确性前提**（累加不溢出 u32、累加器内存有界）
   与两个上限（`MAX_DIM=4096`、`MAX_WORK_BYTES=32MB`）。
   关键一句：「超出上限**明确报错**而不是试着跑」——试着跑的结果是静默算错（溢出回绕）。
2. **L64~86 的 `check_image_dim` / `check_blur_radius`** —— ★ **这是本文件最该读的地方**。
   它们是**两条路径共用**的输入校验。为什么必须共用：
   如果 VM 路径比原生路径多一道限制，「同参数 → 两边都成功 → 逐位一致」这条断言
   就有**盲区**。真实教训见 NOTES §5.3：blur 的半径上限一度只在 VM 侧生效，
   `1x1 r=2` 在 VM 报错、原生成功，**而对拍当时全绿** —— 因为它只比「两边都成功的交集」。
3. **`VmExec` 与 `take_work`（L90~150，其中 `take_work` 在 L143）** —— 为什么复用执行器与工作缓冲：
   每次新建会把分配器的时间算进基准里，测的就不是 VM 而是 `malloc`。
   注意 `take_work` 是**自由函数**而不是方法：用方法会让缓冲借走整个 `self`，
   于是 `self.vm` 再也借不到（E0499）。这不是绕开借用检查，而是如实表达两者不重叠。
4. **三个算子（L158/L176/L221）** —— 结构一致：校验 → 解构 `VmExec` 让借用不重叠 →
   装配 `Memory` → `vm.run(...)` → `map_err(VmError::code)`。
   注意 `inputs` 数组的约定（dominant：像素数；downscale：6 个；blur：3 个），
   与 `algorithms/` 里 `IN_*` 常量必须对齐。
5. **`hardening_status`（L252）** —— 它是「可断言的事实清单」而不是宣传词：
   每个程序的**密文长度** + `intact` 标志。上屏和日志都用它。
6. **`native` 模块（L323+）** —— 原生对照路径，**同样过一遍共享闸门**。
   它刻意编在**同一个 `.so`** 里，这样两条路径的唯一差别是「解释 vs 直接」。

[`android_impl.rs`](../rust/vmp-android/src/android_impl.rs)（357 行）只需知道三件事：

- **符号命名**（L1~10）：`Java_com_interview_vmp_nativebridge_VmpNative_*`。
  子包 `nativebridge` 必须出现 —— 漏了它编译打包全绿、真机全部 `UnsatisfiedLinkError`。
- **`thread_local!` 的 EXEC（L42）**：VM 有状态，必须每线程一份；且**不新建线程**
  （仓库规定 native 计算由 Kotlin 侧 `ThreadPools` 调度）。
  副作用：所有 native 调用必须在同一线程才能复用执行器 —— 这既是纪律也是**测量前提**。
- **`guard()`（在 `lib.rs`）**：`catch_unwind` 兜住 panic，**任何 panic 都不得跨 FFI 边界**。
  它被泛型化成支持 `i32` 与 `i64`（`lastSteps` 会到亿级，`jint` 会静默回绕）。

**最后看 Kotlin 侧**：
[`VmpNative.kt`](../vmp-core/src/main/java/com/interview/vmp/nativebridge/VmpNative.kt)（109 行）
是薄的 JNI 声明；[`VmpBridge.kt`](../vmp-core/src/main/java/com/interview/vmp/VmpBridge.kt)（309 行）
是收口，重点看两层 API 的划分：

- **字节层 `*Bytes`**（L184/L201/L219）—— **对拍唯一可用的层**。
  因为「逐位一致」的定义就是字节流相等，不能被 `Bitmap.sameAs` 的比较口径掩盖。
- **位图层**（L239/L256/L272）—— 面向 UI，负责 Bitmap ↔ RGBA 与缓冲池。
- `acquire(bytes, slot)`（L137）的 `slot` 维度是必需的：blur 输入输出同尺寸，
  只按字节数缓存会拿回**同一块** buffer，破坏「输入输出不别名」契约 → 自读自写、静默算错。

### 第 8 站：测试（读它们等于读需求）— 约 15 分钟

| 文件 | 读什么 |
|---|---|
| [`crosscheck.rs`](../rust/vmp/tests/crosscheck.rs)（288 行） | 7 条对拍。看**用例为什么这么选**：手算用例、合成图、真实尺寸、边界 `8x8→3x2`（原生历史上出过 double-rounding） |
| `vm.rs` 的 `mod tests`（L746+） | 6 条 VM 语义测试。特别注意 `runaway_bytecode_hits_step_limit_instead_of_hanging`（防死循环）与 `fetch_pages_is_lazy_not_whole_program`（防全解密回归） |
| [`VmpGoldenTest.kt`](src/androidTest/java/com/example/myapplication/VmpGoldenTest.kt)（226 行） | 设备端 7 条。**重点看 `bothPathsRejectTheSameInputs`** —— 它就是 NOTES §5.3 那个盲区的回归测试 |
| [`VmpBenchmarkTest.kt`](src/androidTest/java/com/example/myapplication/VmpBenchmarkTest.kt)（170 行） | 基准。注意「**先出表、后断言**」的纪律 —— 断言写在打印前会把数据一起吃掉 |
| [`host_bench.rs`](../rust/vmp-android/tests/host_bench.rs)（136 行） | 宿主基准。注意 `last_steps()` 必须在**紧接**测量后取，否则会拿到别的算子的指令数 |

---

## 3. 三个「不看注释一定会踩」的坑

读代码时如果觉得某处绕，大概率就是下面之一。

**① `ks_index` 的 20 字节偏移**
载荷（被加密的部分）从文件偏移 20 起，所以「载荷下标 = 文件偏移 - 20」。
写错的表现是**解出一堆看起来像字节码的垃圾**，然后报 `BAD_OPCODE` ——
极像「字节码被改坏」。处理原则：`HEADER_LEN` 只在 `ks_index` 一处使用。

**② 操作数约定必须统一：地址在栈顶**
`AUXWR`(31) / `DSTOREB`(7) / `DSTOREW`(8) / `AUXADDU`(48) 全都是**地址在栈顶**。
早先混用过两套约定，结果是 `aux[value] += addr` —— 小值静默写坏随机槽（不报错！），
大值才越界报错。这类 bug 只能靠对拍抓，而对拍只会告诉你「结果差一点」。

**③ 宏的「定义处卫生」**
`macro_rules!` 里的标识符按其**定义处**解析。所以 `Vm::run` 里的
`next!`/`push!`/`pop!` 都必须把 `$code`/`$ip`/`$self` 作为参数接收。
直接在宏体里写 `ip` 会编译失败（not found），而不是拿到调用处的 `ip`。

---

## 4. 顺手记：为什么这么设计（一句话版）

被追问时用得上的浓缩答案。

| 设计 | 一句话理由 |
|---|---|
| 明文头（20B） | VM 必须在不解密的前提下知道载荷多长 |
| ChaCha20 | 流密码，可逐字节惰性解密；无需处理块对齐 |
| FNV-1a32 覆盖密文 | 不解密即可验；**不是**抗篡改（无密钥） |
| 分页惰性解密 | 把「一次 dump 完整段」抬高到「写 tracer 逐页收集」 |
| 16 槽**直映**而非全关联 | 取指是每条指令都走的热路径，槽位定位必须是一次取模 |
| 变长 LEB128 操作数 | 静态无法按固定步长切分指令流，加大反编译摩擦 |
| 跳转用 zigzag | 否则循环回边（负数）要占 10 字节 |
| 汇编器全局不动点 | 偏移长度自指，只能迭代求解 |
| 复合指令 PACKRGBA/MAD/AUXADDU | 为**降低可读性**与体积，不为性能 |
| `MAX_STEPS` | 字节码可被篡改；防住「后台线程 100% CPU」那种无声故障 |
| 两条路径共用一个 `.so` | 让差异只剩「解释 vs 直接」，不含编译选项/加载 |
| 输入校验共用 | 否则对拍只覆盖交集，差额是无人区 |
| `VmpExec` 复用 | 否则基准测的是 `malloc` 而不是 VM |
| `guard()` 兜 panic | panic 跨 FFI 是 UB 级出口 |

---

## 5. 读完怎么自测

按难度递增，都能在代码里找到确切答案：

1. 一次 `dominant` 调用里，`Vm::run` 总共会被调用几次？`VmExec` 复用的是什么？
2. 为什么 `Code::byte` 里「未命中」比「命中」分支重要得多，但热路径上跑的却是「命中」分支？
3. 如果我把 `PAGE_CACHE_SLOTS` 改成 1，哪个算子受伤最重、为什么？（提示：循环体跨几页）
4. `aux` 容量是 16384，而 downscale 的 `SCALAR_BASE = 16000`，两者差 384。
   这个 384 是「够用就行」还是有实际约束？（提示：看 `downscale::aux_need()` 与
   `exec.rs` 里对 `cnt_base` 的越界检查，想想 dw 大到什么时候会撞上）
5. `blur.rs` 的 `emit_clamp` 为什么不能用 `min(w-1)` 直接截断下溢？（提示：无符号）
6. 为什么 `selftest.rs` 要自己写一个编码器，而不是复用 `asm.rs`？
7. 假如有人把 `format::seal` 改成覆盖**明文**载荷，会发生什么？
   （提示：`Code::parse` 在不解密时就能跑到那一步）
8. 两条路径的「同域」测试如果去掉，什么样的 bug 会重新变得不可见？

---

## 6. 想深挖的话

| 主题 | 去哪儿 |
|---|---|
| 加固的诚实边界（动态 dump、抗篡改、密钥分发） | NOTES §8，逐条展开 |
| 单页缓存的失败对照（46s → 0.08s） | NOTES §9 |
| 实测代价与读法（~10 ns/指令、指令数比耗时可靠） | NOTES §6 |
| 静态取证怎么做（`nm`/`strings`/`llvm-readelf` 全部命令与输出） | NOTES §7 |
| ABI 限制为什么传染给主 app | NOTES §2 末节 |
| 指令集的完整语义（含每种状态码的触发条件） | [`ISA.md`](../rust/vmp/ISA.md) |
| 面试怎么讲这个项目（六步提纲） | NOTES §11 |
