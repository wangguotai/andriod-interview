# 面试讲述稿：数据驱动的组件化首页

> 配套实现说明见 [`README.md`](./README.md)（含真机实测数字与两个 bug 的完整复盘）。
> 本文是**面试口径**：先把问题讲清楚，再讲取舍，最后主动交出自己踩的坑。

---

## 〇、开场：一句话版本（30 秒）

> "首页要做到不发版就能改：加楼层、换商品、改列数。所以我做了两层数据模型 ——
> **组件**是一块区域加它的排列策略，**元件**是叶子元素。
> 元件里不带任何位置信息，同一个商品元件在 2 列里是半宽、4 列里是 1/4 宽，元件零感知。
> 渲染上我用**单一 RecyclerView + ConcatAdapter 展平**，而不是每个楼层塞一个 RecyclerView，
> 换来的是同种元件**跨楼层共享 ViewHolder 池**。"

如果对方只记住一件事，我希望是最后那句 —— 因为它能立刻引出追问。

---

## 一、被追问最先问到的问题：为什么要展平？

**先给结论**：这是个取舍，不是"展平更好"。

| | 内嵌容器（每层一个 RV） | 展平（我的做法） |
|---|---|---|
| 布局谁算 | 内层 ViewGroup 自己算 | **我算**，手算 span 交给 GridLayoutManager |
| ViewHolder 复用 | **仅限本楼层** | **跨楼层** |
| 灵活性 | 高 | 受"网格 + span"约束 |
| 复杂度 | 低 | 高 |

**判断依据是场景**：电商首页"楼层多、元素规整、复用率极高"（用户从推荐滑到秒杀，
看到的都是同款商品卡）→ 展平的收益最大。若每个楼层都是完全不同的奇葩布局，
展平就得处处迁就，那时内嵌更合适。

**主动交代代价**（不等对方问）：展平后布局得自己算，也因此踩了两个 bug（见第五节）。

---

## 二、能被追问到源码的一处设计：spanCount 为什么是 60

这题很适合用来展示"推导而不是套用常识"：

> 一个 RecyclerView 只有**一个** GridLayoutManager，也就只有**一个** spanCount。
> 而首页不同楼层列数不同（金刚区 5 列、推荐 2 列）。要让同一个 spanCount 容纳所有列数，
> **spanCount 必须能被每种列数整除**。

12 是常识，但 12 的约数里**没有 5** —— 而 5 列恰恰是金刚区最典型的列数。
用 12 的话 5 列只能 `12/5=2` 取整，每行**右侧空掉 2/12**，且列宽与其它楼层不一致。

所以取 `lcm(1,2,3,4,5,6) = 60`：2→30、3→20、4→15、**5→12**、6→10，全部整除无误差。

**代价也说**：`mCachedBorders` 变成 61 个 float，未命中缓存时 `getSpanIndex` 最多循环 60 次
整数加法。实测这个量级可忽略，但**它是成本**，再往上加就该换方案。

补一句加分点：列数集合是**按"SPAN_COUNT 的约数"定义**的，所以"合法列数除不尽基准"
这种组合压根进不了集合 —— 手写清单做不到这点（改了 SPAN_COUNT 而忘了改清单，5 列会静默坏掉）。

---

## 三、渲染层：ConcatAdapter 的三个关键决定（都有源码依据）

### 3.1 楼层 = sub-adapter，**不能**按元件类型分段

直觉会想"把所有商品卡归到一个 section 好共享池"。**这是错的**：
ConcatAdapter 是"把若干**完整 Adapter 首尾相接**"，`getItemCount()` 就是各 wrapper 的 count 累加，
**没有任何按类型重排的能力**。按类型分段会让页面顺序变成"先所有商品、再所有图标"，楼层顺序全乱。

所以：**一个组件 = 一段连续位置**。连续性是位置层面的约束。

### 3.2 跨楼层复用靠 `isolateViewTypes = false`

