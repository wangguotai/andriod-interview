# 用 Rust 加速图像流水线：实测结论（M3 微基准）

> 全部数字来自**真机** Redmi K40（M2012K11AC，Android 12，arm64），
> `ImagePipelineBenchmarkTest`，暖机 30 次、计时 60 次取**中位数**。
> 运行方式见文末。**没有模拟器数字** —— 理由见最后一节。

## 0. 一句话结论（先给会被追问的那句）

**「换 Rust 就更快」是错的。同一台机器上：主色调提取 Rust 快 2.5×，
而区域降采样 Rust 反而慢 10%。** 真正让它划算的不是语言，是**架构**：
绕开 `ByteArray`↔`direct buffer` 的廉价拷贝后，降采样端到端反超 1.37×。

这三句话必须一起说。只报其中一句，就会得出方向相反的优化结论。

## 1. 三个层次的数字（必须分开看，混在一起就会互相污染）

### 1.1 纯计算（计时区间只有 JNI 调用，不含任何拷贝）

| 算法 | 尺寸 | Java 中位数 | Rust 中位数 | 加速比 |
|---|---|---|---|---|
| **主色调** | 64×64 | 0.135 ms | 0.049 ms | **2.76×** |
| | 256×256 | 1.948 ms | 0.761 ms | **2.56×** |
| | 512×512 | 7.688 ms | 3.038 ms | **2.53×** |
| **降采样** | 512×384→128×96 | 0.595 ms | 0.674 ms | **0.88×** |
| | 1024×768→256×192 | 2.449 ms | 2.718 ms | **0.90×** |
| | 2048×1536→512×384 | 9.761 ms | 10.860 ms | **0.90×** |

**读法**：
- 主色调是**稳定、不随尺寸衰减**的 2.5× 收益 —— 这是真正的语言级胜利。
- 降采样是 **0.9×，即更慢**，而且从 512×384 到 2048×1536 一路都是 0.9×。
  这不是噪声（p90 抖动只有个位数百分比），是稳定的结构性差异。

### 1.2 端到端（走 `ImagePipelineBridge` 真实路径）

| 算法 | 尺寸 | Java | Rust | 加速比 |
|---|---|---|---|---|
| 降采样 | 1024×768→256×192 | 4.619 ms | 3.368 ms | **1.37×** |
| 降采样 | 2048×1536→512×384 | 18.357 ms | 13.232 ms | **1.39×** |
| 主色调 | 512×512 | 6.730 ms | 3.191 ms | **2.11×** |

**注意降采样这两行的反转**：纯计算是 0.90×（更慢），端到端却是 1.37×（更快）。
原因不在算法，在**数据通路**：

- Java 参考实现拿不到 direct buffer（JNI 只接受 direct），
  bridge 里必须先 `direct → ByteArray` 拷一次，结果再拷回去；
- native 直接在调用方的 direct buffer 上就地读写，**零拷贝**。

也就是说：**Rust 赢的是「不用把像素搬来搬去」，不是「算得更快」。**
把这条讲清楚，比报一个漂亮倍数有价值得多 —— 因为它直接指出下一个该优化的地方：
让上游（解码）直接产出 direct buffer，而不是先有 Bitmap 再倒出来。

### 1.3 JNI 固定开销与「小图不划算」的阈值

| 项 | 数值 |
|---|---|
| 单次 JNI 往返（1×1→1×1） | **≈ 3.5 µs** |
| 16×16 降采样：Java vs Rust | 0.001 ms vs 0.009 ms（**0.12×**） |

**结论：小图走 native 是负收益。** 16×16 时算法本身只要 ~1µs，
而一次 JNI 往返就要 3.5µs，开销是计算的 3 倍以上。
所以「一律走 native」是错的；要么设尺寸阈值，要么把小图批量合并成一次调用。

## 2. 为什么降采样器（native）没赢 —— 以及我试过什么

