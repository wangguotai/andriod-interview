//! VMP-1 汇编器 —— 把「助记符 + 标签」编成容器格式（见 `ISA.md` 第 4 节）。
//!
//! **本文件不进 `.so`**：它只在构建期（`build.rs`）与你本机跑单测时使用。
//! 容器格式与操作码定义在 [`crate::format`]，那是 VM 与汇编器共用的契约。
//!
//! ─── 为什么需要一个汇编器，而不是手写字节数组 ───
//!
//! 手写字节数组意味着**每一条跳转偏移都要人肉计算**，程序一改就全盘漂移。
//! 那正是「对拍测试会以最贵的方式失败」的典型来源。有了标签 + 回填，
//! 改程序就只是改程序，偏移由机器算。
//!
//! 它**不做**加密、不知道密钥 —— 加密是 `build.rs` 的活。这样纯逻辑单测
//! 可以直接对「明文容器」跑虚拟机，完全不碰密钥。

use std::collections::HashMap;

use crate::format::{
    leb_encode, leb_len, seal, zigzag, Flags, Op, HEADER_LEN, MAGIC, ISA_VERSION,
};

/// 待回填的跳转：操作数所在位置 + 目标标签。
struct Fixup {
    /// 操作数（zigzag+LEB128）在 `code` 内的起始位置。
    at: usize,
    label: String,
}

/// 程序构建器。链式调用：`b.pushi(0).pushi(1).add()`。
pub struct ProgBuilder {
    code: Vec<u8>,
    consts: Vec<u64>,
    labels: HashMap<String, usize>,
    fixups: Vec<Fixup>,
    flags: Flags,
}

impl ProgBuilder {
    pub fn new(flags: Flags) -> Self {
        Self {
            code: Vec::new(),
            consts: Vec::new(),
            labels: HashMap::new(),
            fixups: Vec::new(),
            flags,
        }
    }

    /// 当前字节码长度 —— 就是下一条要发出的指令的 ip。
    pub fn ip(&self) -> usize {
        self.code.len()
    }

    /// 记录标签。同名标签重复定义会直接 panic（比静默覆盖好）。
    pub fn label(&mut self, name: &str) -> &mut Self {
        if self.labels.insert(name.to_string(), self.ip()).is_some() {
            panic!("标签重复定义: {name}");
        }
        self
    }

    /// 把常量塞进常量池，返回下标（以 `i64` 返回，便于直接喂给 `pushm`）。
    /// 相同常量复用同一下标（减小体积）。
    pub fn konst(&mut self, v: u64) -> i64 {
        if let Some(i) = self.consts.iter().position(|&c| c == v) {
            return i as i64;
        }
        self.consts.push(v);
        (self.consts.len() - 1) as i64
    }

    /// 追加一个常量、**不去重**，返回下标。
    ///
    /// 为什么需要它：常量池也可能承载**表**（如 dominant 的 12 色调色板），
    /// 而表要求下标连续（`PUSHMD(base + i)`）。去重会把重复的调色板分量
    /// 折叠成一个槽，表就不再连续 —— 这个 bug 在字节码层面完全不报错，
    /// 只会让颜色查错项。所以表必须用这个接口。
    pub fn konst_force(&mut self, v: u64) -> i64 {
        self.consts.push(v);
        (self.consts.len() - 1) as i64
    }

    fn op(&mut self, op: Op) -> &mut Self {
        self.code.push(op as u8);
        self
    }

    fn oper_u8(&mut self, op: Op, v: u8) -> &mut Self {
        self.code.push(op as u8);
        self.code.push(v);
        self
    }

    fn jump(&mut self, op: Op, label: &str) -> &mut Self {
        self.code.push(op as u8);
        self.fixups.push(Fixup {
            at: self.code.len(),
            label: label.to_string(),
        });
        // 先占 1 字节；真实长度在 finish 的不动点迭代里可能变长。
        self.code.push(0);
        self
    }

    // ───────────────────────── 压栈类 ─────────────────────────

