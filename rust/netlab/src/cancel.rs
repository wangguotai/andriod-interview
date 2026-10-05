//! 取消令牌 —— 状态机 + 计时快照。
//!
//! 对应设计文档 [A · §4.3 取消]：第一版用**协作式取消**（阻塞式 `block_on`
//! 无法被外部线程硬中断），Java 侧置标志，Rust 侧在阶段边界检查。
//!
//! ─── 为什么单独成一个模块并写测试 ───
//!
//! 「取消」是并发代码里最容易写出**偶发**错误的地方，而偶发错误在真机上极难复现：
//!   · 竞态：cancel 与 fetch 完成同时发生 → 可能上报了结果，也可能上报了取消，
//!     两者都「看起来正常」，却会导致上层 UI 状态错乱；
//!   · 重复取消：幂等性没定义清楚时，第二次 cancel 可能把「已完成」翻转成「已取消」。
//!
//! 这些用单测钉死成本极低（微秒级），放到真机上排查成本极高。所以：
//! **状态机放这里，用穷举式的用例把每条边都走一遍。**

use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::Arc;

/// 状态编码（用 `u8` 存进 atomic，避免 `Mutex` 带来的争用与额外依赖）。
const STATE_RUNNING: u8 = 0;
const STATE_CANCELLED: u8 = 1;
const STATE_DONE: u8 = 2;

/// 取消状态机的**终态判定结果**。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Outcome {
    /// 正常完成
    Completed,
    /// 被取消（终态，且不会再变回 Completed）
    Cancelled,
}

/// 可跨线程共享的取消令牌。
///
/// Kotlin 侧持有同一份句柄（经 JNI 指针），调用 `cancel()`；
/// Rust 传输在阶段边界调用 `is_cancelled()`。
#[derive(Clone, Default)]
pub struct CancelToken {
    state: Arc<AtomicU8>,
}

impl CancelToken {
    pub fn new() -> Self {
        Self { state: Arc::new(AtomicU8::new(STATE_RUNNING)) }
    }

    /// 是否已被取消。传输实现的阶段边界调用它。
    pub fn is_cancelled(&self) -> bool {
        self.state.load(Ordering::Acquire) == STATE_CANCELLED
    }

    /// 请求取消。
    ///
    /// **只在 RUNNING 时生效**：若已经 DONE，取消必须被忽略 ——
    /// 否则会出现「请求明明成功了，却被上报成取消」的诡异现象。
    /// 这是本状态机最容易被写错、也最容易被漏测的一条边。
    ///
    /// 返回 `true` 表示「本次调用真正把状态翻到了 CANCELLED」，
    /// 便于 Kotlin 侧判断取消信号是否被接受（对应 OkHttp `cancel()` 无返回值，
    /// 这里给返回值是为了对拍时可断言）。
    pub fn cancel(&self) -> bool {
        self.state
            .compare_exchange(
                STATE_RUNNING,
                STATE_CANCELLED,
                Ordering::AcqRel,
                Ordering::Acquire,
            )
            .is_ok()
    }

