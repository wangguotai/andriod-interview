//! VMP-1 容器格式与字节码定义 —— **VM 与汇编器共同的底层契约**。
//!
//! 这个文件被三方使用，且必须三方一致：
//!   1. `build.rs`（构建期，生成加密字节码）—— 直接 `include!` 本文件；
//!   2. `vm.rs`（运行期，解释执行）；
//!   3. `asm.rs`（汇编器，只在本机测试与构建期使用）。
//!
//! ─── 为什么把「格式」与「汇编器」拆开 ───
//!
//! `format.rs` 会随 `.so` 分发（VM 需要它），`asm.rs` 不会（它只在构建期与你本机
//! 跑测试时用）。这么拆的意义不只是体积：**它明确了「算法」与「工具」的边界** ——
//! 三个受保护算子的算法既不在这两个文件里，也不在 VM 里，而在构建期生成、加密后
//! 落进 `.so` 的字节码里。被追问时这一条要能一句话说清。

/// 容器 magic：`"VPM1"`（小端读出为 0x56504D31）。
pub const MAGIC: u32 = 0x5650_4D31;
/// ISA 版本，写进头部并参与校验（见 `ISA.md` 第 6 节）。
pub const ISA_VERSION: u32 = 1;
/// 明文头长度：magic(4) + isa_version(4) + code_len(4) + const_count(4) + checksum(4)。
pub const HEADER_LEN: usize = 20;

/// 内存段编号 —— `LOAD*/DSTORE*` 的操作数取值。
pub mod region {
    /// 只读输入（源图 / 中间数据）。
    pub const SRC: u8 = 0;
    /// 可写输出（downscale 把它接成 32 位宽的直写视图，写完即落到目标缓冲）。
    pub const DST: u8 = 1;
    /// 可写工作缓冲（blur / dominant 的中间与结果都放这里，由 JNI 层拷出）。
    pub const WORK: u8 = 2;
}

/// 结果落到哪个内存段（`Flags::out` 的取值）。
pub mod out {
    /// 结果留在 `WORK` 段，JNI 层负责拷出。
    pub const WORK: u8 = 2;
    /// 结果直接写进 `DST` 段（downscale）。
    pub const DST: u8 = 1;
}

/// 程序元信息：**不进字节码**，由 JNI 层在启动 VM 时供给。
///
/// 为什么元信息不编进字节码：`input_dims` 携带的是**每次调用的参数**（图像尺寸、
/// 模糊半径），编进字节码就等于把「这一次调用的参数」写死进代码 —— 既不可复用，
/// 也等于把参数白送给反编译者。
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Flags {
    /// 每个输入槽的维度（JNI 层用它做地址校验；VM 只把它当 `u64`）。
    pub input_dims: Vec<u8>,
    /// 结果落到哪个段（见 [`out`]）。
    pub out: u8,
}

/// VMP-1 操作码。数值与 `ISA.md` 第 2 节的表**必须逐项一致**。
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub enum Op {
    Halt = 0,
    PushI = 1,
    PushM = 2,
    PushN = 3,
    LoadB = 4,
    LoadBS = 5,
    LoadW = 6,
    DStoreB = 7,
    DStoreW = 8,
    Add = 9,
    Sub = 10,
    Mul = 11,
    Div = 12,
    Rem = 13,
    IDiv = 14,
    IRem = 15,
    And = 16,
    Or = 17,
    Xor = 18,
    Shl = 19,
    UShr = 20,
    IShr = 21,
    Lt = 22,
    Gt = 23,
    Le = 24,
    Ge = 25,
    Eq = 26,
    Ne = 27,
    CmpLtU = 28,
    CmpGtU = 29,
    AuxRd = 30,
    AuxWr = 31,
    Pop = 32,
    Dup = 33,
    Swap = 34,
    Over = 35,
    Jmp = 36,
    Jz = 37,
    Jnz = 38,
    Call = 39,
    Ret = 40,
    MinU = 41,
    MaxU = 42,
    MinI = 43,
    MaxI = 44,
    ModB = 45,
    PackRgba = 46,
    Mad = 47,
    AuxAddu = 48,
    /// `idx = pop(); push CONSTS[idx]` —— 运行时变址取常量（表查找）。
    PushMd = 49,
}

