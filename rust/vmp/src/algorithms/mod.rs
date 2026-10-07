//! 三个受保护算子的「自然版本」—— 它们**只在构建期与本机测试里运行**。
//!
//! ─── 一个容易误解的地方：这里的函数不是「算法实现」───
//!
//! 它们写成了「读起来像算法」的样子（循环、条件、索引），但真实角色是
//! **字节码生成器**：`build.rs` 调 `dominant::emit(...)` 得到一段 VMP-1 字节码，
//! 加密后嵌进 `.so`。发布出去的 `.so` 里**没有这个目录**。
//! 之所以还让它们长得像算法，是因为这样最容易与 `imagepipeline` 的原生实现
//! 逐行对照 —— 而「逐位对拍」正是本 Lab 的验收红线。
//!
//! ─── 对拍的三方 ───
//!
//! ```text
//!     imagepipeline::dominant_color     （原生机器码，M3 的既有实现）
//!              │  必须逐位相等
//!              ▼
//!     algorithms::dominant::emit 生成的字节码
//!              │  由 vm.rs 解释执行
//!              ▼
//!     VMP 结果
//! ```
//!
//! 三者用同一套输入（含边界尺寸、退化图、随机图）交叉比对，见各模块的 `tests`。

pub mod blur;
pub mod dominant;
pub mod downscale;

use crate::asm::ProgBuilder;
use crate::format::Flags;

/// 便捷函数：新建一个带指定输入维度与输出段的 builder。
pub(crate) fn builder(input_dims: Vec<u8>, out: u8) -> ProgBuilder {
    ProgBuilder::new(Flags { input_dims, out })
}
