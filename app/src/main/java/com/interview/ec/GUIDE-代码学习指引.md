# 代码学习指引：组件化首页

> 这份指引回答的是"**怎么读这份代码**"，不是"这份代码讲了什么"。
> 讲什么看 [README.md](./README.md)（实现说明）与 [INTERVIEW-组件化首页.md](./INTERVIEW-组件化首页.md)（面试口径）。
> 本文按**阅读顺序**组织，每节末尾有自测题 —— 读不下去就先做题，做不出来再回去读。

---

## 〇、按你的时间选路线

| 时间 | 读什么 | 目标 |
|---|---|---|
| **15 分钟** | 第一节「先建立地图」 + 2.3 节 spanCount + 第四节 实验 | 能讲清"spanCount 为什么是 60"，能自己改 JSON 改页面 |
| **1 小时** | 第一节 → 第二节（全） → 第四节 | 能讲清整条链路（JSON → 模型 → 计划 → 屏幕），知道两个 bug 为什么发生 |
| **半天** | 全部 + 对着开源库源码看第五节的推理链 | 能回答面试官任何一句"为什么"（包括"为什么不是内嵌容器"） |

**不要按文件顺序从 `EcPageModel.kt` 第一行往下读。** 那个文件里的枚举定义很枯燥，
你先读会睡着，而且会漏掉真正的重点。按下面的顺序读，每一步都有"现在该看什么"。

---

## 一、先建立地图

### 1.1 依赖方向（记住这张图，后面所有疑问都能靠它定位）

```
                    assets/ec-home/page.json        ← 唯一的输入（改它就能改页面）
                              │
                              ▼
        ┌─────────────────────────────────────────────┐
        │  model/   ← 纯 Kotlin，**不 import 任何 Android 类** │  可 JVM 单测
        │    EcPageModel.kt    数据模型 + schema 常量         │
        │    EcPageParser.kt   解析 + 降级                    │
        └─────────────────────────────────────────────┘
                              │  EcPage（已验证的领域模型）
                              ▼
        ┌─────────────────────────────────────────────┐
        │  render/PagePlan.kt  ← 同样不依赖 Android          │  可 JVM 单测
        │    把"两层树"算成"每个 item 占几列"（纯函数）        │
        └─────────────────────────────────────────────┘
                              │  PagePlan（占位计划）
                              ▼
        ┌─────────────────────────────────────────────┐
        │  render/PagePlanRenderer.kt  ← 从这里开始依赖 Android │  只能真机验证
        │    ConcatAdapter 装配 + SpanSizeLookup + 复用        │
        └─────────────────────────────────────────────┘
                              │
                              ▼
                EcHomeActivity.kt（装配 + 诊断面板 + A/B 开关）
```

**这张图最重要的一行是那条"不 import 任何 Android 类"的分界线。**
它把代码切成了"能测的"和"只能看的"两半 —— 而恰好**最容易错的那部分（占比计算）被切进了能测的一半**。
这不是巧合，是 2.4 节 讲的取舍。

### 1.2 自测（读完上图就能答）

1. 如果我要改页面顺序、加一个楼层，改哪个文件？（答：`page.json`，一行 Kotlin 都不用动）
2. 哪两个文件可以在**不启动模拟器**的情况下测？（答：`EcPageModel`/`EcPageParser`/`PagePlan` 三个 —— 但那两个大文件才是重点）
3. 如果"某个卡片宽度不对"，我应该先怀疑哪一层？（答：`PagePlan` 的 span 计算 **或** `PagePlanRenderer` 的宽度重置，见第五节的 5.2）

---

## 二、逐文件精读

### 2.1 `assets/ec-home/page.json` —— 先读数据，再读代码

**先做这一步，不要跳过。** 数据结构看不懂，后面所有代码都在猜。

这个文件是**故意写坏的**（这很关键）—— 它不是一份"正常数据"，而是喂给代码的**考题**。
在开始读 Kotlin 之前，先自己找出来它哪里不对：

<details>
<summary>点开看答案（先自己找，别急着点）</summary>

