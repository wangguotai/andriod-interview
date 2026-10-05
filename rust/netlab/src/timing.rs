//! 阶段耗时累加 —— 字段与 Kotlin `NetMetrics.Stage` **逐一对齐**。
//!
//! 对应设计文档 [A · §5 度量回灌]。
//!
//! ─── 为什么计时也要放在 Rust ───
//!
//! 阶段边界（开始解析、连接建立、TLS 完成、首字节、结束）都发生在传输实现内部。
//! 若在 Kotlin 侧靠「进入/返回」两次打点，只能得到总耗时，拿不到分段；
//! 而分段恰恰是定位「弱网问题出在哪一段」的唯一依据。
//!
//! ─── 必须诚实的一点（写进类型里，而不是只在文档里）───
//!
//! QUIC 把传输握手与 TLS 握手**融合**了，因此不可能得到与 TCP 路径一一对应的
//! `connect` / `tls` 两段。本模块用 `#[derive]` 之外的显式标记
//! [`TransportKind`] 把「口径不同」固化成数据的一部分 —— 汇总统计时必须按
//! transport 分开呈现，否则会把口径差异误读成性能差异。

/// 传输类型。用于标记「分段口径」，不是装饰性字段。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum TransportKind {
    /// TCP + TLS：connect/tls 两段独立可测（OkHttp 路径）
    #[default]
    Tcp,
    /// QUIC：握手与加密融合，connect 段含加密，tls 段无独立含义
    Quic,
}

/// 各阶段耗时（毫秒）。未发生的阶段为 `None`。
///
/// 用 `Option<u64>` 而非 `-1`：`-1` 这种哨兵值在跨语言传输时极易被当成
/// 真实数值参与计算（`avg` 会把 -1 算进去），用 `Option` 从类型上杜绝，
/// JNI 侧再映射成 Kotlin 的 `-1` 约定（那是 Kotlin 侧的既有约定，不改）。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct StageTiming {
    /// DNS 解析。Rust 路径由 Kotlin 侧先用 InterviewDns 解析、把 IP 传入，
    /// 故本段耗时由 Kotlin 记录，这里通常为 `None`。
    pub dns_ms: Option<u64>,
    /// 连接建立。`TransportKind::Quic` 时**含加密握手**。
    pub connect_ms: Option<u64>,
    /// TLS 握手。`TransportKind::Quic` 时无独立含义，恒为 `None`。
    pub tls_ms: Option<u64>,
    /// 首字节（请求发出 → 响应头到达）
    pub first_byte_ms: Option<u64>,
    /// 总耗时（含 JNI 往返开销）
    pub total_ms: u64,
    /// 传输类型，标记分段口径
    pub kind: TransportKind,
}

/// 单调计时器。用 `Instant` 而非系统时间（系统时间会被改，导致负耗时）。
#[derive(Debug, Clone, Copy)]
pub struct Stopwatch {
    start: Option<std::time::Instant>,
    /// 上一次打点时刻，用于算「相邻两点的差」
    last: Option<std::time::Instant>,
}

impl Stopwatch {
    pub const fn new() -> Self {
        Self { start: None, last: None }
    }

    /// 开始计时（等价 Kotlin 侧的 `callStart`）。
    pub fn start(&mut self) {
        let now = std::time::Instant::now();
        self.start = Some(now);
        self.last = Some(now);
    }

    /// 打一个「自上一个打点以来」的耗时（毫秒）。
    ///
    /// 未 start 过时返回 `None` 而不是 `0` —— `0ms` 会被误读成「瞬间完成」，
    /// 而真实情况可能是「压根没开始计时」。这类「0 与未知混淆」是度量里最常见的假数据来源。
    pub fn lap(&mut self) -> Option<u64> {
        let now = std::time::Instant::now();
        let last = self.last?;
        self.last = Some(now);
        Some((now - last).as_millis() as u64)
    }

    /// 从 start 到现在的总耗时（毫秒）。未 start 时返回 0（总耗时字段是必填的）。
    pub fn total(&self) -> u64 {
        match self.start {
            Some(s) => (std::time::Instant::now() - s).as_millis() as u64,
            None => 0,
        }
    }
}

impl Default for Stopwatch {
    fn default() -> Self {
        Self::new()
    }
}

