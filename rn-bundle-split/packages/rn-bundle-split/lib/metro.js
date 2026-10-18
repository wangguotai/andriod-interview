'use strict';

/**
 * Metro 侧两件事：
 *   1. 用「路径哈希 module id + processModuleFilter」产出 base bundle 与 mapping；
 *   2. 让业务工程直接用同一份 mapping（id 与 base 完全一致），并把 base 已有模块
 *      整个从业务包中剔除（不是简单过滤模块体，而是把依赖表里的引用一起改写成
 *      数字 + 去掉 verboseName，否则 dev 模式下 verboseName 的全局登记会与 base 冲突）。
 */
const fs = require('fs');
const path = require('path');
const {
  resolveFromProject,
  tryResolveFromProject,
  toPosixRel,
  sha1,
  logger,
} = require('./util');

/** 与 metro/src/lib/createModuleIdFactory.js 里的 ID_MASK_SHIFT 保持一致。 */
const ID_MASK_SHIFT = 16;

function posixRelative(from, to) {
  return path.relative(from, to).replace(/\\/g, '/');
}

/**
 * 生成一个「按路径哈希」的 module id 工厂。
 *
 * 为什么不用 metro 默认的 createModuleIdFactory（自增整数）：
 *   自增 id 依赖「模块被访问的顺序」，业务工程与基础工程的依赖图不同，
 *   顺序必然不同 —— 同一个文件会拿到不同 id，映射也就无从谈起。
 *   用「路径 -> 稳定数值」的确定性函数，两个工程无需共享任何运行时状态
 *   即可算出完全相同的 id 表。
 *
 * @param {string} projectDir 目标工程根目录（用于把绝对路径归一成相对路径）
 * @returns {(absolutePath: string) => number}
 */
function createStableModuleIdFactory(projectDir) {
  const seen = new Map();
  return function createModuleId(absolutePath) {
    let id = seen.get(absolutePath);
    if (typeof id === 'number') {
      return id;
    }
    // 只对「工程相对路径」做哈希，保证同一文件在不同机器/不同工作目录下 id 一致。
    // 目录分隔符统一成 '/'，Windows 上也不会漂移。
    const key = toPosixRel(projectDir, absolutePath);
    // 20 bit 保留给 local id（与 Metro 的 segment 打包约定一致）。
    id = parseInt(sha1(key).slice(0, 8), 16) % (1 << 20);
    seen.set(absolutePath, id);
    return id;
  };
}

/**
 * 归一化模块路径：把 metro 绝对路径变成「工程内相对路径」；
 * 工程外的模块（monorepo 上级、node_modules 的 realpath 等）用相对路径 ../ 表达，
 * 只要两个工程用同样的规则，就能互相识别。
 */
function makeNormalizePath(projectDir) {
  const root = path.resolve(projectDir);
  return (absolutePath) => {
    return posixRelative(root, path.resolve(absolutePath));
  };
}

/**
 * 基于「目标工程自己的 metro.config.js」加载配置。
 *
 * 关键：复用工程原有 config，只覆写 serializer 的这几项。
 * 尤其 getPolyfills / getModulesRunBeforeMainModule 由 RN CLI 的
 * getOverrideConfig 强制注入（见 @react-native/community-cli-plugin/dist/utils/loadMetroConfig.js），
 * 我们这里必须自己做同样的事，否则 base bundle 里会缺 core polyfills 与
 * InitializeCore，业务包再跑也就起不来。
 *
 * ⚠️ 上面这句"必须自己做同样的事"需要一点补充，否则会读错：
 *   RN CLI 的 getOverrideConfig 是**真的往数组里塞 InitializeCore**（它 override
 *   了工程的 getModulesRunBeforeMainModule，返回自己拼的数组）。
 *   而本函数**没有做等价的塞入**，走的是另一条路：**沿用工程配置的返回值**
 *   （RN 模板的 getDefaultConfig 本就会给 InitializeCore + js-polyfills），
 *   仅在工程没给时兜底补一次（见函数末尾的 if）。
 *   ⇒ 两条路殊途同归，但机制不同：CLI 是"覆盖"，我们是"透传 + 兜底"。
 *   之所以不走 CLI 那条路，是因为 CLI 的版本依赖 ctx.reactNativePath 与
 *   out-of-tree platform 表（都是 CLI 的运行时上下文），本工具作为独立打包
 *   入口拿不到那些 ctx，强行复刻反而更脆。
 */
