//! 加固密钥与非名表（被 `build.rs` 与 `src/` 同时 `include!`，避免两处漂移）。
//!
//! ⚠️ **这不是一个真正的密钥管理方案**，这一点必须写在最前面，否则整个 Lab 会误导人：
//! 密钥以明文常量形式随 `.so` 一起分发，任何能拿到 `.so` 的人都能 `strings`/反汇编
//! 得到它。真实加固器要么用「密钥分片 + 白盒」，要么把解密链下移到 vmp 掩护代码里、
//! 甚至放到联网下发的 map 中。本 Lab 只演示**机制**（字节码不是明文、VM 调度可变），
//! 密钥分发属于另一层，见 `NOTES-vmp-lab.md` 的「本 Lab 证不了什么」一节。

/// 主密钥（ChaCha20，32 字节）。build.rs 用它加密字节码，VM 用它解密。
///
/// 取值毫无意义，只是 32 个可打印字节，便于在反汇编里一眼认出「这里就是 key」——
/// 演示场景下「容易被找到」反而是诚实的，省得给人「这样就安全了」的错觉。
pub const MASTER_KEY: [u8; 32] = *b"vmp-lab-key-v1-0123456789abcdef!";

/// 每个虚拟机程序的 nonce（12 字节）。不同程序用不同 nonce，避免相同明文产生
/// 相同的密文前缀 —— 这不是安全上的必需（ChaCha20 是流密码），而是为了
/// **让「同一份 .so 里三份字节码不共享 keystream」成为可检查的事实**。
pub const PROGRAMS: &[(&str, [u8; 12])] = &[
    ("dominant", *b"dom-vmp-0001"),
    ("downscale", *b"dsc-vmp-0001"),
    ("blur", *b"blr-vmp-0001"),
];

/// 取某程序的 nonce；名字写错时在构建期就会失败（返回 `None` 会被 unwrap 掉）。
pub fn nonce_of(name: &str) -> Option<[u8; 12]> {
    PROGRAMS.iter().find(|(n, _)| *n == name).map(|(_, n)| *n)
}
