# 组件化首页（数据驱动的组件 / 元件渲染）

本目录是一个**可跑的**电商首页实验模块：页面的楼层构成、内容、排列方式**全部来自一份 JSON**，
客户端只提供"渲染能力"。配套的面试讲述稿见 [`INTERVIEW-组件化首页.md`](./INTERVIEW-组件化首页.md)。

实验页入口：`com.example.myapplication/com.interview.ec.EcHomeActivity`（已登记在首页目录）。
日志 `TAG = EcHome`。

---

## 一、一句话说清它解决什么问题

> 首页要能**不发版**地改：加一个楼层、换一批商品、把一个宫格从 3 列改成 4 列。

这要求把"布局"从代码搬进数据。于是需要两层模型：

```
页面
 └── 组件 Component   ← 一块区域 + 这块区域"怎么排列"子元素（= 需求里的"抽屉形式"）
      └── 元件 Element ← 叶子节点，只说"我是什么、显示什么"，不说"我在哪"
```

**关键约束**：元件里**没有** width / 列号 / 行号之类的字段。
同一个 `product` 元件在 2 列里是半宽、在 4 列里是 1/4 宽 —— 这个差别**完全由组件的列数决定，
元件零感知**。这条约束是"数据驱动"与"用数据描述布局"的分界线。

（唯一的例外是 `needsFullRow`：它表达的是"我要横跨全部列"，性质类似 CSS 的
`grid-column: 1 / -1` —— 是关于**元件自己**的要求，不是坐标。详见
`Element.needsFullRow` 的注释。）

---

## 二、三种排列策略（"抽屉形式"）

| 策略 | 子元素去哪 | 有"容器"吗 | 外层占几个 item |
|---|---|---|---|
| `BANNER` | 展平进外层 RV，占满整行 | ❌ | 每个元素 1 个 |
| `GRID` | 展平进外层 RV，按列数占 span | ❌ | 每个元素 1 个 |
| `HORIZONTAL` | **内嵌**一个横向 RecyclerView | ✅ | **整层 1 个** |

`BANNER` 与 `GRID` 在实现上是同一件事（展平 + 占若干 span），保留 `BANNER` 是为了让服务端
下发时可读。**这个"语义与能力分离"是 schema 设计里最容易被做歪的地方**：
一旦为 `BANNER` 写独立渲染代码，接下来 `BIG_PIC_L1`、`BIG_PIC_L2` 就会各来一套，
能力集从"3 种排列"膨胀成"无数种专题页"。

---

## 三、一个"不撞墙就想不到"的设计推导：spanCount 为什么是 60

这是本模块最值得讲的一处推导，因为它由一条**硬约束**逼出来：

> 一个 RecyclerView 只有**一个** GridLayoutManager，也就只有**一个** spanCount。

而首页里不同楼层列数不同（金刚区 5 列、推荐 2 列、清单 4 列）。
要让同一个 spanCount 容纳所有列数，唯一办法是：

> **spanCount 必须能被每一种用到的列数整除。**

若按最"常识"的 12 来定：12 的约数是 1/2/3/4/6/12 —— **5 不是**，而 5 列恰恰是金刚区最典型的列数。
用 12 的话，5 列只能 `12/5 = 2`（取整），**每行右侧空掉 2/12**，且列宽与其它楼层不一致。

所以取支持列数的**最小公倍数**：`lcm(1,2,3,4,5,6) = 60`。于是每种列数都整除、没有取整误差：

| 列数 | 每元素 span |
|---|---|
| 2 | 30 |
| 3 | 20 |
| 4 | 15 |
| **5** | **12** |
| 6 | 10 |

代价也要说清楚：`mCachedBorders` 数组变成 61 个 float，未命中缓存时 `getSpanIndex` 最多循环 60 次整数加法。
实测在 60 这个量级可忽略，但**它确实是成本**，再往上加就该考虑换方案。
另外列数集合是**按"SPAN_COUNT 的约数"定义的**（`EcSchema.LEGAL_COLUMNS`），
所以"某个合法列数除不尽基准"这种组合**压根进不了集合** —— 手写清单则做不到这一点。

---

## 四、展平 vs 内嵌容器：本模块的核心取舍

| | 内嵌容器（每楼层一个 RV） | **展平（本实现）** |
|---|---|---|
| 布局谁算 | 内层 ViewGroup 的 onMeasure/onLayout | **我们**，手算 span 交给 GridLayoutManager |
| ViewHolder 复用 | **仅限本楼层** | **跨楼层**（同种元件共用一个池） |
| 滚动 | 楼层多时明显掉帧 | 一屏内是一个连续布局过程 |
| 灵活性 | 高（想怎么排怎么排） | 受"网格 + span"约束 |
| 复杂度 | 低（各层各写各的） | 高（要自己算占位、处理楼层边界） |

