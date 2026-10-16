# 从零理解 RN 分包：从代码组织到拆包合包

> 写给初学者。全程用**这个项目里的真实代码和真实数字**当教材，不举抽象例子。
> 每章末尾有「到这里你应该理解了」，卡住了就回头看那一句。

---

# 第 1 章 一个 RN App 打包前/后长什么样

## 1.1 打包前：源码是"模块化"的

你写的代码，是**一个文件一个模块**，文件之间用 `import` 互相引用。
看真实的 `business-app/index.js`：

```js
import {AppRegistry} from 'react-native';   // 用别人的模块
import App from './App';                    // 用自己的模块

export const BUSINESS_APP_NAME = 'BusinessApp';
AppRegistry.registerComponent(BUSINESS_APP_NAME, () => App);
export default App;                          // 给别人用
```

三个要点：
1. 每个文件是一个**独立模块**
2. `import` 是**引用别的模块**
3. `export` 是**暴露自己给别人用**

这就是"代码组织结构"。

## 1.2 打包后：源码变成"一堆编号的抽屉"

但上面那段 `index.js`，打包后**变成这样**（这是真实产物，我原样贴过来）：

```js
__d(function(g,r,i,a,m,e,d){
  var t=r(d[0]);
  Object.defineProperty(e,"__esModule",{value:!0}),
  e.default=e.BUSINESS_APP_NAME=void 0;
  var u=r(d[1]), n=t(r(d[2])), _=e.BUSINESS_APP_NAME='BusinessApp';
  u.AppRegistry.registerComponent(_,function(){return n.default});
  e.default=n.default
},938847,[572174,172571,868085]);
```

看懂这段需要解开三个"密码"：

| 源码里 | 产物里 | 含义 |
|---|---|---|
| `import {AppRegistry} from 'react-native'` | `r(d[1])` | **不是**写 `'react-native'`，而是"取我依赖表第 1 项" |
| `import App from './App'` | `r(d[2])` | ↑ 同理，第 2 项 |
| 整个文件 | `__d(function(...){...}, 938847, [572174,172571,868085])` | 整个文件被包进 `__d(...)` |

**关键转变：模块之间的引用，从"名字"变成了"数字"。**

- `'react-native'` 这个名字 → 变成了 `172571` 这个数字
- `./App` 这个名字 → 变成了 `868085` 这个数字

上面那段最后的 `[572174,172571,868085]` 就是这个模块的**依赖表**（谁被我用到了），
下标 0/1/2 分别对应 `d[0]`/`d[1]`/`d[2]`。

> **到这里你应该理解了**：打包的本质之一，就是**把"按名字引用"改成"按数字引用"**，
> 并把每个文件包成一个 `__d(...)` 调用。

---

# 第 2 章 模块管理机制：`__d` / `__r` / 全局表

## 2.1 三个角色

产物里反复出现三个东西，记住它们的分工：

| 东西 | 全称联想 | 干什么 | 类比 |
|---|---|---|---|
| `__d` | **d**efine | **登记**一个模块（我这儿有个 938847 号，代码在这） | 贴编号、上架 |
| `__r` | **r**equire | **取用**一个模块（把 938847 号给我执行） | 按编号取货 |
| `modules` 表 | — | 所有已登记模块住的地方 | 书架 |

## 2.2 prelude：这个"书架"从哪来

产物**最开头**有一大段代码（在你的 base bundle 里是 8706 个字符），叫 **prelude（前奏）**。
它就是**搭书架 + 定义 `__d` 和 `__r`**。真实代码（压缩过），我拆开讲：