| 位置 | 埋的雷 | 期望行为 |
|---|---|---|
| `c_bad_columns.columns = 7` | 7 不是 60 的约数 | 夹到 6 列 + 告警 |
| `c_future.type = "live_stream_room"` | 客户端不认识这个**组件**类型 | 整个楼层跳过 + 告警 |
| `c_mixed` 里的 `ar_try_on` | 客户端不认识这个**元件**类型 | **保留**，降级成占位块 + 告警 |
| `schemaVersion: "1.0"` | 主版本是认识的 | 正常 |
| `c_kingkong.columns = 5` | 5 是合法的（60÷5=12） | 一行 5 个，且都占 12 span |
| 标题 `"异常列数（7 不是 60 的约数）"` | —— | 这只是给人看的标签 |

</details>

注意最后三行的**对比**：`c_bad_columns` 的 7 列是"坏了但要救"，`c_future` 是"坏了救不了"。
**同一个文件里放了三种不同级别的错误处理**，这就是这个 Lab 的核心命题之一。
读完代码后你要能回答：为什么这两种的处理方式不同？（提示：看 2.2 节）

**为什么 `banner` 组件没有 `columns` 字段？** 先记住这个问题，2.3 节回答。

### 2.2 `EcPageParser.kt` —— 降级必须"可见"

**读法：不要从第一行读。** 先跳到第 133 行 `parseComponent` 和第 191 行 `parseElement`，
把这两个函数的**开头几行**看完 —— 组件级未知和元件级未知的处理差别就写在最前面。

```
EcPageParser.kt:133  parseComponent   ← 未知组件：整块丢弃
EcPageParser.kt:191  parseElement     ← 未知元件：降级保留
```

**读的时候盯这三个问题：**

1. `parse()` 返回的不是 Boolean，而是 `EcPageParseResult`（`Success` / `Failure`）。
   为什么要这么麻烦？（提示：`Success(page, warnings)` —— 看 `EcPageModel.kt:311`）
2. `resolveColumns()`（`:257`）里，列数夹取的判据是什么？为什么是"最近合法值"而不是"报错"？
3. `checkSchemaVersion()`（`:101`）—— 为什么"未知主版本"只告警不拒绝？

**读完自测：**

> Q：服务端下发了一个 `columns: 7` 的楼层。最终这个楼层会怎么显示？
>
> A：按 6 列显示，并在页面上出现一条 `bad_columns` 告警。
> 关键在于 `resolveColumns` 里的 `LEGAL_COLUMNS.minByOrNull { abs(it - requested) }` ——
> 夹到"最近的"合法列数，而不是"直接取 6"或"报错"。

### 2.3 `EcSchema`：`SPAN_COUNT` 为什么是 60

**这是全篇最值得花时间的 20 行。** 位置：`EcPageModel.kt:221` 到 `:283`。

先自己想：一个 RecyclerView 有几个 spanCount？（答：一个，因为它只有一个 GridLayoutManager）
那首页要有 2 列、4 列、5 列怎么办？→ spanCount 必须被**每一个**列数整除。

然后算一下：如果按常识取 12，5 列怎么办？`12 / 5 = 2`（取整），5 个元素只占 10 span，
**每行右侧空 2/12**。所以取 `lcm(1,2,3,4,5,6) = 60`。

现在回头看 2.1 节留的问题：**为什么 banner 组件没有 `columns`？**
答：看 `resolveColumns`（`:257`）—— `if (layout == BANNER) return 1`。
banner 的"要几列"由 `type` 语义决定（通栏 = 1 列），配置里的 `columns` 被**忽略**。
这是"更具体的 type 优先于通用配置"的一个实例。

**这三行要能背下来**（`:276`）：

```kotlin
val LEGAL_COLUMNS: List<Int> = (1..6).filter { SPAN_COUNT % it == 0 }
fun spanPerElement(columns: Int): Int? =
    if (columns in LEGAL_COLUMNS) SPAN_COUNT / columns else null
```

