//! VMP-1 虚拟机 —— 解释执行字节码。
//!
//! ─── 这一层的职责（以及它**不**负责什么）───
//!
//! 它负责：解析容器 → 分页惰性解密 → 逐条解释 → 维护栈/aux/内存 → 报告故障。
//! 它**不负责**：任何图像算法。算法在字节码里。这句话要能一句话说清，
//! 因为「你的 VM 里是不是把算法又写了一遍」是面试里最直接的质疑点。
//!
//! ─── 两种运行模式：加密 / 明文 ───
//!
//! `run` 的 `key` 参数为 `Some((key, nonce))` 时，按 ChaCha20 解密载荷；
//! 为 `None` 时按明文读。为什么要有明文模式：
//!   - 本机单测要能在**不碰密钥**的前提下验证 VM 语义与算法对拍；
//!   - 内建自检程序（`selftest`）不承载算法，明文存放即可。
//!
//! ─── 为什么是「分页惰性解密」而不是「启动时全解密」───
//!
//! 全解密意味着「一次 `mprotect` + dump 就能拿到完整字节码」—— VM 的保护等于零。
//! 分页惰性解密下，**明文的常驻上限是一个固定的页窗口**（见 [`PAGE_CACHE_SLOTS`]，
//! 当前 16 页 = 4KB），要 dump 完整程序必须主动 hook 取指路径、并覆盖所有页
//! （含只在冷分支才执行的页）。这**不是**「不可 dump」，而是把攻击成本从
//! 「读一块内存」抬高到「写一个 tracer」—— 加固的定价从来是「抬高成本」，
//! 不是「不可破解」。这一点必须写在文档里。
//!
//! ─── 为什么是「一小组页」而不是「一次只留一页」───
//!
//! 第一版只缓存**一页**，结果在跨页循环里几乎失效：downscale 的循环体跨 4 页，
//! 每轮迭代都要把 4 页轮流解密一遍，实测单次调用触发了 **15 万次**解密 ——
//! 解密本身（每次一个 ChaCha 块，约 400 次算术）反而成了主要开销。
//!
//! 这是一个真实的工程取舍，两端都要说清楚：
//!   - 窗口**越大** ⇒ 解密越省、性能越好，但常驻明文越多、越容易 dump；
//!   - 窗口**越小** ⇒ 越难 dump，但性能越差（甚至比原生慢一个数量级）。
//!
//! 选 16 页（4KB）的理由：本 Lab 三个程序都只有 3~4 页，因此**预热后命中率
//! 接近 100%**，惰性解密几乎是免费的；而 4KB 的常驻明文相对「一个几 MB 的
//! `.so`」依然很小。真实加固器还会把这条与「按需释放」「多线程分片」组合起来。
//!
//! 代价只是每次取指多一次「标签比较」，未命中才真正解密（见 [`Code::byte`]）。
//! 顺序执行的解释器里，这个保护几乎是免费的 —— 这是本 Lab 想让看到的主要结论，
//! 而上面那次「单页缓存」的失败正是它的反面证据。

use crate::crypt::keystream_byte;
use crate::format::{unzigzag, HEADER_LEN, ISA_VERSION, MAGIC, region};

/// 明文页大小。256 字节 = 64 条最短指令（4 字节/条）的量级：
/// 既要小到「一次 dump 拿不到多少」，又要大到「减少跨页开销」。
pub const PAGE_SIZE: usize = 256;

/// 页缓存的槽数（直映，按 `页号 % SLOTS` 定位）。
///
/// 明文常驻上限 = `PAGE_CACHE_SLOTS × PAGE_SIZE` = 4KB。
/// 为什么是 16 而不是 1 或「全部」：见模块注释里那次「单页缓存」的实测失败。
pub const PAGE_CACHE_SLOTS: usize = 16;

/// 主操作数栈容量（格）。
pub const STACK_CAP: usize = 4096;
/// aux 数组容量（格）。downscale 要把每个输出列的 x0/x1 预计算进 aux
/// （每个 2 格），故给到 16384 —— 上限约为 8000 列的输出宽度，对缩略图足够。
pub const AUX_CAP: usize = 16384;
/// 调用栈容量（层）。
pub const RET_CAP: usize = 256;