    /// 小整数立即数（语义上按 i64 解释；负数以二补码 LEB 编码）。
    pub fn pushi(&mut self, v: i64) -> &mut Self {
        self.code.push(Op::PushI as u8);
        leb_encode(v as u64, &mut self.code);
        self
    }

    /// 读常量池下标 `idx`。
    pub fn pushm(&mut self, idx: i64) -> &mut Self {
        self.code.push(Op::PushM as u8);
        leb_encode(idx as u64, &mut self.code);
        self
    }

    /// 读输入槽 `idx`（由调用方在启动时填入）。
    pub fn pushn(&mut self, idx: i64) -> &mut Self {
        self.code.push(Op::PushN as u8);
        leb_encode(idx as u64, &mut self.code);
        self
    }

    /// 压入一个常量（自动进常量池）。
    pub fn push_const(&mut self, v: u64) -> &mut Self {
        let idx = self.konst(v);
        self.pushm(idx)
    }

    // ───────────────────────── 内存访问 ─────────────────────────
    //
    // 注意操作数语义：对 `LOAD*` 是**源段编号**（读取段），对 `DSTORE*` 是**目标段编号**。
    // 两端固定不同段：src 只读、dst/work 可写。VM 对「写 src」会报 BadOperand。

    pub fn loadb(&mut self, src: u8) -> &mut Self {
        self.oper_u8(Op::LoadB, src)
    }
    pub fn loadbs(&mut self, src: u8) -> &mut Self {
        self.oper_u8(Op::LoadBS, src)
    }
    pub fn loadw(&mut self, src: u8) -> &mut Self {
        self.oper_u8(Op::LoadW, src)
    }
    pub fn dstoreb(&mut self, dst: u8) -> &mut Self {
        self.oper_u8(Op::DStoreB, dst)
    }
    pub fn dstorew(&mut self, dst: u8) -> &mut Self {
        self.oper_u8(Op::DStoreW, dst)
    }

    // ───────────────────────── 算术 / 逻辑 ─────────────────────────

    pub fn add(&mut self) -> &mut Self {
        self.op(Op::Add)
    }
    pub fn sub(&mut self) -> &mut Self {
        self.op(Op::Sub)
    }
    pub fn mul(&mut self) -> &mut Self {
        self.op(Op::Mul)
    }
    pub fn div(&mut self) -> &mut Self {
        self.op(Op::Div)
    }
    pub fn rem(&mut self) -> &mut Self {
        self.op(Op::Rem)
    }
    pub fn idiv(&mut self) -> &mut Self {
        self.op(Op::IDiv)
    }
    pub fn irem(&mut self) -> &mut Self {
        self.op(Op::IRem)
    }
    pub fn and(&mut self) -> &mut Self {
        self.op(Op::And)
    }
    pub fn or(&mut self) -> &mut Self {
        self.op(Op::Or)
    }
    pub fn xor(&mut self) -> &mut Self {
        self.op(Op::Xor)
    }
    pub fn shl(&mut self) -> &mut Self {
        self.op(Op::Shl)
    }
    pub fn ushr(&mut self) -> &mut Self {
        self.op(Op::UShr)
    }
    pub fn ishr(&mut self) -> &mut Self {
        self.op(Op::IShr)
    }
    pub fn lt(&mut self) -> &mut Self {
        self.op(Op::Lt)
    }
    pub fn gt(&mut self) -> &mut Self {
        self.op(Op::Gt)
    }
    pub fn le(&mut self) -> &mut Self {
        self.op(Op::Le)
    }
    pub fn ge(&mut self) -> &mut Self {
        self.op(Op::Ge)
    }
    pub fn eq(&mut self) -> &mut Self {
        self.op(Op::Eq)
    }
    pub fn ne(&mut self) -> &mut Self {
        self.op(Op::Ne)
    }
    pub fn cmpltu(&mut self) -> &mut Self {
        self.op(Op::CmpLtU)
    }
    pub fn cmpgtu(&mut self) -> &mut Self {
        self.op(Op::CmpGtU)
    }
    pub fn minu(&mut self) -> &mut Self {
        self.op(Op::MinU)
    }
    pub fn maxu(&mut self) -> &mut Self {
        self.op(Op::MaxU)
    }
    pub fn mini(&mut self) -> &mut Self {
        self.op(Op::MinI)
    }
    pub fn maxi(&mut self) -> &mut Self {
        self.op(Op::MaxI)
    }
    /// 栈顶对模数 `m`（1..=255）取余。`m == 0` 会被构建期挡掉。
    pub fn modb(&mut self, m: u8) -> &mut Self {
        assert!(m >= 1, "MODB 的模数必须 ≥ 1");
        self.oper_u8(Op::ModB, m)
    }
    /// `(r,g,b,a) → (r<<24)|(g<<16)|(b<<8)|a`，消费 4 格、压入 1 格。
    pub fn packrgba(&mut self) -> &mut Self {
        self.op(Op::PackRgba)
    }
    /// `(a,b,c) → a*b+c`，消费 3 格、压入 1 格。
    pub fn mad(&mut self) -> &mut Self {
        self.op(Op::Mad)
    }