> **加分点（面试时说出来会加分）**：`LEGAL_COLUMNS` 不是手写清单，而是**定义为 spanCount 的约数**。
> 手写 `setOf(1,2,3,4,6)` 的话，将来有人把 SPAN_COUNT 改成别的值，清单不会报错，
> 但某个列数就**静默**坏掉了。写成 `filter` 则"除不尽"的组合**根本进不了集合**。
> 这叫"约束即定义"。

### 2.4 `PagePlan.kt` 的一个关键取舍：一份计划，一份真相

跳到 `PagePlanRenderer.kt:140` 的 `getSpanSize`，看它**做了什么**：

```kotlin
override fun getSpanSize(position: Int): Int {
    return plan.rows.getOrNull(position)?.spanSize ?: 1   // ← 只有查表，没有任何判断
}
```

**这里没有任何 if-else 布局逻辑，这是刻意设计的。** 所有"这个元素该占几列"的推理
都在 `PagePlanBuilder` 里（`PagePlan.kt:141`），渲染层只负责"照着计划画"。

> 为什么这个取舍重要：如果布局逻辑同时散落在"算计划的代码"和"渲染的代码"里，
> 你就会有两份**会各自演化**的真相。改了一处忘了另一处，表现就是"某些地方对某些地方错"
> —— 最难查的一类 bug。

**别错过 `validate()`（`PagePlan.kt:82`）。** 它只做三件事（数行数、看起点连不连得上），
但它是**防"沉默错位"的保险丝**：如果计划的行数和 ConcatAdapter 实际的位置错开 1 个，
span 就会算到**错误的 item** 上，表现是"卡片宽度莫名不对"而不是崩溃。
所以 `PagePlanRenderer` 的 `init` 里第一件事就是调它（`:126`）。

### 2.5 `PagePlanBuilder.flatten()` —— 读懂它，你就读懂了整个展平方案

位置：`PagePlan.kt:236`。**这是全模块最该逐行读的函数**，因为两个核心不变量都在这里。

**先读注释（`:188` 到 `:235`），那段注释比代码长，而且本来就该比代码长。**
它记录了一个**被推翻的推理**：

- 最初假设：满行元素（如楼层标题）不加补齐的话，会被"挤进"当前半行 → 所以要补齐。
- **这个假设是错的。** 源码 `SpanSizeLookup.getSpanIndex()` 对满 span 元素**直接 `return 0`**，
  它永远从新行第 0 列开始，不可能被挤。
- 那补齐还需要吗？**需要，但理由是另一个**：展平后所有楼层是**一条连续位置序列**，
  楼层之间**没有换行标记**。楼层 A 以半行结尾（如 `c_bad_columns`，6 列 7 个元素）
  → 楼层 B 的第一个元素会被塞进这半行里 → **两个楼层粘连**。

**这个"机制对、理由错"的记录是本模块最诚实的地方**，也是你该重点学的地方：
单测的价值不只是防回归，还能**修正你对系统的理解**。

**接着读代码的三段结构：**

| 位置 | 做什么 |
|---|---|
| `:247` 循环开始 | 逐个元件算 span：满行元素给 60，否则给 `spanPerElement` |
| `:251` 中段补齐 | 当前行没排满，而下一个元素要独占整行 → 先把当前行补满 |
| `:268` 收尾补齐 | ★ **楼层结束时补满 —— 这是防止"下一个楼层漏进来"的唯一手段** |
| `:291` `fillerRows` | 占位行怎么造：用 `_filler` 这个 rawType 标记，而不是新开一个 ElementType |

> **`:258` 有个细节值得学**：`Row` 的 `floorIndex`/`localPosition` 先填 `-1` 哨兵，
> 由外层 `build()` 统一回填。注释写得很明白：用哨兵而不是猜 0，
> 是因为"万一忘了回填，-1 会立刻越界暴露，而猜 0 会安静地渲染成第 0 楼层（几乎发现不了的错渲染）"。
> **这是"让错误尽早、尽量响"的一个具体手法。**

### 2.6 `PagePlanRenderer.kt` 的三个关键决定

这个文件 811 行，**但只需要精读三处**，其余是布局绑定（读起来很平，可以快过）。

**① 装配（`:110` - `:164`）** —— 对着 `init` 块读：

