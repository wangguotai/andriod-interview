//! HTTP/3 (QUIC) 客户端内核。
//!
//! ─── 线程治理（本模块最重要的设计约束）───
//!
//! 公开 API [`fetch`] 是**阻塞式**的：内部创建 **current-thread** runtime 并用
//! `block_on` 驱动，runtime **不额外起任何线程**，IO 由调用方线程完成。
//!
//! 为什么这点至关重要：用 multi-thread runtime 会起一组 worker 线程，
//! 它们绕过本仓库 `ThreadPools` 的命名 / 配额 / 背压治理，也拦不到 `thread-lint`
//! 的三条规则 —— 那正是 `rust/README.md` 明确禁止的情形。
//! 用 current-thread + block_on，网络任务**落在调用方的 net 泳道线程上**，
//! 与图像流水线「native 计算由泳道调度」的纪律一致。
//! （Cargo.toml 里刻意**不开** `rt-multi-thread` feature，从依赖层面兜住这一点。）
//!
//! 代价：单次调用的并发度受泳道线程数约束（net 泳道 core=4）。这是**有意的**——
//! 泳道的注释已论证「RTT 受限下 n=1→4 有线性收益」，且并发上限本来就该由泳道决定。
//!
//! ─── 证书固定（安全红线，见设计文档 §6）───
//!
//! rustls 默认用 Mozilla 根集合校验链，但**证书固定（pinning）必须显式实现**：
//! 只做链校验而漏掉 pin，等于相对 OkHttp 的 `certificatePinner` 发生了**静默降级**，
//! 且不会报错。所以 [`TlsConfig::pins`] 非空时，本模块用自定义 `ServerCertVerifier`
//! 在链校验**之后**再校验 SPKI SHA-256。
//!
//! 未实现（如实标注）：Android 系统信任库 / 企业自签 CA 的注入 —— 第一版用
//! Mozilla 根集合，覆盖公网服务，覆盖不到企业 CA。要支持需经 JNI 传入 CA，
//! 属后续里程碑（见 NETLAB 文档的缺口表）。

use std::net::{SocketAddr, ToSocketAddrs};
use std::sync::Arc;
use std::time::{Duration, Instant};

use bytes::Buf;
use http::Request as HttpRequest;

use crate::request::{self, RejectReason};

/// 一次 HTTP/3 请求的输入。
#[derive(Debug, Clone)]
pub struct FetchRequest {
    /// 必须 https（见 [`crate::request::validate`]）
    pub url: String,
    pub method: String,
    /// 是否要求走代理。第一版不支持 → 直接拒绝（不静默直连）。
    pub has_proxy: bool,
    /// 整体超时预算（毫秒）
    pub timeout_ms: u64,
    /// 用户自定义请求头。`host` / `connection` 等由协议层接管，传入会被忽略。
    pub headers: Vec<(String, String)>,
    /// 请求体。第一版只做「已完整读入内存」的小 body（见设计文档 §7）。
    pub body: Option<Vec<u8>>,
}

impl Default for FetchRequest {
    fn default() -> Self {
        Self {
            url: String::new(),
            method: "GET".into(),
            has_proxy: false,
            timeout_ms: 15_000,
            headers: Vec::new(),
            body: None,
        }
    }
}

/// 一次 HTTP/3 请求的结果。字段与 [`crate::wire`] 和 Kotlin `NetMetrics.Stage` 对齐。
#[derive(Debug, Clone)]
pub struct FetchResponse {
    pub status: u16,
    /// HTTP/3 里拿到的是 `(name, value)` 列表：**顺序保留且允许重复键**
    /// （HTTP/2/3 允许重复头，这是与 HTTP/1.1 map 语义的真实差异）
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
    /// QUIC 建连（含 TLS，融合）耗时
    pub connect_ms: u64,
    /// 首个响应头到达耗时
    pub first_byte_ms: u64,
    /// 总耗时（含 runtime 启动与跨 FFI 前的准备）
    pub total_ms: u64,
    /// 是否复用了已有 QUIC connection
    pub reused_connection: bool,
    /// 服务端证书链（DER）。用于回填 OkHttp 的 `Handshake` —— 否则合成 Response
    /// 的 `handshake` 只能为 null，等于相对 OkHttp 路径发生了**信息降级**。
    ///
    /// 注意：这**不是**为了给 Kotlin 侧用。固定校验已在 rustls 握手内完成，
    /// 这里回传仅用于「让两条路径的观测面尽量对齐」，不参与任何安全判定。
    pub peer_certificates: Vec<Vec<u8>>,
}