默认配置下 ConcatAdapter 会把各 sub-adapter 的 viewType **偏移隔离**
（`IsolatedViewTypeStorage.obtainViewType()`，全局递增计数器 `mNextViewType`）。
官方注释写得很直白：*"each added adapter will have its own isolated pool of ViewHolders,
with no re-use in between added adapters"*。

注意一个容易说错的细节：**并不是每个 sub-adapter 各自持有一个 Pool 对象** ——
RecyclerView 只有一个 `RecycledViewPool`，它的 scrap 是 `SparseArray<ScrapData> mScrap`，
按 **viewType 为 key**。所谓"独立池"只是"global viewType 互不相同，落到不同 key 桶里"。

所以关掉隔离（`SharedIdRangeViewTypeStorage.localToGlobal()` 原样返回 localType）后，
同一个 layout 在不同楼层命中**同一个桶**。

**用它的前提必须一起说**：共用 viewType 要求"同一个 viewType 在所有 adapter 里创建同一种
ViewHolder"。所以我把 viewType 严格定义成 **layout 资源 id** ——
同一个 viewType 必然对应同一个 layout，`onCreateViewHolder` 不可能不一致。
若有人写成"同一 viewType 下按组件 inflate 不同布局"，复用必然拿到错的 ViewHolder。

### 3.3 跨 LayoutManager 共享池安全吗？（这是个好问题，答案不是"当然安全"）

横滑楼层的内层是 `LinearLayoutManager`，外层是 `GridLayoutManager`，两者共用池。
它们 LayoutParams 类型不同，而 GridLayoutManager 会**硬转** `(LayoutParams) view.getLayoutParams()`
（`GridLayoutManager.java:624` 等处）—— 直觉上会 ClassCastException。

**但不会**：RecyclerView 在**每次取用** ViewHolder 时都会校验并按需重建 LayoutParams
（`RecyclerView.java:6926`）：

```java
} else if (!checkLayoutParams(lp)) {
    rvLayoutParams = (LayoutParams) generateLayoutParams(lp);
    holder.itemView.setLayoutParams(rvLayoutParams);
}
```

所以复用的 ViewHolder 拿到的 LayoutParams 一定是当前 LayoutManager 的类型。

> ⚠️ **但这个"安全"是有代价的，而且我踩了** —— 下一节。

---

## 四、A/B 对照实验：怎么证明池真的共用了

"共用池更快"是个**不会报错、不会掉帧到肉眼可辨**的结论，极易被"感觉还行"糊弄过去。
所以我把它变成屏幕上的数字：统计 `onCreateViewHolder` 的次数。

同一台机器（Redmi K40 / Android 12）、同一段"逐屏滚完整页"的操作序列，只改一个变量：

| 模式 | product | icon | header | **合计** |
|---|---|---|---|---|
| 隔离 viewType | 17 | 17 | 2 | **45** |
| **共用（默认）** | **15** | **12** | **1** | **37** |

**-17.8%**。数字不大（本页只有 42 个 item），但方向与预期一致，
且**随楼层数/类型数增长会被放大**（隔离时创建量随"楼层数 × 类型数"叠加）。

> 这里有个**方法论**值得说：实验页刻意做成"**改开关不自动重建**"，
> 必须手动点「重建页面」。因为若自动重建，你分不清变化是"开关引起的"还是"重建引起的"
> —— 对照实验必须**一次只改一个变量**。

---

## 五、我踩的两个坑（主动交出来，这是最能证明"真做过"的部分）

### 坑 1：楼层以半行结尾 → 下一个楼层漏进来

展平后所有楼层是**一条连续位置序列**，楼层之间**没有换行标记**。
若楼层 A 最后一行只排了一半（2 列宫格剩 30 span），而楼层 B 首个元素恰好也是 30 span
→ **它被塞进楼层 A 的那半行里**，两个楼层视觉上粘连。

修法：保证**每个楼层的 span 总量都是 spanCount 的整数倍**（楼层收尾补齐），
并把这个不变量写成单测逐楼层断言。

