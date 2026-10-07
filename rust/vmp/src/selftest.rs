//! 内建自检（`SELFTEST` 通道）—— 一组 VM 字节码形式的算术断言。
//!
//! ─── 为什么需要它 ───
//!
//! VM 的语义是**我们自己定义的**，没有任何现成的编译器或测试套件能验证它。
//! 如果 `IREM` 被写成「向负无穷取整」（C 的行为）而不是「向零截断」（Rust/Kotlin 的行为），
//! 那么所有把负数喂给 `IREM` 的算子都会**静默算错** —— 不崩、不报错，只是数字差一点。
//! 自检把「VM 与 ISA 规范一致」变成一条**可在设备上随时重放**的证据。
//!
//! ─── 为什么用明文，且手写编码 ───
//!
//! - **明文**：自检不承载任何算子算法，加密它没有收益，还会让「VM 坏了」和
//!   「解密坏了」两类故障混在一起，难以定位。
//! - **手写编码器**：这里刻意不复用 `asm.rs`。如果自检也走汇编器，那么
//!   「汇编器把跳转偏移算错」这类 bug 会同时污染自检与受保护程序，自检就失去意义。
//!   手写编码器是本文件**独立实现**的，构成一次真正的交叉验证。
//!   因此本程序**不用任何跳转**（全是直线代码），也就不需要回填 —— 简单到不可能错。
//!
//! ─── 覆盖范围 ───
//!
//! 有符号/无符号除法与取余的符号语义、向零截断、回绕乘法、两种右移、
//! 有符号/无符号比较、`MODB`、大立即数（LEB128 多字节）、`MAXU`、`MAD`、`PACKRGBA`。

use crate::format::{leb_encode, seal, MAGIC, ISA_VERSION};

// 操作码常量 —— 刻意写成本地别名而不是 `use crate::format::Op`：
// 这个文件要能一眼看出「它用的每个操作码都是硬编码的十进制值」，
// 与 ISA 文档第 2 节的表对照。用 Op:: 枚举反而会掩盖「值有没有被改」。
const OP_PUSH_I: u8 = 1;
const OP_PUSH_M: u8 = 2;
const OP_MUL: u8 = 11;
const OP_DIV: u8 = 12;
const OP_IDIV: u8 = 14;
const OP_IREM: u8 = 15;
const OP_AND: u8 = 16;
const OP_SHL: u8 = 19;
const OP_USHR: u8 = 20;
const OP_ISHR: u8 = 21;
const OP_LT: u8 = 22;
const OP_EQ: u8 = 26;
const OP_CMPGTU: u8 = 29;
const OP_MAXU: u8 = 42;
const OP_MODB: u8 = 45;
const OP_PACKRGBA: u8 = 46;
const OP_MAD: u8 = 47;
const OP_HALT: u8 = 0;

/// 极简直线编码器（无标签、无跳转、无回填）。
struct Enc {
    code: Vec<u8>,
    consts: Vec<u64>,
}

impl Enc {
    fn new() -> Self {
        Self { code: Vec::new(), consts: Vec::new() }
    }

    /// 立即数（有符号，二补码经 LEB128）。
    fn i(&mut self, v: i64) -> &mut Self {
        self.code.push(OP_PUSH_I);
        leb_encode(v as u64, &mut self.code);
        self
    }

    /// 常量池（只放得进 u32 的值 —— 容器里常量就是 u32）。
    fn c(&mut self, v: u64) -> &mut Self {
        assert!(v <= u32::MAX as u64, "自检常量必须放得进 u32");
        let idx = match self.consts.iter().position(|&x| x == v) {
            Some(i) => i,
            None => {
                self.consts.push(v);
                self.consts.len() - 1
            }
        };
        self.code.push(OP_PUSH_M);
        leb_encode(idx as u64, &mut self.code);
        self
    }

    fn op(&mut self, o: u8) -> &mut Self {
        self.code.push(o);
        self
    }

    fn op_u8(&mut self, o: u8, v: u8) -> &mut Self {
        self.code.push(o);
        self.code.push(v);
        self
    }

    /// 消耗栈顶两格，`&` 到一起 —— 用来把一串「断言」归约成最终的一个 0/1。
    fn and2(&mut self) -> &mut Self {
        self.op(OP_AND)
    }

