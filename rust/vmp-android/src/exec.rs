//! 可复用的虚拟机执行器 —— JNI 层与宿主测试**共用**的一条路径。
//!
//! ─── 为什么把「装配内存 + 启动 VM」抽出来 ───
//!
//! JNI 函数本身在宿主平台上跑不了（要 `JNIEnv`），如果逻辑都写在 JNI 函数里，
//! 「参数校验 / 缓冲尺寸 / 输入槽约定」这些**最容易错**的部分就只能上真机验。
//! 抽成这个模块后，它们全部可以用 `cargo test` 在本机秒级跑完 —— 而且与
//! JNI 调用的是**同一份代码**，不存在「测的和跑的不是一个东西」。
//!
//! ─── 尺寸上限：不是保守，是 VM 的**正确性前提** ───
//!
//! 三个算子的字节码都建立在两条不变量上，它们必须由这里兜住：
//!
//! 1. **累加不溢出 u32**：downscale 把整块和累加进 u32 槽，上界是
//!    `src_w × 255`；`MAX_DIM = 4096` 使其 ≤ 1.04M，远小于 2³²。
//!    （原生实现用 u64 是为了 8K 图的极端情形，本 Lab 用上限换掉了那个类型，
//!    代价就是必须在这里卡住尺寸 —— 见 `downscale.rs` 的注释。）
//! 2. **累加器内存有界**：downscale 的累加器是 `dw × dh × 16` 字节，
//!    比输出本身大 4 倍。`MAX_WORK_BYTES = 32MB` 把它限制在
//!    `dw × dh ≤ 2M`（约 1414×1414），对「缩略图/占位图」这个用途足够。
//!
//! 超出上限**明确报错**而不是试着跑：试着跑的结果是静默算错（溢出回绕），
//! 这比拒绝服务糟糕得多。

use vmp::format::HEADER_LEN;
use vmp::vm::{Memory, Vm, VmError};
use vmp::{programs, PROGRAM_NAMES};

/// 单边最大尺寸。见模块注释的第 1 条不变量。
pub const MAX_DIM: u32 = 4096;
/// 工作缓冲的字节上限（downscale 的累加器暂存）。见模块注释的第 2 条不变量。
pub const MAX_WORK_BYTES: usize = 32 * 1024 * 1024;

/// 参数/尺寸不合法 —— 与 `VmError` 区分开：这一类比 VM 内部错误更常见，
/// 而且是**调用方的错**，值得单独一个码，便于日志里一眼分辨。
pub const ERR_BAD_ARGUMENT: i32 = -1;
/// FFI 边界上捕获到 panic（与 `imagepipeline_android` 保持同一约定）。
pub const ERR_PANIC: i32 = -2;

/// 与 Kotlin 侧 `VmpBridge.EXPECTED_ABI_VERSION` 对齐。
///
/// 变更记录：1 → M1：abiVersion / selftest / hardeningStatus / 三个 VM 算子 / 原生对照。
pub const ABI_VERSION: i32 = 1;

// ─────────────────────────────────────────────────────────────────────────────
// 输入校验：**两条路径共用**，这是「逐位对拍」能被信任的前提。
//
// 为什么必须共用（而不是各管各的）：如果 VM 路径比原生路径多一道限制，
// 那么「同参数 → 两边都成功 → 逐位一致」这条断言就有**盲区** ——
// 有些参数在 VM 上被拒、在原生上照跑，测试却显示「全绿」。
// 这正是本项目真实踩到的坑：blur 的 `radius > max(w,h)` 只在 VM 侧生效，
// 于是 `1x1 r=2` 在 VM 上报错、在原生上成功。逐位对拍当时**看不见**这个差异，
// 直到把「两边同域」本身写进测试才暴露。
//
// 所以这里的规则是：**VM 的正确性前提 = 整个加固 API 的输入域**。
// 原生路径也必须过同一道闸 —— 它的存在意义是「加固前后的公平对照」，
// 而不是「一个功能更宽的宽松版本」。
// ─────────────────────────────────────────────────────────────────────────────

/// 校验一组图像尺寸，返回「该尺寸所需的 RGBA 字节数」。
///
/// 前提（与 `MAX_DIM` 的注释一致）：`w × 255` 不能溢出 u32 的累加器，
/// 且 `w×h×4` 不能撑爆 `usize`。两条路径都必须是同一套。
pub fn check_image_dim(w: u32, h: u32) -> Result<usize, i32> {
    if w == 0 || h == 0 || w > MAX_DIM || h > MAX_DIM {
        return Err(ERR_BAD_ARGUMENT);
    }
    Ok((w as usize) * (h as usize) * 4)
}