/// VM 的内存视图：三段字节缓冲。
///
/// 三段都是**字节视图**；`LOAD*/DSTORE*` 的地址就是「相对该段起点的字节偏移」。
/// 没有 stride 概念 —— 二维索引的算术在字节码里显式做（`mad` 就是为它准备的），
/// 这样 VM 保持极薄，而「怎么算索引」属于算法、属于字节码。
///
/// 像素的 32 位视图（`.so` 之外的 JNI 层用 `u32` 偏移）由 JNI 侧换算成字节偏移后
/// 填进输入槽，VM 完全不需要知道。见 `vmp_android` 的 `input_slot` 说明。
pub struct Memory<'a> {
    pub src: &'a [u8],
    pub dst: &'a mut [u8],
    pub work: &'a mut [u8],
}

/// 停机状态码（与 `ISA.md` 第 2 节的表一致）。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VmError {
    /// 容器头非法（magic / 版本 / 长度 / 校验和）。
    BadHeader,
    /// 未知操作码 —— 通常是「拿错了 nonce/key」或字节码被改坏。
    BadOpcode(u8),
    /// 操作数越界：常量池下标、输入槽下标、栈/aux 溢出、RET 下溢、写只读段。
    BadOperand,
    /// 除数为 0（含 `MODB` 的模数为 0）。
    DivZero,
    /// 内存访问越界（地址 + 宽度超出所在段）。
    OutOfBounds,
    /// 超出指令步数上限 —— 字节码无法停机。
    StepLimit,
}

impl VmError {
    /// 转成 `ISA.md` 定义的负状态码，供 JNI 层回给 Kotlin。
    pub fn code(self) -> i32 {
        match self {
            VmError::BadHeader => -6,
            VmError::BadOpcode(_) => -1,
            VmError::BadOperand => -2,
            VmError::DivZero => -3,
            VmError::OutOfBounds => -4,
            VmError::StepLimit => -7,
        }
    }
}

/// 单次 `run` 的指令步数上限。
///
/// ─── 为什么 VM **必须**有步数上限（而不是「反正我们的字节码不会死循环」）───
///
/// 字节码是被保护对象，也就是**可以被篡改的对象**。一个把回边偏移改坏的补丁，
/// 就能让解释器永远转下去 —— 在 Android 上表现为「某个后台泳道线程 100% CPU」，
/// 而且没有崩溃、没有日志，非常难归因。加上限之后，最坏情况变成「一次调用
/// 返回错误码」，错误是可上报、可降级的。这是**加固本身引入的新攻击面**，
/// 必须由加固方案自己闭环 —— 是很值得在面试里主动提的一点。
///
/// 取值：2 亿步。按 ~100M 步/秒的量级，对应约 2 秒上限；而三个算子里最重的
/// 一次调用（512×512、r=6 的模糊）约在 150 万步量级，留了两个数量级余量。
pub const MAX_STEPS: u64 = 200_000_000;

/// 解密参数；`None` 表示明文（自检 / 单测）。
pub type Crypt<'a> = Option<(&'a [u8; 32], &'a [u8; 12])>;

/// 已解析的容器头 + 分页解密状态。
struct Code<'a> {
    blob: &'a [u8],
    /// 常量池起点（= HEADER_LEN）。
    consts_off: usize,
    const_count: usize,
    /// 字节码起点（= HEADER_LEN + const_count*4）。
    code_off: usize,
    code_len: usize,
    crypt: Crypt<'a>,

    /// 解密页缓存：直映，`tags[i]` 记录的页号若等于 `EMPTY` 表示该槽为空。
    tags: [usize; PAGE_CACHE_SLOTS],
    pages: Box<[[u8; PAGE_SIZE]]>,
    /// 本次运行解密了多少页（未命中次数）—— benchmark 与测试用它取证。
    fetched_pages: u32,
    /// 本次运行命中缓存的次数 —— 与未命中一起给出「缓存到底有没有用」。
    cache_hits: u32,
}

