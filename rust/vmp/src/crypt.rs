//! ChaCha20 流密码 —— 本文件被 `build.rs` 与 `src/` **同时 `include!`**。
//!
//! ─── 为什么必须同源 ───
//!
//! 加密在构建期（build.rs）做，解密在运行期（VM）做。这两段代码一旦漂移，
//! 后果不是「构建失败」，而是**运行期解出一堆垃圾字节码**，然后 VM 报个
//! `BAD_OPCODE` —— 排查成本全在设备上。用 `include!` 共享同一份实现，
//! 从机制上消灭「两份 ChaCha20」的可能。
//!
//! ─── 为什么是 ChaCha20 ───
//!
//! 1. **流密码 ⇒ 可随机访问**。VM 要能「不解密整段、只解密第 k 页」。
//!    分组密码（AES-CBC）做不到：第 k 块依赖前面所有块，必须从头解密。
//!    ChaCha20 的 keystream 是 counter 的纯函数，取任意 64 字节块都是 O(1)。
//!    这一条直接决定了 `vm.rs` 的惰性分页解密能不能成立。
//! 2. **实现极短且无查表**：ARX（add-rotate-xor）结构，约 60 行，反汇编里没有
//!    S-box 大表可被特征匹配。写进 `.so` 的固定特征越少越好。
//! 3. 它不解决「密钥分发」问题（见 `key.rs` 的说明），只解决「字节码不是明文」。
//!
//! ⚠️ 这里没有做恒定时间处理。VM 解密的是自己的字节码，不面对远程时序攻击面，
//! 为它引入恒定时间约束只会让代码更难读。

/// 块大小（字节）。keystream 按 64 字节一块生成。
pub const BLOCK_SIZE: usize = 64;

/// 对 `counter` 号 keystream 块求值，返回 64 字节。
///
/// `nonce` 固定 12 字节（ChaCha20 的 IETF 变体），因此 counter 是 32 位：
/// 我们只用得上前几千个块，不存在写满的风险。
pub fn keystream_block(key: &[u8; 32], nonce: &[u8; 12], counter: u32) -> [u8; BLOCK_SIZE] {
    // 初始状态：4 个常量字 + 8 个密钥字 + counter + 3 个 nonce 字
    let mut state = [0u32; 16];
    state[0] = 0x6170_7865; // "expa"
    state[1] = 0x3320_646e; // "nd 3"
    state[2] = 0x7962_2d32; // "2-by"
    state[3] = 0x6b20_6574; // "te k"
    for i in 0..8 {
        state[4 + i] = u32::from_le_bytes([
            key[i * 4],
            key[i * 4 + 1],
            key[i * 4 + 2],
            key[i * 4 + 3],
        ]);
    }
    state[12] = counter;
    for i in 0..3 {
        state[13 + i] = u32::from_le_bytes([
            nonce[i * 4],
            nonce[i * 4 + 1],
            nonce[i * 4 + 2],
            nonce[i * 4 + 3],
        ]);
    }

    let init = state;
    // 10 个「双轮」= 20 轮（ChaCha20 的名字就来自这个 20）
    for _ in 0..10 {
        quarter(&mut state, 0, 4, 8, 12);
        quarter(&mut state, 1, 5, 9, 13);
        quarter(&mut state, 2, 6, 10, 14);
        quarter(&mut state, 3, 7, 11, 15);
        quarter(&mut state, 0, 5, 10, 15);
        quarter(&mut state, 1, 6, 11, 12);
        quarter(&mut state, 2, 7, 8, 13);
        quarter(&mut state, 3, 4, 9, 14);
    }

    let mut out = [0u8; BLOCK_SIZE];
    for i in 0..16 {
        // 回加初始状态，再按小端落成字节
        out[i * 4..i * 4 + 4].copy_from_slice(&state[i].wrapping_add(init[i]).to_le_bytes());
    }
    out
}

/// ChaCha20 的 quarter round：`a += b; d ^= a; d <<<= 16; ...`（就地修改 `state`）。
#[inline(always)]
fn quarter(state: &mut [u32; 16], a: usize, b: usize, c: usize, d: usize) {
    state[a] = state[a].wrapping_add(state[b]);
    state[d] = (state[d] ^ state[a]).rotate_left(16);
    state[c] = state[c].wrapping_add(state[d]);
    state[b] = (state[b] ^ state[c]).rotate_left(12);
    state[a] = state[a].wrapping_add(state[b]);
    state[d] = (state[d] ^ state[a]).rotate_left(8);
    state[c] = state[c].wrapping_add(state[d]);
    state[b] = (state[b] ^ state[c]).rotate_left(7);
}

/// 对 `data` 做原地 XOR：`data[j] ^= keystream[j]`，keystream 从**载荷起点**计数。
///
/// 注意这里的位置语义：`j` 是**载荷内**的下标（0 起），不是文件偏移。
/// 明文头（`isa.md` 第 4 节的 20 字节）不在载荷里，也就永远不参与加密 ——
/// 这样 VM 才能在不解密的前提下先知道「载荷有多长」。
///
/// 因为 ChaCha20 是流密码，`XOR` 是自逆运算：加密与解密是**同一个调用**。
pub fn apply_keystream(key: &[u8; 32], nonce: &[u8; 12], data: &mut [u8]) {
    for (block_idx, chunk) in data.chunks_mut(BLOCK_SIZE).enumerate() {
        let ks = keystream_block(key, nonce, block_idx as u32);
        for (b, k) in chunk.iter_mut().zip(ks.iter()) {
            *b ^= *k;
        }
    }
}