/// 传输错误。区分「被取消」与「真失败」，因为两者对上层语义不同。
#[derive(Debug)]
pub enum FetchError {
    Rejected(RejectReason),
    Dns(String),
    /// QUIC/TLS 建连失败（含证书校验失败）
    Connect(String),
    /// 传输中被取消（协作式）
    Cancelled,
    Timeout,
    Transport(String),
}

impl FetchError {
    /// 该失败是否由**证书固定（pinning）不匹配**引起。
    ///
    /// 为什么值得单独判：pin 不匹配是**安全事件**（可能正在被中间人），
    /// 而上层若把它和其它连接失败一视同仁地「回退 OkHttp 重试」，
    /// 就会把一次攻击告警当成一次网络抖动。诊断码要能让上层分辨这一点。
    ///
    /// 注意：链校验失败（过期/域名不符/签发链不可信）**不算** pin 不匹配 ——
    /// 那是另一种失败，混为一谈会让日志误导排查方向。
    pub fn pin_mismatch(&self) -> bool {
        matches!(self, FetchError::Connect(m) if m.contains(PIN_MISMATCH_MARKER))
    }
}

/// 内嵌在错误信息里的标记，供 [`FetchError::pin_mismatch`] 识别。
/// 用常量而非字符串字面量散落各处，避免「改了这边忘了那边」。
const PIN_MISMATCH_MARKER: &str = "SPKI-PIN-MISMATCH";

impl std::fmt::Display for FetchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FetchError::Rejected(r) => write!(f, "rejected: {r:?}"),
            FetchError::Dns(m) => write!(f, "dns: {m}"),
            FetchError::Connect(m) => write!(f, "connect: {m}"),
            FetchError::Cancelled => write!(f, "cancelled"),
            FetchError::Timeout => write!(f, "timeout"),
            FetchError::Transport(m) => write!(f, "transport: {m}"),
        }
    }
}

impl std::error::Error for FetchError {}

/// 稳定的错误码，供 JNI 回传给 Kotlin（值语义，不涉及字符串生命周期）。
impl FetchError {
    pub const fn code(&self) -> i32 {
        match self {
            FetchError::Rejected(r) => r.code(),
            FetchError::Dns(_) => 10,
            FetchError::Connect(_) => 11,
            FetchError::Cancelled => 12,
            FetchError::Timeout => 13,
            FetchError::Transport(_) => 14,
        }
    }

    pub fn message(&self) -> String {
        self.to_string()
    }
}

/// TLS / 信任配置。
#[derive(Clone, Default)]
pub struct TlsConfig {
    /// SPKI SHA-256 固定。**空 = 不做 pin（仍做完整链校验）**。
    ///
    /// 用 SPKI（SubjectPublicKeyInfo）而非整证书哈希，是 OkHttp `CertificatePinner`
    /// 与 HPKP 的通行做法：叶子证书会轮换，而**公钥在续期时常常复用**，
    /// 用 SPKI 可在证书轮换后不失效 —— 直接关系到「pin 上了会不会把自己锁死」。
    ///
    /// 每项为 32 字节 SHA-256；host 为 `None` 时对所有主机生效。
    pub pins: Vec<(Option<String>, [u8; 32])>,
}

impl std::fmt::Debug for TlsConfig {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("TlsConfig")
            .field("pins", &format_args!("{} pin(s)", self.pins.len()))
            .finish()
    }
}

impl TlsConfig {
    /// 返回适用于该 host 的 pin 集合（host 精确匹配的 + 全局的）。
    pub fn pins_for(&self, host: &str) -> Vec<[u8; 32]> {
        self.pins
            .iter()
            .filter(|(h, _)| h.is_none() || h.as_deref() == Some(host))
            .map(|(_, p)| *p)
            .collect()
    }
}

// ─────────────────────────────────────────
// 阻塞式入口（线程治理友好）
// ─────────────────────────────────────────

