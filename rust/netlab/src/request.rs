//! 请求准入校验 —— 决定「这次请求是否交给 Rust 传输」。
//!
//! 对应设计文档 [A · §2 路由判据] 与 [§6 安全对齐]。
//!
//! ─── 为什么这套判定必须在 Rust 侧也有一份 ───
//!
//! Kotlin 的路由层可以用 `NetworkQuality.level` 决定「想不想走 Rust」，
//! 但**「允不允许走 Rust」是安全策略**，不能只由调用方口头保证：
//!   · 一旦有人绕过 Kotlin 侧直接调 bridge（测试、脚本、未来的其他调用方），
//!     明文 http 就会被放行 —— 这是绕过平台明文策略的安全降级；
//!   · 因此校验必须在**跨语言边界内侧**再做一次，作为最后一道闸门。
//!
//! 这与「输入校验不能只信客户端」是同一个原则，只不过这里的「客户端」
//! 是同一进程里的另一层代码。

/// 校验失败的原因。返回具体原因而不是 bool，是为了让 Kotlin 侧能打出
/// 可诊断的日志（`bool` 只能告诉你「不行」，不能告诉你「为什么不行」）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RejectReason {
    /// URL 为空或格式非法
    BadUrl,
    /// 非 https：直接拒绝，避免绕过 Android 的明文流量策略
    NotHttps,
    /// 方法不在允许列表内（第一版只支持幂等安全的读写）
    MethodNotAllowed,
    /// 需要代理，但第一版不支持（**明确报错而不是静默直连**）
    ProxyUnsupported,
}

impl RejectReason {
    /// 稳定的数值错误码，供 JNI 回传。`> 0` 表示拒绝，`0` 表示通过。
    ///
    /// 用固定数值而不是字符串：跨 FFI 传字符串要管生命周期，而错误码是值语义、
    /// 不会泄漏。人类可读描述由 Kotlin 侧按码映射（见 `NetLabNative.describe`）。
    pub const fn code(self) -> i32 {
        match self {
            RejectReason::BadUrl => 1,
            RejectReason::NotHttps => 2,
            RejectReason::MethodNotAllowed => 3,
            RejectReason::ProxyUnsupported => 4,
        }
    }
}

/// 第一版允许的方法。
///
/// 为什么只有这些：它们要么是安全的（无副作用），要么是幂等的（重复执行结果一致），
/// 因此即使传输层在弱网下重发也不会造成数据损坏 —— 与
/// `AdaptiveRetryInterceptor` 的幂等判定是同一套语义。
/// POST/PATCH 暂不放行：它们在 Rust 传输里重试无幂等键保护，风险大于收益。
pub const ALLOWED_METHODS: &[&str] = &["GET", "HEAD", "PUT", "DELETE"];

/// 校验一次请求。通过返回 `Ok(())`，否则返回具体拒绝原因。
///
/// @param url          完整 URL（必须是 https）
/// @param method       HTTP 方法（大小写不敏感）
/// @param has_proxy    调用方是否要求走代理（第一版不支持）
pub fn validate(url: &str, method: &str, has_proxy: bool) -> Result<(), RejectReason> {
    if !is_acceptable_url_shape(url) {
        return Err(RejectReason::BadUrl);
    }
    // 大小写不敏感：HTTP 方法按规范是大小写敏感的 token，但实际客户端常写小写，
    // 这里做归一化，避免「get」被误判为不支持。
    if !url.starts_with("https://") {
        return Err(RejectReason::NotHttps);
    }
    let upper = method.to_ascii_uppercase();
    if !ALLOWED_METHODS.contains(&upper.as_str()) {
        return Err(RejectReason::MethodNotAllowed);
    }
    if has_proxy {
        // ⚠️ 刻意「报错而非忽略」：静默直连会让企业内网用户绕过代理审计，
        // 既是功能 bug 也是合规问题。见设计文档 §6。
        return Err(RejectReason::ProxyUnsupported);
    }
    Ok(())
}

/// URL 形状的轻量校验（不是完整解析器）。
///
/// 只做「足够拦住畸形输入」的检查：必须有 scheme 分隔符、非空 host、无空白。
/// 完整解析由传输层负责（那里能报出更准确的错误）。
/// 这里刻意不引 URL 解析库：保持本 crate 零依赖，host 侧测试秒级。
pub fn is_acceptable_url_shape(url: &str) -> bool {
    if url.is_empty() || url.len() > 8192 {
        return false;
    }
    // URL 中不允许出现空白字符（头注入/畸形输入的常见来源）
    if url.chars().any(|c| c.is_whitespace()) {
        return false;
    }
    // 必须有 "://" 且 scheme 非空
    let Some(scheme_end) = url.find("://") else {
        return false;
    };
    if scheme_end == 0 {
        return false;
    }
    // scheme 之后必须有非空 host（不能是 "https://" 或 "https:///path"）
    let rest = &url[scheme_end + 3..];
    !rest.is_empty() && !rest.starts_with('/')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_plain_https_get() {
        assert_eq!(validate("https://api.github.com/zen", "GET", false), Ok(()));
    }

    #[test]
    fn method_is_case_insensitive() {
        assert_eq!(validate("https://example.com/a", "get", false), Ok(()));
        assert_eq!(validate("https://example.com/a", "Delete", false), Ok(()));
    }

    #[test]
    fn rejects_cleartext_http() {
        // 安全红线：明文必须拒绝，绝不能因为「只是个测试」就放行。
        assert_eq!(
            validate("http://example.com/a", "GET", false),
            Err(RejectReason::NotHttps)
        );
    }

    #[test]
    fn rejects_disallowed_methods() {
        assert_eq!(
            validate("https://example.com/a", "POST", false),
            Err(RejectReason::MethodNotAllowed)
        );
        assert_eq!(
            validate("https://example.com/a", "PATCH", false),
            Err(RejectReason::MethodNotAllowed)
        );
    }

    #[test]
    fn rejects_proxy_explicitly_not_silently() {
        assert_eq!(
            validate("https://example.com/a", "GET", true),
            Err(RejectReason::ProxyUnsupported)
        );
    }

    #[test]
    fn rejects_malformed_urls() {
        assert_eq!(validate("", "GET", false), Err(RejectReason::BadUrl));
        assert_eq!(validate("not-a-url", "GET", false), Err(RejectReason::BadUrl));
        assert_eq!(validate("https://", "GET", false), Err(RejectReason::BadUrl));
        assert_eq!(validate("https:///path", "GET", false), Err(RejectReason::BadUrl));
        // 空白字符（头注入的常见来源）
        assert_eq!(
            validate("https://example.com/a b", "GET", false),
            Err(RejectReason::BadUrl)
        );
    }

    #[test]
    fn reject_codes_are_stable_and_distinct() {
        let codes = [
            RejectReason::BadUrl.code(),
            RejectReason::NotHttps.code(),
            RejectReason::MethodNotAllowed.code(),
            RejectReason::ProxyUnsupported.code(),
        ];
        assert!(codes.iter().all(|&c| c > 0), "拒绝码必须为正，0 留给通过");
        let mut sorted = codes.to_vec();
        sorted.sort_unstable();
        sorted.dedup();
        assert_eq!(sorted.len(), codes.len(), "拒绝码不得重复");
    }
}