/// 取载荷内第 `idx` 个字节的 keystream 值（O(1)，供 VM 的分页解密用）。
///
/// 这是「惰性解密」的关键：VM 想解密第 `idx` 字节，不必从 0 解到 `idx`。
pub fn keystream_byte(key: &[u8; 32], nonce: &[u8; 12], idx: usize) -> u8 {
    let block = keystream_block(key, nonce, (idx / BLOCK_SIZE) as u32);
    block[idx % BLOCK_SIZE]
}

#[cfg(test)]
mod tests {
    use super::*;

    /// RFC 8439 §2.3.2 的 block 向量（key/nonce 全 0，counter=1）。
    ///
    /// ⚠️ 备注：我最初凭记忆抄的这份向量**尾部是错的**（前 35 字节对、之后分叉）。
    /// 发现方式是：先用 Python 独立实现算出向量，再用 RFC 8439 §2.4.2 的**加密**
    /// 向量（见下一个测试）交叉仲裁 —— 后者是另一段已知答案，能证明
    /// 「Python 与 Rust 一致 ≠ 双方都错」。这条经验值得留着：
    /// **用已知答案互相仲裁，而不是相信任何一方记忆。**
    #[test]
    fn matches_rfc8439_keystream_vector() {
        let key = [0u8; 32];
        let nonce = [0u8; 12];
        let block = keystream_block(&key, &nonce, 1);
        let expected: [u8; 64] = [
            159, 7, 231, 190, 85, 81, 56, 122, 152, 186, 151, 124, 115, 45, 8, 13, 203, 15, 41,
            160, 72, 227, 101, 105, 18, 198, 83, 62, 50, 238, 122, 237, 41, 183, 33, 118, 156, 230,
            78, 67, 213, 113, 51, 176, 116, 216, 57, 213, 49, 237, 31, 40, 81, 10, 251, 69, 172,
            225, 10, 31, 75, 121, 77, 111,
        ];
        assert_eq!(block, expected, "必须与 RFC 8439 官方向量一致");
    }

    /// RFC 8439 §2.4.2 的**加密**向量：这是一段独立于上一条的已知答案。
    ///
    /// 它比「自加密再自解密」强得多：后者只能证明实现自洽（换成一堆 XorShift 也自洽）。
    /// 有了这条，「我的 ChaCha20 是不是真的 ChaCha20」就不再依赖任何人的记忆。
    #[test]
    fn matches_rfc8439_encryption_vector() {
        let key: [u8; 32] = core::array::from_fn(|i| i as u8); // 00 01 02 ... 1f
        let mut nonce = [0u8; 12];
        nonce[7] = 0x4a; // RFC 里的 000000000000004a00000000
        let plaintext = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
        let expected_ct: [u8; 114] = [
            0x6e, 0x2e, 0x35, 0x9a, 0x25, 0x68, 0xf9, 0x80, 0x41, 0xba, 0x07, 0x28, 0xdd, 0x0d,
            0x69, 0x81, 0xe9, 0x7e, 0x7a, 0xec, 0x1d, 0x43, 0x60, 0xc2, 0x0a, 0x27, 0xaf, 0xcc,
            0xfd, 0x9f, 0xae, 0x0b, 0xf9, 0x1b, 0x65, 0xc5, 0x52, 0x47, 0x33, 0xab, 0x8f, 0x59,
            0x3d, 0xab, 0xcd, 0x62, 0xb3, 0x57, 0x16, 0x39, 0xd6, 0x24, 0xe6, 0x51, 0x52, 0xab,
            0x8f, 0x53, 0x0c, 0x35, 0x9f, 0x08, 0x61, 0xd8, 0x07, 0xca, 0x0d, 0xbf, 0x50, 0x0d,
            0x6a, 0x61, 0x56, 0xa3, 0x8e, 0x08, 0x8a, 0x22, 0xb6, 0x5e, 0x52, 0xbc, 0x51, 0x4d,
            0x16, 0xcc, 0xf8, 0x06, 0x81, 0x8c, 0xe9, 0x1a, 0xb7, 0x79, 0x37, 0x36, 0x5a, 0xf9,
            0x0b, 0xbf, 0x74, 0xa3, 0x5b, 0xe6, 0xb4, 0x0b, 0x8e, 0xed, 0xf2, 0x78, 0x5e, 0x42,
            0x87, 0x4d,
        ];
        let mut buf = plaintext.to_vec();
        // 注意：RFC 的 counter=1，而 `apply_keystream` 从 counter=0 起。
        // 这里显式从 counter=1 生成 keystream 来对齐 RFC。
        for (i, b) in buf.iter_mut().enumerate() {
            let ks = keystream_block(&key, &nonce, 1 + (i / BLOCK_SIZE) as u32);
            *b ^= ks[i % BLOCK_SIZE];
        }
        assert_eq!(&buf[..], &expected_ct[..], "RFC 8439 §2.4.2 加密向量");
    }

    #[test]
    fn apply_is_self_inverse() {
        let key = *b"0123456789abcdef0123456789abcdef";
        let nonce = *b"nonce-nonce!";
        let original: Vec<u8> = (0..200u32).map(|i| (i * 7) as u8).collect();
        let mut data = original.clone();
        apply_keystream(&key, &nonce, &mut data);
        assert_ne!(data, original, "加密后应与明文不同");
        apply_keystream(&key, &nonce, &mut data);
        assert_eq!(data, original, "再异或一次必须还原（流密码自逆）");
    }

    #[test]
    fn keystream_byte_agrees_with_block() {
        let key = [7u8; 32];
        let nonce = [9u8; 12];
        let block = keystream_block(&key, &nonce, 3);
        // 第 3 块内的每个下标都要与「整块」一致 —— 这是分页解密正确性的前提
        for j in 0..BLOCK_SIZE {
            assert_eq!(keystream_byte(&key, &nonce, 3 * BLOCK_SIZE + j), block[j]);
        }
    }
}
