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

### 6.1 首屏样例的真实数字（可直接当课堂例题）

```
原图 2000 × 2500，目标 180 × 180
  逐维 floor: 2000/180 = 11, 2500/180 = 13  →  取 min = 11
  2 的幂:   highestOneBit(11) = 8
  →  inSampleSize = 8，解码 250 × 312

  全尺寸:  2000×2500×4 = 19.07 MB
  解码后:   250× 312×4 =  0.30 MB      ← 约 1/64
  显示占用: 180× 180×4 =  0.12 MB      ← 三态对照里恒定的那一项
```

## 7. RecyclerView 为什么要拦截 `requestLayout()`，以及完整的三阶段流程

> 需求来源：读懂 1.3.2 源码里这段覆写到底在防什么，并给出 measure → layout → draw 的全链路。
> 源码位置：`androidx/recyclerview/widget/RecyclerView.java`（1.3.2）、
> `android/view/View.java`、`android/view/ViewRootImpl.java`（API 36）。

### 7.1 两套互相独立的"拦截"，别混为一谈

| 机制 | 字段 / 位置 | 它吞掉的是 |
|---|---|---|
| **A. RecyclerView 自我批处理** | `mInterceptRequestLayoutDepth`、`mLayoutWasDefered`（`RecyclerView.java:493~535`，覆写点 `4925`） | RV **内部主动**在跑 layout/scroll 时，子 View 发来的 `requestLayout()` |
| **B. framework 的"布局中再请求布局"** | `View.mPrivateFlags & PFLAG_FORCE_LAYOUT`（`View.java:28311`）、`ViewRootImpl.mLayoutRequesters` / `mHandlingLayoutInLayoutRequest`（`ViewRootImpl.java:5040`、`5078`） | 任何 View 在 `ViewRootImpl` 布局遍历中发出的 `requestLayout()` |

两者独立。第 9.1 节那个**静默失败**是 B 在起作用（实测 `mInterceptRequestLayoutDepth == 0`），
A 只会在 RV 自己的 layout/scroll 进行中触发。

### 7.2 `RecyclerView.requestLayout()` 覆写的真实意图

```java
// RecyclerView.java:4925
public void requestLayout() {
    if (mInterceptRequestLayoutDepth == 0 && !mLayoutSuppressed) {
        super.requestLayout();
    } else {
        mLayoutWasDefered = true;   // 只记账，不往上传
    }
}
```

`startInterceptRequestLayout()` / `stopInterceptRequestLayout(boolean)`
（定义 `2425` / `2442`）成对包裹住**任何会诱使子 View 发 `requestLayout()` 的临界区**：

```java
void startInterceptRequestLayout() {
    mInterceptRequestLayoutDepth++;
    if (mInterceptRequestLayoutDepth == 1 && !mLayoutSuppressed) {
        mLayoutWasDefered = false;
    }
}
void stopInterceptRequestLayout(boolean performLayoutChildren) {
    ...
    if (!performLayoutChildren && !mLayoutSuppressed) mLayoutWasDefered = false; // 丢弃
    if (mInterceptRequestLayoutDepth == 1) {
        if (performLayoutChildren && mLayoutWasDefered && !mLayoutSuppressed
                && mLayout != null && mAdapter != null) {
            dispatchLayout();                 // ← 攒够了，在这里补一次
        }
        if (!mLayoutSuppressed) mLayoutWasDefered = false;
    }
    mInterceptRequestLayoutDepth--;
}
```

**为什么要这样：**

1. **防重入 / 防丢更新**。一次 `dispatchLayoutStep2()` 里要 bind + `addView` 几十个 child，
   每个 child 上树都可能触发 `requestLayout()`。若逐个往上抛，会在**同一帧内**触发
   嵌套布局；若不抛又不记账，这一批数据变更就彻底丢了。`mLayoutWasDefered` 就是那个「记账本」：
   攒到临界区结束，`stopInterceptRequestLayout(true)` 时**合并成一次** `dispatchLayout()`。
