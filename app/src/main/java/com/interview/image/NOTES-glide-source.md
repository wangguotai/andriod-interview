# Glide 4.12 降采样算法核对笔记（源码级，非记忆）

> 来源：`glide:4.12.0:sources`，从 aliyun maven 镜像拉取后解包核对。
> 相关文件：
> - `com/bumptech/glide/load/resource/bitmap/Downsampler.java`
> - `com/bumptech/glide/load/resource/bitmap/DownsampleStrategy.java`

## 1. 真实调用链

```
Downsampler.decode()            // 两趟：第一趟 inJustDecodeBounds 读元信息
  └─ Downsampler.decodeFromWrappedStreams()
       ├─ ic.getImageType()                        // 嗅探 JPEG / PNG / WEBP
       ├─ calculateScaling(...)                    // ← 就在这一步算出 inSampleSize
       ├─ calculateConfig(...)
       └─ decodeStream(...)                        // 真正解码
```

`Downsampler.java:300` 附近先决定 `targetWidth/targetHeight`，其中关键的一句是
`requestedWidth == Target.SIZE_ORIGINAL ? sourceWidth : requestedWidth`
—— 这就是 `override(SIZE_ORIGINAL)` 会打穿降采样的代码级原因。

## 2. calculateScaling 的真实步骤（Downsampler.java:420~545）

```java
float exactScaleFactor = strategy.getScaleFactor(srcW, srcH, targetW, targetH);

SampleSizeRounding rounding = strategy.getSampleSizeRounding(...);

int outWidth  = round(exactScaleFactor * srcW);   // round(v) = (int)(v + 0.5)
int outHeight = round(exactScaleFactor * srcH);

int widthScaleFactor  = srcW / outWidth;          // 整数除法
int heightScaleFactor = srcH / outHeight;

// ★ 关键：QUALITY → min（宁可大一点，避免糊）
//         MEMORY  → max（宁可小一点，省内存）
int scaleFactor = (rounding == MEMORY)
    ? Math.max(widthScaleFactor, heightScaleFactor)
    : Math.min(widthScaleFactor, heightScaleFactor);

int powerOfTwoSampleSize = Math.max(1, Integer.highestOneBit(scaleFactor));

// ★ MEMORY 模式下还会再兜一刀：如果采样后仍小于目标，就再翻倍
if (rounding == MEMORY && powerOfTwoSampleSize < (1.f / exactScaleFactor)) {
    powerOfTwoSampleSize = powerOfTwoSampleSize << 1;
}
options.inSampleSize = powerOfTwoSampleSize;
```

## 3. 各策略的 scale / rounding（DownsampleStrategy.java）

| 策略 | getScaleFactor | rounding | 取值方向 |
|---|---|---|---|
| `DEFAULT` = `CENTER_OUTSIDE`（**ImageView centerCrop 就是这个**） | `max(rw/sw, rh/sh)` | QUALITY | 铺满，采后 ≥ 目标 |
| `AT_LEAST` | `1 / highestOneBit(min(sh/rh, sw/rw))`（**已是整数除法**） | QUALITY | 采到 min 边 ≥ 目标（注：Glide 核心代码里**从未使用**该策略，仅声明） |
| `AT_MOST` | `1 / highestOneBit(max(sh/rh, sw/rw))` | MEMORY | 采到 max 边 ≤ 目标 |
| `FIT_CENTER`（`fitCenter` 默认） | `min(rw/sw, rh/sh)` | QUALITY | 装进去 |
| `CENTER_INSIDE` | `min(1, FIT_CENTER)` | 同上 | 不放大 |
| `CENTER_OUTSIDE` | `max(rw/sw, rh/sh)` | QUALITY | 铺满 |

> 结论：用户原稿里那条「对每个维度分别 floor(src/req) 取较小者再向下取 2 的幂」
> 对 **CENTER_OUTSIDE / FIT_CENTER + QUALITY 的默认图片链路是成立的**
> （因为两者都走 QUALITY→取 min→highestOneBit，结果与逐维 floor 取小等价），
> 但它**不是** Glide 的通用算法。真正的通用算法是上面第 2 节那条：
> 先由 strategy 给浮点 scale → 反算 out 尺寸 → 再整数除 → 再按 rounding 取
> min/max → 最后才 `highestOneBit`。**rounding 的 min/max 才是「逼近目标尺寸」
> 的真正机制**，而 MEMORY 模式那记 `<<1` 兜底是查询里几乎没人提到的细节。
>
> 顺带纠一个高频错误说法：网上常写「centerCrop 时 Glide 选 AT_LEAST」。
> 错。`DownsampleStrategy:87` 写着 `DEFAULT = CENTER_OUTSIDE`，
> `BaseRequestOptions:722` 写着 `centerCrop() → CENTER_OUTSIDE`，
> 而 `AT_LEAST` 在 Glide 核心代码里从未被引用。
>
> 还有两个真实陷阱：
> - `BaseRequestOptions` 里方法名是 `downsample(strategy)`，**不是** `downsampleStrategy(...)`。
> - `downsample()` 会覆盖 transform 路径设过的策略，但后调的 `centerCrop()`/`fitCenter()`
>   又会把它盖回去 —— 谁在后面谁生效，别混用。
> - `clearMemory()` 在 `Glide` 类上（且内部 `assertMainThread()`），
>   不在 `RequestManager` 上。