impl StageTiming {
    /// 组装一次 QUIC 传输的计时结果。
    ///
    /// 把「QUIC 没有独立 tls 段」这条约束**写进构造函数**，而不是靠调用方自觉：
    /// 传进来的 `tls` 会被忽略并强制为 `None`，从源头杜绝「QUIC 路径谎报 tls 耗时」。
    pub fn for_quic(connect_ms: Option<u64>, first_byte_ms: Option<u64>, total_ms: u64) -> Self {
        Self {
            dns_ms: None, // 由 Kotlin 侧记录（见字段注释）
            connect_ms,
            tls_ms: None, // QUIC 融合握手，无独立 TLS 段
            first_byte_ms,
            total_ms,
            kind: TransportKind::Quic,
        }
    }

    /// 组装一次 TCP 传输的计时结果（用于对拍基线）。
    pub fn for_tcp(
        connect_ms: Option<u64>,
        tls_ms: Option<u64>,
        first_byte_ms: Option<u64>,
        total_ms: u64,
    ) -> Self {
        Self {
            dns_ms: None,
            connect_ms,
            tls_ms,
            first_byte_ms,
            total_ms,
            kind: TransportKind::Tcp,
        }
    }
}

/// 施加取消检查的**时间预算**辅助：返回「剩余可用毫秒」。
///
/// 传输实现应在每个阶段边界调用它，`None` 表示已超预算（应当中止并结算为取消）。
pub fn remaining_budget(elapsed_ms: u64, budget_ms: u64) -> Option<u64> {
    if elapsed_ms >= budget_ms {
        None
    } else {
        Some(budget_ms - elapsed_ms)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::thread;
    use std::time::Duration;

    #[test]
    fn lap_without_start_is_none_not_zero() {
        // 「0 与未知」必须可区分 —— 这是度量假数据的头号来源。
        let mut w = Stopwatch::new();
        assert_eq!(w.lap(), None);
        assert_eq!(w.total(), 0);
    }

    #[test]
    fn lap_measures_elapsed_time() {
        let mut w = Stopwatch::new();
        w.start();
        thread::sleep(Duration::from_millis(20));
        let lap = w.lap().expect("start 之后 lap 必须有值");
        assert!(lap >= 15, "睡眠 20ms 后 lap 应 >=15，实际 {lap}");
        assert!(lap < 200, "lap 不应异常偏大，实际 {lap}");
    }

    #[test]
    fn total_is_monotonic_across_laps() {
        let mut w = Stopwatch::new();
        w.start();
        thread::sleep(Duration::from_millis(10));
        let _ = w.lap();
        thread::sleep(Duration::from_millis(10));
        assert!(w.total() >= 15, "总耗时应累计两段睡眠");
    }

    #[test]
    fn quic_timing_never_reports_tls() {
        // QUIC 融合握手：即便调用方想塞一个 tls 值也不该生效。
        let t = StageTiming::for_quic(Some(42), Some(90), 100);
        assert_eq!(t.tls_ms, None, "QUIC 路径不得有独立 TLS 段");
        assert_eq!(t.kind, TransportKind::Quic);
        assert_eq!(t.connect_ms, Some(42));
        assert_eq!(t.first_byte_ms, Some(90));
    }

    #[test]
    fn tcp_timing_keeps_all_segments() {
        let t = StageTiming::for_tcp(Some(10), Some(25), Some(60), 70);
        assert_eq!(t.connect_ms, Some(10));
        assert_eq!(t.tls_ms, Some(25));
        assert_eq!(t.first_byte_ms, Some(60));
        assert_eq!(t.kind, TransportKind::Tcp);
    }

    #[test]
    fn transport_kinds_are_distinct() {
        // 汇总统计必须能据此分开呈现，否则会把口径差异误读成性能差异。
        assert_ne!(TransportKind::Tcp, TransportKind::Quic);
    }

    #[test]
    fn remaining_budget_boundaries() {
        assert_eq!(remaining_budget(0, 100), Some(100));
        assert_eq!(remaining_budget(99, 100), Some(1));
        assert_eq!(remaining_budget(100, 100), None, "刚好用尽即超预算");
        assert_eq!(remaining_budget(101, 100), None);
    }
}