/// 阻塞式 HTTP/3 请求。
///
/// **由调用方线程驱动**：内部 current-thread runtime + `block_on`，
/// 不产生额外的 runtime 线程（见模块头注释）。
///
/// `cancelled` 表示调用方是否已请求取消（来自 JNI 侧的 `CancelToken`）。
///
/// 取消机制如实说明（见设计文档 §4.3）：
///   · 由 `block_on` 内的 `select!` **每 50ms 轮询**该闭包，命中即丢弃整个
///     fetch future，从而在 ≤50ms 内中止传输（含正在进行的握手/流读取）；
///   · 返回前再查一次，覆盖竞态窗口；
///   · 但它**不是硬中断** —— 不能打断底层 socket syscall 本身，只是不再等待它。
///     极端情况（线程卡在不可中断的 syscall）下取消仍可能延迟。此缺口如实保留。
pub fn fetch<F>(
    req: FetchRequest,
    tls: &TlsConfig,
    cancelled: F,
) -> Result<FetchResponse, FetchError>
where
    F: Fn() -> bool + Send + Sync + 'static,
{
    let started = Instant::now();

    // ── 安全闸门 ──
    // 与 JNI 侧同一套校验（明文拒绝、方法白名单、代理明确拒绝）。放在最前面，
    // 保证非法请求**在任何网络动作之前**就被拒。
    request::validate(&req.url, &req.method, req.has_proxy).map_err(FetchError::Rejected)?;

    let parsed = ParsedUrl::parse(&req.url).map_err(|_| FetchError::Rejected(RejectReason::BadUrl))?;

    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| FetchError::Transport(format!("runtime build: {e}")))?;

    let outcome = rt.block_on(async {
        let deadline = tokio::time::Instant::now() + Duration::from_millis(req.timeout_ms);
        // 取消探针：每 50ms 查一次取消标志。
        //
        // 为什么需要它（诚实说明它的能力边界）：协作式取消只能**等待**传输自己
        // 在阶段边界让步，而正在进行中的单次 socket 读不会主动让步 —— 没有这个
        // 探针，「取消」就要等整个超时预算耗尽才生效。有了它，取消的生效延迟
        // 收敛到 ≤50ms：select! 命中后**丢弃整个 fetch future**，从而中止
        // 正在进行的 QUIC 握手/流读取。
        // **它仍不是硬中断**：不能打断底层 socket syscall 本身，只是不再等待它。
        // ⚠️ 不能用 `tokio::time::interval(...)` 或 `interval_at(...)` 直接当取消分支：
        // interval 的 tick 会**按周期无条件完成**，于是每 50ms 取消分支就命中一次 ——
        // 无论 cancelled 是否为真。表现是「所有请求都在 50ms 后被判为 cancelled」。
        // （真实踩到：examples/h3_fetch 反复报 cancelled。）
        // 正确做法是等一个**只在标志真的置位时**才完成的 future。
        let work = fetch_async(&parsed, &req, tls, started);
        tokio::select! {
            r = work => r,
            _ = tokio::time::sleep_until(deadline) => Err(FetchError::Timeout),
            _ = wait_cancelled(&cancelled) => Err(FetchError::Cancelled),
        }
    });

    // 返回后再查一次：覆盖「传输刚好在取消置位之后完成」的竞态窗口，
    // 避免上层用「一个已放弃的请求」的结果去更新 UI。
    if cancelled() {
        return Err(FetchError::Cancelled);
    }
    outcome
}

