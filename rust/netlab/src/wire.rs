//! JNI 回传的**线格式**编解码。
//!
//! 对应设计文档 [A · §3 合成 Response]：Rust 把一个传输结果跨 FFI 交回 Kotlin，
//! Kotlin 据此合成 `okhttp3.Response`。
//!
//! ─── 为什么自定义文本格式，而不是 JSON/Protobuf ───
//!
//! 1. **零依赖**：本 crate 要能在无网络环境 `cargo test` 与交叉编译，
//!    引 serde 会显著拉高构建成本。
//! 2. **可读**：跨语言边界的错误最难查，人眼可读的线格式能在 logcat 里直接看。
//! 3. **字段错位是最阴的错法**：若用「位置约定」的二进制布局（第 0 个 int 是 status…），
//!    两端字段顺序不一致会**静默解析出错误的值**，不报错。带 key 的文本格式从
//!    格式上消除这类错误。
//!
//! ─── 格式（v1）───
//!
//! ```text
//! OK            ← 或 ERR:<code>
//! status=200
//! proto=HTTP_3
//! reused=true
//! connect_ms=42        ← 可缺省（QUIC 融合握手仍会有；缺失表示未测到）
//! first_byte_ms=90     ← 可缺省
//! total_ms=120
//! body_bytes=1024
//! hdr=Content-Type: application/json   ← 可 0..N 行；值内的 ':' 由首个 ':' 切分
//! <空行>
//! <body 原始字节，长度 = body_bytes>
//! ```
//!
//! 用**首个** ':' 切分 header，是因为 header 值本身常含 ':'（如 URL、时间戳），
//! 按「所有 ':' 切分」会把值截断 —— 这是 header 解析的经典 bug。

use std::collections::BTreeMap;

/// 解析错误。返回具体原因而非 bool，便于 Kotlin 侧诊断。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WireError {
    /// 缺少头部/空行分隔
    MissingSeparator,
    /// 首行不是 OK / ERR:<code>
    BadStatusLine,
    /// 必填字段缺失
    MissingField(&'static str),
    /// 字段值无法解析
    BadFieldValue(&'static str),
    /// body_bytes 与实际 body 长度不符
    BodyLengthMismatch { declared: usize, actual: usize },
}

/// 一次传输结果的线格式表示（不含 body 本身，body 由调用方另行携带）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WireResponse {
    pub status: u16,
    /// 协议标识，如 `HTTP_3` / `HTTP_2`
    pub proto: String,
    pub reused_connection: bool,
    pub connect_ms: Option<u64>,
    pub first_byte_ms: Option<u64>,
    pub total_ms: u64,
    pub body_bytes: usize,
    /// 响应头。用 BTreeMap 保证编码顺序稳定（便于对拍逐字节比较）。
    pub headers: BTreeMap<String, String>,
}

fn itoa(n: u64) -> String {
    n.to_string()
}

/// 编码为线格式字节。`body` 必须与 `resp.body_bytes` 一致，否则返回错误。
pub fn encode(resp: &WireResponse, body: &[u8]) -> Result<Vec<u8>, WireError> {
    if body.len() != resp.body_bytes {
        return Err(WireError::BodyLengthMismatch {
            declared: resp.body_bytes,
            actual: body.len(),
        });
    }
    let mut head = String::new();
    head.push_str("OK\n");
    head.push_str(&format!("status={}\n", resp.status));
    head.push_str(&format!("proto={}\n", resp.proto));
    head.push_str(&format!("reused={}\n", resp.reused_connection));
    if let Some(v) = resp.connect_ms {
        head.push_str(&format!("connect_ms={}\n", itoa(v)));
    }
    if let Some(v) = resp.first_byte_ms {
        head.push_str(&format!("first_byte_ms={}\n", itoa(v)));
    }
    head.push_str(&format!("total_ms={}\n", resp.total_ms));
    head.push_str(&format!("body_bytes={}\n", resp.body_bytes));
    // BTreeMap 迭代顺序即 key 字典序，编码结果确定，便于逐字节对拍。
    for (k, v) in &resp.headers {
        head.push_str(&format!("hdr={k}: {v}\n"));
    }
    head.push('\n');

    let mut out = head.into_bytes();
    out.extend_from_slice(body);
    Ok(out)
}