```js
!(function(e){"use strict";
  // ① 定义 __r
  e.__r = i;
  // ② 定义 __d —— 往书架 r 里放模块
  e[`...__d`] = function(e,n,o){
    if(r.has(n))return;                 // ← 已经有同号模块？直接跳过！【先到先得】
    var i={dependencyMap:o, factory:e,  // factory = 那段 function(...){...}
           isInitialized:!1,
           publicModule:{exports:{}}};
    r.set(n,i)                          // ← 存进书架
  },
  e.__c = o,                            // ③ __c = 清空书架（clear）
  ...
  var r = o(),                          // ★ r = 书架本体！o() 就是 clear，返回 new Map()
  ...
  function o(){return r=new Map}        // clear 的实现：换一张全新的空表
  function i(e){                        // __r 的实现（要一个模块）
    var n=r.get(e);
    return n && n.isInitialized ? n.publicModule.exports : d(e,n)
  }
  function h(t,n){                      // 真正"执行"模块的地方
    if(!n) throw Error('Requiring unknown module "'+t+'".');
    n.isInitialized=!0;
    var _=n.factory, m=n.dependencyMap;
    var w=n.publicModule;
    return _(e,i,a,l,w,w.exports,m),    // ★ 调用 factory！把依赖表当第 7 个参数传进去
           w.exports                    // 返回 exports
  }
})(...);
```

**只要记住两件事**：

1. `var r = ... new Map()` 就是**全局模块表**（书架）。它是**运行时里的一个变量**，
   全 App 只有一份。
2. `__d` 第一行 `if(r.has(n))return;` —— **同号模块不会重复登记，先到先得**。
   （这一行是后面"合包"能成立的基石，先记住。）

## 2.3 解开 `function(g,r,i,a,m,e,d)` 这个签名

这是读压缩产物的**万能钥匙**。`__d` 里的 factory 有 7 个参数，顺序固定：

```js
__d(function(g, r, i, a, m, e, d){ ... }, 模块号, 依赖表)
            │  │  │  │  │  │  │
            │  │  │  │  │  │  └─ d = dependencyMap  依赖表（那个数组）
            │  │  │  │  │  └──── e = exports        我对外暴露的东西
            │  │  │  │  └─────── m = module         我自身
            │  │  │  └────────── a = importAll      import * as 用
            │  │  └───────────── i = importDefault  import default 用
            │  └──────────────── r = require（就是 __r）取别的模块用
            └─────────────────── g = global         全局对象
```

所以那句 `r(d[1])` 就是：「**调用 require，取我依赖表第 1 项**」。

## 2.4 一次 require 的完整生命周期

拿真实例子走一遍。业务入口 `938847` 要拿 `react-native`：

```
1. 执行 __r(938847)
2. __r 去书架 r 查 938847 → 找到了（__d 登记过）
3. 没执行过 → 调用它的 factory，传入依赖表 [572174,172571,868085]
4. factory 里执行到 r(d[1]) → 也就是 __r(172571)
5. __r 去书架查 172571 → 在不在？
      ├─ 在 → 执行/返回它（这就是"引用到别处登记的模块"）
      └─ 不在 → 抛 Requiring unknown module "172571"
6. 拿到 react-native，registerComponent 执行，界面注册成功
```

**第 5 步那个分支，就是"分包"能吃到的红利处** —— 只要书架上有，从哪个 bundle 登记的**无所谓**。

## 2.5 为什么必须用数字，不能用路径？

如果用路径 `r('react-native')`：
- 每个模块里都要存一长串字符串 → 产物**变大很多**
- 查表要按字符串比对 → **变慢**

用数字：
- 省空间（`r(d[1])` 比 `r('node_modules/react-native/index.js')` 短几百倍）
- 查表是 Map 的数字键 → 快

代价是：**数字是打包时"发"的号**，所以两个 bundle 必须对"发号规则"达成一致。
这个代价，正是第 4 章要解决的问题。

> **到这里你应该理解了**：RN 运行时靠一张全局 `modules` 表管模块；
> `__d` 是登记（且同号不覆盖），`__r` 是取用（查表 → 执行 factory）；
> 模块间引用已从名字变成数字。

---

# 第 3 章 Metro 打包：到底做了什么

Metro 是 RN 的打包器。它把一堆源文件变成**一个 bundle**，分 5 步：

## 3.1 第 1 步：建依赖图

从入口文件出发，顺着 `import` 一路追，把**所有用到的文件**都找出来。

你的 base 工程追出来 **489 个模块**（react、react-native 本体、babel helper……）。

## 3.2 第 2 步：转换（transform）

每个源文件过一遍 Babel：
- TypeScript / JSX → 普通 JS
- `import {X} from 'y'` → 转成 `r(d[0])` 这种运行时调用
- minify（压缩）：变量改名、去空格