/// 轮询取消标志的专用 future：**只在标志真的置位时**才完成。
///
/// 这是协作式取消的关键实现细节：它必须「条件完成」，而不是「周期完成」。
/// 每 50ms 查一次标志，查到时返回；这决定了取消的**生效延迟上限 ≈ 50ms**。
///
/// 能力边界（如实标注）：它让 `select!` 放弃等待传输，但**不是硬中断** ——
/// 不能打断底层 socket syscall 本身。极端情况下取消仍可能延迟到一次 IO 返回。
async fn wait_cancelled<F>(cancelled: &F)
where
    F: Fn() -> bool + Send + Sync + 'static,
{
    loop {
        if cancelled() {
            return;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}

async fn fetch_async(
    parsed: &ParsedUrl,
    req: &FetchRequest,
    tls: &TlsConfig,
    started: Instant,
) -> Result<FetchResponse, FetchError> {
    let addr: SocketAddr = format!("{}:{}", parsed.host, parsed.port)
        .to_socket_addrs()
        .map_err(|e| FetchError::Dns(e.to_string()))?
        .next()
        .ok_or_else(|| FetchError::Dns("无解析结果".into()))?;

    // ── QUIC + TLS 建连 ──
    let mut endpoint = quinn::Endpoint::client("0.0.0.0:0".parse().unwrap())
        .map_err(|e| FetchError::Connect(format!("endpoint: {e}")))?;
    let client_cfg = build_client_config(parsed, tls)?;
    endpoint.set_default_client_config(client_cfg);

    let connect_started = Instant::now();
    let conn = endpoint
        .connect(addr, &parsed.host)
        .map_err(|e| FetchError::Connect(format!("connect: {e}")))?
        .await
        .map_err(handshake_error)?;
    let connect_ms = connect_started.elapsed().as_millis() as u64;

    // ── 取回证书链 ──
    // 两个用途：① 回填合成 Response 的 handshake，避免信息降级；
    // ② 做**直接、精确的 pin 判定**（见下）。
    let peer_certificates = peer_certificates(&conn);

    // ── pin 的精确判定（在握手之外再做一次）──
    //
    // 为什么握手内已经校验过还要再做一次：握手失败时 quinn 只给出字符串化的
    // 错误，pin 不匹配与「证书过期/域名不符」会被压成同一个传输错误，
    // 上层无从分辨「可能被中间人」与「证书该轮换了」。
    //
    // 因此：握手**成功**后，这里对链中的叶证书再算一次 SPKI。若配了 pin 却对不上，
    // 直接判为 pin 失败并给出明确标记 —— 这只有在「握手内的 verifier 被绕过/替换」时
    // 才可能发生，属于必须显式告警的安全事件。
    // 握手**失败**时走下面的 `handshake_error`，那里会识别 verifier 报出的 pin 错误串。
    let pins = tls.pins_for(&parsed.host);
    if !pins.is_empty() {
        let matched = peer_certificates
            .first()
            .and_then(|der| extract_spki_sha256(der))
            .is_some_and(|spki| pins.contains(&spki));
        if !matched {
            return Err(FetchError::Connect(format!(
                "{PIN_MISMATCH_MARKER}: 叶证书 SPKI 未命中固定指纹"
            )));
        }
    }

    // ── HTTP/3 请求 ──
    let (mut connection, mut send_request) = h3::client::new(h3_quinn::Connection::new(conn))
        .await
        .map_err(|e| FetchError::Transport(format!("h3 new: {e}")))?;

    // 驱动 Connection：必须持续 poll_close，否则控制流 / QPACK 不推进。
    // 用 tokio::spawn 在**同一 current-thread runtime** 上跑 —— 不额外起线程。
    let driver = tokio::spawn(async move {
        futures_util::future::poll_fn(|cx| connection.poll_close(cx)).await;
    });

    let result = send_h3_request(&mut send_request, req).await;
    driver.abort();

    let (status, headers, body) = result?;
    let first_byte_ms = started.elapsed().as_millis() as u64;
    Ok(FetchResponse {
        status,
        headers,
        body,
        connect_ms,
        first_byte_ms,
        total_ms: started.elapsed().as_millis() as u64,
        // 第一版每次调用新建 endpoint/connection，故恒为 false。
        // 复用需在 Kotlin 侧持有连接池 —— 明确留作后续（见文档缺口表）。
        reused_connection: false,
        peer_certificates,
    })
}

/// 把 quinn 的握手错误翻译成 [`FetchError`]，并**单独识别 pin 不匹配**。
///
/// quinn 把校验失败压成字符串，这里按标记关键字判定：命中则带上
/// [`PIN_MISMATCH_MARKER`]，让上层能把它当作安全事件（而不是网络抖动）处理。
fn handshake_error(e: quinn::ConnectionError) -> FetchError {
    let text = e.to_string();
    if text.contains("SPKI") || text.contains("固定") || text.contains(PIN_MISMATCH_MARKER) {
        FetchError::Connect(format!("{PIN_MISMATCH_MARKER}: {text}"))
    } else {
        FetchError::Connect(format!("handshake: {text}"))
    }
}

async fn send_h3_request<O>(
    send_request: &mut h3::client::SendRequest<O, bytes::Bytes>,
    req: &FetchRequest,
) -> Result<(u16, Vec<(String, String)>, Vec<u8>), FetchError>
where
    O: h3::quic::OpenStreams<bytes::Bytes>,
{
    let mut builder = HttpRequest::builder().method(req.method.as_str()).uri(&req.url);
    for (k, v) in &req.headers {
        // 这些头由 h3/QUIC 层接管，用户值会被忽略或冲突，显式跳过。
        let lk = k.to_ascii_lowercase();
        if matches!(lk.as_str(), "host" | "connection" | "transfer-encoding" | "content-length") {
            continue;
        }
        builder = builder.header(k.as_str(), v.as_str());
    }
    let request = builder
        .body(())
        .map_err(|e| FetchError::Transport(format!("build request: {e}")))?;

    let mut stream = send_request
        .send_request(request)
        .await
        .map_err(|e| FetchError::Transport(format!("send_request: {e}")))?;

    if let Some(body) = &req.body {
        stream
            .send_data(bytes::Bytes::copy_from_slice(body))
            .await
            .map_err(|e| FetchError::Transport(format!("send_data: {e}")))?;
    }
    // 无论有无 body，都必须结束发送流，否则服务端不会开始响应。
    stream
        .finish()
        .await
        .map_err(|e| FetchError::Transport(format!("finish: {e}")))?;

    let response = stream
        .recv_response()
        .await
        .map_err(|e| FetchError::Transport(format!("recv_response: {e}")))?;
    let status = response.status().as_u16();
    let headers = response
        .headers()
        .iter()
        .map(|(k, v)| {
            (k.as_str().to_string(), String::from_utf8_lossy(v.as_bytes()).into_owned())
        })
        .collect();

    let mut body = Vec::new();
    while let Some(mut chunk) = stream
        .recv_data()
        .await
        .map_err(|e| FetchError::Transport(format!("recv_data: {e}")))?
    {
        let n = chunk.remaining();
        body.extend_from_slice(&chunk.copy_to_bytes(n));
    }

    Ok((status, headers, body))
}

// ─────────────────────────────────────────
// TLS：链校验 + 可选的 SPKI 固定
// ─────────────────────────────────────────

fn build_client_config(
    parsed: &ParsedUrl,
    tls: &TlsConfig,
) -> Result<quinn::ClientConfig, FetchError> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());

    #[cfg(feature = "webpki-roots")]
    let roots = {
        let mut r = rustls::RootCertStore::empty();
        r.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
        r
    };

    // 没有根证书就无法完成链校验。**明确报错，绝不静默放行** ——
    // 一个「不校验证书」的客户端在网络层是最危险的东西。
    #[cfg(not(feature = "webpki-roots"))]
    {
        let _ = &provider;
        return Err(FetchError::Connect(
            "未编译 webpki-roots，且尚未接入 Android 系统信任库；拒绝在不校验证书的情况下建连".into(),
        ));
    }

    let pins = tls.pins_for(&parsed.host);

    // 注意 builder 的状态机：`dangerous()` 只存在于 WantsVerifier 阶段，
    // 必须在 with_root_certificates / with_no_client_auth **之前**调用。
    // （踩过：写成 `...with_root_certificates(roots).dangerous()` 会报「方法找不到」。）
    let builder = rustls::ClientConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()
        .map_err(|e| FetchError::Connect(format!("tls versions: {e}")))?;

    let mut cfg = if pins.is_empty() {
        // 无 pin：标准链校验。**安全默认** —— 不做 pin ≠ 不校验。
        builder.with_root_certificates(roots.clone()).with_no_client_auth()
    } else {
        // 有 pin：用自定义 verifier 在链校验之后再校验 SPKI。
        // 该 verifier 内部自带 roots 做链校验，因此这里不再调 with_root_certificates。
        builder
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(SpkiPinningVerifier::new(
                pins,
                roots.clone(),
                provider,
            )))
            .with_no_client_auth()
    };

    // HTTP/3 的 ALPN 必须是 h3，否则服务端不会走 QUIC。
    cfg.alpn_protocols = vec![b"h3".to_vec()];

    let quic_crypto = quinn::crypto::rustls::QuicClientConfig::try_from(cfg)
        .map_err(|e| FetchError::Connect(format!("quic crypto: {e}")))?;
    Ok(quinn::ClientConfig::new(Arc::new(quic_crypto)))
}