/// 编码一个错误结果（无 body）。
pub fn encode_error(code: i32, message: &str) -> Vec<u8> {
    format!("ERR:{code}\nmsg={message}\n\n").into_bytes()
}

/// 解码线格式。返回 `(WireResponse, body)`。
pub fn decode(buf: &[u8]) -> Result<(WireResponse, Vec<u8>), WireError> {
    // 头部是 UTF-8 文本，body 可能任意字节 → 先找到「空行」的分界。
    let sep = find_header_end(buf).ok_or(WireError::MissingSeparator)?;
    let head = std::str::from_utf8(&buf[..sep]).map_err(|_| WireError::BadStatusLine)?;
    let body = buf[sep..].to_vec();

    let mut lines = head.lines();
    let status_line = lines.next().ok_or(WireError::BadStatusLine)?;
    if !status_line.starts_with("OK") {
        return Err(WireError::BadStatusLine);
    }

    let mut status: Option<u16> = None;
    let mut proto: Option<String> = None;
    let mut reused = false;
    let mut connect_ms = None;
    let mut first_byte_ms = None;
    let mut total_ms = 0u64;
    let mut body_bytes: Option<usize> = None;
    let mut headers = BTreeMap::new();

    for line in lines {
        if line.is_empty() {
            continue;
        }
        if let Some(rest) = line.strip_prefix("hdr=") {
            // 用**首个** ':' 切分：header 值本身常含 ':'。
            if let Some(idx) = rest.find(':') {
                let k = rest[..idx].trim().to_ascii_lowercase();
                let v = rest[idx + 1..].trim().to_string();
                headers.insert(k, v);
            } else {
                headers.insert(rest.trim().to_ascii_lowercase(), String::new());
            }
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue; // 未知行：忽略而不是报错，保证向后兼容（新增字段不炸旧解析器）
        };
        match key {
            "status" => {
                status = Some(value.parse().map_err(|_| WireError::BadFieldValue("status"))?)
            }
            "proto" => proto = Some(value.to_string()),
            "reused" => reused = value == "true",
            "connect_ms" => connect_ms = value.parse().ok(),
            "first_byte_ms" => first_byte_ms = value.parse().ok(),
            "total_ms" => {
                total_ms = value.parse().map_err(|_| WireError::BadFieldValue("total_ms"))?
            }
            "body_bytes" => {
                body_bytes =
                    Some(value.parse().map_err(|_| WireError::BadFieldValue("body_bytes"))?)
            }
            _ => {}
        }
    }

    let status = status.ok_or(WireError::MissingField("status"))?;
    let proto = proto.ok_or(WireError::MissingField("proto"))?;
    let declared = body_bytes.ok_or(WireError::MissingField("body_bytes"))?;
    if declared != body.len() {
        return Err(WireError::BodyLengthMismatch { declared, actual: body.len() });
    }

    Ok((
        WireResponse {
            status,
            proto,
            reused_connection: reused,
            connect_ms,
            first_byte_ms,
            total_ms,
            body_bytes: declared,
            headers,
        },
        body,
    ))
}