/// 校验 blur 的半径，并返回其**语义上限**。
///
/// `radius` 超过 `max(w, h)` 之后，clamp 窗口已覆盖整幅图，再大也不会改变结果 ——
/// 只会在原生实现里把 `O(w·h·r)` 的时间放大（`r=10⁶` 就是一次事实上的 DoS）。
/// 因此这个上限**不是 VM 的私有约束**，而是这个算子的输入域边界，两条路径都该守。
pub fn check_blur_radius(w: u32, h: u32, radius: u32) -> Result<(), i32> {
    // 用 u64 比较：`radius as u64` 不会被截断，`max` 也无需担心符号。
    if radius as u64 > w.max(h) as u64 {
        return Err(ERR_BAD_ARGUMENT);
    }
    Ok(())
}

/// 可复用的执行器。
///
/// **为什么要复用**：`Vm` 内部有三个预分配的数组（栈 4096、aux 16384、调用栈），
/// 每次调用都新建会在基准里把分配器的时间算进来 —— 那样测出来的就不是 VM，
/// 而是 `malloc`。这与 `ImagePipelineBridge.RgbaBufferPool` 的动机一致
/// （见 `NOTES-rust-pipeline.md` 关于「把分配成本摊进耗时」的提醒）。
pub struct VmExec {
    vm: Vm,
    /// downscale 的累加器暂存 / blur 的中间结果。按需扩容、**只增不减**。
    work: Vec<u8>,
}

impl Default for VmExec {
    fn default() -> Self {
        Self::new()
    }
}

impl VmExec {
    pub fn new() -> Self {
        Self { vm: Vm::new(), work: Vec::new() }
    }

    /// 上次运行惰性解密了多少页（未命中）—— 基准取证：证明不是「启动时全解密」。
    pub fn last_fetch_pages(&self) -> u32 {
        self.vm.last_fetched_pages()
    }

    /// 上次运行命中页缓存的次数 —— 与未命中一起说明「惰性解密到底贵不贵」。
    pub fn last_cache_hits(&self) -> u32 {
        self.vm.last_cache_hits()
    }

    /// 上次运行执行的指令条数。
    ///
    /// 与耗时相除得到「ns/指令」，这是把「VM 慢」拆成「字节码太长」还是
    /// 「解释器太慢」的唯一办法。也是「加固代价」里**唯一与设备无关**的量 ——
    /// 毫秒数换台机器就变，指令数不会。
    pub fn last_steps(&self) -> u64 {
        self.vm.last_steps()
    }

    /// 运行内建自检（VM 的算术语义与 ISA 规范是否一致）。
    pub fn selftest(&mut self) -> bool {
        self.vm.run_selftest()
    }