/// 在链校验之后追加 SPKI SHA-256 固定校验的 verifier。
///
/// ─── 为什么必须「链校验 + pin」而不是「只看 pin」───
/// 只比对公钥指纹而跳过链校验，会让**任何**持有该公钥的证书通过 ——
/// 包括用同一公钥签出、但域名不匹配的证书。那不是 pin，是漏洞。
/// 本实现**先**用 WebPKI 完成完整链校验（含主机名、有效期、签发链），**再**比对 pin。
struct SpkiPinningVerifier {
    pins: Vec<[u8; 32]>,
    inner: Arc<rustls::client::WebPkiServerVerifier>,
}

impl SpkiPinningVerifier {
    fn new(
        pins: Vec<[u8; 32]>,
        roots: rustls::RootCertStore,
        provider: Arc<rustls::crypto::CryptoProvider>,
    ) -> Self {
        let inner = rustls::client::WebPkiServerVerifier::builder_with_provider(
            Arc::new(roots),
            provider,
        )
        .build()
        .expect("构建 WebPKI verifier 失败（roots 非空，不应失败）");
        Self { pins, inner }
    }
}

impl std::fmt::Debug for SpkiPinningVerifier {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SpkiPinningVerifier")
            .field("pins", &self.pins.len())
            .finish()
    }
}