impl<'a> Code<'a> {
    /// 解析容器头（明文部分）并校验校验和。
    ///
    /// ⚠️ 校验和覆盖的是**载荷原样字节**（通常是密文），所以这一步不需要密钥 ——
    /// 见 `format::seal` 的注释。
    fn parse(blob: &'a [u8], crypt: Crypt<'a>) -> Result<Self, VmError> {
        if blob.len() < HEADER_LEN {
            return Err(VmError::BadHeader);
        }
        let u32_at = |p: usize| u32::from_le_bytes([blob[p], blob[p + 1], blob[p + 2], blob[p + 3]]);
        if u32_at(0) != MAGIC || u32_at(4) != ISA_VERSION {
            return Err(VmError::BadHeader);
        }
        let code_len = u32_at(8) as usize;
        let const_count = u32_at(12) as usize;
        let checksum = u32_at(16);
        let expect_len = HEADER_LEN + const_count * 4 + code_len;
        if blob.len() != expect_len || code_len == 0 {
            return Err(VmError::BadHeader);
        }
        let actual = crate::format::fnv1a32(&[&blob[4..16], &blob[HEADER_LEN..]].concat());
        if actual != checksum {
            return Err(VmError::BadHeader);
        }
        let consts_off = HEADER_LEN;
        Ok(Self {
            blob,
            consts_off,
            const_count,
            code_off: consts_off + const_count * 4,
            code_len,
            crypt,
            tags: [usize::MAX; PAGE_CACHE_SLOTS],
            // 4KB 的页缓存放在堆上：`Code` 是 `run` 的局部变量，
            // 直接把 16×256 的数组放在栈上在 Android 线程栈上是不必要的风险。
            pages: vec![[0u8; PAGE_SIZE]; PAGE_CACHE_SLOTS].into_boxed_slice(),
            fetched_pages: 0,
            cache_hits: 0,
        })
    }

    /// 载荷内某字节的 keystream 下标。载荷 = `consts + code`，从文件偏移 20 起。
    ///
    /// 关键点：明文头（20 字节）**不参与**加密，所以「载荷下标」与「文件偏移」差 20。
    /// 这个 20 必须只在**这一个地方**出现，否则迟早会有一处写成文件偏移。
    #[inline]
    fn ks_index(file_off: usize) -> usize {
        file_off - HEADER_LEN
    }

    /// 解密（或原样返回）载荷内单字节。
    #[inline]
    fn raw(&self, file_off: usize) -> u8 {
        let b = self.blob[file_off];
        match self.crypt {
            None => b,
            Some((key, nonce)) => b ^ keystream_byte(key, nonce, Self::ks_index(file_off)),
        }
    }

    /// 读取常量池第 `i` 项（必要时解密）。
    fn konst(&self, i: usize) -> Result<u64, VmError> {
        if i >= self.const_count {
            return Err(VmError::BadOperand);
        }
        let off = self.consts_off + i * 4;
        let v = [
            self.raw(off),
            self.raw(off + 1),
            self.raw(off + 2),
            self.raw(off + 3),
        ];
        Ok(u32::from_le_bytes(v) as u64)
    }

    /// 取字节码第 `ip` 字节，必要时解密整页。
    ///
    /// **这是整个 VM 的热路径**：顺序执行时只是「页号比较」，跨页才解密。
    /// 「惰性分页几乎免费」的说法就来自这里。
    #[inline]
    fn byte(&mut self, ip: usize) -> Result<u8, VmError> {
        if ip >= self.code_len {
            // 越过末尾：交给上层判 BadOpcode（流程跑飞，通常是字节码被改或 nonce 错）
            return Err(VmError::BadOpcode(0xff));
        }
        let page_no = ip / PAGE_SIZE;
        let slot = page_no % PAGE_CACHE_SLOTS;
        if self.tags[slot] != page_no {
            // 未命中：解密整页到该槽（直映，直接覆盖旧页）
            let start = self.code_off + page_no * PAGE_SIZE;
            let n = (self.code_len - page_no * PAGE_SIZE).min(PAGE_SIZE);
            match self.crypt {
                None => {
                    self.pages[slot][..n].copy_from_slice(&self.blob[start..start + n]);
                }
                Some((key, nonce)) => {
                    for k in 0..n {
                        self.pages[slot][k] = self.blob[start + k]
                            ^ keystream_byte(key, nonce, Self::ks_index(start + k));
                    }
                }
            }
            // 页尾不足一页时补零（越界读会在 ip 检查处被拦，补零只为消除未初始化）
            for k in n..PAGE_SIZE {
                self.pages[slot][k] = 0;
            }
            self.tags[slot] = page_no;
            self.fetched_pages += 1;
        } else {
            self.cache_hits += 1;
        }
        Ok(self.pages[slot][ip % PAGE_SIZE])
    }

    /// 在字节码里解一个 LEB128，返回 `(值, 新 ip)`。
    fn leb(&mut self, mut ip: usize) -> Result<(u64, usize), VmError> {
        let mut v = 0u64;
        let mut shift = 0u32;
        loop {
            if shift > 63 {
                return Err(VmError::BadOperand);
            }
            let b = self.byte(ip)?;
            ip += 1;
            v |= ((b & 0x7f) as u64) << shift;
            if b & 0x80 == 0 {
                return Ok((v, ip));
            }
            shift += 7;
        }
    }
}