    /// 按名字取一个受保护程序；不存在返回 `None`（JNI 层据此报错）。
    fn program(&self, name: &str) -> Option<&'static programs::Program> {
        vmp::find(name)
    }

    /// 取得一块至少 `bytes` 长的已清零工作缓冲。
    ///
    /// 写成**自由函数**（而不是 `&mut self` 的方法）是有原因的：
    /// 调用方在拿到这块缓冲后还要 `vm.run(...)`，而「工作缓冲」与「VM」是
    /// `VmExec` 的两个字段。若用方法，缓冲的生命周期会借走整个 `self`，
    /// 于是 `self.vm` 就再也借不到了（E0499）。解构出两个字段后再各借各的，
    /// 才能同时持有 —— 这不是绕开借用检查，而是**如实表达两者互不重叠**。
    fn take_work(work: &mut Vec<u8>, bytes: usize) -> Result<&mut [u8], i32> {
        if bytes > MAX_WORK_BYTES {
            return Err(ERR_BAD_ARGUMENT);
        }
        if work.len() < bytes {
            work.resize(bytes, 0);
        }
        // 清零是必须的：downscale 假设累加器从 0 开始累加。
        // blur 的两趟都会覆盖用到的位置，但清零同样让它与原生实现的
        // 「新分配向量」语义一致。成本是每次调用一次 memset，可接受。
        work[..bytes].fill(0);
        Ok(&mut work[..bytes])
    }

    /// VM 版主色调提取。返回 `0x00RRGGBB`。
    pub fn dominant(&mut self, src: &[u8], w: u32, h: u32) -> Result<u32, i32> {
        let need = check_image_dim(w, h)?;
        if src.len() < need {
            return Err(ERR_BAD_ARGUMENT);
        }
        let prog = self.program("dominant").ok_or(ERR_BAD_ARGUMENT)?;
        // dominant 不用 `WORK`（直方图住在 aux），但 `Memory` 需要三段齐全。
        let mut mem = Memory { src: &src[..need], dst: &mut [], work: &mut [] };
        // 输入槽 0 = 像素数（与 `algorithms::dominant` 的约定一致）
        let inputs = [(need / 4) as u64];
        self.vm
            .run(prog.blob, Some((prog.key, prog.nonce)), &inputs, Some(&mut mem))
            .map_err(VmError::code)?;
        let rgb = self.vm.stack_top(1).first().copied().unwrap_or(0);
        Ok((rgb & 0x00FF_FFFF) as u32)
    }

    /// VM 版区域平均降采样。
    pub fn downscale(
        &mut self,
        src: &[u8],
        sw: u32,
        sh: u32,
        dst: &mut [u8],
        dw: u32,
        dh: u32,
    ) -> Result<(), i32> {
        let src_need = check_image_dim(sw, sh)?;
        let dst_need = check_image_dim(dw, dh)?;
        if src.len() < src_need || dst.len() < dst_need {
            return Err(ERR_BAD_ARGUMENT);
        }
        // 累加器：每输出像素 16 字节（4 个 u32 槽）
        let work_bytes = (dw as usize) * (dh as usize) * 16;
        if work_bytes > MAX_WORK_BYTES {
            return Err(ERR_BAD_ARGUMENT);
        }
        // aux 布局：xs（每列 2 格）从 0 起，cnt（每列 1 格）紧随其后。
        // 必须保证不与标量区（16000 起）重叠。
        let xs_base: u64 = 0;
        let cnt_base: u64 = (2 * dw) as u64;
        if cnt_base + dw as u64 >= vmp::algorithms::downscale::SCALAR_BASE as u64 {
            return Err(ERR_BAD_ARGUMENT);
        }

        let prog = self.program("downscale").ok_or(ERR_BAD_ARGUMENT)?;
        // 解构出让借用互不重叠：`work` 与 `vm` 是两个字段，各自独立可变借用。
        let VmExec { vm, work } = self;
        let work = VmExec::take_work(work, work_bytes)?;
        let mut mem = Memory { src: &src[..src_need], dst: &mut dst[..dst_need], work };
        let inputs = [
            sw as u64,
            sh as u64,
            xs_base,
            cnt_base,
            dw as u64,
            dh as u64,
        ];
        vm.run(prog.blob, Some((prog.key, prog.nonce)), &inputs, Some(&mut mem))
            .map_err(VmError::code)
    }

    /// VM 版盒式模糊。
    pub fn blur(
        &mut self,
        src: &[u8],
        w: u32,
        h: u32,
        dst: &mut [u8],
        radius: u32,
    ) -> Result<(), i32> {
        let need = check_image_dim(w, h)?;
        if src.len() < need || dst.len() < need {
            return Err(ERR_BAD_ARGUMENT);
        }
        check_blur_radius(w, h, radius)?;
        let prog = self.program("blur").ok_or(ERR_BAD_ARGUMENT)?;
        let VmExec { vm, work } = self;
        let work = VmExec::take_work(work, need)?;
        let mut mem = Memory { src: &src[..need], dst: &mut dst[..need], work };
        let inputs = [w as u64, h as u64, radius as u64];
        vm.run(prog.blob, Some((prog.key, prog.nonce)), &inputs, Some(&mut mem))
            .map_err(VmError::code)
    }
}

/// 加固状态摘要 —— 进日志、上屏都用它。
///
/// 它是**可断言的事实清单**，不是宣传词：
///   - `isa`：ISA 版本（参与字节码头校验）；
///   - `programs`：受保护程序名与**密文长度**（让「算法不在机器码里」变成可核对的数据）；
///   - `selftest`：VM 算术语义自检结果（当场上设备就能重放）；
///   - `asm_alg_absent`：说明构建期生成器与汇编器未被链接进 `.so`（由 `NOTES` 的
///     `nm`/`strings` 步骤核实，这里只做提示）。
pub fn hardening_status() -> String {
    let mut s = String::new();
    s.push_str(&format!("vmp abi={ABI_VERSION} isa={}", vmp::format::ISA_VERSION));
    s.push_str(&format!(" target={}", std::env::consts::ARCH));
    s.push_str(" programs=[");
    for (i, name) in PROGRAM_NAMES.iter().enumerate() {
        if i > 0 {
            s.push(' ');
        }
        match vmp::find(name) {
            Some(p) => {
                let payload = p.blob.len() - HEADER_LEN;
                s.push_str(&format!("{name}:ct={payload}b,intact={}", header_intact(p.blob)));
            }
            None => s.push_str(&format!("{name}:MISSING")),
        }
    }
    s.push_str("] code=vm-interpreted");
    s
}