## 3.3 第 3 步：编号 ★ 关键扩展点

这是**分包方案的命门**。Metro 要回答：**每个文件发几号？**

默认规则（`createModuleIdFactory`）：**从 0 开始，按模块被访问的顺序递增**。

```
base 工程：  入口排第 1 个 → 0
             react-native 排第 2 个 → 1
             ...
business 工程：业务入口排第 1 个 → 0      ← 又是 0！
             业务组件排第 2 个 → 1
```

**看问题了吗**：同一个 `react-native`，在 base 里是 1，在 business 里可能是 87。
两个包各说各话 → **对不上**。

好消息：Metro 把这个规则**开放出来给你改**（`serializer.createModuleIdFactory`）。
我们的方案就是从这里下手的（第 4 章讲）。

## 3.4 第 4 步：拼接（serialization）

把每个模块包成 `__d(function(){...}, 模块号, 依赖表)`，然后**首尾相接**：

```
[prelude] + [__d模块1][__d模块2]...[__d模块489] + [__r(启动入口)]
```

## 3.5 第 5 步：加上启动指令

最后一行 `__r(入口号)` —— 告诉运行时"从这儿开始跑"。

你的 base bundle 结尾是真实这样的：

```js
__r(258377);   // InitializeCore ← RN 自己的初始化
__r(469228);   // base 的入口（App 组件）
```

## 3.6 所以 bundle 就三段

```
┌─────────────────────────────────────────┐
│ ① prelude（搭书架 + 定义 __d/__r）        │  ~8706 字符
├─────────────────────────────────────────┤
│ ② 模块定义 __d × 489                     │  绝对大头（868KB 主要是这）
├─────────────────────────────────────────┤
│ ③ 启动指令 __r(258377); __r(469228);     │  两行
└─────────────────────────────────────────┘
```

**把这三段记牢，第 4 章的"拆包"就是围绕它们做手术。**

> **到这里你应该理解了**：Metro 干的是「追依赖 → 转换 → **发号** → 拼接 → 加启动指令」；
> 产物分 prelude / 模块定义 / 启动指令三段；而"发号"规则是开放的，可以被换掉。

---

# 第 4 章 拆包：拆的到底是什么

## 4.1 想达到的效果

- **base bundle**：RN 本体 + react + 基础库（868KB），跟 App 走
- **business bundle**：只有业务代码（4.8KB），可以独立更新

难点：两个包是**两个独立工程、两次独立构建**打出来的。

## 4.2 难点 1：发号规则不一致

如 3.3 所说，默认按"访问顺序"发号 → 两个工程必然算出不同数字。

**类比**：两个学校都按"报名顺序"编学号。同一个转学生，A 校是 1 号、B 校是 7 号。
现在要用一份名册管两个学校 → 对不上。

**解法**：**别按顺序编号，按"身份"编号**。

```js
id = parseInt(sha1(文件的工程内相对路径).slice(0,8), 16) % 2^20
```

`react-native/index.js` 这个**路径** → 哈希 → 一个固定数字。
不管在哪个工程算、哪台机器算、算几次，**结果都一样**。
两个工程各算各的，天然对齐，**不需要互相通信**。

> 这就是整个方案的**地基**：id 从"依赖运行时状态的序号"退化成"文件身份的纯函数"。

## 4.3 难点 2：如果 key 只用路径，会撞车

踩过的坑：base 工程和 business 工程里**都有** `index.js`、`App.tsx`、`app.json`。
如果 key 就是相对路径，这些**同名文件会算出同一个 id**。

后果很严重：业务工程的 `index.js` 会被判成"base 里已经有了" →
**被整段剥掉** → 业务入口消失 → 运行时 `Requiring unknown module`。

**解法**：key 分两类编址。

| 文件位置 | key | 效果 |
|---|---|---|
| `node_modules/**`（react、react-native、三方库） | 就用相对路径 | **跨工程共享**：两工程算出同一个号 ✓ |
| 其他（你自己写的源码） | `appId/相对路径` | **按工程隔离**：不同工程天然不同号 ✓ |

你的配置里 `appId` 是 `business-app`，所以业务的 `index.js` 的 key 是
`business-app/index.js`，与 base 的 `RnBundleSplit/index.js` 天然区分。

