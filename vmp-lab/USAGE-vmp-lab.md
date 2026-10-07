# VMP 加固 Lab 使用说明

一个可单独安装的 Demo App，把三个图像算子**加固成加密字节码**由自定义虚拟机执行，
并与未加固的原生实现**逐位对拍**。用来讨论「VMP 保护了什么、代价是多少」。

完整设计、实测数据与诚实的缺口见 [NOTES-vmp-lab.md](NOTES-vmp-lab.md)。

## 快速开始

```bash
# 编译（会顺带用 cargo 交叉编译出 arm64-v8a 的 libvmp_android.so）
./gradlew :vmp-lab:assembleDebug

# 安装（真机若开着「USB 安装需确认」，MIUI 需先在开发者选项里打开「USB 安装」）
adb install -r -t vmp-lab/build/outputs/apk/debug/vmp-lab-debug.apk

# 启动（自带 LAUNCHER，也可从桌面图标进）
adb shell am start -n com.interview.vmplab/com.interview.vmp.ui.VmpLabActivity
```

> ⚠️ 只编 **arm64-v8a**。x86 模拟器上 `native` 会显示不可用，属预期（Kotlin 侧安静降级，
> 不崩）。Apple Silicon 上的 arm64 模拟器可以直接跑。

## 界面

顶部是**加固状态**一行（`native=Rust abi=1 …` + VM 自检 + 程序头完好），
下面是四个按钮，最底下是 **monospace 证据面板**（可选中、可滚动，证据只留在本页）。

| 按钮 | 它回答的问题 | 你会看到 |
|---|---|---|
| **加固状态** | 「你到底保护了什么？」 | 每个程序的**密文长度**、容器头是否完好、VM 语义自检结果 |
| **逐位对拍** | 「加固后还算得对吗？」 | 三个算子 × 多尺寸，VM vs 原生逐字节比较的结论 |
| **A/B 对照** | 「两条路径的结果看起来一样吗？」 | 两个路径的结果位图 + 平均绝对误差（MAE） |
| **跑基准** | 「代价是多少？」 | 中位/min/p90 耗时、比值、VM 指令数、ns/指令；页缓存取证 |

若顶部显示 `native=False`，先解决它 —— 后面三个按钮都依赖 native。

## 建议的演示顺序（面试用）

1. **加固状态** —— 先给出「程序是密文」这个事实与它的尺寸（774/894/890 字节）。
   顺手把「明文头是刻意的取舍」讲掉：能看出「有个多大的程序」，看不到一条指令。
2. **逐位对拍** —— 红线是**逐字节相等**，不是「看起来一样」。
   重点讲 `8x8→3x2` 这个尺寸（历史 double-rounding 回归点）。
3. **A/B 对照** —— 让「看起来一样」变成「位与位一样」的对照；MAE 应恒为 0。
4. **跑基准** —— 落到 `~10 ns/指令`这个量级，并当场用「指令数 × 10ns」估算代价。
   然后指出**页缓存取证**那一行：解密 4 页 / 命中 1075 万次
   ⇒ 代价不在解密，在解释执行。

## 命令行跑测试（比 UI 更适合留证据）

```bash
# 装测试 APK（注意：ddmlib 失败时会卸载主 APK，所以要重新按顺序装）
adb install -r -t vmp-lab/build/outputs/apk/androidTest/debug/vmp-lab-debug-androidTest.apk

# 金标准：逐位对拍
adb shell am instrument -w -r -e class com.example.myapplication.VmpGoldenTest \
  com.interview.vmplab.test/androidx.test.runner.AndroidJUnitRunner

# 基准（结果在 System.out）
adb shell am instrument -w -r -e class com.example.myapplication.VmpBenchmarkTest \
  com.interview.vmplab.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d | grep -a "System.out"
```

宿主机上（不需要设备，最快）：

```bash
cd rust
cargo test -p vmp                              # 14 单测 + 7 对拍
cargo test -p vmp_android --release            # 10 单测（含「同域」测试）
cargo test -p vmp_android --release --test host_bench -- --ignored --nocapture   # 解释 vs 直接
```

## 两个已知的运行坑

1. **`gradlew connectedDebugAndroidTest` 在 MIUI 真机上会被拦**
   （`INSTALL_FAILED_USER_RESTRICTED`）。手动 `adb install` 两个 APK 再 `am instrument`。
2. **长基准会被系统冻结**（约 25 秒无界面 → `cached_apps_freezer` 把它放进
   `freezer` cgroup，主线程变 `D` 状态且 CPU 时间不涨，看起来像死锁）。
   跑之前先把 App 拉到前台；诊断用 `cat /proc/<pid>/cgroup` 看有没有 `/perf/frozen`。

## 看代码的入口

| 想知道 | 看哪儿 |
|---|---|
| 字节码长什么样、指令怎么编码 | [`rust/vmp/ISA.md`](../rust/vmp/ISA.md) |
| 加固流水线（生成 → 加密 → 嵌入） | [`rust/vmp/build.rs`](../rust/vmp/build.rs) |
| 解释器与分页惰性解密 | [`rust/vmp/src/vm.rs`](../rust/vmp/src/vm.rs) |
| 两个算子怎么变成字节码 | [`rust/vmp/src/algorithms/`](../rust/vmp/src/algorithms/) |
| 输入校验为什么必须两条路径共用 | [`rust/vmp-android/src/exec.rs`](../rust/vmp-android/src/exec.rs) |
| JNI 符号命名纪律 | [`rust/vmp-android/src/android_impl.rs`](../rust/vmp-android/src/android_impl.rs) |
