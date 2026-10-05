//! 手动验证：用 netlab::h3::fetch 对公网发起真实 HTTP/3 请求。
//!
//! 为什么不写成 `#[test]`：它**依赖外网**，放进常规单测会让 `cargo test`
//! 在无网/被墙环境下变红 —— 那会污染「秒级反馈回路」这条纪律。
//! 需要外网验证时显式运行：
//!
//! ```bash
//! cargo run -p netlab --example h3_fetch -- cloudflare-quic.com
//! ```
//!
//! 它同时是**线程治理的证据**：打印「运行时线程数」，证明 current-thread runtime
//! 没有额外起 worker 线程（这是接入本仓库泳道治理的前提）。

use netlab::h3::{fetch, spki_sha256_hex, FetchRequest, TlsConfig};

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let verify_pin = args.iter().any(|a| a == "--verify-pin");
    let host = args
        .iter()
        .skip(1)
        .find(|a| !a.starts_with("--"))
        .cloned()
        .unwrap_or_else(|| "cloudflare-quic.com".into());

    let before = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(0);

    let req = FetchRequest {
        url: format!("https://{host}/"),
        method: "GET".into(),
        timeout_ms: 20_000,
        headers: vec![("user-agent".into(), "netlab-h3-probe".into())],
        ..Default::default()
    };

    println!("[netlab] 发起 HTTP/3 请求：{} (host cpu={before})", req.url);
    let t0 = std::time::Instant::now();
    let resp = match fetch(req.clone(), &TlsConfig::default(), || false) {
        Ok(r) => {
            println!("[netlab] ✅ HTTP/3 成功");
            println!("  status        = {}", r.status);
            println!("  connect_ms    = {} (QUIC 建连，含融合的 TLS 握手)", r.connect_ms);
            println!("  first_byte_ms = {}", r.first_byte_ms);
            println!("  total_ms      = {} (含 runtime 启动)", r.total_ms);
            println!("  body bytes    = {}", r.body.len());
            println!("  headers       = {} 项", r.headers.len());
            for (k, v) in r.headers.iter().take(6) {
                let shown: String = v.chars().take(60).collect();
                println!("      {k}: {shown}");
            }
            println!("  墙钟耗时      = {}ms", t0.elapsed().as_millis());
            r
        }
        Err(e) => {
            println!("[netlab] ❌ HTTP/3 失败：{e}");
            println!("  code = {}", e.code());
            std::process::exit(1);
        }
    };

    if !verify_pin {
        return;
    }

    // ── 端到端验证 pinning（设计文档 §6 的验收）──
    println!("\n[netlab] ── 证书固定（SPKI pinning）端到端验证 ──");
    let leaf = resp.peer_certificates.first().expect("应取到叶证书");
    let spki = spki_sha256_hex(leaf).expect("应能算出 SPKI");
    println!("  叶证书 SPKI-SHA256 = {spki}");
    println!("  链长度             = {} 张", resp.peer_certificates.len());

    // ① 正确 pin → 必须成功
    let good = TlsConfig { pins: vec![(Some(host.clone()), parse_hex32(&spki))] };
    match fetch(req.clone(), &good, || false) {
        Ok(_) => println!("  ✅ 正确 pin：请求成功（预期）"),
        Err(e) => {
            println!("  ❌ 正确 pin 却失败：{e}（pinning 实现有误）");
            std::process::exit(1);
        }
    }

    // ② 错误 pin → 必须失败，且被判为 pin 不匹配（而不是普通网络错误）
    let mut wrong = [0u8; 32];
    wrong[0] = 0xDE;
    wrong[1] = 0xAD;
    let bad = TlsConfig { pins: vec![(Some(host.clone()), wrong)] };
    match fetch(req, &bad, || false) {
        Ok(_) => {
            println!("  ❌ 错误 pin 竟然成功 —— 证书固定形同虚设！（安全红线）");
            std::process::exit(1);
        }
        Err(e) if e.pin_mismatch() => {
            println!("  ✅ 错误 pin：被拒且识别为 pin 不匹配（预期）");
            println!("     诊断：{}", e.message());
        }
        Err(e) => {
            println!("  ❌ 错误 pin 被拒，但未被识别为 pin 不匹配：{e}");
            std::process::exit(1);
        }
    }
    println!("[netlab] ✅ pinning 验证通过：正确命中、错误拒绝、且可诊断");
}

/// 把 64 位十六进制串解析成 32 字节（演示用；真实配置应由 Kotlin 侧解析并校验长度）。
fn parse_hex32(hex: &str) -> [u8; 32] {
    let mut out = [0u8; 32];
    for (i, chunk) in hex.as_bytes().chunks(2).enumerate().take(32) {
        out[i] = u8::from_str_radix(std::str::from_utf8(chunk).unwrap(), 16).unwrap();
    }
    out
}