impl rustls::client::danger::ServerCertVerifier for SpkiPinningVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &rustls::pki_types::CertificateDer<'_>,
        intermediates: &[rustls::pki_types::CertificateDer<'_>],
        server_name: &rustls::pki_types::ServerName<'_>,
        ocsp_response: &[u8],
        now: rustls::pki_types::UnixTime,
    ) -> Result<rustls::client::danger::ServerCertVerified, rustls::Error> {
        // 第一步：标准链校验（含主机名、有效期、签发链）。**不可跳过。**
        let verified = self
            .inner
            .verify_server_cert(end_entity, intermediates, server_name, ocsp_response, now)?;

        // 第二步：SPKI 固定校验。
        let spki = extract_spki_sha256(end_entity.as_ref())
            .ok_or_else(|| rustls::Error::General("无法解析证书 SPKI".into()))?;
        if self.pins.iter().any(|p| p == &spki) {
            Ok(verified)
        } else {
            Err(rustls::Error::General(
                "证书 SPKI 未命中任何固定指纹（pinning 失败）".into(),
            ))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &rustls::pki_types::CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        self.inner.verify_tls12_signature(message, cert, dss)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &rustls::pki_types::CertificateDer<'_>,
        dss: &rustls::DigitallySignedStruct,
    ) -> Result<rustls::client::danger::HandshakeSignatureValid, rustls::Error> {
        self.inner.verify_tls13_signature(message, cert, dss)
    }

    fn supported_verify_schemes(&self) -> Vec<rustls::SignatureScheme> {
        self.inner.supported_verify_schemes()
    }
}

/// 从 DER 证书里提取 SPKI 并算 SHA-256。
///
/// 复用 rustls 依赖树里已有的 `rustls-webpki` 做 DER 解析，**不自己写 ASN.1**——
/// 手写 DER 解析是安全代码里最容易出错的部分，没有必要重造。
fn extract_spki_sha256(der: &[u8]) -> Option<[u8; 32]> {
    let cert_der = rustls::pki_types::CertificateDer::from(der.to_vec());
    let cert = webpki::EndEntityCert::try_from(&cert_der).ok()?;
    let spki = cert.subject_public_key_info();
    Some(sha256(spki.as_ref()))
}

/// 公开给 JNI 侧：给定叶子证书 DER，返回 SPKI SHA-256 的十六进制串。
///
/// 用途是**给设备上算 pin 用的**（对着真实服务器跑一次、把指纹填进配置），
/// 而不是给运行时判定用（判定在 [`fetch`] 内完成）。与 OkHttp
/// `CertificatePinner` 的 `sha256/...` 是对同一块数据的两种表述。
pub fn spki_sha256_hex(cert_der: &[u8]) -> Option<String> {
    extract_spki_sha256(cert_der).map(|h| h.iter().map(|b| format!("{b:02x}")).collect())
}