    fn finish(self) -> Vec<u8> {
        let mut out = Vec::new();
        out.extend_from_slice(&MAGIC.to_le_bytes());
        out.extend_from_slice(&ISA_VERSION.to_le_bytes());
        out.extend_from_slice(&(self.code.len() as u32).to_le_bytes());
        out.extend_from_slice(&(self.consts.len() as u32).to_le_bytes());
        out.extend_from_slice(&0u32.to_le_bytes());
        for c in &self.consts {
            out.extend_from_slice(&(*c as u32).to_le_bytes());
        }
        out.extend_from_slice(&self.code);
        seal(&mut out);
        out
    }
}

/// 构造自检程序。
///
/// 结构：每条断言在栈上留一个 0/1，然后依次 `AND` 归约，最后 `HALT`。
/// 因为全是直线代码，栈深随时只有几格，不需要 `DUP`/`SWAP` 之外的栈技巧。
pub fn blob() -> Vec<u8> {
    let mut e = Enc::new();

    // 断言 1：无符号除法 1000/7 = 142（截断）
    e.c(1000).c(7).op(OP_DIV).c(142).op(OP_EQ);

    // 断言 2：有符号除法 (-1000)/7 = -142（向零截断）
    e.i(-1000).c(7).op(OP_IDIV).i(-142).op(OP_EQ).and2();

    // 断言 3：有符号取余 (-1000)%7 = -6（结果符号跟随被除数，不是 +1）
    //
    // 这一条是自检里最值钱的：如果 IREM 被实现成「Python 式」的
    // (-1000) mod 7 = 1，断言 3 会失败，而它失败得非常安静 ——
    // 只有负数输入才会暴露，而正常图片数据全是非负字节。
    e.i(-1000).c(7).op(OP_IREM).i(-6).op(OP_EQ).and2();

    // 断言 4：向零截断而不是向下取整：(-7)/2 = -3（向下取整会给 -4）
    e.i(-7).c(2).op(OP_IDIV).i(-3).op(OP_EQ).and2();

    // 断言 5：回绕乘法 (1<<63)*2 = 0
    e.i(1).c(63).op(OP_SHL).c(2).op(OP_MUL).c(0).op(OP_EQ).and2();

    // 断言 6：算术右移 (-16) >> 2 = -4
    e.i(-16).c(2).op(OP_ISHR).i(-4).op(OP_EQ).and2();

    // 断言 7：逻辑右移 (1<<63) >>> 63 = 1（若误用算术右移会得到 -1）
    e.i(1).c(63).op(OP_SHL).c(63).op(OP_USHR).c(1).op(OP_EQ).and2();

    // 断言 8：有符号比较 -1 < 1 → 1（若按无符号比会得到 0）
    e.i(-1).c(1).op(OP_LT).c(1).op(OP_EQ).and2();

    // 断言 9：无符号比较 (2^64-1) > 1 → 1（若按有符号比会得到 0）
    e.i(-1).c(1).op(OP_CMPGTU).c(1).op(OP_EQ).and2();

    // 断言 10：MODB (1<<63) % 128 = 0
    e.i(1).c(63).op(OP_SHL).op_u8(OP_MODB, 128).c(0).op(OP_EQ).and2();

    // 断言 11：LEB128 多字节立即数（32767 需要 3 字节编码）
    e.i(32767).c(32767).op(OP_EQ).and2();

    // 断言 12：MAXU(3,9) = 9
    e.c(3).c(9).op(OP_MAXU).c(9).op(OP_EQ).and2();

    // 断言 13：MAD 3*4+5 = 17
    e.c(3).c(4).c(5).op(OP_MAD).c(17).op(OP_EQ).and2();

    // 断言 14：PACKRGBA(1,2,3,4) = 0x01020304
    e.c(1).c(2).c(3).c(4).op(OP_PACKRGBA).c(0x0102_0304).op(OP_EQ).and2();

    e.op(OP_HALT);
    e.finish()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::vm::Vm;

    #[test]
    fn selftest_program_passes_on_plain_vm() {
        let mut vm = Vm::new();
        let blob = blob();
        vm.run(&blob, None, &[], None).expect("自检程序必须能跑完");
        assert_eq!(vm.stack_top(1), &[1], "所有断言 AND 归约后应为 1");
    }

    #[test]
    fn selftest_blob_is_plaintext_but_wellformed() {
        let b = blob();
        assert_eq!(&b[4..8], &ISA_VERSION.to_le_bytes());
        // 明文意味着 code 段里能直接看到 PUSH_I 操作码（值 1）—— 这是刻意的
        // （自检不加密）。受保护程序则**不能**有这种可见性，见 programs.rs 的测试。
        assert!(
            b.contains(&OP_PUSH_I),
            "自检是明文程序，应当能直接看到明文操作码"
        );
    }
}