/// 校验一个程序容器头（魔数/版本/长度/校验和）。**不解密**，因此很便宜。
///
/// 用途是「加固自检」：篡改字节码必然破坏校验和；加载时先判掉，
/// 比让它跑出一个错误结果要好。注意它的边界（见 `format::seal` 的注释）：
/// **FNV 挡不住有意的篡改**（改完重算即可），它挡的是意外损坏与版本错配。
pub fn header_intact(blob: &[u8]) -> bool {
    if blob.len() < HEADER_LEN {
        return false;
    }
    let u32_at = |p: usize| u32::from_le_bytes([blob[p], blob[p + 1], blob[p + 2], blob[p + 3]]);
    if u32_at(0) != vmp::format::MAGIC || u32_at(4) != vmp::format::ISA_VERSION {
        return false;
    }
    let code_len = u32_at(8) as usize;
    let const_count = u32_at(12) as usize;
    if blob.len() != HEADER_LEN + const_count * 4 + code_len || code_len == 0 {
        return false;
    }
    vmp::format::fnv1a32(&[&blob[4..16], &blob[HEADER_LEN..]].concat()) == u32_at(16)
}

/// 所有受保护程序的头部是否完好。
pub fn all_programs_intact() -> bool {
    PROGRAM_NAMES
        .iter()
        .all(|n| vmp::find(n).map(|p| header_intact(p.blob)).unwrap_or(false))
}

/// 原生（未加固）对照路径 —— 只用于对拍与基准。
///
/// ─── 为什么把原生实现也编进这个 `.so` ───
///
/// 本 Lab 要交付的性能结论是「VMP 相对原生的代价」，所以需要一个**同机、
/// 同输入、同调用约定**的对照物。让 Kotlin 侧走另一个 `.so` 会引入
/// 「两次加载、不同优化等级」的噪声。这里的原生路径**不是**加固对象，
/// 它在 `.so` 里是正常的机器码 —— 这正是对拍时「一边看得见、一边看不见」的直观来源。
/// 原生（未加固）对照路径 —— 只用于对拍与基准。
///
/// ─── 校验口径必须与 VM 路径**完全一致** ───
///
/// 这几个函数先过一遍共享闸门（[`check_image_dim`] / [`check_blur_radius`]），
/// 再调 `imagepipeline`。看起来像多余的一步，但它决定了「逐位对拍」这个证据
/// 是否可信：若原生路径接受 VM 路径会拒绝的参数，那么「两边都成功且逐位一致」
/// 就只覆盖了输入域的交集，**差额部分完全没有被测到**。
/// 真实教训：blur 的半径上限一度只在 VM 侧生效，`1x1 r=2` 于是成为一条
/// 「原生成功 / VM 报错」的缝，而对拍测试当时是全绿的。
///
/// 同理，缓冲尺寸检查放在这里而不是 JNI 层：JNI 层的 `direct_bytes` 只保证
/// 「拿到一块 buffer」，**不保证它够大**。
//─────────────────────────────────────────────────────────────────────────────
pub mod native {
    use super::{check_blur_radius, check_image_dim, ERR_BAD_ARGUMENT};

    /// 原生主色调（`0x00RRGGBB`）。
    pub fn dominant(src: &[u8], w: u32, h: u32) -> Result<u32, i32> {
        let need = check_image_dim(w, h)?;
        if src.len() < need {
            return Err(ERR_BAD_ARGUMENT);
        }
        Ok(imagepipeline::dominant::dominant_color(&src[..need]).rgb & 0x00FF_FFFF)
    }