    // ───────────────────────── aux 数组 ─────────────────────────

    /// `addr = pop(); push aux[addr]`。
    pub fn auxrd(&mut self, dim: u8) -> &mut Self {
        self.oper_u8(Op::AuxRd, dim)
    }
    /// `addr = pop(); v = pop(); aux[addr] = v` —— **地址在栈顶**。
    ///
    /// 约定「地址在栈顶」是刻意的：这样「先算好值、再压地址、然后 `AUXWR`」
    /// 的序列读起来最自然，且与 `AUXRD` 的操作数顺序一致（都先弹地址）。
    pub fn auxwr(&mut self, dim: u8) -> &mut Self {
        self.oper_u8(Op::AuxWr, dim)
    }
    /// `v = pop(); a = pop(); aux[a] += v`（回绕）—— 直方图/累加器的融合指令。
    ///
    /// 出现频率极高（看一眼三个算子的字节码生成器就知道），单开一条是为了
    /// 同时压体积与压「手写序列写错」的机会，见 `ISA.md` 第 2 节。
    pub fn auxaddu(&mut self) -> &mut Self {
        self.op(Op::AuxAddu)
    }

    /// 运行时变址取常量：`idx = pop(); push CONSTS[idx]`。
    pub fn pushmd(&mut self) -> &mut Self {
        self.op(Op::PushMd)
    }
    // ───────────────────────── 栈操作 ─────────────────────────

    pub fn pop(&mut self) -> &mut Self {
        self.op(Op::Pop)
    }
    pub fn dup(&mut self) -> &mut Self {
        self.op(Op::Dup)
    }
    pub fn swap(&mut self) -> &mut Self {
        self.op(Op::Swap)
    }
    pub fn over(&mut self) -> &mut Self {
        self.op(Op::Over)
    }

    // ───────────────────────── 控制流 ─────────────────────────

    pub fn jmp(&mut self, label: &str) -> &mut Self {
        self.jump(Op::Jmp, label)
    }
    pub fn jz(&mut self, label: &str) -> &mut Self {
        self.jump(Op::Jz, label)
    }
    pub fn jnz(&mut self, label: &str) -> &mut Self {
        self.jump(Op::Jnz, label)
    }
    pub fn call(&mut self, label: &str) -> &mut Self {
        self.jump(Op::Call, label)
    }
    pub fn ret(&mut self) -> &mut Self {
        self.op(Op::Ret)
    }
    pub fn halt(&mut self) -> &mut Self {
        self.op(Op::Halt)
    }

    /// 本程序的输入维度表 / 输出段（JNI 层启动 VM 时使用）。
    pub fn flags(&self) -> &Flags {
        &self.flags
    }