## 4. inSampleSize 的除法在各解码器上取整方向不同（Downsampler.java:516 起）

Glide 源码里带注释说明「已在 Android 15~26 的模拟器上实测过」：

| 格式 | 取整方式 |
|---|---|
| JPEG | 向上取整（libjpegturbo，且 sample 上限 8）；超过 8 的部分再走 skia 用整数除法（向下）二次缩放 |
| PNG | 一律向下取整 |
| WEBP | N（API 24）之前向下取整；N 及之后四舍五入 |

JPEG 的精确复刻：

```java
int nativeScaling = Math.min(powerOfTwoSampleSize, 8);
powerOfTwoWidth  = (int) Math.ceil(orientedSourceWidth  / (float) nativeScaling);
int secondaryScaling = powerOfTwoSampleSize / 8;
if (secondaryScaling > 0) {
    powerOfTwoWidth = powerOfTwoWidth / secondaryScaling;   // 整数除法
}
```

> 所以 `inSampleSize = 4` 配 JPEG，解码结果**不是**简单的 `src / 4`，
> 而是 `ceil(src / 4)`。教学 demo 里如果拿真实 Bitmap 的 `width` 和
> 「手算的 src/4」对不上，不是 bug，就是这段。

## 5. 其它可讲的源码细节

- `BitmapFactory.Options` 的 `inJustDecodeBounds=true` **同样遵守 inSampleSize**
  （`Downsampler.java:550` 附近注释明确写了）。所以 bounds 也能量出降采样后的尺寸。
- `inSampleSize` 非 2 的幂时，framework 会向下取到 2 的幂 —— 所以 Glide 自己
  先用 `highestOneBit` 算好，不把这个不确定性留给 framework。
- pre-KitKat（< API 19）复用 `inBitmap` 时要求尺寸**完全一致**，所以那段代码里
  有 `options.inSampleSize == 1 || isKitKatOrGreater` 的条件。
- API <= 23 的 wbmp 不支持降采样（b/27305903），Glide 直接 `powerOfTwoSampleSize = 1`。

## 6. 教学复刻式的等价性验证（穷举，非"感觉等价"）

`DownsampleKit.calculateInSampleSize` 用的是简化式：

```kotlin
sample = highestOneBit( max(1, min(srcW / reqW, srcH / reqH)) )   // 逐维 floor → 取 min → 2 的幂
```

把它与 Glide 4.12 的真实链路（`CENTER_OUTSIDE` + `QUALITY`，即 `centerCrop()` 的路径）
做穷举对照：

| 范围 | 组数 | 不一致 |
|---|---|---|
| srcW/srcH ∈ [600, 3200] step 137/149；req ∈ {60,90,120,180,240,360,540,720,1080} | 27,702 | **0（0.0000%）** |

结论：**对默认图片链路，简化式与 Glide 逐位等价**，教学复刻不是"近似"。
（这也解释了为什么网上那条简化算法流传这么广 —— 它在默认路径上确实成立；
它失效的是 `AT_MOST` / `MEMORY` 与 JPEG 取整那几个分支。）

### 首屏样例的真实数字（可直接当课堂例题）

```
原图 2000 × 2500，目标 180 × 180
  逐维 floor: 2000/180 = 11, 2500/180 = 13  →  取 min = 11
  2 的幂:   highestOneBit(11) = 8
  →  inSampleSize = 8，解码 250 × 312

  全尺寸:  2000×2500×4 = 19.07 MB
  解码后:   250× 312×4 =  0.30 MB      ← 约 1/64
  显示占用: 180× 180×4 =  0.12 MB      ← 三态对照里恒定的那一项
```

## 7. 本 demo 的教学取舍

- **不做反射偷 Glide 内部 `options.inSampleSize`**。理由：`BitmapFactoryDecoder` 里
  那个 `Options` 是局部变量，没有稳定可依赖的钩子，反射会随版本碎掉，
  教学 demo 不该建立在这种脆弱假设上。
- 改为双通道取信：
  1. **瀑布流页（走 Glide）**：用 `RequestListener.onResourceReady(bitmap)` 拿
     **真实解码出的 Bitmap** —— `width/height/byteCount` 是 ground truth，
     足以反证 inSampleSize 确实生效了。
  2. **解剖台页（走裸 BitmapFactory）**：我们自己控制 `Options`，
     可以逐行打印真实的 `options.inSampleSize` 与推导过程。
  两页互为对照，比反射更稳，也更能讲清因果关系。