    /// 原生降采样。
    pub fn downscale(
        src: &[u8],
        sw: u32,
        sh: u32,
        dst: &mut [u8],
        dw: u32,
        dh: u32,
    ) -> Result<(), i32> {
        let src_need = check_image_dim(sw, sh)?;
        let dst_need = check_image_dim(dw, dh)?;
        if src.len() < src_need || dst.len() < dst_need {
            return Err(ERR_BAD_ARGUMENT);
        }
        imagepipeline::downscale::downscale_area(src, sw, sh, dst, dw, dh)
            .map(|_| ())
            .map_err(|_| ERR_BAD_ARGUMENT)
    }

    /// 原生盒式模糊。
    pub fn blur(
        src: &[u8],
        w: u32,
        h: u32,
        dst: &mut [u8],
        radius: u32,
    ) -> Result<(), i32> {
        let need = check_image_dim(w, h)?;
        if src.len() < need || dst.len() < need {
            return Err(ERR_BAD_ARGUMENT);
        }
        check_blur_radius(w, h, radius)?;
        imagepipeline::blur::blur_box(src, w, h, dst, radius)
            .map(|_| ())
            .map_err(|_| ERR_BAD_ARGUMENT)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn synth(w: usize, h: usize) -> Vec<u8> {
        let mut v = Vec::with_capacity(w * h * 4);
        for i in 0..w * h {
            v.extend_from_slice(&[
                ((i * 7) & 0xff) as u8,
                ((i * 13) & 0xff) as u8,
                ((i * 29) & 0xff) as u8,
                255,
            ]);
        }
        v
    }

    #[test]
    fn jni_path_matches_native_on_all_three_operators() {
        // 这条测试覆盖的正是 JNI 会走的代码：尺寸校验 → 装配 Memory → 启动 VM。
        // 它存在的意义：把「只能在真机上发现」的错误，压到本机秒级。
        let mut ex = VmExec::new();
        let src = synth(64, 48);

        let vm_color = ex.dominant(&src, 64, 48).unwrap();
        assert_eq!(vm_color, native::dominant(&src, 64, 48).unwrap(), "dominant");

        let mut d1 = vec![0u8; 16 * 12 * 4];
        let mut d2 = vec![0u8; 16 * 12 * 4];
        ex.downscale(&src, 64, 48, &mut d1, 16, 12).unwrap();
        native::downscale(&src, 64, 48, &mut d2, 16, 12).unwrap();
        assert_eq!(d1, d2, "downscale");

        let mut b1 = vec![0u8; 64 * 48 * 4];
        let mut b2 = vec![0u8; 64 * 48 * 4];
        ex.blur(&src, 64, 48, &mut b1, 3).unwrap();
        native::blur(&src, 64, 48, &mut b2, 3).unwrap();
        assert_eq!(b1, b2, "blur");
    }

    /// **两条路径必须接受完全相同的输入域。**
    ///
    /// 这是本项目真实踩过的坑留下的回归测试：blur 的 `radius > max(w,h)` 一度
    /// 只在 VM 路径生效，于是 `1x1 r=2` 在 VM 上报 `-1`、在原生上却成功 ——
    /// 而当时「逐位对拍」是全绿的，因为对拍只比「两边都成功」的交集。
    /// 结论：**逐位对拍必须配一条「同域」测试**，否则它给出的绿灯有盲区。
    #[test]
    fn both_paths_reject_the_same_inputs() {
        // (w, h, radius)：都在 VM 的正确性前提之外
        let bad_blur = [(1u32, 1u32, 2u32), (2, 1, 5), (3, 3, 4), (0, 4, 1)];
        for (w, h, r) in bad_blur {
            // 注意：这里只断言 blur —— `1x1`/`2x1` 对 dominant 与 downscale 是
            // **合法**输入（它们的限制只有 MAX_DIM 与缓冲大小），把它们也塞进
            // 「一律拒绝」会让测试自己先说错话。第一版就是这么写的。
            let mut d = vec![0u8; 64];
            assert_eq!(
                native::blur(&[0u8; 64], w, h, &mut d, r),
                Err(ERR_BAD_ARGUMENT),
                "native blur 必须拒绝 {w}x{h} r={r}"
            );
            let mut ex = VmExec::new();
            assert_eq!(
                ex.blur(&[0u8; 64], w, h, &mut d, r),
                Err(ERR_BAD_ARGUMENT),
                "VM blur 必须拒绝 {w}x{h} r={r}"
            );
        }
        // 边长超过 MAX_DIM：两条路径都要拒绝
        let big = vec![0u8; 8];
        let mut out = vec![0u8; 8];
        assert_eq!(native::dominant(&big, MAX_DIM + 1, 1), Err(ERR_BAD_ARGUMENT));
        assert_eq!(
            native::downscale(&big, 1, 1, &mut out, MAX_DIM + 1, 1),
            Err(ERR_BAD_ARGUMENT)
        );
        let mut ex = VmExec::new();
        assert_eq!(ex.dominant(&big, MAX_DIM + 1, 1), Err(ERR_BAD_ARGUMENT));
    }


    #[test]
    fn executor_reuses_work_buffer_across_calls() {
        // 复用工作缓冲是性能前提（见 `VmExec` 的注释）。这里钉住「复用之后
        // 结果依然正确」—— 因为不清零/不重置的复用正是经典的自读自写错误来源。
        let mut ex = VmExec::new();
        let src = synth(32, 32);
        let mut a = vec![0u8; 8 * 8 * 4];
        let mut b = vec![0u8; 8 * 8 * 4];
        ex.downscale(&src, 32, 32, &mut a, 8, 8).unwrap();
        ex.downscale(&src, 32, 32, &mut b, 8, 8).unwrap();
        assert_eq!(a, b, "复用缓冲不得让第二次调用得到不同结果");
    }

    #[test]
    fn rejects_oversized_and_bad_args() {
        let mut ex = VmExec::new();
        let src = vec![0u8; 16];
        assert_eq!(ex.dominant(&src, 0, 1), Err(ERR_BAD_ARGUMENT), "零宽");
        assert_eq!(ex.dominant(&src, MAX_DIM + 1, 1), Err(ERR_BAD_ARGUMENT), "超上限");
        assert_eq!(ex.dominant(&src, 100, 100), Err(ERR_BAD_ARGUMENT), "缓冲过小");
        let mut dst = vec![0u8; 16];
        // 累加器过大（dw*dh*16 > 32MB）
        assert_eq!(
            ex.downscale(&vec![0u8; 4], 1, 1, &mut dst, 2048, 2048),
            Err(ERR_BAD_ARGUMENT),
            "累加器超上限"
        );
        assert_eq!(ex.blur(&src, 2, 2, &mut dst, 999), Err(ERR_BAD_ARGUMENT), "半径过大");
    }

    #[test]
    fn selftest_and_integrity_pass() {
        let mut ex = VmExec::new();
        assert!(ex.selftest(), "VM 内建自检必须全绿");
        assert!(all_programs_intact(), "三个程序的容器头都必须完好");
        let s = hardening_status();
        assert!(s.contains("intact=true"), "状态串应报告完好: {s}");
    }

    #[test]
    fn corrupted_program_is_detected_by_header_check() {
        let p = vmp::find("blur").unwrap();
        let mut blob = p.blob.to_vec();
        blob[HEADER_LEN] ^= 0x01;
        assert!(!header_intact(&blob), "改一个字节就必须被头部校验抓到");
    }

    #[test]
    fn vm_path_decrypts_lazily() {
        // 尺寸较大的一次降采样：页数应远小于「整段常驻」的页数，
        // 但仍会随循环反复触达同一批页（缓存命中，不重复计数）。
        let mut ex = VmExec::new();
        let src = synth(256, 256);
        let mut dst = vec![0u8; 32 * 32 * 4];
        ex.downscale(&src, 256, 256, &mut dst, 32, 32).unwrap();
        let pages = ex.last_fetch_pages();
        let hits = ex.last_cache_hits();
        let blob = vmp::find("downscale").unwrap().blob.len();
        // 向上取整：最后一页不一定填满，用整除会少算一页（第一版就因此误报）
        let blob_pages = blob.div_ceil(vmp::vm::PAGE_SIZE) as u32;
        assert!(blob_pages > 1, "测试前提：程序应跨多页（实际 {blob_pages} 页）");
        // 断言两层含义：
        //   1. **每个页最多解密一次** —— 这正是「分页缓存」的定义。若缓存被去掉
        //      （或窗口小到反复抖动），这个数会随循环次数线性增长（实测曾到 15 万）。
        //   2. 命中次数远多于未命中 —— 说明解密确实被循环「摊薄」了，
        //      「惰性解密几乎免费」这句话才有数据支撑，而不是一句断言。
        assert!(
            pages <= blob_pages,
            "每个页最多解密一次：未命中={pages} 总页数={blob_pages}"
        );
        assert!(
            hits > pages * 10,
            "命中({hits}) 应远多于未命中({pages})，否则说明页窗口太小、解密成了瓶颈"
        );
    }
}