async function loadProjectMetroConfig(projectDir, { platform = 'android' } = {}) {
  const { loadConfig } = resolveFromProject(projectDir, 'metro-config');
  if (typeof loadConfig !== 'function') {
    throw new Error(
      '无法从目标工程解析 metro-config 的 loadConfig；请确认工程内已安装 metro-config',
    );
  }
  const baseConfig = await loadConfig({ cwd: projectDir });

  const reactNativePath = (() => {
    try {
      return path.dirname(
        require.resolve('react-native/package.json', { paths: [projectDir] }),
      );
    } catch (e) {
      return null;
    }
  })();

  // ═══════════════════════════════════════════════════════════════════════════
  // serializer 的两个「启动期脚本」钩子 —— 先读这段再读代码
  // ═══════════════════════════════════════════════════════════════════════════
  //
  // 【1. 这两个钩子是干什么的】
  // metro 打完 bundle 后会在末尾追加几条 `__r(id);` 启动语句，顺序是：
  //
  //     getModulesRunBeforeMainModule() 给的模块  →  entry（工程入口 index.js）
  //
  // 前者就是 RN 的 InitializeCore：它装载 ErrorUtils、Promise、定时器、
  // console 转发、Systrace 等全局设施。**缺了它，bundle 加载后 require('react')
  // 会直接抛错** —— 这就是注释里说"业务包再跑也就起不来"的原因。
  // getPolyfills() 给的是 JS polyfill 的模块路径清单（如 js-polyfills/console.js）。
  //
  // 【2. ⚠️ 最容易误读的一点：这段包装**不补任何东西**】
  // 原注释写的是"补齐 InitializeCore 与 core polyfills"，容易让人以为下面这段
  // 代码里会看到 InitializeCore 被塞进数组。**并没有** —— 它只是调用工程配置
  // 原有的函数，把返回值原样传出去（原地转发 / pass-through）。
  //
  // 这两个值实际来自哪里，按优先级：
  //   ① 工程 metro.config.js 调了 getDefaultConfig(__dirname)（RN 模板的默认写法）
  //      → @react-native/metro-config/dist/index.js:64-67 已注入 InitializeCore
  //        与 js-polyfills。实测本工程：getModulesRunBeforeMainModule() 返回 1 项、
  //        getPolyfills() 返回 2 项 —— 已经齐了，这层包装不需再补。
  //   ② 若工程是「裸 metro 配置」（手写 module.exports = {...}，没走
  //      getDefaultConfig）→ 上面两个都返回空数组，此时由**本函数末尾那个
  //      `if (!current || current.length === 0)` 兜底**补一次。
  //      ⇒ 真正的"补齐"在兜底处，不在这里。
  //      （此处刻意不写行号：注释一插行号就失效，写"函数末尾的 if"更耐用）
  //
  // 【3. 那这层包装还有价值吗？有，且只有一个：形态归一化】
  // metro 自己给的永远是函数（metro-config/src/defaults/index.js:50-51 是
  // `() => []`），但**用户可以手写成数组**（如 getPolyfills: ['./a.js']）。
  // 这里统一成「一定是函数、且一定返回数组」，调用方（Server.js 三处）就不必
  // 每次判断形态。若工程配置没问题，这层包装的行为与"直接引用原函数"完全一致
  // —— 它是防御性代码，不是功能代码。
  //
  // 【4. 为什么用 originalXxx 变量先把原值捞出来，而不是直接写在 merged 里】
  // 因为下方兜底会**覆盖** merged.serializer 上的同名 key。先把工程原值捕获在
  // 闭包里，兜底时才能调用 merged 上的函数来判断"工程到底配了没有"，而不会
  // 把自己绕进递归/自我覆盖。
  //
  // 【5. 为什么返回 [...fromProject] 而不是 fromProject 本身】
  // 防御性拷贝：避免调用方拿到数组后原地 push，污染工程配置里的那个数组。
  // （补充事实：metro 的 getAppendScripts.js:16 是
  //  `[...options.runBeforeMainModule, entryPoint]`，它自己就会复制，
  //   所以这层拷贝在当前 metro 版本下是冗余的 —— 留着是为了防上游改行为。）
  // ═══════════════════════════════════════════════════════════════════════════

  // 捞取工程配置的原始值（可能是函数，也可能是数组 —— 两种形态都要兼容）
  //
  // ⚠️ 下面这行是**死代码**（本次仅加注释，原样保留）：
  //      const runBeforeMainModule = ['react-native/Libraries/Core/InitializeCore'];
  //    赋值后**再无任何代码引用**（grep 'runBeforeMainModule' 只命中它自己与
  //    若干注释，其中 build.js:310 那条是讲 metro 行为的说明，与本行无关）。
  //    极易误导：
  //    看着像"InitializeCore 在这里就补进去了"，**实际没有** —— 真正生效的是
  //    本函数末尾的 tryResolveFromProject 兜底（见下）。建议你删掉（零风险）；
  //    若保留，请留着这段注释抵消误导。
  //    也不能"顺手修好它"（改成塞进 merged 数组）：只塞模块名是无效的，
  //    原因见下方兜底处的 ⚠️ 说明（metro 做的是绝对路径 === 比较）。
  const runBeforeMainModule = ['react-native/Libraries/Core/InitializeCore'];
  const originalRunBefore = baseConfig.serializer.getModulesRunBeforeMainModule;
  const originalPolyfills = baseConfig.serializer.getPolyfills;

  const merged = {
    ...baseConfig,
    serializer: {
      ...baseConfig.serializer,
      // metro 实际调用时会传 1 个参：entryPoint 相对 projectRoot 的路径
      // （Server.js:139/230/739 都是 `getModulesRunBeforeMainModule(rel)`）。
      //
      // ⚠️ 已知偏差（如实记录，**保持原行为未改**）：这里**没有**把这个参数
      // 继续传给 originalRunBefore。对 RN 默认实现无影响（它的形参个数为 0，
      // 实测 length=0，压根不读参数）；但若工程自定义了依赖 entryPoint 的实现，
      // 它拿到的是 undefined —— 这类"沉默的行为偏差"最难查，故在此明确标出。
      // 若要对齐 metro 语义，把 `originalRunBefore()` 改成
      // `originalRunBefore(entryPoint)` 并给箭头函数加回形参即可。
      getModulesRunBeforeMainModule: () => {
        const fromProject = Array.isArray(originalRunBefore)
          ? originalRunBefore // 手写数组形态
          : typeof originalRunBefore === 'function'
            ? originalRunBefore() // 标准函数形态（不转发 entryPoint，见上）
            : []; // 工程没配（不太可能，metro 默认给了函数）
        return [...fromProject];
      },
      // 与上面同构，差别只是这里把整个 opts 原样转发（metro 传的是
      // {platform, dev, ...}，见 Server.js 的 serializerOptions）
      getPolyfills: (opts) => {
        const fromProject =
          typeof originalPolyfills === 'function'
            ? originalPolyfills(opts)
            : Array.isArray(originalPolyfills)
              ? originalPolyfills
              : [];
        return [...fromProject];
      },
      // ⚠️ 另一处已知缺口（如实记录，未修）：下方兜底只覆盖
      // getModulesRunBeforeMainModule，**没覆盖 getPolyfills**。
      // 若工程是裸配置（polyfills 返回空数组），base bundle 会缺 console 等
      // polyfill。本工程走 getDefaultConfig，polyfills 非空，暂不受影响。
    },
  };

  // ── 兜底：工程配置里一个 InitializeCore 都没有时，手工补一次 ────────────────
  // 旧注释说 "getDefaultConfig(projectRoot) 已经带了 InitializeCore + js-polyfills"，
  // 这句话只对了一半，容易误导，补充事实：
  //   · metro 自己的默认值是 `() => []`（metro-config/src/defaults/index.js:50-51）→ 空
  //   · 真正的 InitializeCore 由 @react-native/metro-config 的 getDefaultConfig
  //     注入（dist/index.js:64-67）→ 非空
  //   所以"有没有"取决于工程有没有调 getDefaultConfig：调了就不进这个 if，
  //   裸配置才进来补。这里只做「没有才补」。
  //
  // ⚠️ 为什么必须用 tryResolveFromProject 解析成**绝对路径**，不能直接用模块名：
  //   metro 的 getAppendScripts.js 把 runBeforeMainModule 的每一项与依赖图里的
  //   module.path（绝对路径）做 `===` 精确比较（:16 拼接、:18 比较）：
  //       const paths = [...options.runBeforeMainModule, entryPoint];   // :16
  //       if (modules.some((module) => module.path === path)) {...}     // :18
  //   **匹配不上就静默跳过、不产出那条 __r(id)**。所以传相对模块名
  //   （'react-native/Libraries/Core/InitializeCore'）等于没传 —— 不报错，
  //   只是 InitializeCore 没被启动，直到运行时 require('react') 才炸。
  //   （这也正是上面那行死代码"不能顺手改成塞进数组"的原因：只塞模块名是无效的。）
  const current = merged.serializer.getModulesRunBeforeMainModule();
  if (!current || current.length === 0) {
    const resolved = tryResolveFromProject(
      projectDir,
      'react-native/Libraries/Core/InitializeCore',
    );
    if (resolved) {
      merged.serializer.getModulesRunBeforeMainModule = () => [resolved];
    } else {
      logger.warn(
        `未能在 ${projectDir} 里解析 InitializeCore，base bundle 可能缺少 RN 初始化代码`,
      );
    }
  }

  return { config: merged, reactNativePath };
}

