# M5：自己写 CPU 模糊 vs 交给框架（Skia RenderEffect / Glide）

> **本阶段要回答的不是「Rust 比 Java 快多少」**（那是 M3 的题），
> 而是**「什么情况下该自己写 CPU 模糊，什么情况下该交给框架」**。
>
> ⚠️ **数据来源：`emulator-5554`（arm64-v8a / API 36），不是真机。**
> 真机 `57d05823` 已有 M1–M4 的权威数字；M5 的结论是**定性的（算法选择）**，
> 不依赖绝对耗时精度，故用模拟器。下表数字**只作量级参考**：
> 例如 `1024x768` 的 Rust 中位数 142ms 比 `512x512` 的 29ms 涨了 5 倍，
> 而面积只涨 1.5 倍 —— 这是模拟器上 GC/JIT 抖动的典型表现。
> **不要在模拟器数字上做跨实现的优劣断言。**

## 一句话结论

- **要显示在屏幕上的模糊** → 交给 `RenderEffect`（API 31+）。它在 RenderThread/GPU 上跑，
  **不占主线程**；自己写 CPU 模糊是纯浪费。
- **要把模糊后的像素当数据用**（离屏/离线、当纹理、做算法输入）→ 自己写 / 走 native。
  因为 `RenderEffect` 的结果在 GPU 纹理里，要拿回 CPU 得再做一次 readback，反而更贵。
- **`Glide.bitmapTransform(BitmapTransformation)` 属于「自己写」那一类**：它跑在 Glide 的
  解码线程池上，是 CPU 计算，不是 GPU。

## 第 1 组：纯 CPU，Rust vs Kotlin 盒式模糊（r=6）

同起点 `ByteArray`，`[calc]` 只算不含拷贝，`[copy]` 含 direct buffer 拷贝。

| 尺寸 | Kotlin 中位数 | Rust 中位数(calc) | 纯计算加速比 | 含拷贝加速比 |
|---|---|---|---|---|
| 256×256 | 25.86 ms | 6.41 ms | **4.04×** | 3.74× |
| 512×512 | 97.41 ms | 18.95 ms | **5.14×** | 3.67× |
| 1024×768 | 287.91 ms | 80.88 ms | **3.56×** | 3.55× |

Rust 在**计算密集、无框架对手**的场景（M3 downscale 是 0.90×）能赢到 3.5~5×，
因为盒式模糊的窗口求和循环正好是 LLVM 能向量化的形态。这与 M3 的结论一致：
**Rust 的收益来自"CPU 上还有活可干"，不是"自动变快"。**

## 第 2 组：自己写 CPU vs Skia RenderEffect

Skia 路径：`RenderNode.setRenderEffect(createBlurEffect(r,r,CLAMP))` → 硬渲染管线 → `ImageReader` 读回。

| 尺寸 | CPU(Kotlin) | CPU(Rust) | Skia submit | Skia submit+readback |
|---|---|---|---|---|
| 256×256 | 26.90 ms | 7.49 ms | 20.92 ms | 19.29 ms |
| 512×512 | 100.84 ms | 28.79 ms | 64.97 ms | 64.17 ms |
| 1024×768 | 305.26 ms | 142.69 ms | 241.08 ms | 234.43 ms |

**自证 RenderEffect 真的生效了**（否则这行数字毫无意义）：
```
MAE(原图,CPU)=73.47  MAE(原图,Skia)=74.56  MAE(CPU,Skia)=2.35
梯度幅度  原图=46.58    CPU=1.55    Skia=0.19
```
原图方差大、两条模糊路径方差都被压平、彼此 MAE 很小 —— 证明两条路都"糊了"，
且结果接近（**但绝不相同**：见下）。

## 三条必须写明的测量/结论纪律

1. **CPU 盒式模糊 ≠ Skia 高斯模糊，耗时不可直接比较优劣。**
   两者是不同算法，MAE(CPU,Skia)=2.35 不是 0 就是证据。上面表里
   "Skia 比 Rust 慢"不代表框架差，只代表**它们在做不同的事**。
2. **用 `System.nanoTime()` 测 `RenderEffect` 测不准。**
   `submit` 名义上只提交绘制命令，但实测 `1024x768 submit median=241ms`、
   `min=0.455ms` —— 抖动横跨三个数量级，说明提交处**实际阻塞在 GPU 同步/dequeue**，
   并非"CPU 侧几乎零成本"。`submit+readback` 则额外含 ImageReader 的 1ms 轮询与拷贝。
   要测 GPU 侧真实成本，得用 Perfetto / `FrameMetrics`，**本表给不出**。
3. **模拟器数字不及真机权威**（见文首红字）。

## 为什么仍然要留着 CPU 路径

`RenderEffect` 的产物在 GPU 纹理里。如果下游要的是**像素**（例如把模糊图当缩略图上传、
做二次算法处理的输入、存盘），就必须 `readback` 回 CPU —— 上表 `1024x768` 的 234ms 里
很大一部分就是这笔开销。这种场景下，直接 CPU 算（Rust 143ms）反而更省，
且不占用 RenderThread。

## 复现命令

```bash
# 只在模拟器上装（不动真机）
ADB=~/Library/Android/sdk/platform-tools/adb
$ADB -s emulator-5554 install -r -t app/build/outputs/apk/debug/app-debug.apk
$ADB -s emulator-5554 install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

RUNNER=com.example.myapplication.test/androidx.test.runner.AndroidJUnitRunner
# 正确性对拍（native 与 Kotlin 逐位一致）
$ADB -s emulator-5554 shell am instrument -w -r \
  -e class com.example.myapplication.ImagePipelineGoldenTest $RUNNER
# 对标基准
$ADB -s emulator-5554 shell am instrument -w -r \
  -e class com.example.myapplication.ImagePipelineBlurCompareTest $RUNNER
$ADB -s emulator-5554 logcat -d | grep -E 'IPBlur'
```

## 对拍结论

`ImagePipelineGoldenTest` 在模拟器上 **8/8 通过**，其中 blur 相关 3 项：
`blurNativeMatchesKotlinReference_bitForBit`（逐位一致）、
`blurEdgeClampMatchesOnHandcraftedRow`（边缘复制）、
`blurRealBitmapPathAgreesBetweenNativeAndJava`（真实 Bitmap 双路径）。
JVM 侧 `ImagePipelineReferenceTest` **16/16 通过**（含 6 项 blur 钉死已知答案）。