这是本次最有价值的「失败」，值得完整记录。

### 2.1 第一版：朴素二维块求和 → 纯计算 0.79~0.81×

对每个目标像素遍历它覆盖的 k×k 源块。渐近复杂度没问题（O(src)），
但内存访问模式差：内层在 k×k 块里跳，步进 4 字节、行跨 `src_w*4`，
每个目标像素要跳 4 行、每行只取 16 字节，缓存行利用率低。

### 2.2 第二版：可分离两趟（先水平后垂直）→ **破坏了逐位对拍**

两趟更缓存友好，代码也更短。但中间图必须存成 `u8`，于是「求平均」被做了
**两次**（每趟各舍入一次）。实测 8×8→3×2 就出现 `native=120 vs 参考=119`。

**这个失败比它看起来重要**：它不是「精度差不多」，而是**直接违反验收红线**
（native 与参考实现必须逐字节相等）。而且它是**对拍测试抓出来的**，
不是靠肉眼 —— 这正是 M2 那道防线的价值。
结论：**在要求逐位一致的场合，不要引入中间量化。**

### 2.3 第三版（当前）：单趟累加、只舍一次、按输出行重排 → 0.88~0.90×

每个输出行维护 `dst_w×4` 个 `u64` 累加器，扫完它覆盖的所有源行后才做一次
`/count`，与参考实现的运算顺序完全一致 → 逐位相等恢复（对拍 10/10 绿）。
内层仍是从左到右顺序读。比第一版好约 10%，但**仍然没赢过 Kotlin**。

### 2.4 为什么赢不了（诚实的推测，非定论）

两个实现都已接近「每源像素 4 次 u8 加法」的下限，瓶颈不在语言而在
**没有向量化**：4 个通道各有独立累加器、按 stride-4 交错读取，
LLVM 没有把它识别成可向量化的归约；而 ART 的 JIT 对同样结构的
`ByteArray` 循环优化得相当好（边界检查被消除、int 局部变量直接进寄存器）。
**单就这个循环，JIT 与 LLVM 打平。**

## 3. 这对「什么时候该上 Rust」的启示

1. **算得多、搬得少 → 收益大**（主色调：一遍扫描、统计、无中间缓冲，2.5×）。
2. **算得少、搬得多 → 收益小甚至为负**（降采样在小图上被 JNI 与拷贝吃掉）。
3. **真正的大头往往是数据通路，不是语言**：降采样端到端从 0.9× 翻到 1.37×，
   靠的是消除一次直接缓冲↔数组的往返，而不是把循环写得更聪明。
4. **必须设小图阈值**：< 约 32×32 的输入，native 固定开销占主导。

## 4. 方法论备忘（做性能结论时的三条纪律）

- **中位数，不是均值**：GC 停顿与 CPU 调频会制造离群点，均值会被单次 STW 拉偏。
- **同时看 min 与 p90**：min 是「最好能多快」，p90 是「抖动多大」。
  只看中位数会漏掉「中位数不错但偶尔卡 10 倍」的实现。
- **绝不用模拟器下结论**：本次在 API 36 arm64 模拟器上测到同一份 Java 代码
  512×384 用 16ms、而 2048×1536 只用 3ms —— 自相矛盾，是模拟器 JIT/宿主机
  调度的假象。真机上两个尺寸分别是 0.6ms / 9.8ms，比例正常。
  与 `tools/perfetto/INTERVIEW-perfetto.md` 的结论一致：**性能结论必须真机复验**。

## 5. 复现方式

```bash
# 真机（先确保安装不被拦：adb shell settings put global verifier_verify_adb_installs 0）
ANDROID_SERIAL=<serial> ./gradlew :app:connectedDebugAndroidTest -x lint \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.myapplication.ImagePipelineBenchmarkTest

# 读结果
adb logcat -d | grep IPBench
```