    /// 回填跳转、拼装容器，返回**明文**字节流（未加密）。
    ///
    /// ─── 为什么回填是一个「全局不动点」而不是逐条算 ───
    ///
    /// 跳转偏移用变长 LEB128，而「偏移编成几个字节」又取决于偏移值 —— 自指。
    /// 更麻烦的是：**任何一条跳转变长，都会移动它之后所有指令的位置**，
    /// 于是它自己的目标位置、以及后面每条跳转的偏移都跟着变。
    /// 逐条 `splice` 会把先前算好的标签位置全部作废（很容易写出「偏移差 1」的 bug）。
    ///
    /// 正确做法是把「每条跳转的编码长度」当成一个向量整体求不动点：
    ///
    /// ```text
    /// A[j]   = at[j] + Σ_{i: at[i] < at[j]}   (len[i] - 1)      // 第 j 条操作数的最终位置
    /// T(L)   = pos(L) + Σ_{i: at[i] < pos(L)} (len[i] - 1)      // 标签 L 的最终位置
    /// rel[j] = T(label_j) - (A[j] + len[j])                     // ip_next 指操作数之后
    /// len[j] = leb_len(zigzag(rel[j]))
    /// ```
    ///
    /// 从全 1 开始迭代，收敛不了直接 panic —— 静默产出坏字节码比构建失败难查得多。
    /// 最后**一次性重建**整段字节码，不再原地 splice。
    pub fn finish(self) -> Vec<u8> {
        let n = self.fixups.len();
        let ats: Vec<usize> = self.fixups.iter().map(|f| f.at).collect();
        debug_assert!(ats.windows(2).all(|w| w[0] < w[1]), "跳转必须按位置递增发出");

        let label_pos: Vec<usize> = self
            .fixups
            .iter()
            .map(|f| {
                *self
                    .labels
                    .get(&f.label)
                    .unwrap_or_else(|| panic!("未定义的标签: {}", f.label))
            })
            .collect();

        let shift_before = |p: usize, lens: &[usize]| -> usize {
            (0..n).filter(|&i| ats[i] < p).map(|i| lens[i] - 1).sum()
        };

        let mut lens = vec![1usize; n];
        let mut converged = false;
        for _ in 0..64 {
            let mut new_lens = vec![1usize; n];
            for j in 0..n {
                let a_j = ats[j] + shift_before(ats[j], &lens);
                let t = label_pos[j] + shift_before(label_pos[j], &lens);
                let rel = t as i64 - (a_j + lens[j]) as i64;
                new_lens[j] = leb_len(zigzag(rel));
            }
            if new_lens == lens {
                converged = true;
                break;
            }
            lens = new_lens;
        }
        assert!(converged, "跳转编码长度迭代不收敛，请检查标签定义");

        // 用稳定后的 lens 重算 rel，并一次性重建字节码。
        let mut rels = vec![0i64; n];
        for j in 0..n {
            let a_j = ats[j] + shift_before(ats[j], &lens);
            let t = label_pos[j] + shift_before(label_pos[j], &lens);
            rels[j] = t as i64 - (a_j + lens[j]) as i64;
        }

        let extra: usize = lens.iter().map(|l| l - 1).sum();
        let mut code = Vec::with_capacity(self.code.len() + extra);
        let (mut i, mut j) = (0usize, 0usize);
        while i < self.code.len() {
            if j < n && ats[j] == i {
                leb_encode(zigzag(rels[j]), &mut code);
                i += 1; // 跳过 1 字节占位
                j += 1;
            } else {
                code.push(self.code[i]);
                i += 1;
            }
        }
        assert_eq!(j, n, "有跳转操作数未被回填");

        // 拼容器：头(20) + consts(u32 LE each) + code
        let mut out = Vec::with_capacity(HEADER_LEN + self.consts.len() * 4 + code.len());
        out.extend_from_slice(&MAGIC.to_le_bytes());
        out.extend_from_slice(&ISA_VERSION.to_le_bytes());
        out.extend_from_slice(&(code.len() as u32).to_le_bytes());
        out.extend_from_slice(&(self.consts.len() as u32).to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes()); // checksum 占位，seal 里填
        for c in &self.consts {
            out.extend_from_slice(&(*c as u32).to_le_bytes());
        }
        out.extend_from_slice(&code);
        seal(&mut out);
        out
    }
}
