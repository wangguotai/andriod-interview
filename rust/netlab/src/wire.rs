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
//! status=200            ← ERR 时无此行
//! proto=HTTP_3
//! reused=true
//! connect_ms=42         ← 可缺省（缺失表示未测到）
//! first_byte_ms=90      ← 可缺省
//! total_ms=120
//! spki=<64 hex>         ← 可缺省：叶证书 SPKI-SHA256，供 pin 对拍
//! hdr=Content-Type: application/json   ← 0..N 行，**有序且允许重复键**
//! hdr=Set-Cookie: a=1
//! hdr=Set-Cookie: b=2
//! <空行>
//! <body 原始字节，长度 = body_bytes>
//! ```
//!
//! ─── 两处刻意的设计（都是踩过的坑）───
//!
//! **① header 用首个 ':' 切分**：header 值本身常含 ':'（URL、时间戳），
//! 按「所有 ':' 切分」会把值截断 —— header 解析的经典 bug。
//!
//! **② headers 用有序 Vec 而不是 map**：HTTP **允许重复头**，最典型的是
//! `Set-Cookie`（一次响应发多个 cookie）与 `Warning`。若用 `BTreeMap` 存，
//! 重复键会被覆盖 —— **静默丢 cookie**，而且是只在「同时设多个 cookie」的
//! 场景才出现、本地单测若无重复头样本就永远发现不了的错法。
//! 顺序也保留：某些服务端依赖头的相对顺序。
//!
//! ⚠️ 诚实缺口：QUIC 把 TLS 与传输握手融合，且 quinn 未暴露 cipher/TLS 版本，
//! 因此本格式**只回传叶证书 SPKI**，不足以在 Kotlin 侧完整重建 `okhttp3.Handshake`。
//! 合成 Response 时 `handshake` 会置为 null 并单独打标（见 A §3 的诚实清单）。

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
    /// 叶证书 SPKI-SHA256（小写十六进制）。用于两端 pin 对拍。
    pub spki_sha256: Option<String>,
    /// 响应头。**有序 + 允许重复键**（见模块头 ②）。
    pub headers: Vec<(String, String)>,
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
        head.push_str(&format!("connect_ms={v}\n"));
    }
    if let Some(v) = resp.first_byte_ms {
        head.push_str(&format!("first_byte_ms={v}\n"));
    }
    head.push_str(&format!("total_ms={}\n", resp.total_ms));
    head.push_str(&format!("body_bytes={}\n", resp.body_bytes));
    if let Some(spki) = &resp.spki_sha256 {
        head.push_str(&format!("spki={spki}\n"));
    }
    // **按原始顺序逐条 emit**，重复键不合并。
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
    let mut spki_sha256 = None;
    let mut headers: Vec<(String, String)> = Vec::new();

    for line in lines {
        if line.is_empty() {
            continue;
        }
        if let Some(rest) = line.strip_prefix("hdr=") {
            // 用**首个** ':' 切分：header 值本身常含 ':'。
            if let Some(idx) = rest.find(':') {
                headers.push((
                    rest[..idx].trim().to_ascii_lowercase(),
                    rest[idx + 1..].trim().to_string(),
                ));
            } else {
                headers.push((rest.trim().to_ascii_lowercase(), String::new()));
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
            "spki" => spki_sha256 = Some(value.to_string()),
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
            spki_sha256,
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

// ─────────────────────────────────────────
// 请求头/响应头的「行格式」编解码
// ─────────────────────────────────────────
//
// JNI 侧传 header 不用 `Map<String, List<String>>`，而用一段文本：
// 对象多了 JNI 取值要逐次 get，往返次数与出错面都变大；一段文本一次读入、
// 解析在一处。格式：每行 `Name: value`，行器以 **`\r\n`** 分隔。
//
// 为什么必须放内核而不是 JNI 层：这样解析逻辑能在**宿主**上秒级单测
// （见本模块 #[cfg(test)]），JNI 层保持「只做类型翻译」的薄度。
// 与 wire::decode 同源，也便于两端对拍。
//
// 用 `\r\n` 分行有个安全上的好处：HTTP 头值**本就禁止**含 CR/LF，
// 因此不会出现「值里带 \n 把一条头劈成两条」的注入类 bug。单测里固化这一点。

/// 编码为 `Name: value\r\n` 行格式。
pub fn encode_header_lines(headers: &[(String, String)]) -> Vec<u8> {
    let mut out = String::new();
    for (k, v) in headers {
        out.push_str(k);
        out.push_str(": ");
        out.push_str(v);
        out.push_str("\r\n");
    }
    out.into_bytes()
}

/// 解析 `Name: value\r\n` 行格式。
///
/// 与 [`decode`] 的 `hdr=` 行同一约定：按**首个** ':' 切分，
/// **保留顺序与重复键**，没有 ':' 的行按「空值」保留（不静默丢）。
pub fn decode_header_lines(bytes: &[u8]) -> Vec<(String, String)> {
    let text = String::from_utf8_lossy(bytes);
    text.split("\r\n")
        .filter(|l| !l.is_empty())
        .map(|line| match line.find(':') {
            Some(idx) => (line[..idx].trim().to_string(), line[idx + 1..].trim().to_string()),
            None => (line.trim().to_string(), String::new()),
        })
        .collect()
}

// ─────────────────────────────────────────
// 证书固定（SPKI pin）的配置通道
// ─────────────────────────────────────────
//
// JNI 侧把 pin 传进来用行格式：`<host|*>\t<64 位十六进制>\r\n`（host 为 `*` = 全局）。
//
// ⚠️ 这条通道是**安全红线**（设计 §6）：没有它，自研传输相对 OkHttp 就是
// 「证书固定被静默降级」。所以解析必须**严格**：
//   · 十六进制长度不为 64 → 丢弃该行（并在解析结果里报告），**绝不**截断/补零；
//   · 非十六进制字符 → 丢弃该行；
//   · 无法识别分隔符 → 丢弃该行。
// 宁可「某条 pin 没生效」也不要「用错指纹还当成功」——后者是静默降级。
//
// 返回 `(pins, rejected)`：`rejected` 是**被拒的原始行**，供 JNI/Kotlin 侧打日志。
// 为什么不直接 panic/报错：一条格式错的 pin 不应让整个请求失败，但**必须可见**。

/// 解析 pin 行格式。返回 `(有效 pins, 被拒的原始行)`。
///
/// 每项 pins 为 `(Option<host>, [u8; 32])`：host 为 `None` 表示全局生效。
pub fn parse_pin_lines(bytes: &[u8]) -> (Vec<(Option<String>, [u8; 32])>, Vec<String>) {
    let text = String::from_utf8_lossy(bytes);
    let mut pins = Vec::new();
    let mut rejected = Vec::new();
    for raw in text.split("\r\n").filter(|l| !l.is_empty()) {
        match parse_one_pin(raw) {
            Some(p) => pins.push(p),
            None => rejected.push(raw.to_string()),
        }
    }
    (pins, rejected)
}

fn parse_one_pin(line: &str) -> Option<(Option<String>, [u8; 32])> {
    // 分隔符用制表符：host 里不会有制表符，且比 ':' 更不易与 IPv6/端口混淆。
    let (host_part, hex_part) = line.split_once('\t')?;
    let host = host_part.trim();
    let hex = hex_part.trim();
    // 严格要求 64 个十六进制字符 = 32 字节。长度不对直接拒，绝不补零/截断。
    if hex.len() != 64 {
        return None;
    }
    let mut out = [0u8; 32];
    for (i, byte) in out.iter_mut().enumerate() {
        let hi = hex.as_bytes()[i * 2] as char;
        let lo = hex.as_bytes()[i * 2 + 1] as char;
        // 大小写都接受（Kotlin 侧可能给大写）；非法字符 → 整行拒。
        *byte = (hi.to_digit(16)? as u8) << 4 | (lo.to_digit(16)? as u8);
    }
    let host = if host.is_empty() || host == "*" {
        None
    } else {
        Some(host.to_ascii_lowercase())
    };
    Some((host, out))
}

/// 与 [`parse_pin_lines`] 对称的编码（测试与 Golden 生成用）。
pub fn encode_pin_lines(pins: &[(Option<String>, [u8; 32])]) -> Vec<u8> {
    let mut out = String::new();
    for (host, pin) in pins {
        out.push_str(host.as_deref().unwrap_or("*"));
        out.push('\t');
        for b in pin {
            out.push_str(&format!("{b:02x}"));
        }
        out.push_str("\r\n");
    }
    out.into_bytes()
}

#[cfg(test)]
mod pin_tests {
    use super::*;

    fn hex_of(b: u8) -> String {
        std::iter::repeat(format!("{b:02x}")).take(32).collect()
    }

    #[test]
    fn parses_host_scoped_and_global_pins() {
        let raw = format!("api.example.com\t{}\r\n*\t{}\r\n", hex_of(1), hex_of(2));
        let (pins, rejected) = parse_pin_lines(raw.as_bytes());
        assert!(rejected.is_empty());
        assert_eq!(pins.len(), 2);
        assert_eq!(pins[0].0.as_deref(), Some("api.example.com"));
        assert_eq!(pins[0].1, [1u8; 32]);
        assert_eq!(pins[1].0, None, "`*` 表示全局");
        assert_eq!(pins[1].1, [2u8; 32]);
    }

    #[test]
    fn roundtrip_is_stable() {
        let pins = vec![
            (Some("a.example.com".to_string()), [7u8; 32]),
            (None, [9u8; 32]),
        ];
        let (decoded, rejected) = parse_pin_lines(&encode_pin_lines(&pins));
        assert!(rejected.is_empty());
        assert_eq!(decoded, pins);
    }

    #[test]
    fn rejects_wrong_length_instead_of_padding() {
        // 安全红线：长度不对必须**拒**，绝不能补零/截断成 32 字节。
        for bad in ["", "abcd", &hex_of(0)[..62], &format!("{}ff", hex_of(0))] {
            let raw = format!("h\t{bad}\r\n");
            let (pins, rejected) = parse_pin_lines(raw.as_bytes());
            assert!(pins.is_empty(), "长度非 64 必须被拒：{bad:?}");
            assert_eq!(rejected.len(), 1, "被拒的行必须可见：{bad:?}");
        }
    }

    #[test]
    fn rejects_non_hex_instead_of_silently_zeroing() {
        let mut s: Vec<char> = hex_of(0).chars().collect();
        s[5] = 'z'; // 非十六进制
        let raw = format!("h\t{}\r\n", s.into_iter().collect::<String>());
        let (pins, rejected) = parse_pin_lines(raw.as_bytes());
        assert!(pins.is_empty(), "含非十六进制字符必须整行拒，不得静默当 0");
        assert_eq!(rejected.len(), 1);
    }

    #[test]
    fn accepts_uppercase_hex() {
        let raw = format!("h\t{}\r\n", hex_of(0xab).to_uppercase());
        let (pins, rejected) = parse_pin_lines(raw.as_bytes());
        assert!(rejected.is_empty());
        assert_eq!(pins[0].1, [0xab; 32]);
    }

    #[test]
    fn rejects_line_without_separator() {
        let (pins, rejected) = parse_pin_lines(format!("{}\r\n", hex_of(1)).as_bytes());
        assert!(pins.is_empty());
        assert_eq!(rejected.len(), 1, "无法识别的行必须报告，不得静默丢");
    }

    #[test]
    fn empty_input_yields_no_pins() {
        let (pins, rejected) = parse_pin_lines(b"");
        assert!(pins.is_empty() && rejected.is_empty());
    }

    #[test]
    fn host_is_lowercased_for_matching() {
        let raw = format!("API.Example.COM\t{}\r\n", hex_of(3));
        let (pins, _) = parse_pin_lines(raw.as_bytes());
        assert_eq!(pins[0].0.as_deref(), Some("api.example.com"), "host 需归一化后才能与 URL 匹配");
    }
}

#[cfg(test)]
mod line_tests {
    use super::*;
    #[test]
    fn header_lines_roundtrip_preserves_order() {
        let hs = vec![
            ("Accept".to_string(), "application/json".to_string()),
            ("X-Trace".to_string(), "abc".to_string()),
        ];
        assert_eq!(decode_header_lines(&encode_header_lines(&hs)), hs);
    }

    #[test]
    fn header_lines_value_may_contain_colon() {
        let hs = vec![("Referer".to_string(), "https://a.b/c:8443/x".to_string())];
        assert_eq!(decode_header_lines(&encode_header_lines(&hs)), hs);
    }

    #[test]
    fn header_lines_do_not_merge_duplicates() {
        let hs = vec![
            ("Cookie".to_string(), "a=1".to_string()),
            ("Cookie".to_string(), "b=2".to_string()),
        ];
        assert_eq!(decode_header_lines(&encode_header_lines(&hs)), hs);
    }

    #[test]
    fn header_lines_split_on_crlf_not_bare_lf() {
        // 值里若含裸 \n（调用方不应这么传），不得把一条头劈成两条 ——
        // 这与「按 \n 分行」的实现有本质区别，是头部注入的防线。
        let raw = b"A: x\ny\r\nB: z\r\n";
        let parsed = decode_header_lines(raw);
        assert_eq!(
            parsed,
            vec![
                ("A".to_string(), "x\ny".to_string()),
                ("B".to_string(), "z".to_string()),
            ],
            "裸 LF 必须留在值内，不得成为分行依据"
        );
    }

    #[test]
    fn header_lines_handle_empty_and_malformed() {
        assert!(decode_header_lines(b"").is_empty());
        // 没有 ':' 的行按空值保留，不静默丢弃
        assert_eq!(decode_header_lines(b"garbage\r\n"), vec![("garbage".to_string(), String::new())]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample() -> WireResponse {
        WireResponse {
            status: 200,
            proto: "HTTP_3".to_string(),
            reused_connection: true,
            connect_ms: Some(42),
            first_byte_ms: Some(90),
            total_ms: 120,
            body_bytes: 5,
            spki_sha256: Some(
                "d8a2b48e16bb321bd8bf2e98ccdb3b38ab12b147acda75c1efa559d0fb19b663".to_string(),
            ),
            headers: vec![
                ("content-type".to_string(), "application/json".to_string()),
                ("x-trace".to_string(), "2026-10-05T12:00:00Z".to_string()),
            ],
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
        // header 值含 ':' 是常态（时间戳、URL）。若按所有 ':' 切分会截断值。
        let encoded = encode(&sample(), b"hello").unwrap();
        let (resp, _) = decode(&encoded).unwrap();
        assert_eq!(
            resp.headers
                .iter()
                .find(|(k, _)| k == "x-trace")
                .map(|(_, v)| v.as_str()),
            Some("2026-10-05T12:00:00Z"),
            "值中的 ':' 不得被当作分隔符"
        );
    }

    #[test]
    fn duplicate_headers_are_preserved_in_order() {
        // ── 这条测试守着「静默丢 cookie」这个 bug ──
        // 一次响应发多个 Set-Cookie 是常态；用 map 存会覆盖掉除最后一条外的所有 cookie。
        let mut r = sample();
        r.headers = vec![
            ("set-cookie".into(), "a=1; Path=/".into()),
            ("set-cookie".into(), "b=2; Path=/".into()),
            ("set-cookie".into(), "c=3; Path=/".into()),
        ];
        let encoded = encode(&r, b"hello").unwrap();
        let (resp, _) = decode(&encoded).unwrap();
        let cookies: Vec<&str> = resp
            .headers
            .iter()
            .filter(|(k, _)| k == "set-cookie")
            .map(|(_, v)| v.as_str())
            .collect();
        assert_eq!(cookies, vec!["a=1; Path=/", "b=2; Path=/", "c=3; Path=/"], "重复头必须全部保留且有序");
    }

    #[test]
    fn optional_timing_fields_can_be_absent() {
        let mut r = sample();
        r.connect_ms = None;
        r.first_byte_ms = None;
        r.spki_sha256 = None;
        let encoded = encode(&r, b"hello").unwrap();
        let (resp, _) = decode(&encoded).unwrap();
        assert_eq!(resp.connect_ms, None);
        assert_eq!(resp.first_byte_ms, None);
        assert_eq!(resp.spki_sha256, None);
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
        encoded.truncate(encoded.len() - 1); // 裁掉一个 body 字节
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