**没有普适最优解**：电商首页是"楼层多、元素规整、复用率极高"，所以展平收益最大。
若每个楼层都是完全不同的奇葩布局，展平就得处处迁就 —— 那时内嵌更合适。

---

## 五、实测数据（Redmi K40 / Android 12 / arm64 真机）

### 5.1 默认资产（`assets/ec-home/page.json`）的装配结果

```
组件 6 → 楼层 6 / item 42 / spanCount 60 / 警告 3
```

JSON 里写了 **7** 个组件、**43** 个元件；`live_stream_room` 是客户端不认识的类型
→ **整块跳过**（连带它里面的 1 个元件），故只剩 6 个楼层、42 个外层 item。
逐楼层明细（`real` = 数据里的元件数，`filler` = 为对齐行边界插入的 0 高度占位）：

| 楼层 | 策略 | 列数 | 外层 item | real | filler | span 合计 |
|---|---|---|---|---|---|---|
| c_top_banner | BANNER | 1 | 1 | 1 | 0 | 60 |
| c_kingkong | GRID | 5 | 10 | 10 | 0 | 120 |
| c_flash_sale | HORIZONTAL | — | 1 | 1 | 0 | 60 |
| c_recommend_2col | GRID | 2 | 9 | 9 | 0 | 300 |
| c_bad_columns | GRID | **6**（下发 7，被夹） | 12 | 7 | **5** | 120 |
| c_mixed | GRID | 4 | 9 | 9 | 0 | 180 |
| **合计** | | | **42** | | | |

两处值得注意：

- `c_bad_columns` 的 7 个元件在 6 列下每个占 10 span（共 70），`70 % 60 = 10`
  → 需要 **5 个占位**补齐到整行（120 = 2 整行）。这 5 个占位就是第 6.1 节那条不变量的代价，
  也解释了为什么屏幕上该楼层第二行有空位。
- `c_recommend_2col` 有 9 个 item 但只有 8 个商品：多出来的 1 个是
  `needsFullRow = true` 的楼层标题（`e_hdr_rec`）。**展平楼层没有容器**，
  所以 `component.title` 无处渲染，标题只能由 `section_header` 元件承担 ——
  这是展平架构的固有不对称，见第 6 节与 `PagePlanBuilder.horizontal` 的说明。

### 5.2 三种降级路径都真实触发了（logcat 原文）

```
bad_columns @components[4]: columns=7 不是合法列数，已夹到 6 列（合法值：1/2/3/4/5/6）
unknown_component @components[5]: 未知组件类型 "live_stream_room"，已整块跳过
unknown_element @components[6].elements[5]: 未知元件类型 "ar_try_on"，已降级为占位块展示
```

对应屏幕上的表现：`7 列` 的楼层按 6 列画；一个楼层整块消失；
一张**黄虚线**占位块上打着 `ar_try_on`。

### 5.3 共享池 vs 隔离池（本模块的对照实验）

同一台机器、同一段"逐屏滚完整页"的操作序列，只改一个变量：

| 模式 | product | icon | header | **合计** |
|---|---|---|---|---|
| 隔离 viewType（`isolateViewTypes = true`） | 17 | 17 | 2 | **45** |
| **共用（默认 `false`）** | **15** | **12** | **1** | **37** |

共用池少创建 **8 次（-17.8%）**。数字不大，因为本页只有 42 个 item 且元素本就不多 ——
但方向与预期一致，且**随楼层数/元素数增长会被放大**（隔离时创建量随"楼层数 × 类型数"叠加）。

复现：进入页面 → 记下默认（共用）的合计 → 打开「隔离 viewType」开关 → 点「重建页面」→
用同样的方式滚一遍 → 对比。**一次只改一个变量**。

---

## 六、真机上抓出来的两个 bug（都不是编译期能发现的）

这两个是本模块最值钱的部分：它们都属于"**不崩、只画错**"，而且**只在特定条件下出现**。

### 6.1 楼层以半行结尾 → 下一个楼层漏进来（测试抓出来的）

展平后所有楼层是**一条连续位置序列**，楼层之间**没有任何"换行标记"**。
若楼层 A 的最后一行只排了一半（如 2 列宫格剩 30 span），
而楼层 B 的首个元素恰好也是 30 span → **它会被塞进楼层 A 的那半行里**，两个楼层视觉上粘连。

修法：**保证每个楼层的 span 总量都是 spanCount 的整数倍**（楼层收尾补齐）。
这个不变量已写成单测逐楼层断言（`真实资产里每个楼层的 span 总量都是整屏的整数倍`）。