/// VMP-1 虚拟机。
///
/// 缓冲区用 `vec![...].into_boxed_slice()` 而不是 `Box::new([0u64; N])`：
/// 后者会先在**栈上**构造整个数组再搬进堆，`AUX_CAP = 16384` 就是 128KB 栈临时，
/// 在 Android 线程栈上是个隐患。
pub struct Vm {
    stack: Box<[u64]>,
    top: usize,
    aux: Box<[u64]>,
    ret: Box<[usize]>,
    ret_top: usize,
    inputs: Vec<u64>,
    /// 运行时自检的最后一次结果（供 JNI 查询）。
    selftest_ok: bool,
    /// 上次运行解密了多少页（未命中，benchmark 的取证）。
    last_fetched_pages: u32,
    /// 上次运行命中页缓存的次数。
    last_cache_hits: u32,
    /// 上次运行执行了多少条指令（HALT 计入）。
    ///
    /// 为什么值得单独一个字段：它是**解释执行的规模**，与耗时相除就得到
    /// 「ns/指令」——这把「VM 慢」拆成了两个可分别改进的问题：
    /// 指令条数太多（字节码写得笨），还是单条指令太慢（解释器开销大）。
    /// 只看毫秒数是分不清这两者的。
    last_steps: u64,
    /// 上次运行最后停在哪条指令 —— **故障诊断的锚点**。
    ///
    /// 为什么必须有它：VM 报 `BadOperand`/`OutOfBounds` 只说「哪里不对」，
    /// 不说「执行到哪一条」，而手写字节码的 bug 往往就在上一条/下一条指令的
    /// 栈序上。有 ip 才能把「行号」反查到具体的 `b.xxx()` 调用，
    /// 把「看着字节码猜」变成「照着生成器逐行对」。
    last_ip: usize,
    /// 是否打印执行轨迹（仅宿主调试；`.so` 里恒为 false）。
    trace: bool,
}

impl Default for Vm {
    fn default() -> Self {
        Self::new()
    }
}

impl Vm {
    pub fn new() -> Self {
        Self {
            stack: vec![0u64; STACK_CAP].into_boxed_slice(),
            top: 0,
            aux: vec![0u64; AUX_CAP].into_boxed_slice(),
            ret: vec![0usize; RET_CAP].into_boxed_slice(),
            ret_top: 0,
            inputs: Vec::new(),
            selftest_ok: false,
            last_fetched_pages: 0,
            last_cache_hits: 0,
            last_steps: 0,
            last_ip: 0,
            trace: std::env::var_os("VMP_TRACE").is_some(),
        }
    }

    /// 上次运行执行的指令条数（HALT 计入）。
    pub fn last_steps(&self) -> u64 {
        self.last_steps
    }

    /// 上次运行最后停在哪条指令（故障诊断锚点）。
    pub fn last_ip(&self) -> usize {
        self.last_ip
    }

    /// 打开/关闭执行轨迹（宿主调试用；`.so` 里永不打开）。
    pub fn set_trace(&mut self, on: bool) {
        self.trace = on;
    }

    /// 上次运行的惰性解密页数（未命中）。基准用它区分「惰性分页」与「全解密」。
    pub fn last_fetched_pages(&self) -> u32 {
        self.last_fetched_pages
    }

    /// 上次运行命中页缓存的次数。
    ///
    /// 与未命中一起给出「缓存到底有没有用」：本 Lab 的三个程序预热后
    /// 命中率接近 100%，这正是「惰性解密几乎免费」这句话的**数据来源**。
    pub fn last_cache_hits(&self) -> u32 {
        self.last_cache_hits
    }

    /// 运行时自检是否通过。
    pub fn selftest_ok(&self) -> bool {
        self.selftest_ok
    }

    /// 栈顶的 `n` 个格（调试 / 取结果用）。
    pub fn stack_top(&self, n: usize) -> &[u64] {
        let start = self.top.saturating_sub(n);
        &self.stack[start..self.top]
    }

    /// 栈顶一格的快照（`dominant` 的结果从这里取）。
    pub fn stack_at(&self, i: usize) -> Option<u64> {
        self.stack.get(i).copied().filter(|_| i < self.top)
    }