> **更值一提的是：我最初的推理被自己的单测推翻了。**
> 我写补齐的理由是"防止满行元素（楼层标题）被挤进半行"。
> 单测把这个理由否了 —— 源码 `SpanSizeLookup.getSpanIndex()` 对
> `positionSpanSize == spanCount` 的元素**直接 `return 0`**（1.3.2 约 987 行），
> 满 span 元素**永远**从新行第 0 列开始。**机制是对的、理由是错的**：
> 补齐真正防的是"跨楼层漏入"。我把这段纠正留在了代码注释里。
> 这说明单测的价值不只是"防回归"，还能**修正你对系统的理解**。

### 坑 2：内层固定宽度污染外层宫格（真机滚动才暴露）

内层横向列表必须给卡片写固定宽度（否则 `match_parent` 在横向 LinearLayoutManager 里
会被解释成"占满父容器"，一屏一张卡）。而池是跨 LayoutManager 共享的，于是：

> 一个在内层被改成 396px 的 ViewHolder，被外层宫格复用 → **外层商品卡宽度变成 396px**。

关键在于 RecyclerView 的 LayoutParams 重建逻辑**救不了我们**（正是 3.3 那段代码）：

- `checkLayoutParams` 只判"是不是本 LayoutManager 的 LayoutParams 类型"；
- 内层是 `RecyclerView.LayoutParams`、外层要 `GridLayoutManager.LayoutParams` → 类型不符；
- → 走 `generateLayoutParams(lp)` **拷贝**一份，而拷贝会把 `width=396` 一起带过去
  （`RecyclerView.java:12544`）；
- → `GridLayoutManager.measureChild` 用 `lp.width` 直接算 MeasureSpec
  （`GridLayoutManager.java:748`；`childDimension >= 0` 时给 **EXACTLY 396px**），
  **完全绕过 span 算出来的宽度**。

修法：**"谁摆放谁写尺寸"必须双向成立** —— 外层 bind 恢复 `match_parent`，
内层 bind 写固定 dp。因为 bind 必在 measure 之前，两侧都覆盖。

> **这类 bug 的价值在于它教了一个方法**：它不报错、不复现（必须恰好复用到一个来自内层的
> ViewHolder），表现只是"某些卡片宽度莫名其妙不对"。
> 定位靠的是**照源码确认 LayoutParams 的传递路径**，而不是靠试。
> 所以我不止读了 RecyclerView 的文档，而是把 sources jar 解压出来逐行看。

---

## 六、数据契约：降级必须"可见"

服务端一定会下发客户端不认识的东西（灰度、新玩法）。我的原则：

> **能降级就不要失败；一旦降级，必须让上层能看见。**

分三级处理（`EcPageParser`）：

| 级别 | 处理 | 理由 |
|---|---|---|
| 元件未知 | **降级成占位块**，屏上打出原始类型名 | 丢掉会出现"配了 5 个只显示 4 个"的**静默缺失** |
| 组件未知 | **整块跳过** + 告警 | 连它要几列、要不要横滑都不知道，硬画比少一块更糟 |
| 列数非法 | **夹到最近合法值** + 告警 | 通常是运营手滑（7 想写 6），按 6 画大概率是对的 |
| JSON 语法错 | 整体失败 | 连结构都没有，没有"部分成功"的可能 |

两个细节能体现取舍：

1. **未知元件保留占位并显示类型名**：渲染成空白的话，看到的人会以为是"数据没下发"（去查服务端），
   而事实是"数据到了、客户端不认识"（该发版）。**把类型名打出来，责任方一眼分清**。
2. **告警渲染到页面上，不只打 log**。日志要连 adb 才看得到，
   而"哪些楼层被降级了"是产品/测试必须当场知道的事。

还有一条：**解析器的返回值不是"成功/失败"二元**，而是 `Success(page, warnings)` /
`Failure(msg)`。因为真实场景是"服务端配错一个楼层 → 全站首页白屏"，
根因就是把"数据有问题"当成了"整体失败"。