对拍（跨语言逐位一致）：
```bash
./gradlew :app:connectedDebugAndroidTest -x lint \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.myapplication.ImagePipelineGoldenTest
```

---

# M4：接入瀑布流 bind 链路 + 端到端复测

> 真机 Redmi K40，`tools/perfetto/` 工具链，往返滚动 16s。

## 做了什么

把 Rust **主色调**算子接成瀑布流卡片的「占位底色」
（`PlaceholderPalette` → `WaterfallAdapter` → `item_image_card.xml` 的 `v_palette`）。
选它的理由直接来自 M3：它是唯一稳定大赢的算子（2.5×），且不随尺寸衰减。

真机日志确认走的是 native：
```
ImageLab: 占位色 id=ii-0-0 color=#001D78 path=rust 耗时=14.40ms
ImageLab: 占位色 id=ii-0-2 color=#A27900 path=rust 耗时=7.75ms
...
```
每次 3~14ms（含 JNI 与 Bitmap 读像素），与 M3 的 512×512 ≈ 5ms 量级一致。

## 端到端结论：jank **没有改善**（13 → 14 帧，即持平）

| | App Deadline Missed | Buffer Stuffing |
|---|---|---|
| 对照组（占位色关） | **13** | 7 |
| 实验组（占位色开） | **14** | 29 |

**这个「没改善」是预期内的，而且它本身就是 M4 要交付的结论。**
按本仓库 `INTERVIEW-perfetto.md` 的方法论，同一个 trace 里 app 主线程最耗时的
slice 是 `traversal`(1086ms) / `inflate`(164ms) / `RV Prefetch`(287ms) /
`RV OnLayout`(127ms) —— 滚动卡顿由**布局与预取**主导，不是像素运算。
把像素运算换成更快的实现，自然不会移动这条曲线上的数字。

**如果这里报出「jank 从 13 降到 5」，那才是需要怀疑的**：它多半意味着
测量过程被别的东西污染了（例如两次抓取时的滚动幅度不同、缓存冷热不同）。

## 但它确实带来两件事（这两件是可以证的）

1. **线程归属正确**：trace 里 `rustDominantColor` 共 9 次，**全部**落在
   `app-cpu-1..7`（`ThreadPools.cpu` 泳道），主线程命中 **0** 次。
   总耗时 ≈ 69ms / 16s，摊在 7 条泳道线程上。
   ```sql
   SELECT t.name, t.is_main_thread, COUNT(*), SUM(s.dur)/1e6
   FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread t ON tt.utid=t.utid
   WHERE s.name='rustDominantColor' GROUP BY t.utid;
   ```
   —— 这回答了 native 接入最容易被质疑的问题：「你是不是把重活挪回主线程了？」
2. **观感**：图片**再次出现**时（滑走再滑回、或 recycle 后重新 bind）立刻铺底色，
   不再闪一下灰底。

## 必须写明的边界（否则这个功能会被误解）

**首次加载时没有占位色。** 因为主色调的来源就是「已解码的位图」——
图没解码就没有像素可算，也就不可能先有颜色。要让首屏就有色，得让上游先给一张
极小图（缩略图 / BlurHash / ThumbHash），那是另一条链路，**不在本次范围内**。

所以本功能的准确描述是：**「重复出现时的观感改善」，不是「首屏加载更快」。**

## 复现方式

```bash
# 两态对照（脚本自动：起 perfetto → 启页面 → 切换开关 → 往返滚动 → 拉回 trace）
SERIAL=<serial> tools/perfetto/m4-placeholder-compare.sh on  tools/perfetto/out/m4-on.perfetto
SKIP_TAP=1 SERIAL=<serial> tools/perfetto/m4-placeholder-compare.sh off tools/perfetto/out/m4-off.perfetto

# 归因
python3 tools/perfetto/analyze_trace.py tools/perfetto/out/m4-off.perfetto --pkg com.example.myapplication
```