    /// 执行一个程序。
    ///
    /// - `crypt`：`Some((key, nonce))` 解密运行；`None` 明文运行。
    /// - `inputs`：输入槽（由调用方按程序约定填好）。
    /// - `mem`：内存视图；不需要内存访问的程序传 `None`，此时任何 `LOAD*/DSTORE*`
    ///   都会得到 `OutOfBounds`（而不是 panic）。
    pub fn run(
        &mut self,
        blob: &[u8],
        crypt: Crypt<'_>,
        inputs: &[u64],
        mem: Option<&mut Memory<'_>>,
    ) -> Result<(), VmError> {
        self.top = 0;
        self.ret_top = 0;
        self.inputs.clear();
        self.inputs.extend_from_slice(inputs);

        let mut code = Code::parse(blob, crypt)?;
        let mut mem = mem;

        // ── 取指与栈操作宏 ──
        //
        // ⚠️ 每个宏都必须把「用到的局部量」作为**参数**接进来（`$code`、`$ip`、`$self`）。
        // 不能像写普通函数那样在宏体里直接写 `ip`/`code`/`self`：`macro_rules!` 对
        // 局部变量是**定义处卫生**（definition-site hygiene），宏体里的 `ip` 会去
        // 宏定义所在作用域找绑定 —— 那里没有，于是编译期报「cannot find value `ip`」。
        // 把接收者当参数传，标识符就变成「调用处的」，卫生问题消失。
        macro_rules! next {
            ($code:ident, $ip:ident) => {{
                let b = $code.byte($ip)?;
                $ip += 1;
                b
            }};
        }
        macro_rules! next_jump {
            ($code:ident, $ip:ident) => {{
                let (z, nip) = $code.leb($ip)?;
                $ip = nip;
                unzigzag(z)
            }};
        }
        macro_rules! pop {
            ($self:ident) => {{
                if $self.top == 0 {
                    return Err(VmError::BadOperand);
                }
                $self.top -= 1;
                $self.stack[$self.top]
            }};
        }
        macro_rules! push {
            ($self:ident, $v:expr) => {{
                if $self.top >= STACK_CAP {
                    return Err(VmError::BadOperand);
                }
                $self.stack[$self.top] = $v;
                $self.top += 1;
            }};
        }
        macro_rules! binop {
            ($self:ident, |$a:ident, $b:ident| $e:expr) => {{
                let $b = pop!($self);
                let $a = pop!($self);
                push!($self, $e);
            }};
        }

        let mut ip = 0usize;
        let mut steps = 0u64;
        loop {
            steps += 1;
            if steps > MAX_STEPS {
                self.last_ip = ip;
                // 超限也要落账，否则「步数=0」会被误读成「一步没走」。
                self.last_steps = steps;
                return Err(VmError::StepLimit);
            }
            self.last_ip = ip;
            let op = next!(code, ip);
            if self.trace {
                eprintln!("[vmp] ip={ip0} op={op} top={top}", ip0 = self.last_ip, op = op, top = self.top);
            }
            match op {
                0 => break, // HALT
                1 => {
                    let (v, nip) = code.leb(ip)?;
                    ip = nip;
                    push!(self, v);
                }
                2 => {
                    let (i, nip) = code.leb(ip)?;
                    ip = nip;
                    let v = code.konst(i as usize)?;
                    push!(self, v);
                }
                3 => {
                    let (i, nip) = code.leb(ip)?;
                    ip = nip;
                    let v = *self.inputs.get(i as usize).ok_or(VmError::BadOperand)?;
                    push!(self, v);
                }
                4 | 5 | 6 => {
                    let src = next!(code, ip);
                    let addr = pop!(self);
                    push!(self, mem_read(mem.as_deref(), src, addr, op)?);
                }
                7 | 8 => {
                    // ⚠️ 弹出顺序：**地址在栈顶**，与 AUXWR/AUXADDU/AUXRD 一致。
                    // 早先 DSTOREB 用的是「值在栈顶」，与 AUXWR 相反 —— 两种约定
                    // 并存时，手写字节码会在其中一个上必然写错，而且症状是
                    // 「写到了某个合法但错误的地址」（静默）或 OutOfBounds（才报错）。
                    // 统一之后这类错只剩一种排查方式：`note` 里记着的那次 blur 拷贝 bug。
                    let dst = next!(code, ip);
                    let addr = pop!(self);
                    let v = pop!(self);
                    mem_write(mem.as_deref_mut(), dst, addr, v, op)?;
                }
                9 => binop!(self, |a, b| a.wrapping_add(b)),
                10 => binop!(self, |a, b| a.wrapping_sub(b)),
                11 => binop!(self, |a, b| a.wrapping_mul(b)),
                12 => binop!(self, |a, b| if b == 0 {
                    return Err(VmError::DivZero);
                } else {
                    a / b
                }),
                13 => binop!(self, |a, b| if b == 0 {
                    return Err(VmError::DivZero);
                } else {
                    a % b
                }),
                14 => binop!(self, |a, b| if b == 0 {
                    return Err(VmError::DivZero);
                } else {
                    ((a as i64) / (b as i64)) as u64
                }),
                15 => binop!(self, |a, b| if b == 0 {
                    return Err(VmError::DivZero);
                } else {
                    ((a as i64) % (b as i64)) as u64
                }),
                16 => binop!(self, |a, b| a & b),
                17 => binop!(self, |a, b| a | b),
                18 => binop!(self, |a, b| a ^ b),
                19 => binop!(self, |a, b| a << (b & 63)),
                20 => binop!(self, |a, b| a >> (b & 63)),
                21 => binop!(self, |a, b| ((a as i64) >> (b & 63)) as u64),
                22 => binop!(self, |a, b| ((a as i64) < (b as i64)) as u64),
                23 => binop!(self, |a, b| ((a as i64) > (b as i64)) as u64),
                24 => binop!(self, |a, b| ((a as i64) <= (b as i64)) as u64),
                25 => binop!(self, |a, b| ((a as i64) >= (b as i64)) as u64),
                26 => binop!(self, |a, b| (a == b) as u64),
                27 => binop!(self, |a, b| (a != b) as u64),
                28 => binop!(self, |a, b| (a < b) as u64),
                29 => binop!(self, |a, b| (a > b) as u64),
                30 => {
                    // AUXRD: 区域操作数仅为与 AUXWR 对称，aux 不分区
                    let _ = next!(code, ip);
                    let addr = pop!(self);
                    if addr as usize >= AUX_CAP {
                        return Err(VmError::BadOperand);
                    }
                    push!(self, self.aux[addr as usize]);
                }
                31 => {
                    let _ = next!(code, ip);
                    let addr = pop!(self);
                    let v = pop!(self);
                    if addr as usize >= AUX_CAP {
                        return Err(VmError::BadOperand);
                    }
                    self.aux[addr as usize] = v;
                }
                32 => {
                    let _ = pop!(self);
                }
                33 => {
                    let v = pop!(self);
                    push!(self, v);
                    push!(self, v);
                }
                34 => {
                    let b = pop!(self);
                    let a = pop!(self);
                    push!(self, b);
                    push!(self, a);
                }
                35 => {
                    if self.top < 2 {
                        return Err(VmError::BadOperand);
                    }
                    let v = self.stack[self.top - 2];
                    push!(self, v);
                }
                36 | 39 => {
                    let rel = next_jump!(code, ip);
                    let target = (ip as i64 + rel) as usize;
                    if op == 39 {
                        if self.ret_top >= RET_CAP {
                            return Err(VmError::BadOperand);
                        }
                        self.ret[self.ret_top] = ip;
                        self.ret_top += 1;
                    }
                    ip = target;
                }
                37 | 38 => {
                    let rel = next_jump!(code, ip);
                    let target = (ip as i64 + rel) as usize;
                    let c = pop!(self);
                    if (op == 37 && c == 0) || (op == 38 && c != 0) {
                        ip = target;
                    }
                }
                40 => {
                    if self.ret_top == 0 {
                        return Err(VmError::BadOperand);
                    }
                    self.ret_top -= 1;
                    ip = self.ret[self.ret_top];
                }
                41 => binop!(self, |a, b| a.min(b)),
                42 => binop!(self, |a, b| a.max(b)),
                43 => binop!(self, |a, b| (a as i64).min(b as i64) as u64),
                44 => binop!(self, |a, b| (a as i64).max(b as i64) as u64),
                45 => {
                    let m = next!(code, ip);
                    if m == 0 {
                        return Err(VmError::DivZero);
                    }
                    let v = pop!(self);
                    push!(self, v % m as u64);
                }
                46 => {
                    // PACKRGBA: 栈上依次是 r,g,b,a（a 在栈顶）→ 逐个弹出
                    let a = pop!(self);
                    let b = pop!(self);
                    let g = pop!(self);
                    let r = pop!(self);
                    push!(self, (r << 24) | (g << 16) | (b << 8) | a);
                }
                47 => {
                    // MAD: 栈上依次是 a,b,c → a*b+c
                    let c = pop!(self);
                    let b = pop!(self);
                    let a = pop!(self);
                    push!(self, a.wrapping_mul(b).wrapping_add(c));
                }
                48 => {
                    // AUXADDU: a=pop(); v=pop(); aux[a] += v  （回绕）
                    //
                    // ⚠️ 弹出顺序必须与 `AUXWR`/`AUXRD` 一致：**地址在栈顶**。
                    // 写成 `v=pop(); a=pop()`（地址在次顶）时，小的值会静默写坏
                    // 一个随机 aux 槽（不报错！），大的值才越界报 BadOperand ——
                    // 第一版就是这样，靠 dominant 的对拍才抓出来。见 ISA.md。
                    //
                    // 为什么为它单开一条指令：`aux[a] += v` 是直方图/累加器的**唯一**
                    // 热模式（dominant 的权重与亮度累加、downscale 的行累加器、
                    // 计数器和自增全是它）。用「读-算-写回」三条指令表达会：
                    //   1. 让字节码体积翻倍（欠保护的算子本该尽量短）；
                    //   2. 把「地址要压两次」这个易错点暴露给每个手写序列 —— 而对拍
                    //      测试只会告诉你「结果差一点」，不会告诉你是哪一处。
                    // 融合成一条，既省体积也更不透明。与 PACKRGBA / MAD 同一动机。
                    let a = pop!(self);
                    let v = pop!(self);
                    if a as usize >= AUX_CAP {
                        return Err(VmError::BadOperand);
                    }
                    self.aux[a as usize] = self.aux[a as usize].wrapping_add(v);
                }
                49 => {
                    // PUSHMD: idx=pop(); push CONSTS[idx]
                    //
                    // 为什么需要「运行时变址取常量」：调色板查表（dominant）、
                    // 以及任何「用算出来的下标去取一张表」的算法。
                    // 没有它就只能把整张表塞进 aux 再读 —— 那是把常量池的活
                    // 搬到运行期内存里，既更长也让字节码更像「一批数据搬运」。
                    let i = pop!(self);
                    let v = code.konst(i as usize)?;
                    push!(self, v);
                }
                other => return Err(VmError::BadOpcode(other)),
            }
        }
        self.last_fetched_pages = code.fetched_pages;
        self.last_cache_hits = code.cache_hits;
        // HALT 的那一次也计入 —— 它是解释器真实执行过的一条指令，
        // 排除掉会让「ns/指令」略微高估解释器的速度。
        self.last_steps = steps;
        Ok(())
    }