2. **顺带把 measure 也管住**。`onMeasure()` 里调 `mLayout.onMeasure(...)` 时同样会走这套
   （`4048/4061` auto-measure 分支、`4077/4079` 自定义 onMeasure 分支），
   避免 measure 期间的长老链重入。
3. **scroll 期间只记账不重排**。`scrollStep()`（`2038`/`2057`）用 `false` 结束，
   因为滚动过程中的请求应由滚动本身处理，不该再起一次完整 layout。

`performLayoutChildren` 这个参数是关键开关：**只有 `true` 才补 `dispatchLayout()`**。
全库用到 `true` 的只有两处 —— `consumePendingUpdateOperations()`（`2101`，滚动前消费挂起的更新）
和 `removeAnimatingView()`（`1603`，当真的移除了动画 View 时）。
`dispatchLayoutStep1/2/3` 自己（`4622`/`4653`/`4738`）用的都是 `false`：
**布局步骤内部产生的请求一律丢弃**，因为这次布局马上就会读取最新数据，不需要另起一次。

### 7.3 measure → layout → draw 全链路

```
ViewRootImpl.performTraversals()
 ├─ performMeasure()  → host.measure(...)
 │     └─ RecyclerView.onMeasure()                      // 3989
 │          ├─ 无 LayoutManager → defaultOnMeasure()    // 立即返回
 │          ├─ isAutoMeasureEnabled()（Linear/Grid/Staggered 默认 true）
 │          │     ├─ 先跑 mLayout.onMeasure(...)       // 让 LM 记下 spec
 │          │     ├─ mLastAutoMeasureSkippedDueToExact = (宽高都 EXACTLY)  // 40-45
 │          │     ├─ 若 EXACTLY（或 mAdapter == null）→ return；          // 核心：跳过子 View 测量
 │          │     ├─ mState.mLayoutStep == STEP_START → dispatchLayoutStep1()  // 4542
 │          │     ├─ mState.mIsMeasuring = true
 │          │     ├─ dispatchLayoutStep2()              // 4631 ← 真正 bind + 摆放 child
 │          │     ├─ mLayout.setMeasuredDimensionFromChildren(...)
 │          │     └─ shouldMeasureTwice() → 再来一遍 step2
 │          └─ 否则（自定义 onMeasure）
 │                ├─ mAdapterUpdateDuringMeasure → startIntercept…处理更新…stopIntercept(false)
 │                └─ startIntercept() → mLayout.onMeasure() → stopIntercept(false)   // 4077/4079
 │
 ├─ performLayout()  → host.layout(0,0,mw,mh)          // ViewRootImpl:5059，mInLayout = true
 │     └─ RecyclerView.onLayout()                       // 4917
 │          └─ dispatchLayout()                         // 唯一的驱动入口
 │               ├─ STEP_START  → step1(); setExactMeasureSpecsFrom(this); step2()
 │               ├─ 有待处理更新 / 尺寸变了 → step2()   // onMeasure 里跑过，这里补
 │               └─ dispatchLayoutStep3()               // 4662
 │     [回调 View.layout 返回后] mInLayout = false；复查 mLayoutRequesters
 │
 └─ performDraw()      → host.draw(canvas)
       └─ RecyclerView.draw()  // 4943：super.draw() → ItemDecoration.onDrawOver()
       └─ RecyclerView.onDraw()// 5004：ItemDecoration.onDraw()
```

`dispatchLayoutStep3`（`4662`）只做「记录动画首末状态 + 触发动画 + 收尾」，
**不会**重新 bind 或重新测量 —— 这正是 9.1 那个坑里「step2 已经跑完就再没人管新数据」的由来。

三个 `State.mLayoutStep` 常量把一次完整布局**拆到 measure 与 layout 两次遍历里**：
`STEP_START → dispatchLayoutStep1 → STEP_LAYOUT → dispatchLayoutStep2 → STEP_ANIMATIONS
→ dispatchLayoutStep3 → STEP_START`。所以看到「RV 在 onMeasure 里就把 child 排好了」
不是 bug，是 auto-measure 的设计：先测出内容尺寸，再由父容器决定最终大小。