- `:128` `ConcatAdapter(Config.setIsolateViewTypes(...), *floorAdapters)`
  → 为什么关掉 viewType 隔离？看类注释 ②（`:53`）。
  关键：**默认配置下每个 sub-adapter 的 viewType 被偏移隔离，池就被池边界挡死了**，
  而这个后果**不报错、只是变慢**。
- `:138` `GridLayoutManager(context, SPAN_COUNT)` + `spanSizeLookup`
- `:159` `isSpanIndexCacheEnabled = true` —— 以及它下面的注释：
  **将来支持"局部更新 span"必须调 `invalidateSpanIndexCache()`**，这是唯一会踩的坑。

**② `FloorAdapter`（`:302`）** —— 精读 `onBindViewHolder`（`:353`）：

- `:358` 横滑楼层先处理（绑容器，不是绑元素）
- `:366` 占位行直接 `return`（**必须显式拦截**，否则会走进 `bindUnknown` 画出红框）
- `:400` ★ `resetWidthToMatchParent` —— **这是 6.2 那个 bug 的修复点**，
  读它上面那段注释（`:368` - `:399`），它完整记录了 RecyclerView 的 LayoutParams 拷贝路径。

**③ `bindHorizontalFloor`（`:444`）** —— 横滑楼层的"容器怎么做"：

- `:474` `rv.setRecycledViewPool(sharedPool)` —— 注意注释：**必须写成方法调用**，
  不能写属性赋值 `rv.recycledViewPool = ...`（Kotlin 不会为读写类型不一致的 Java 属性合成 var，会编译报错）。
- `:490` 恢复滚动位置、`:500` 记录滚动位置 —— 按**楼层 id** 记忆，
  注释解释了为什么不能挂在 ViewHolder 上（会被复用给别的楼层，位置串味）。

### 2.7 `EcHomeActivity.kt` —— 把结论变成屏幕上的数字

先读文件头注释（`:30` 一带），它画了整条链路。
然后读 `buildPage()`（`:121`）→ `renderPage()`（`:142`），这就是主流程。

**重点看 `renderPage` 里"为什么把警告显示出来"**（`:186` 一带）：
不只打 log 到 logcat，而是**渲染到页面诊断面板上**。注释解释了原因 ——
logcat 要连 adb 才看得到，而"哪些楼层被降级了"是产品/测试必须当场知道的事。

**再做一次对照实验**（`:219` `rebuild()`）：
注意它**重建时重新走了一遍解析 + 展平**，而且 `stats` 一并重置 ——
否则两次实验的数字会累加，对比就失效了。**这是一次只改一个变量的实验纪律。**

---

## 三、读测试：42 个用例怎么读

测试文件只有一个：`app/src/test/java/com/interview/ec/EcPageParserTest.kt`，
里面装了 **4 个测试类**，按重要性排序：

| 类 | 用例数 | 位置 | 读它的理由 |
|---|---|---|---|
| `PagePlanTest` | 14 | `:369` | ★ **最该读的**，展平的核心不变量全在这里 |
| `EcPageParserTest` | 21 | `:39` | 数据契约 + 降级规则 |
| `PriceFormatterTest` | 6 | `:316` | 纯函数，快速扫 |
| `SchemaRoundTripTest` | 1 | `:671` | JSON 字段名契约 |

**从 `PagePlanTest` 开始读，挑这三个：**

1. `:380` `每个楼层的 span 总量应等于整屏的整数倍（楼层边界必须落在行边界上）`
   → 这是**不变量本身**，也是 6.1 那个 bug 的回归测试。
2. `:408` `楼层以半行结尾时应被补齐 否则下一个楼层会漏进这半行`
   → 看它的注释，里面写了那个**被推翻的推理**。
3. `:463` `满行元素占满整行 且其前面的半行会被补齐`
   → 注意断言列表：它同时验证了"标题独占整行"和"总量仍是整屏整数倍"。

**然后注意 `:54` 的 `realPageJson()`** —— 测试**直接读 assets 里那份真 JSON**，
而不是在测试里另写一份。理由写在注释里：另写的话，测试通过只证明"我编的那份能被解析"，
真正会上线的那份写歪了测试**完全不会知道**。