/** 创建一次性的 Metro Server（bundle / getAssets 共用，避免重复构建依赖图）。 */
function createServer(config) {
  const projectDir = config && config.projectRoot ? config.projectRoot : process.cwd();
  // metro 0.80 的包入口不再导出 Server，必须直接引 src/Server（与 RN CLI 的做法一致：
  // @react-native/community-cli-plugin/dist/commands/bundle/buildBundle.js:16）
  const ServerCtor = resolveFromProject(projectDir, 'metro/src/Server');
  if (typeof ServerCtor !== 'function') {
    throw new Error('无法从目标工程解析 metro/src/Server');
  }
  return new ServerCtor(config);
}

function buildRequestOptions({
  entryFile,
  platform,
  dev,
  minify,
  modulesOnly,
  sourceMapUrl,
}) {
  return {
    entryFile,
    platform,
    dev,
    minify,
    modulesOnly,
    runModule: !modulesOnly,
    sourceMapUrl,
  };
}

/**
 * 收集「当前依赖图里所有 JS 模块」的路径清单（归一化后）。
 * 用 Server.getOrderedDependencies 复用同一份构建结果。
 */
async function collectModulePaths(server, { entryFile, platform, dev }) {
  const files = await server.getOrderedDependencies({
    entryFile,
    platform,
    dev,
    minify: false,
  });
  return files;
}

function writeJson(file, data) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(data, null, 2) + '\n', 'utf8');
}

module.exports = {
  ID_MASK_SHIFT,
  createStableModuleIdFactory,
  makeNormalizePath,
  loadProjectMetroConfig,
  createServer,
  buildRequestOptions,
  collectModulePaths,
  writeJson,
  buildServer: createServer,
};
