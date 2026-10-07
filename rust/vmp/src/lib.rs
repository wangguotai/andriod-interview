//! `vmp` —— VMP 加固 Lab 的 native 内核。
//!
//! ─── 这个 crate 是什么 ───
//!
//! 它把「图片处理 SO 的核心算子」编译成**自定义字节码**，在运行期由一个
//! 极薄的虚拟机解释执行。目的不是让程序更快（它会更慢），而是让这些算子
//! **不再以机器码形态出现在 `.so` 里** —— 也就是业界所说的 VMP（Virtual Machine
//! Protect）加固。这个 Lab 用于面试讨论「VMP 到底保护了什么、代价是多少」。
//!
//! ─── 模块地图（读代码的顺序）───
//!
//! ```text
//! key.rs       加固密钥与各程序 nonce（build.rs 与运行期共用）
//! crypt.rs     ChaCha20 流密码（被 build.rs `include!`，加解密同源）
//! format.rs    VMP-1 容器格式 / 操作码 / LEB128 / zigzag / 校验和  ← 与 ISA.md 对齐
//! asm.rs       VMP-1 汇编器（**不进 .so**，只在构建期与本机测试用）
//! vm.rs        虚拟机：解析容器 → 分页惰性解密 → 解释执行   ← `.so` 里的核心
//! selftest.rs  内建自检：一组 VM 字节码形式的算术断言（明文，独立编码器）
//! algorithms/  三个算子的「自然版本」—— 既是字节码生成器，也是对拍参照
//! programs.rs  build.rs 生成、编译期嵌入的**加密**程序（`include!`）
//! ```
//!
//! ─── 一条必须记住的边界 ───
//!
//! **算法不在这个 crate 的 Rust 代码里，在字节码里。** `algorithms/` 下的函数
//! 虽然长得像算法实现，但它们的角色是「构建期的字节码生成器」：
//! `build.rs` 调它们产出字节码、加密、然后把密文嵌进 `.so`。
//! 发布出去的 `.so` 里既没有 `algorithms/`，也没有 `asm.rs` —— 只有 `vm.rs`
//! 和一段看不懂的密文。这可以用 `nm`/`strings` 直接验证，见 NOTES 文档。

#![deny(unsafe_op_in_unsafe_fn)]

// `algorithms`（字节码生成器）与 `asm`（汇编器）**不进 `.so`**（见模块地图），
// 它们只在构建期（`build.rs` 用 `include!`）与对拍测试里使用。因此在 lib 目标下
// 有一批「本目标用不到」的项；这是**刻意的边界**，不是遗漏。
#[allow(dead_code)]
pub mod algorithms;
#[allow(dead_code)]
pub mod asm;
pub mod crypt;
pub mod format;
pub mod key;
pub mod selftest;
pub mod vm;

/// 编译期嵌入的加密程序。由 `build.rs` 生成到 `OUT_DIR/programs.rs`。
///
/// 没有任何 `#[cfg]` 门控：`.so` 里的 VM 若拿不到程序就没有存在意义。
/// 若 `build.rs` 没能生成它（例如你把 `build.rs` 删了），编译会**失败**而不是
/// 静默产出一个「没有程序的加固库」—— 后者是典型的假绿。
pub mod programs {
    include!(concat!(env!("OUT_DIR"), "/programs.rs"));
}

/// 三个受保护算子的名字，供 JNI 层做「程序是否存在」的查询与日志。
pub const PROGRAM_NAMES: [&str; 3] = ["dominant", "downscale", "blur"];

/// 查找某个程序。
pub fn find(name: &str) -> Option<&'static programs::Program> {
    programs::ALL.iter().find(|p| p.name == name).copied()
}