/// LEB128 无符号编码（写进 `out`）。
pub fn leb_encode(mut v: u64, out: &mut Vec<u8>) {
    loop {
        let byte = (v & 0x7f) as u8;
        v >>= 7;
        if v == 0 {
            out.push(byte);
            break;
        }
        out.push(byte | 0x80);
    }
}

/// LEB128 编码需要的字节数（供跳转回填的迭代使用）。
pub fn leb_len(mut v: u64) -> usize {
    let mut n = 1;
    while {
        v >>= 7;
        v != 0
    } {
        n += 1;
    }
    n
}

/// zigzag 编码：把有符号数映射成无符号，使**正负小数都只占 1~2 字节**。
///
/// 为什么跳转偏移必须 zigzag：VMP-1 的相对偏移是 `rel = target - ip_next`，
/// 循环回边（向后跳）得到**负数**、分支跳过（向前跳）得到正数。
/// 若直接把 `rel as u64` 塞进 LEB128，负数的二补码是 64 位全 1 前缀，
/// 一条回边要占 10 字节 —— 而循环是热点，这不可接受。
/// zigzag 让 ±1 都落在 1 位数量级。
pub fn zigzag(v: i64) -> u64 {
    ((v << 1) ^ (v >> 63)) as u64
}

/// zigzag 的逆运算。
pub fn unzigzag(z: u64) -> i64 {
    ((z >> 1) as i64) ^ -((z & 1) as i64)
}

/// FNV-1a 32 位 —— 只用于「抓字节码损坏 / 版本错配」，**不是**密码学完整性。
///
/// 必须写明这一点：它挡不住有意的篡改（改一个字节再重算校验和是几行代码的事）。
/// 真正的抗篡改要靠签名，本 Lab 不做 —— 见 `NOTES-vmp-lab.md` 的对抗强度一节。
pub fn fnv1a32(data: &[u8]) -> u32 {
    let mut h: u32 = 0x811C_9DC5;
    for &b in data {
        h ^= b as u32;
        h = h.wrapping_mul(0x0100_0193);
    }
    h
}

/// 对已组帧的容器重算并写入校验和。
///
/// ─── 校验和覆盖什么：**载荷原样字节**，通常是密文 ───
///
/// 覆盖 `blob[4..16]`（版本/长度/常量数）与 `blob[20..]`。因为载荷可能是密文，
/// 所以这个校验和**在不解密的前提下就能验** —— 这正是 VM 需要的：解析容器时先把
/// 「文件是否完整」判掉，再谈解密。
///
/// 构建顺序因此是：先组明文帧并 `seal` 一次；再加密载荷、**再 `seal` 一次**覆盖密文。
pub fn seal(blob: &mut [u8]) {
    assert!(blob.len() >= HEADER_LEN, "容器太短");
    let h = fnv1a32(&[&blob[4..16], &blob[HEADER_LEN..]].concat());
    blob[16..20].copy_from_slice(&h.to_le_bytes());
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn zigzag_roundtrip_and_compactness() {
        for v in [-1000i64, -128, -127, -1, 0, 1, 63, 64, 127, 128, 100000] {
            assert_eq!(unzigzag(zigzag(v)), v, "zigzag 必须可逆: {v}");
        }
        // ±1 在 zigzag 后都落在 1 位数量级 —— 这是「回边只占 1 字节」的前提
        assert!(zigzag(1) < 128);
        assert!(zigzag(-1) < 128);
        assert!(zigzag(63) < 128);
    }

    #[test]
    fn leb128_boundaries() {
        for v in [0u64, 1, 127, 128, 16383, 16384, 1 << 30] {
            let mut buf = Vec::new();
            leb_encode(v, &mut buf);
            assert_eq!(buf.len(), leb_len(v), "len 与 encode 必须一致: {v}");
            // 手工解码自证
            let mut got = 0u64;
            let mut shift = 0;
            for &b in &buf {
                got |= ((b & 0x7f) as u64) << shift;
                shift += 7;
            }
            assert_eq!(got, v);
        }
    }
}