/// 从 QUIC 连接取回服务端证书链（DER）。
///
/// 第一版**不做链校验**：链校验在 TLS 握手内由 rustls/WebPKI 完成。
/// 这里只取叶证书用于 pin 复核与 handshake 回填。
fn peer_certificates(conn: &quinn::Connection) -> Vec<Vec<u8>> {
    conn.peer_identity()
        .and_then(|any| any.downcast::<Vec<rustls::pki_types::CertificateDer<'static>>>().ok())
        .map(|certs| certs.iter().map(|c| c.as_ref().to_vec()).collect())
        .unwrap_or_default()
}

/// SHA-256（用 ring，避免再引一个哈希库）。
fn sha256(data: &[u8]) -> [u8; 32] {
    let digest = ring::digest::digest(&ring::digest::SHA256, data);
    let mut out = [0u8; 32];
    out.copy_from_slice(digest.as_ref());
    out
}

/// 解析出的 URL 片段。第一版只支持 https、不支持 userinfo（够用且减少攻击面）。
struct ParsedUrl {
    host: String,
    port: u16,
}

impl ParsedUrl {
    fn parse(url: &str) -> Result<Self, ()> {
        let rest = url.strip_prefix("https://").ok_or(())?;
        let authority = rest.split(['/', '?', '#']).next().ok_or(())?;
        if authority.is_empty() || authority.contains('@') {
            return Err(()); // 不支持 userinfo（避免凭据出现在 URL 里）
        }
        let (host, port) = match authority.rsplit_once(':') {
            Some((h, p)) => (h.to_string(), p.parse::<u16>().map_err(|_| ())?),
            None => (authority.to_string(), 443),
        };
        if host.is_empty() {
            return Err(());
        }
        Ok(Self { host, port })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parsed_url_extracts_host_and_default_port() {
        let p = ParsedUrl::parse("https://example.com/a/b?c=1#d").unwrap();
        assert_eq!(p.host, "example.com");
        assert_eq!(p.port, 443);
    }

    #[test]
    fn parsed_url_honours_explicit_port() {
        assert_eq!(ParsedUrl::parse("https://example.com:8443/x").unwrap().port, 8443);
    }

    #[test]
    fn parsed_url_rejects_userinfo_and_cleartext() {
        assert!(ParsedUrl::parse("https://user:pw@example.com/").is_err());
        assert!(ParsedUrl::parse("http://example.com/").is_err());
        assert!(ParsedUrl::parse("not a url").is_err());
    }

    #[test]
    fn pins_are_scoped_by_host() {
        let global = [1u8; 32];
        let host = [2u8; 32];
        let cfg = TlsConfig { pins: vec![(None, global), (Some("api.example.com".into()), host)] };
        assert_eq!(cfg.pins_for("api.example.com"), vec![global, host]);
        assert_eq!(cfg.pins_for("other.example.com"), vec![global]);
    }

    #[test]
    fn fetch_rejects_cleartext_before_touching_network() {
        // 安全：明文必须在**任何网络动作之前**被拒（此处不会真的联网/解析 DNS）。
        let req = FetchRequest { url: "http://example.com/".into(), ..Default::default() };
        let err = fetch(req, &TlsConfig::default(), || false).unwrap_err();
        assert!(matches!(err, FetchError::Rejected(RejectReason::NotHttps)));
        assert_eq!(err.code(), RejectReason::NotHttps.code());
    }

    #[test]
    fn fetch_rejects_disallowed_method() {
        let req = FetchRequest {
            url: "https://example.com/".into(),
            method: "POST".into(),
            ..Default::default()
        };
        let err = fetch(req, &TlsConfig::default(), || false).unwrap_err();
        assert!(matches!(err, FetchError::Rejected(RejectReason::MethodNotAllowed)));
    }

    #[test]
    fn fetch_reports_cancellation() {
        // 契约：调用方已取消时，fetch 返回 Cancelled 而不是其它错误。
        let req = FetchRequest { url: "https://127.0.0.1:1/".into(), timeout_ms: 1, ..Default::default() };
        let err = fetch(req, &TlsConfig::default(), || true).unwrap_err();
        assert!(matches!(err, FetchError::Cancelled), "实际：{err:?}");
        assert_eq!(err.code(), 12);
    }

    /// 取消探针必须是**条件完成**（标志置位才返回），而不是周期完成。
    ///
    /// 为什么值得专门钉这条：一旦写成 `tokio::time::interval(..)` 直接当取消分支，
    /// tick 会按周期无条件完成 → select! 每 50ms 就命中取消，结果是
    /// **所有请求都被判成 cancelled**（真实踩过这个坑）。这条测试用「120ms 后才
    /// 置位标志」来区分两种实现：条件完成 ⇒ 恰在 ~120ms 返回；周期完成 ⇒ 50ms 就返回。
    #[tokio::test]
    async fn wait_cancelled_is_condition_based_not_periodic() {
        use std::sync::atomic::{AtomicBool, Ordering};
        use std::sync::Arc;

        let flag = Arc::new(AtomicBool::new(false));
        let setter = flag.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(120)).await;
            setter.store(true, Ordering::SeqCst);
        });

        let probe = flag.clone();
        let start = Instant::now();
        tokio::time::timeout(Duration::from_secs(3), wait_cancelled(&move || probe.load(Ordering::SeqCst)))
            .await
            .expect("标志已置位，wait_cancelled 必须完成");
        let elapsed = start.elapsed();

        assert!(
            elapsed >= Duration::from_millis(110),
            "过早返回 {elapsed:?} —— 说明取消分支是周期完成（bug），会把正常请求也判成取消"
        );
        assert!(elapsed < Duration::from_millis(1000), "过晚返回 {elapsed:?}");
    }

    #[test]
    fn error_codes_are_distinct_and_stable() {
        let codes = [
            FetchError::Rejected(RejectReason::BadUrl).code(),
            FetchError::Dns(String::new()).code(),
            FetchError::Connect(String::new()).code(),
            FetchError::Cancelled.code(),
            FetchError::Timeout.code(),
            FetchError::Transport(String::new()).code(),
        ];
        let mut s = codes.to_vec();
        s.sort_unstable();
        s.dedup();
        assert_eq!(s.len(), codes.len(), "错误码不得重复");
        assert!(codes.iter().all(|&c| c > 0), "错误码必须为正");
    }

    #[test]
    fn pin_mismatch_is_distinguishable_from_generic_connect_failure() {
        // 安全事件不能与网络抖动混为一谈：pin 失败必须能被单独识别。
        let pin = FetchError::Connect(format!("{PIN_MISMATCH_MARKER}: 叶证书 SPKI 未命中"));
        let other = FetchError::Connect("handshake: 证书已过期".into());
        assert!(pin.pin_mismatch(), "pin 不匹配必须被标记");
        assert!(!other.pin_mismatch(), "普通链校验失败不得误判为 pin 不匹配");
        assert!(!FetchError::Timeout.pin_mismatch());
    }

    #[test]
    fn spki_hex_is_lowercase_64_chars() {
        // 对真实证书算一次，确认输出形态与 OkHttp CertificatePinner 的 sha256/ 一致。
        // 这里不依赖网络：用一段内置的自签证书 DER。
        let der = SAMPLE_CERT_DER;
        let hex = spki_sha256_hex(der).expect("应能解析 SPKI");
        assert_eq!(hex.len(), 64, "SHA-256 十六进制应为 64 字符");
        assert!(hex.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()));
    }

    #[test]
    fn spki_matches_independently_computed_openssl_value() {
        // ── 这条测试是 §6「证书固定必须等价实现」的关键证据 ──
        // 期望值由 **openssl 独立算出**（与本实现的 ASN.1/SHA 路径完全不同的实现）：
        //   openssl x509 -in cert.pem -pubkey -noout \
        //     | openssl pkey -pubin -outform DER | openssl dgst -sha256
        // 两者一致 ⇒ 本实现提取的确实是「SubjectPublicKeyInfo 的 SHA-256」，
        // 而不是碰巧过测试的某种其它哈希。换任何证书自签都会变，故此断言有真实约束力。
        const OPENSSL_SPKI_SHA256: &str =
            "d8a2b48e16bb321bd8bf2e98ccdb3b38ab12b147acda75c1efa559d0fb19b663";
        assert_eq!(
            spki_sha256_hex(SAMPLE_CERT_DER).as_deref(),
            Some(OPENSSL_SPKI_SHA256),
            "SPKI 提取必须与 openssl 独立计算的值一致"
        );
    }

    #[test]
    fn spki_hex_is_deterministic_and_rejects_garbage() {
        assert_eq!(spki_sha256_hex(SAMPLE_CERT_DER), spki_sha256_hex(SAMPLE_CERT_DER));
        assert_eq!(spki_sha256_hex(b"not a certificate"), None);
    }

    /// 一段固定的自签证书 DER（仅用于解析测试，不含私钥、与任何真实服务无关）。
    const SAMPLE_CERT_DER: &[u8] = include_bytes!("../tests/data/sample_cert.der");
}