> **读测试的方法**：测试名就是需求文档。先把 42 个测试名字从上到下扫一遍
> （`grep -o 'fun \`[^\`]*\`' ...`），你会对整个模块的"应该行为"有个全景印象，
> 比读实现代码快得多。

---

## 四、动手改一次 data（改 JSON，不用碰 Kotlin）

**这是检验"你真的懂了"的唯一方式。** 改完按第八节的命令跑起来看。

### 实验 1：加一个 3 列楼层（预计 3 分钟）

在 `page.json` 的 `components` 数组里插一段：

```json
{
  "id": "c_my_row",
  "type": "grid",
  "columns": 3,
  "title": "我加的 3 列",
  "elements": [
    { "id": "m1", "type": "product", "title": "A", "priceCents": 9900 },
    { "id": "m2", "type": "product", "title": "B", "priceCents": 19900 },
    { "id": "m3", "type": "product", "title": "C", "priceCents": 29900 }
  ]
}
```

**预期**：页面上多一个楼层，一行正好 3 个，每个占 20 span（60÷3）。
诊断面板的 `item` 数从 42 → **45**（实测确认：`items=3 floors=1 fillers=0`，无占位行）。

> ⚠️ 这里的 45 是 **item 数**，和第 3 个实验里那个"隔离模式创建 45 次"的 45
> 只是数字巧合，两者毫无关系。

### 实验 2：把 `c_bad_columns` 的列数改成 4（预计 2 分钟）

**预期**（实测确认）：
- 摘要行从 `组件 6 → 楼层 6 / item 42 / 警告 3` 变成 `item 38 / 警告 2`
  —— 因为 4 是合法列数，`bad_columns` 这条告警消失；且该楼层由 12 个 item 缩到 8 个
- 该楼层仍有 **1 个占位行**：7 个元素 × 15 span = 105 = 60 + 45，剩 15 span 需补满
- **先自己算一遍 item 数再去看诊断面板** —— 算对了才算真懂 span 补齐

### 实验 3：切 A/B 开关，观察池命中（预计 5 分钟）

1. 打开「组件化首页 Lab」，**关掉**「隔离 viewType」开关（保持默认 = 共用）
2. 点「滚到底并汇报」，记下 `createViewHolder 次数` 里 `product=` 的数字
3. 点「重建」（**必须重建**，否则数字累加）
4. **打开**「隔离 viewType」，再点「滚到底并汇报」
5. 对比两次的 `product=` 与合计

**参考值**（Redmi K40 / Android 12）：共用 **37** 次 vs 隔离 **45** 次（−17.8%）。
你的数字可能略有出入（取决于滚动过程），但方向应该一致：**隔离明显更多**。

> 如果两次数字一样 —— 检查你是不是忘了点「重建」。这是本实验最容易犯的错，
> 也正是"一次只改一个变量"的意义。

### 实验 4（进阶）：故意把代码改坏，看测试是否抓得住

1. 把 `EcPageModel.kt:263` 的 `SPAN_COUNT` 从 60 改成 12
2. 跑 `./gradlew :app:testDebugUnitTest --tests "com.interview.ec.*"`
3. **实测结果：5 个用例变红**（`42 tests completed, 5 failed`）——
   `金刚区常用的 5 列必须在合法集合内`、`5 列金刚区的每个入口占 12 span`、
   `2 列宫格里每个商品占 30 span`、`满行元素占满整行 且其前面的半行会被补齐`、
   `未知元件应仍然占位（不被展平阶段丢掉）`
4. 改回 60，确认全绿

**这个实验里有一个反直觉的发现，值得单独说：**
`SPAN_COUNT 必须能被每一个合法列数整除` 这条**没有红** ——
因为 `LEGAL_COLUMNS` 是 `(1..6).filter { SPAN_COUNT % it == 0 }` **派生**出来的，
改成 12 后集合自动变成 `[1,2,3,4,6]`，**整除关系依然成立**（它是对自己派生的集合做断言，必然自洽）。