---

## 七、可测试性：为什么解析层与展平层不依赖 Android

这是我在架构上最满意的一处：

> **最容易错的逻辑，恰好被设计成最容易测的。**

解析层与 span 计算层**不 import 任何 Android 类**，所以：

- 42 个纯 JVM 单测，秒级跑完，不需要设备/模拟器；
- 单测**直接读 assets 里那份真 JSON**（不是另写一份），所以"asset 写歪了"会当场红；
- span 计算的边界（补齐、跨楼层、5 列整除）全部被断言钉住。

对照一下：如果这两层依赖了 `Context`/`Color`，那么"4 列宫格宽度不对"这种问题
就只能靠真机+肉眼发现 —— 而那恰恰是最难发现的类别。

---

## 八、诚实边界（被追问时不要硬撑）

- **数据来自 assets，不是网络**。真实项目是下发（版本号+灰度+兜底）。
  换网络只需改 `EcHomeActivity.readAsset` 一处，其余代码不动。
  不接网络是因为网络链路已在另一个 Lab 讲过，此处再引入会稀释命题。
- **横滑楼层的滚动位置是手动记忆的**：外层回收楼层 ViewHolder 时不会替内层保存滚动位置，
  滚出屏幕再回来会回到第 0 个。我按**楼层 id** 记住位置（挂 ViewHolder 上是错的 —— 会复用给别的楼层）。
- **未做 item 动画/DiffUtil**：要加必须走 `ConcatAdapter.Config.setStableIdMode(...)`，
  在 sub-adapter 里 `setHasStableIds(true)` 是**无效**的（ConcatAdapter 会忽略并打警告）。
- **span 只算一次**（`isSpanIndexCacheEnabled = true`）。将来支持"局部改 span"必须
  `invalidateSpanIndexCache()`，否则刷新后错位。
- **Gson 未做严格类型校验**（如 `priceCents` 下发了字符串），当前退化成缺省而非报错。

---

## 九、可能的追问与答法（速查）

| 追问 | 要点 |
|---|---|
| 为什么不用 FlexboxLayout？ | 它解决的是"按内容宽度排列"，我要的是"按列数占 span"；而且它**不参与 RecyclerView 的复用**（一个楼层一个容器 → 回到内嵌方案的老问题） |
| 为什么不用 Compose / LazyColumn？ | 本工程是 View 体系；且 `LazyColumn` 下"按列数占 span"要靠 `LazyVerticalGrid` + span lambda，思路一致但那是另一套复用模型。这里要在既有 RecyclerView 体系内解决 |
| 楼层标题为什么有的是组件字段、有的是元件？ | **展平架构的固有不对称**：横滑楼层有容器 → 能渲染 `component.title`；展平楼层没有容器 → 标题只能由 `section_header` 元件承担。我选择"展平楼层的 title 不参与渲染、只作为诊断信息"，宁可显式不渲染也不要"有时渲染有时不渲染" |
| 加一个新元件要改几处？ | 关键是**编译器会兜住**：`getItemLayoutId` / `applyHorizontalWidth` / `bindElement` 三处都是**对枚举 exhaustive 的 `when`（无 `else`）**，新增枚举值后漏改任何一处都**编译不过**。所以实际改动是：`ElementType` 加枚举、`EcPageParser.elementTypeOf` 加映射、一个 layout xml、三处 `when` 各加一行。**而且 `onCreateViewHolder` 不用改** —— viewType 就是 layout id，直接 `inflate(viewType)`，因此不存在"加了新类型忘了加 inflate 分支"的空指针 |
| 这种架构最怕什么？ | 服务端把 schema 当"布局描述"用（下发绝对宽度、像素间距）。一旦允许，组件化会退化成"远程 UI"，客户端失去对所有设备/版本的适配能力。我的 schema 里**刻意没有**绝对尺寸字段 |