    /// 运行内建自检程序（明文容器，见 `selftest.rs`），返回是否全绿。
    ///
    /// 打上 `#[inline(never)]`：它是独立的调试通道，不该被内联进 `run` 的热路径。
    #[inline(never)]
    pub fn run_selftest(&mut self) -> bool {
        let ok = match self.run(&crate::selftest::blob(), None, &[], None) {
            // 程序约定：全部断言通过时栈上只剩一个 1
            Ok(()) => self.top == 1 && self.stack[0] == 1,
            Err(_) => false,
        };
        self.selftest_ok = ok;
        ok
    }
}

/// `LOADB` / `LOADBS` / `LOADW` 的统一实现（`load_op` 即操作码）。
#[inline]
fn mem_read(mem: Option<&Memory<'_>>, region: u8, addr: u64, load_op: u8) -> Result<u64, VmError> {
    let mem = mem.ok_or(VmError::OutOfBounds)?;
    let seg: &[u8] = match region {
        region::SRC => mem.src,
        region::DST => mem.dst,
        region::WORK => mem.work,
        _ => return Err(VmError::BadOperand),
    };
    let a = addr as usize;
    match load_op {
        4 | 5 => {
            let b = *seg.get(a).ok_or(VmError::OutOfBounds)?;
            Ok(if load_op == 4 { b as u64 } else { (b as i8) as i64 as u64 })
        }
        6 => {
            let w = seg.get(a..a + 4).ok_or(VmError::OutOfBounds)?;
            Ok(u32::from_le_bytes([w[0], w[1], w[2], w[3]]) as u64)
        }
        _ => Err(VmError::BadOperand),
    }
}