/// 找到头部与 body 的分界（连续两个 `\n` 之后的位置）。
fn find_header_end(buf: &[u8]) -> Option<usize> {
    let mut i = 0;
    while i + 1 < buf.len() {
        if buf[i] == b'\n' && buf[i + 1] == b'\n' {
            return Some(i + 2);
        }
        i += 1;
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> WireResponse {
        let mut headers = BTreeMap::new();
        headers.insert("content-type".to_string(), "application/json".to_string());
        headers.insert("x-trace".to_string(), "2026-10-05T12:00:00Z".to_string());
        WireResponse {
            status: 200,
            proto: "HTTP_3".to_string(),
            reused_connection: true,
            connect_ms: Some(42),
            first_byte_ms: Some(90),
            total_ms: 120,
            body_bytes: 5,
            headers,
        }
    }

    #[test]
    fn roundtrip_preserves_all_fields_and_body() {
        let body = b"hello";
        let encoded = encode(&sample(), body).expect("编码应成功");
        let (resp, decoded_body) = decode(&encoded).expect("解码应成功");
        assert_eq!(resp, sample());
        assert_eq!(decoded_body, body);
    }

    #[test]
    fn roundtrip_with_empty_body() {
        let mut r = sample();
        r.body_bytes = 0;
        let encoded = encode(&r, b"").expect("空 body 应可编码");
        let (resp, body) = decode(&encoded).expect("空 body 应可解码");
        assert_eq!(resp, r);
        assert!(body.is_empty());
    }

    #[test]
    fn header_value_may_contain_colon() {
        // header 值含 ':' 是常态（时间戳、URL）。若按所有 ':' 切分会截断 vT
        let encoded = encode(&sample(), b"hello").unwrap();
        let (resp, _) = decode(&encoded).unwrap();
        assert_eq!(
            resp.headers.get("x-trace").map(String::as_str),
            Some("2026-10-05T12:00:00Z"),
            "值中的 ':' 不得被当作分隔符"
        );
    }

    #[test]
    fn optional_timing_fields_can_be_absent() {
        let mut r = sample();
        r.connect_ms = None;
        r.first_byte_ms = None;
        let encoded = encode(&r, b"hello").unwrap();
        let (resp, _) = decode(&encoded).unwrap();
        assert_eq!(resp.connect_ms, None);
        assert_eq!(resp.first_byte_ms, None);
    }

    #[test]
    fn encode_rejects_body_length_mismatch() {
        // 声明 5 字节却给了 3 字节：必须在**编码侧**就拦住，
        // 否则接收方会读到错位的 body（静默错误）。
        let err = encode(&sample(), b"abc").unwrap_err();
        assert_eq!(err, WireError::BodyLengthMismatch { declared: 5, actual: 3 });
    }

    #[test]
    fn decode_rejects_body_length_mismatch() {
        let mut encoded = encode(&sample(), b"hello").unwrap();
        // 篡改：裁掉一个 body 字节，让实际长度变为 4
        encoded.truncate(encoded.len() - 1);
        let err = decode(&encoded).unwrap_err();
        assert_eq!(err, WireError::BodyLengthMismatch { declared: 5, actual: 4 });
    }

    #[test]
    fn decode_rejects_missing_separator() {
        assert_eq!(decode(b"OK\nstatus=200").unwrap_err(), WireError::MissingSeparator);
    }

    #[test]
    fn decode_rejects_bad_status_line() {
        let buf = b"NOPE\nstatus=200\nproto=HTTP_3\nbody_bytes=0\n\n";
        assert_eq!(decode(buf).unwrap_err(), WireError::BadStatusLine);
    }

    #[test]
    fn decode_rejects_missing_required_field() {
        // 缺 proto
        let buf = b"OK\nstatus=200\nbody_bytes=0\n\n";
        assert_eq!(decode(buf).unwrap_err(), WireError::MissingField("proto"));
    }

    #[test]
    fn unknown_fields_are_ignored_for_forward_compat() {
        // 新版本新增字段时，旧解析器不应炸 —— 这是线格式演进的基本要求。
        let buf = b"OK\nstatus=204\nproto=HTTP_3\nfuture_field=xyz\nbody_bytes=0\n\n";
        let (resp, _) = decode(buf).expect("未知字段应被忽略");
        assert_eq!(resp.status, 204);
        assert_eq!(resp.proto, "HTTP_3");
    }

    #[test]
    fn error_encoding_is_parseable_as_error_line() {
        let bytes = encode_error(4, "ProxyUnsupported");
        let text = String::from_utf8(bytes).unwrap();
        assert!(text.starts_with("ERR:4"), "错误码需稳定且可解析，实际：{text}");
        assert!(text.contains("ProxyUnsupported"));
    }

    #[test]
    fn encoding_is_deterministic_for_byte_level_diff() {
        // 确定性编码：同一输入两次编码必须逐字节相同。
        // 这是把线格式拿来做「对拍」的前提（否则 diff 全是噪声）。
        let a = encode(&sample(), b"hello").unwrap();
        let b = encode(&sample(), b"hello").unwrap();
        assert_eq!(a, b);
    }
}