    /// 标记完成。
    ///
    /// 与 `cancel` 对称：**只在 RUNNING 时生效**。若已 CANCELLED，完成信号必须被忽略
    /// —— 保证「先取消后完成」不会把终态翻回去（否则取消形同虚设）。
    ///
    /// 返回 `true` 表示本次调用把状态翻到了 DONE。
    pub fn complete(&self) -> bool {
        self.state
            .compare_exchange(STATE_RUNNING, STATE_DONE, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
    }

    /// 用「哪一方先到」结算终态：谁先 CAS 成功，谁赢。
    ///
    /// 这是给传输实现用的收口函数：无论正常返回还是被取消，都调它一次，
    /// 由它给出**唯一**的终态判定，避免实现里散落「先判断 is_cancelled 再……」的
    /// 双重检查导致的竞态窗口。
    pub fn settle(&self, success: bool) -> Outcome {
        if success {
            if self.complete() {
                Outcome::Completed
            } else if self.is_cancelled() {
                // 完成时发现已被取消：取消优先。
                // 语义解释：既然调用方明确不要这个结果了，就不该再当作成功上报，
                // 否则上层可能用一个「已放弃的请求」的结果去更新 UI。
                Outcome::Cancelled
            } else {
                // 已被并发地 complete（重复结算）：保守判为取消，不重复回报成功。
                Outcome::Cancelled
            }
        } else if self.is_cancelled() {
            Outcome::Cancelled
        } else {
            // 失败但不是取消：仍按取消上报（第一版不区分失败类型，
            // 由 Kotlin 侧依据错误码映射；这里只保证终态是稳定的）。
            let _ = self.complete();
            Outcome::Cancelled
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn initial_state_is_running() {
        let t = CancelToken::new();
        assert!(!t.is_cancelled());
    }

    #[test]
    fn cancel_then_cancel_is_idempotent() {
        let t = CancelToken::new();
        assert!(t.cancel(), "首次取消应生效");
        assert!(!t.cancel(), "重复取消应被忽略（已不是 RUNNING）");
        assert!(t.is_cancelled());
    }

    #[test]
    fn complete_then_cancel_must_not_flip_to_cancelled() {
        // 这条边是最容易写错的：请求已成功，随后的 cancel 不应改变终态。
        let t = CancelToken::new();
        assert!(t.complete());
        assert!(!t.cancel(), "已完成后的取消必须被忽略");
        assert!(!t.is_cancelled(), "不能把 DONE 翻成 CANCELLED");
    }

    #[test]
    fn cancel_then_complete_must_not_flip_back() {
        let t = CancelToken::new();
        assert!(t.cancel());
        assert!(!t.complete(), "已取消后的完成必须被忽略");
        assert!(t.is_cancelled(), "终态必须保持 CANCELLED");
    }

    #[test]
    fn settle_success_when_not_cancelled_is_completed() {
        let t = CancelToken::new();
        assert_eq!(t.settle(true), Outcome::Completed);
    }

    #[test]
    fn settle_success_but_already_cancelled_is_cancelled() {
        // 竞态的关键用例：先取消，再以「成功」结算 → 必须以取消为准，
        // 否则上层会用一个已放弃的请求结果更新 UI。
        let t = CancelToken::new();
        t.cancel();
        assert_eq!(t.settle(true), Outcome::Cancelled);
    }

    #[test]
    fn settle_failure_is_never_completed() {
        let t = CancelToken::new();
        assert_eq!(t.settle(false), Outcome::Cancelled);
    }

    #[test]
    fn concurrent_cancel_only_one_wins() {
        use std::thread;
        // 10 个线程同时取消：必须恰好一个返回 true（CAS 语义）。
        let t = CancelToken::new();
        let mut handles = Vec::new();
        for _ in 0..10 {
            let tt = t.clone();
            handles.push(thread::spawn(move || tt.cancel()));
        }
        // 注意：filter 会把 JoinHandle 按引用交给闭包，而 join 需要所有权，
        // 所以先 join 收集成 Vec<bool> 再统计（踩过：直接 filter 会 E0507）。
        let results: Vec<bool> = handles.into_iter().map(|h| h.join().unwrap()).collect();
        let wins = results.iter().filter(|&&w| w).count();
        assert_eq!(wins, 1, "并发取消应恰好一个成功，其余被忽略");
        assert!(t.is_cancelled());
    }

    #[test]
    fn concurrent_cancel_and_complete_never_both_win() {
        use std::thread;
        // 取消与完成并发：两者互斥，不可能同时返回 true。
        for _ in 0..200 {
            let t = CancelToken::new();
            let t1 = t.clone();
            let t2 = t.clone();
            let a = thread::spawn(move || t1.cancel());
            let b = thread::spawn(move || t2.complete());
            let cancelled = a.join().unwrap();
            let completed = b.join().unwrap();
            assert!(
                !(cancelled && completed),
                "cancel 与 complete 不得同时胜出（终态必须唯一）"
            );
        }
    }
}