/// `DSTOREB` / `DSTOREW` 的统一实现。
#[inline]
fn mem_write(
    mem: Option<&mut Memory<'_>>,
    region: u8,
    addr: u64,
    v: u64,
    store_op: u8,
) -> Result<(), VmError> {
    let mem = mem.ok_or(VmError::OutOfBounds)?;
    let seg: &mut [u8] = match region {
        region::SRC => return Err(VmError::BadOperand), // src 只读
        region::DST => mem.dst,
        region::WORK => mem.work,
        _ => return Err(VmError::BadOperand),
    };
    let a = addr as usize;
    match store_op {
        7 => {
            let s = seg.get_mut(a).ok_or(VmError::OutOfBounds)?;
            *s = v as u8;
            Ok(())
        }
        8 => {
            let s = seg.get_mut(a..a + 4).ok_or(VmError::OutOfBounds)?;
            s.copy_from_slice(&(v as u32).to_le_bytes());
            Ok(())
        }
        _ => Err(VmError::BadOperand),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::asm::ProgBuilder;
    use crate::format::Flags;

    fn flags() -> Flags {
        Flags { input_dims: vec![], out: crate::format::out::WORK }
    }

    #[test]
    fn plain_program_and_jump_backedge() {
        // 循环 3 次后把计数器留在栈上：验证回边（负 rel）与 zigzag 编码。
        //
        // ⚠️ 注意 `AUXWR` 的操作数约定：**地址在栈顶**（值在下、地址在上）。
        // 所以「写 aux[0] = 0」是 `pushi(0)`（值）再 `pushi(0)`（地址）；
        // 这个顺序写反了不会报错，只会让字节码静默算错 —— 正是在这里把我自己
        // 坑过一次，才把约定改成「地址在栈顶」并写进 `ISA.md` 第 2 节。
        let mut b = ProgBuilder::new(flags());
        b.pushi(0).pushi(0).auxwr(0); // aux[0] = 0（值 0，地址 0）
        b.label("loop");
        b.pushi(0).auxrd(0).pushi(3).cmpltu().jz("done");
        b.pushi(0).auxrd(0).pushi(1).add(); // 值 = counter+1
        b.pushi(0); // 地址 = 0
        b.auxwr(0);
        b.jmp("loop");
        b.label("done");
        b.pushi(0).auxrd(0).halt();
        let blob = b.finish();
        let mut vm = Vm::new();
        vm.run(&blob, None, &[], None).unwrap();
        assert_eq!(vm.stack_top(1), &[3], "回边执行 3 次");
    }

    #[test]
    fn runaway_bytecode_hits_step_limit_instead_of_hanging() {
        // 无条件回边（没有出口）—— 模拟「字节码被改坏」。VM 必须自行停下。
        // 这条测试是「加固引入的新攻击面必须自己闭环」的落地检查：
        // 若哪天有人把步数上限去掉，这里会立刻挂死而不是静静放过。
        let mut b = ProgBuilder::new(flags());
        b.label("forever");
        b.jmp("forever");
        let blob = b.finish();
        let mut vm = Vm::new();
        assert_eq!(vm.run(&blob, None, &[], None), Err(VmError::StepLimit));
        assert_eq!(VmError::StepLimit.code(), -7);
    }

    #[test]
    fn div_zero_is_reported_not_panicked() {
        let mut b = ProgBuilder::new(flags());
        b.pushi(1).pushi(0).div().halt();
        let blob = b.finish();
        let mut vm = Vm::new();
        assert_eq!(vm.run(&blob, None, &[], None), Err(VmError::DivZero));
        assert_eq!(VmError::DivZero.code(), -3);
    }

    #[test]
    fn corrupted_blob_is_rejected_by_header() {
        let mut b = ProgBuilder::new(flags());
        b.pushi(1).halt();
        let mut blob = b.finish();
        blob[20] ^= 0xff; // 改一个字节 → 校验和不符
        let mut vm = Vm::new();
        assert_eq!(vm.run(&blob, None, &[], None), Err(VmError::BadHeader));
    }

    #[test]
    fn selftest_passes() {
        let mut vm = Vm::new();
        assert!(vm.run_selftest(), "内建自检必须全绿");
        assert!(vm.selftest_ok());
    }

    #[test]
    fn fetch_pages_is_lazy_not_whole_program() {
        // 造一个足够长的程序，但只在开头执行几页：验证「没有整段解密」
        let mut b = ProgBuilder::new(flags());
        b.pushi(7).halt();
        // 后面塞一堆不会被执行的死代码（用 jmp 跳过）
        for _ in 0..2000 {
            b.pushi(12345).pop();
        }
        let blob = b.finish();
        let mut vm = Vm::new();
        vm.run(&blob, None, &[], None).unwrap();
        let pages = blob.len() / PAGE_SIZE;
        assert!(
            (vm.last_fetched_pages() as usize) < pages,
            "只应触达开头几页：fetched={} 总页数≈{}",
            vm.last_fetched_pages(),
            pages
        );
    }
}
