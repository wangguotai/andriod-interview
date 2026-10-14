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

  // 复刻 RN CLI 的 getOverrideConfig：补齐 InitializeCore 与 core polyfills。
  const runBeforeMainModule = ['react-native/Libraries/Core/InitializeCore'];
  const originalRunBefore = baseConfig.serializer.getModulesRunBeforeMainModule;
  const originalPolyfills = baseConfig.serializer.getPolyfills;

  const merged = {
    ...baseConfig,
    serializer: {
      ...baseConfig.serializer,
      getModulesRunBeforeMainModule: () => {
        const fromProject = Array.isArray(originalRunBefore)
          ? originalRunBefore
          : typeof originalRunBefore === 'function'
            ? originalRunBefore()
            : [];
        return [...fromProject];
      },
      getPolyfills: (opts) => {
        const fromProject =
          typeof originalPolyfills === 'function'
            ? originalPolyfills(opts)
            : Array.isArray(originalPolyfills)
              ? originalPolyfills
              : [];
        return [...fromProject];
      },
    },
  };

  // getDefaultConfig(projectRoot) 已经带了 InitializeCore + js-polyfills；
  // 只有用户手写了裸 metro config 时才需要手工补。这里只做「没有才补」的兜底。
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