## 4.4 难点 3：业务包该放什么？

**直觉（错误）**：「把 base 已有的模块都从业务包里删掉」。
**为什么错**：业务代码里有 `r(d[1])` 指向 `172571`（react-native）。
如果你把这个**依赖表里的数字**也删了，运行时取不到 → 崩。

**正确做法**：

> **删掉 base 模块的「定义」（那段几千字符的 `__d` 代码）✓**
> **保留指向它的「引用」（依赖表里的数字）✓**

因为"定义"才是体积大头（react-native 本体几万行），"引用"只是一个数字。

## 4.5 真实对照：同一个业务文件，剥前 vs 剥后

**没剥之前**，业务工程打出来的包里会有**全部 489 个 base 模块 + 业务模块**（几百 KB）。

**剥完之后**，业务包**只剩 4 个模块定义**（4.8KB）。这是真实全文的核心部分：

```js
__d(function(...){...}, 591196, []);                                          // logic.js
__d(function(...){...}, 868085, [572174,975589,632901,172571,1021812,554779,591196]);  // App.tsx
__d(function(...){...}, 938847, [572174,172571,868085]);                      // index.js
__d(function(...){...}, 1021812, [572174,632901,172571,554779]);              // ScoreBoard.tsx

__r(938847);   // 业务入口
```

**注意 `868085`（App.tsx）的依赖表**，我用映射文件反查了每一项：

```
d[0] = 572174   【base 提供】@babel/runtime/helpers/interopRequireDefault.js
d[1] = 975589   【base 提供】@babel/runtime/helpers/slicedToArray.js
d[2] = 632901   【base 提供】react/index.js
d[3] = 172571   【base 提供】react-native/index.js        ← 界面能力，全靠这个
d[4] = 1021812  【业务包自己定义】ScoreBoard.tsx
d[5] = 554779   【base 提供】react/jsx-runtime.js
d[6] = 591196   【业务包自己定义】logic.js
```

**7 个依赖里，5 个来自 base，2 个是业务自己的。** 这就是"分包"的真面目 ——
业务包**不重复搬 react-native，只留下它的门牌号**。

## 4.6 那"哪些该删"谁说了算？—— mapping 文件

base 构建时会产出一份 **mapping**，本质是一张对照表：

```json
{ "base": { "modules": {
    "node_modules/react-native/index.js": 172571,
    "node_modules/react/index.js": 632901,
    "RnBundleSplit/index.js": 469228,
    ...489 条
}}}
```

业务工程构建时：
- 每个模块算 id → 先查 mapping
- **查到了** → 说明 base 有 → 剥掉它的定义、保留引用
- **没查到** → 说明是业务新增 → 保留

**mapping 就是两个工程之间的"契约"**。业务工程不需要 base 的源码、不需要 base 的 bundle，
只要这张表。

> **到这里你应该理解了**：拆包 = 换发号规则（路径哈希）+ 只删定义保留引用 +
> 用 mapping 当契约。业务包小，是因为它只留门牌号、不搬房子。

---

# 第 5 章 合起来：为什么能进同一个运行时

> 本章讲「为什么一定能合起来」。如果你想看**从点开 App 图标到两段 JS 真正执行的完整调用链**
> （Java → C++ → JS 三层、每跳的源码位置），见 [loading-chain.md](./loading-chain.md)。

## 5.1 关键前提：全局表是"运行时级"的

第 2 章说过，`modules` 表是 prelude 里的一个 `var r = new Map()`。
**它属于运行时，不属于 bundle。** 一个 App 只有一个运行时 → 只有一张表。

所以：

- base 先执行 → 往表里 `set` 了 489 条
- business 后执行 → 往**同一张表**里 `set` 4 条

**这就是"能合起来"的全部原理 —— 它们用的是同一张书架。**

## 5.2 顺序怎么保证？

两次加载都投到**同一条 JS 线程队列**上（异步加载），先入先出：

```
队列：[加载 base] → [加载 business]
执行：[base 的 prelude 建表 + 489 定义 + __r(入口)] → [business 的 4 定义 + __r(938847)]
```

base 先跑完，表里已经有 `172571` 了；business 再跑，`r(d[3])` 一查就取到。