真正抓到问题的是那些**硬编码期望值**的用例（"5 列 → 每元素 12 span"）。
这印证了 2.3 节 说的"约束即定义"：
它保证了**结构**上不可能不一致，但**语义**上的退化（5 列没了）还需要硬编码断言来兜。
**两层防线各管一半，缺一不可** —— 这个认知比实验本身更值钱。

---

## 五、两个 bug：读它们比读正常代码更有价值

这两个 bug 都**不会崩、不会红、只在真机上偶发**，是"最贵的一类 bug"。
它们分别写在 [README 第六节](./README.md) 里，这里只说**该去代码哪里看**。

### 5.1 楼层以半行结尾 → 下一个楼层漏进来（README 6.1）

**看**：`PagePlan.kt:265` 那三行收尾补齐 + `:189` 的长注释。

**要学的方法**：这个 bug 的根因是"**我依赖了一个并不存在的机制**"（以为 GridLayoutManager
知道楼层边界）。**展平方案的固有代价就是：所有"容器本会帮你做的事"都得自己补上。**
读到这一节时问自己：还有哪些事是容器本来会帮你做的？（提示：横向滚动位置的保存 ——
见 5.2 旁边的 `bindHorizontalFloor`）

### 5.2 内层固定宽度污染外层宫格（README 6.2）

**看**：`PagePlanRenderer.kt:368` - `:399` 的注释 + `:406` 的 `resetWidthToMatchParent`。

**要学的方法（这个更重要）**：这个 bug 教会的是**怎么定位"不报错、只画错"的问题** ——
不能靠试，必须**照源码确认数据的传递路径**：

```
内层 applyHorizontalWidth 写了 396px
  → 池跨 LayoutManager 共享（:467 注释 ③）
  → 外层复用这个 ViewHolder 时，RecyclerView 重建 LayoutParams
     但 checkLayoutParams 只判**类型**，类型不符 → 走 generateLayoutParams 拷贝
     而拷贝会把 width=396 **一起带过去**（RecyclerView.java:12544）
  → GridLayoutManager.measureChild 用 lp.width 直接算 MeasureSpec
     （childDimension >= 0 → EXACTLY 396px），**绕过 span 算出来的宽度**
```

**注意这里的推理链有多长**：4 步，跨 2 个文件、2 个开源库的内部行为。
面试时把这条链讲出来，比说"我修了个宽度 bug"有力得多。

---

## 六、常见误解清单（读完了再核对一遍）

读完之后，试着回答下面每一条。**能答对全部，说明你真读懂了。**

| # | 说法 | 对/错 | 为什么 |
|---|---|---|---|
| 1 | "ConcatAdapter 可以按类型分段来共享池" | ❌ | ConcatAdapter 是首尾相接，**没有**重排能力。按类型分段会把楼层顺序打乱。共用池靠 `isolateViewTypes=false`，是**两件事** |
| 2 | "每个 sub-adapter 有自己的 RecycledViewPool 对象" | ❌ | 池只有一个，是 `SparseArray`，**按 viewType 索引**。"独立池"只是"viewType 不同 → 落到不同桶" |
| 3 | "满行元素不加补齐会被挤进半行" | ❌ | 源码 `getSpanIndex()` 对满 span 元素直接 `return 0`。补齐是为了防**跨楼层漏入** |
| 4 | "横滑卡片和外层宫格可以随便共用一个池" | ⚠️ | 共用本身安全（RecyclerView 会重建 LayoutParams），**但固定宽度会跟着 LayoutParams 一起被拷过去** → 必须双向重置宽度 |
| 5 | "spanCount 取 12 是常识" | ❌ | 12 的约数里没有 5，而 5 是金刚区最常见列数。取 `lcm(1..6)=60` |
| 6 | "服务端下发的 data 是网络来的" | ❌ | 本 Lab 是 assets。换网络只需改 `EcHomeActivity.readAsset` 一处（诚实边界，别吹） |
| 7 | "横滑楼层的滚动位置会被自动保存" | ❌ | 外层回收 ViewHolder 时不替内层保存。本实现按**楼层 id** 手动记忆 |
| 8 | "加个 `setHasStableIds(true)` 就能用 stable id" | ❌ | ConcatAdapter 默认模式会**忽略**它并打警告。要走到 `Config.setStableIdMode(...)` |
| 9 | "`isNestedScrollingEnabled = false` 解决了 RV-in-RV 的冲突" | ⚠️ | 正交方向本来就不抢手势，这行对 RV-in-RV **是 no-op**，保留只是为了确定性（不要过度归因） |
| 10 | "`spanPerElement` 对非法列数会取整" | ❌ | 返回 `null`，由调用方决定降级。取整会让"除不尽"静默地错下去 |

