//! 生成**线格式黄金样本**，供 Kotlin 侧解析器对拍。
//!
//! 为什么需要它：Kotlin 侧要自己解析线格式（跨 FFI 回传的是字节），
//! 于是**同一份格式被两门语言各实现一次**——这正是「会静默漂移」的温床。
//! 本示例把 Rust 编码器的输出固化成十六进制串，粘进 Kotlin 单测；
//! 任何一侧改了格式，对拍测试立即变红。
//!
//! ```bash
//! cargo run -p netlab --example wire_golden
//! ```
//!
//! 更新 Kotlin 黄金样本时**必须同时**重跑本命令，并在提交信息里说明格式变更。

use netlab::wire::{encode, WireResponse};

fn main() {
    let resp = WireResponse {
        status: 200,
        proto: "HTTP_3".into(),
        reused_connection: false,
        connect_ms: Some(42),
        first_byte_ms: Some(90),
        total_ms: 120,
        body_bytes: 5,
        spki_sha256: Some(
            "d8a2b48e16bb321bd8bf2e98ccdb3b38ab12b147acda75c1efa559d0fb19b663".into(),
        ),
        headers: vec![
            ("content-type".into(), "application/json".into()),
            // 重复头：证明 Kotlin 侧也不得合并
            ("set-cookie".into(), "a=1; Path=/".into()),
            ("set-cookie".into(), "b=2; Path=/".into()),
            // 值里含 ':'：证明切分只用首个 ':'
            ("x-trace".into(), "2026-10-05T12:00:00Z".into()),
        ],
    };
    let bytes = encode(&resp, b"hello").expect("编码应成功");
    let hex: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    println!("len = {}", bytes.len());
    println!("hex = {hex}");
}