> ⚠️ 这里有个真实的坑：不能为了"保证顺序"改用**同步加载**。
> 同步加载会在**调用线程（主线程）**上直接执行 JS —— 等于在 UI 线程上跑完整个 bundle。
> 用异步 + 同一条队列，顺序本来就有保证。

## 5.3 为什么两个包不会互相覆盖？

两个保护，都来自第 2 章：

1. **`__d` 里那行 `if(r.has(n))return;`** —— 同号模块**先到先得**，
   base 登记的不会被 business 覆盖。（就算误发了一样的号，也不会串。）
2. **业务包"通篇没有 prelude"** —— 这是刻意的。
   prelude 里有个 `__c`（清空书架），而且 **prelude 在启动时就会调用它**：
   `var modules = clear()` —— 这就是"搭一个空书架"的动作。
   如果业务包也带 prelude，它一执行就会 `modules = new Map()`，
   **把 base 那 489 条全清空** → 业务代码一 require 就崩。
   所以业务包构建时用 `modulesOnly: true`，**只出 `__d` 定义，不出 prelude** ——
   没有任何东西会在启动时清表。

   > 补充一个容易搞混的细节：业务包**尾部**还有一段"垫片"（split 命令加的兜底代码），
   > 它里面**确实出现了 `__c` 这个字符串**（`g.__c=function(){m=new Map();...}`）。
   > 但它不构成威胁，两个原因：① 垫片第一行判断 `__r`/`__d` 已存在就直接 `return`，
   > base 跑过的情况下它整段都不会执行；② 就算执行了，它也只是**定义** `__c`，
   > 并不会**调用**它 —— 清表是要主动调 `__c()` 才发生的。
   > 真正"不变量"仍然只有一条：**业务包里没有会在启动时执行 `clear()` 的 prelude**。

## 5.4 完整链路走一遍（真实数据）

```
┌─ 阶段 1：加载 base（868KB）─────────────────────────────┐
│  prelude: 建表 r = new Map()，定义 __d / __r            │
│  __d(..., 172571, [])      → 表里登记 react-native      │
│  __d(..., 469228, [172571])→ 表里登记 base 入口         │
│  ... 共 489 条 ...                                      │
│  __r(258377) → 跑 InitializeCore                        │
│  __r(469228) → 跑 base 入口，界面出 "RnBundleSplit"     │
└─────────────────────────────────────────────────────────┘
                          ↓ 同一张表继续用
┌─ 阶段 2：加载 business（4.8KB）──────────────────────────┐
│  __d(..., 938847, [572174,172571,868085])               │
│                           ↑ 业务入口要 react-native     │
│      但业务包**不定义** 172571，只是"引用"              │
│  __r(938847)                                            │
│    └→ 查表找 172571 → base 阶段登记过 → 取到！           │
│       └→ AppRegistry.registerComponent('BusinessApp')   │
│          └→ 界面出 "BusinessApp"  ✓                     │
└─────────────────────────────────────────────────────────┘
```

真机日志各有一行，**同一个进程**：

```
I/ReactNativeJS: Running "RnBundleSplit"
I/ReactNativeJS: Running "BusinessApp"
```

## 5.5 原生侧是怎么"加载两段"的

Java/Kotlin 侧，RN 允许你换掉"bundle 从哪来"的加载器（`JSBundleLoader`）。
我们写了一个，在里面**顺序加载两次**：

```kotlin
override fun loadScript(delegate: JSBundleLoaderDelegate): String {
    delegate.loadScriptFromAssets(context.assets, "assets://index.android.bundle", false)   // ① base
    delegate.loadScriptFromAssets(context.assets, "assets://business.android.bundle", false) // ② business
    return BASE_ASSET_URL
}
```

就这么多。`ReactInstanceManager` 一次运行只会调一次 `loadScript`，
我们在这个"一次"里塞了两次加载 → 两段 JS 进了同一个运行时。

> **到这里你应该理解了**：能合起来，靠的是"运行时只有一张全局表" +
> "业务包不含 prelude 所以不会清表" + "同一条 JS 队列保证 base 先跑"。

---

# 第 6 章 全流程串一遍