> ⚠️ 这里还有一次**推理被测试推翻**的经历，很值得记录：
> 最初写补齐的理由是"防止满行元素（楼层标题）被挤进半行"。
> 单测把这个理由否了 —— 源码 `GridLayoutManager.SpanSizeLookup.getSpanIndex()`
> 对 `positionSpanSize == spanCount` 的元素**直接 `return 0`**（1.3.2 约 987 行），
> 满 span 元素**永远**从新行第 0 列开始，不可能被挤进去。
> 也就是说**机制是对的、理由是错的**：补齐真正的作用是"防止跨楼层漏入"，
> 而不是"防止标题错位"。这条被纠正的注释留在了 `PagePlanBuilder.flatten` 里。

### 6.2 内层固定宽度污染外层宫格（真机滚动才暴露）

内层横向列表必须给卡片写**固定宽度**（否则 `match_parent` 在横向 LinearLayoutManager 里
会被解释成"占满父容器"，一屏一张卡）。而池是**跨 LayoutManager 共享**的，于是：

> 一个在内层被改成 396px 的 ViewHolder，被外层宫格复用 → **外层那个商品卡的宽度变成 396px**。

关键在于 `RecyclerView` 的重建逻辑**救不了我们**（已核对源码 `RecyclerView.java:6926` 一带）：

```java
} else if (!checkLayoutParams(lp)) {
    rvLayoutParams = (LayoutParams) generateLayoutParams(lp);   // ← 拷贝！
    holder.itemView.setLayoutParams(rvLayoutParams);
}
```

`checkLayoutParams` 只判"是不是本 LayoutManager 的 LayoutParams 类型"。
内层是 `RecyclerView.LayoutParams`、外层要 `GridLayoutManager.LayoutParams` → 类型不符 →
走 `generateLayoutParams(lp)` **拷贝**一份，而拷贝会把 `width=396` 一起带过去
（`RecyclerView.java:12544`）。随后 `GridLayoutManager.measureChild` 用 `lp.width` 直接算
MeasureSpec（`GridLayoutManager.java:748` → `getChildDimension >= 0` 时给 **EXACTLY 396px**），
**完全绕过 span 算出来的宽度**。

修法：**"谁摆放谁写尺寸"必须双向成立** —— 外层 bind 时把宽度恢复成 `match_parent`，
内层 bind 时写成固定 dp。因为 bind 必在 measure 之前，两侧都覆盖，池里流出的陈旧尺寸活不到测量阶段。

> 这类 bug 的可怕之处：它**不报错、不复现**（必须恰好复用到一个来自内层的 ViewHolder），
> 而且表现是"某些卡片宽度莫名其妙不对" —— 容易被归因为"眼花了"。
> 定位手段是**照源码确认 LayoutParams 的传递路径**，而不是靠试。

---

## 七、诚实边界

- **数据来自 assets，不是网络**。真实项目里这份 JSON 来自下发（配版本号 + 灰度 + 兜底）。
  本模块不接网络：网络链路已在 `com.interview.net` 那个 Lab 单独讲过，此处再引入只会稀释命题。
  **从 assets 换成网络只需换 `EcHomeActivity.readAsset` 一处**，其余代码一行不动 ——
  这本身就是分层正确的证据。
- **横滑楼层的滚动位置是手动记忆的**。外层回收楼层 ViewHolder 时**不会**替内层保存滚动位置
  （外层只管自己的 layoutPosition），所以滚出屏幕再滚回来会回到第 0 个。
  本模块按**楼层 id** 记住了 `firstVisibleItemPosition` 并在重新 bind 时恢复
  （挂 ViewHolder 上是错的：ViewHolder 会复用给别的楼层）。
- **span 只在建页时算一次**，`isSpanIndexCacheEnabled = true`。若将来支持
  "下拉刷新局部改 span"，**必须**调用 `invalidateSpanIndexCache()`，否则刷新后错位。
- **未做 item 动画 / DiffUtil**。要加需走 `ConcatAdapter.Config.setStableIdMode(...)`
  且所有 sub-adapter 都实现 stable id —— 在 sub-adapter 里 `setHasStableIds(true)` 是**无效**的
  （`ConcatAdapter` 会忽略并打警告）。
- **`Gson` 解析未做严格的类型校验**（如 `priceCents` 下发了字符串）。当前策略是
  "畸形值退化成缺省"，若线上确有脏数据，应在 DTO 层加自定义 deserializer 并把失败记为告警。

---

## 八、跑起来

```bash
# 单测（纯 JVM，42 个用例，覆盖解析/降级/span 计算/价格格式化）
./gradlew :app:testDebugUnitTest --tests "com.interview.ec.*"

# 装机后：首页「实验台」→「组件化首页 Lab」
./gradlew :app:assembleDebug
```

改数据观察变化：直接编辑 `app/src/main/assets/ec-home/page.json`（加楼层、改列数、写个不存在的 type），
重新 install 即可看到不同结果 —— **不需要碰任何 Kotlin 代码**。