---

## 七、把这份代码当模板用时，改哪里

| 需求 | 改哪 |
|---|---|
| 接入真实网络数据 | `EcHomeActivity.readAsset()`（`:286`）替换成网络请求，其余不动 |
| 加一种元件类型 | `ElementType` 加枚举 + `elementTypeOf` 加映射 + 一个 layout + **三处 exhaustive `when`**（`getItemLayoutId` / `applyHorizontalWidth` / `bindElement`）。**漏改会编译不过**，这是刻意的 |
| 加一种排列策略 | `LayoutStrategy` 加枚举 + `layoutOf` 映射 + `PagePlanBuilder.build` 里的 `when` + `FloorAdapter` 的绑定分支 |
| 支持局部刷新 | 必须：`invalidateSpanIndexCache()` + `ConcatAdapter.Config.setStableIdMode(...)` + 所有 sub-adapter 实现 stable id。**成本不低，先想清楚是否值得** |
| 支持 item 动画 | 同上（动画依赖 stable id / DiffUtil），且要处理"占位行不该参与动画" |

> **加元件类型"漏改会编译不过"这点值得强调**：三处 `when` 都是对枚举 **exhaustive 且没有 `else`**，
> 所以新增一个枚举值后，编译器会逐处报 `'when' expression must be exhaustive`。
> 这比"运行时偶发空指针"好得多。**已经实测验证过**（临时加一个枚举值 → 恰好 3 处编译错误）。

---

## 八、跑起来（命令速查）

```bash
cd /Volumes/ext/workspace/projects/android/interview/android-interview

# 单测（纯 JVM，42 个用例，约 10 秒，不需要设备）
./gradlew :app:testDebugUnitTest --tests "com.interview.ec.*"

# 只看展平计划那 14 个用例
./gradlew :app:testDebugUnitTest --tests "com.interview.ec.PagePlanTest"

# 编译 + 打包 + 装机
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 入口：首页 → 「实验台」分组 → 「组件化首页 Lab」
adb shell am start -n com.example.myapplication/com.interview.ec.EcHomeActivity

# 看降级告警的完整 detail（页面诊断面板只有简版）
adb logcat -s EcHome

# 改完 JSON 后重新装（assets 变更不被增量安装捕获时要 -r 重装）
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**排查技巧**：如果页面空白，先看 `adb logcat -s EcHome` ——
三种降级路径的每一步都会打日志（`bad_columns` / `unknown_component` / `unknown_element`）。

---

## 九、这份代码的"诚实边界"（别在面试里说过头）

读到什么程度算读懂？**知道它没做什么，和知道它做了什么一样重要。**

- 数据来自 **assets 不是网络**（接网络只需改一处，但确实没接）
- 横滑滚动位置是**手动记忆**的（不是自动的）
- **没有** item 动画 / DiffUtil / stable id
- span **只算一次**（局部刷新需 `invalidateSpanIndexCache()`）
- Gson **未做严格类型校验**（`priceCents` 下发字符串会退化成缺省，不报错）
- `isNestedScrollingEnabled = false` 对 RV-in-RV **是 no-op**，保留只为确定性
- 池的收益是 **−17.8%**，属于"方向正确、幅度不大"（本页只有 42 个 item）；
  楼层数和类型数增长时会放大，但别把它讲成数量级的提升

完整的边界清单在 [README 第七节](./README.md) 和 [INTERVIEW 第八节](./INTERVIEW-组件化首页.md)。