```
【开发期】
你写源码：一个文件一个模块，import 互相引用
        │
        ▼
【Metro 打包】追依赖 → 转换 → 发号 → 拼接三段 → 加启动指令
        │                          ↑
        │                  这里被换成「路径哈希」（否则跨工程对不上）
        ▼
  base    = [prelude][489个__d][__r入口]       868KB   + mapping（契约）
  business= [4个__d][__r业务入口]               4.8KB   ← 只删定义、留引用
        │
        ▼
【塞进 APK】两个文件都放 assets/，随 App 一起装到手机上
        │
        ▼
【运行时】自定义 JSBundleLoader 顺序加载两段到同一条 JS 队列
        │
        ▼
  阶段1：base 建全局表、登记 489 个模块
  阶段2：business 登记 4 个模块；它引用 react-native(172571)
         → 查同一张表 → base 登记过 → 取到 → 界面渲染成功
```

---

# 附录 A：三个难题 ↔ 三个手段（总复习）

| 难题 | 手段 | 在哪一章 |
|---|---|---|
| 两个工程发号必须一致 | id 改成**路径哈希**（不依赖顺序） | 4.2 |
| 同名文件会撞号 | key 分两类：`node_modules` 共享 / 源码按 `appId` 隔离 | 4.3 |
| 业务包不能重复搬 react-native | **只删定义、保留引用** | 4.4 |
| 业务包不能清空 base 的表 | 走 `modulesOnly`，**通篇不含 prelude** | 5.3 |
| 顺序必须 base 先 | 同一条 JS 队列**先入先出**（用异步，别用同步） | 5.2 |
| 谁该删谁该留 | **mapping 当契约**（路径 → id 对照表） | 4.6 |

# 附录 B：初学者常见困惑

**Q：`__d`、`__r` 是谁造的？**
A：prelude 造的，写在 bundle 最开头。所以**每个带 prelude 的 bundle 都会重新定义它们**。

**Q：业务包里的 `__r` 从哪来？**
A：从 base 来。业务包自己不含 prelude，它执行时 base 已经把 `__r` 挂到全局了。

**Q：业务包尾部那段垫片是干嘛的？里面不是也有清表逻辑吗？**
A：它是 `split` 命令加的**兜底**，本意是"万一 base 没加载，自己搭个最小的书架"。
但两个原因让它成了 no-op：① 第一行判断 `__r`/`__d` 已存在就 `return`，正常合包场景下它整段不执行；
② 它只**定义** `__c`，不会主动**调用** —— 清表要调 `__c()` 才发生。
另外它还有个位置 bug（被追加到文件末尾，而 `__d` 定义在开头），
所以业务包其实**不能单独加载**（会报 `ReferenceError: __d is not defined`）——
这属于已知边界，详见 `implementation.md` 第 7.2 节。

**Q：那业务包单独跑会怎样？**
A：会报 `ReferenceError: __d is not defined` —— 因为没人给它搭书架。
（这个项目里业务包确实不能单独加载，属于已知边界。）

**Q：数字编号会不会撞？**
A：哈希取模理论上会。这个项目在生成 mapping 时**显式检测碰撞并直接报错**，不静默通过。

**Q：为什么业务包里有的 `__d` 依赖表是空的 `[]`？**
A：`logic.js` 那个模块是**纯逻辑，谁也不 import**，所以依赖表为空。

**Q：怎么自己验证这些？**
A：打开 `business-app/output/business.android.release.bundle`，
它只有 4.8KB，**可以整个读完** —— 对照第 1、2 章的表格看，全部能对上。

# 附录 C：想自己验证的几条命令

```bash
# 看业务包全文（很短，适合当教材读）
cat business-app/output/business.android.release.bundle

# 看 base 的 prelude（第一个 __d 之前的全部内容）
python3 -c "
s=open('host-app/output/base.android.release.bundle').read()
print(s[:s.find('__d(')])"

# 反查某个 id 是哪个文件
python3 -c "
import json; m=json.load(open('host-app/output/base.android.release.mapping.json'))
rev={v:k for k,v in m['base']['modules'].items()}
for i in [172571,632901,469228,938847]: print(i, rev.get(i,'(业务包自定义)'))"
```