### 7.4 `mLastAutoMeasureSkippedDueToExact` 与 `dispatchLayout()` 的补偿

若宽高都是 `EXACTLY`（比如 `match_parent` 且父容器给死），`onMeasure` 直接 return，
**跳过子 View 测量**（省一次无用功）。代价是 step1/step2 没跑，于是 `onLayout → dispatchLayout()`
要补上（见 7.3 第二个分支）。

如果 onMeasure 当时是非 EXACTLY（我们瀑布流就是 `MATCH_PARENT×MATCH_PARENT` 但对齐方式可能给
`AT_MOST`），已经跑过 step1/step2；只要尺寸一致、也没有新更新，`dispatchLayout()` 就**只跑 step3**。
这就是「onMeasure 里 childCount=0、onLayout 后突然出现 19 个 child」的原因：child 是在
**measure 阶段**的 step2 里生出来的。

## 8. 实测纠正：Glide 最终交付的位图尺寸**精确等于 target**（两段式缩放）

网上（包括本项目早期注释）流传一个说法：「因为 inSampleSize 只能取 2 的幂，
所以解码尺寸会是目标的 1~2 倍」。**在 API 36 模拟器上实测证伪了这个说法。**

Glide 的缩放是**两段式**的：

1. **第一段**：`inSampleSize` 粗采样，结果偏大（幂次限制）
2. **第二段**：`BitmapFactory` 的 density 缩放，精确缩到 target

实测数据（`WaterfallAdapter` 卡片徽标上显示的原始数字）：

```
原图 3068 × 2012，目标 515 × 338
  CENTER_OUTSIDE: scale = max(515/3068, 338/2012) = 0.167992
                  out   = round(scale × src)      = 515 × 338
  scaleFactor  = min(3068/515, 2012/338) = min(5, 5) = 5
  inSampleSize = highestOneBit(5) = 4            ← 第一段只能到 4

  若只有第一段：3068/4 × 2012/4 = 767 × 503 = 1.47 MB
  实测最终交付：515  × 338     = 0.66 MB        ← 第二段又缩掉一半
```

结论：
- 徽标上的「÷N」是**两段叠加**的结果，不能读作 `inSampleSize`。
- 想单独观察 `inSampleSize` 这一段，必须用「解剖台」页（裸 BitmapFactory，无第二段）。
- 三态对照页用裸 BitmapFactory，所以它的 `inSampleSize` 数字是**第一段的真实值**，
  与瀑布流页的「÷N」口径不同 —— 这不是矛盾，是两段的差别。

> 另外仍成立的两条（第 5 节已证）：`DEFAULT = CENTER_OUTSIDE`（非 AT_LEAST，
> `AT_LEAST` 在 Glide 核心代码里从未被引用）；`downsample()` 与 `centerCrop()`
> 会互相覆盖，谁在后谁生效。

## 9. RecyclerView 时序实测：两个不报错的静默失败

这两个都是真机/模拟器实测出来的，不是推断。

### 9.1 在 `doOnLayout` 里首次 `submit` → 永远空白

```
doOnLayout { adapter.submit(...) }        // isInLayout = true  → childCount=0，onBind 0 次
doOnLayout { post { adapter.submit(...) } } // isInLayout = false → childCount=19，onBind 19 次
```

原因：`doOnLayout` 回调发生在 **layout 遍历过程中**，此时 `View.isInLayout == true`。

> ⚠️ 这里我第一版写错过成因：**不是** `mInterceptRequestLayoutDepth > 0`。
> 埋点实测该值为 **0**，真正被吃掉的是 `View.requestLayout()` 里的
> `PFLAG_FORCE_LAYOUT`（见第 7 节）。下面这行日志是证据：
>
> ```
> >>> submit   isLaidOut=false isLayoutRequested=true isInLayout=true
>             mInterceptRequestLayoutDepth=0
>   >> RV.requestLayout() 进入  isInLayout=true mInterceptRequestLayoutDepth=0
> [[submit 后下一帧]]          childCount=0
> ```

机制（完整推导见第 7 节）：
1. 回调在 `ViewRootImpl.performLayout()` 内部，`mInLayout=true`；
2. `notifyDataSetChanged()` → `AdapterHelper` 置待处理更新 → 调用 RV 覆写的
   `requestLayout()`；此时 depth==0，**照常调用 `super.requestLayout()`**
   （实测 `isLayoutRequested=true`）；
3. 但 `View.layout()` 一定在 `onLayout(...)` 返回后才清 `PFLAG_FORCE_LAYOUT`，
   而 `ViewRootImpl` 复查待处理请求者时**只认这个位** → 新一轮请求被视为"已经被处理"，
   第二遍 layout 遍历被跳过，什么都没发生；
4. 「没被调度的数据变更」要等下次触发布局才由
   `dispatchLayout() → dispatchLayoutStep2()` 消费 —— 而这里**永远没有下一次**。
   于是永久空白：adapter 有 20 条，界面上一个 child 都没有。

反过来说，这也解释了两条实用结论：
- 布局期间改数据应走 `setAdapter` / `post`（下一帧），或至少调一次真正的
  `requestLayout()`（不经过 RK 的吞并路径）来补一次调度；
- 这个坑**不是 RecyclerView 独有的**：任何在 `onLayout` 里 `notifyXxx` 的 AdapterView
  都会丢这一帧。RV 的特殊之处只在于它的 `dispatchLayoutStep3` 之后不再补布局。

### 9.2 `onBindViewHolder` 里从 `itemView.parent` 取宽度 → 恒为 0

```
onBindViewHolder pos=0  itemView.parent=null
onBindViewHolder pos=1  itemView.parent=null
```

RecyclerView 的 `tryGetViewHolderForPositionByDeadline` 是**先 bind、后 addView**，
所以 bind 阶段 `itemView.parent == null`。用 `itemView.parent as? RecyclerView` 取宽度
会静默拿到 0 → 目标尺寸退化成 1px → 每张卡被压成一条线，且高度参差全丢
（所有 height 都被 `coerceIn` 到同一个最小值）。

正解：宽度由 Activity 在 `doOnLayout`（onLayout 之后）量好，显式喂给 adapter。

## 10. 端到端实测：对照组真的把降采样架空了（数字对比）

在 API 36 模拟器上跑通完整交互后的**原始读数**（界面上卡片徽标显示的就是这些）：

| 模式 | 原图 | 目标 | 实际解码 | 单张内存 | ÷ | 省 |
|---|---|---|---|---|---|---|
| 正常 | 3068 × 2012 | 515 × 338 | 515 × 338 | **0.66 MB** | ÷6 | 35.5× |
| 正常 | 2982 × 2558 | 515 × 442 | 515 × 442 | 0.88 MB | ÷6 | 35.5× |
| 对照组 | 3108 × 2256 | SIZE_ORIGINAL | 3108 × 2256 | **26.75 MB** | ÷1 | 1.0× |
| 对照组 | 2464 × 2058 | SIZE_ORIGINAL | 2464 × 2058 | 19.34 MB | ÷1 | 1.0× |
| 对照组 | 2538 × 2678 | SIZE_ORIGINAL | 2538 × 2678 | 25.93 MB | ÷1 | 1.0× |

- **单张差约 40 倍**（0.66 MB → 26.75 MB）
- 进程 native 堆：**25.24 MB → 97.37 MB**（而这只是屏幕上可见的 6 张里的 3 张）
- `÷1` 与 `省 1.0×` 是「降采样被架空」的直接读数 —— 目标尺寸等于原图尺寸时，
  `inSampleSize` 恒为 1，没有任何采样发生。

> 注意：**模拟器上 `summary.graphics` 恒为 0.00 MB**（实测确认，
> `ActivityManager.getProcessMemoryInfo` 的该字段在模拟器上不填充）。
> 所以水位条要看真实数值必须用真机 —— 界面上同时打印 native 与 java 堆就是为了兜底。

## 11. 本 demo 的教学取舍

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
