'use strict';

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

/**
 * 从「目标工程」的 node_modules 里解析模块。
 *
 * 本工具被设计成可以放进任意 RN 工程使用，因此绝不能依赖工具自身目录下的
 * metro / react-native —— 必须使用目标工程安装的那一份（版本可能与本工具开发时不同）。
 */
function resolveFromProject(projectDir, request) {
  return require(require.resolve(request, { paths: [projectDir] }));
}

/** 尝试解析，失败返回 null（用于可选依赖）。 */
function tryResolveFromProject(projectDir, request) {
  try {
    return resolveFromProject(projectDir, request);
  } catch (e) {
    return null;
  }
}

/** 统一成 POSIX 风格相对路径：跨平台消除 \\ 与绝对前缀。 */
function toPosixRel(projectDir, absolutePath) {
  if (typeof absolutePath !== 'string') {
    return String(absolutePath);
  }
  let p = absolutePath.replace(/\\/g, '/');
  const root = path.resolve(projectDir).replace(/\\/g, '/');
  if (p === root) {
    return '.';
  }
  if (p.startsWith(root + '/')) {
    p = p.slice(root.length + 1);
  }
  return p;
}

function sha1(input) {
  return crypto.createHash('sha1').update(input).digest('hex');
}

/**
 * 「可共享路径」编址规则 —— 整个拆分方案的关键设计点。
 *
 * 问题：如果 module id 只由「工程内相对路径」决定，那么两个工程里
 * 同名的文件（index.js、App.tsx、app.json……）会算出同一个 id，
 * 于是业务工程的入口会被误判成「base 里已经有了」而被整个过滤掉。
 *
 * 规则：
 *   - node_modules 下的模块（react / react-native / 三方库）：**跨工程共享**，
 *     键就是相对路径本身。base 与业务工程必须算出一致的 id，才能靠 base 提供注册。
 *   - 其余（工程自有源码）：**按工程命名空间隔离**，键为 `${appId}/${相对路径}`。
 *     同一个工程内稳定，不同工程天然不同，绝不会互相顶掉。
 *
 * 代价（诚实说明）：不在 node_modules 里的 monorepo workspace 包（相对路径形如
 *   ../pkg/...）不会参与共享，会被当作各自工程的自有代码重复打包。
 *   需要共享时把它们软链/安装进 node_modules。
 */
function isSharedPath(relPath) {
  return relPath === 'node_modules' || relPath.startsWith('node_modules/');
}

function keyForModule(relPath, appId) {
  if (isSharedPath(relPath)) {
    return relPath;
  }
  return appId ? `${appId}/${relPath}` : relPath;
}

/**
 * 从目标工程 package.json 的 name 推导 appId（稳定、无需人工维护）。
 * 同名工程会冲突，此时请用 --app-id 显式指定。
 */
function readAppId(projectDir) {
  try {
    const pkg = JSON.parse(
      fs.readFileSync(path.join(projectDir, 'package.json'), 'utf8'),
    );
    if (pkg && typeof pkg.name === 'string' && pkg.name.trim()) {
      return pkg.name.trim();
    }
  } catch (e) {
    // 忽略：下面给兜底值
  }
  return path.basename(path.resolve(projectDir));
}

/**
 * 统一的「绝对路径 -> module key」解析器。
 * 全流程（base 产出 mapping、业务工程查表）必须用同一个解析器，
 * 否则 id 会对不上。
 */
function makeModuleKeyResolver(projectDir, appId) {
  return (absolutePath) =>
    keyForModule(toPosixRel(projectDir, absolutePath), appId);
}

/** 判断某个 key 是否来自 node_modules（即「可跨工程共享」的那一类）。 */
function keyIsShared(key) {
  return isSharedPath(key);
}

/** 业务工程侧反查：key -> 工程内相对路径（仅用于日志/报告）。 */
function displayPathFromKey(key, appId) {
  if (isSharedPath(key)) {
    return key;
  }
  const prefix = appId ? `${appId}/` : '';
  return prefix && key.startsWith(prefix) ? key.slice(prefix.length) : key;
}

function formatBytes(n) {
  if (n < 1024) {
    return `${n} B`;
  }
  if (n < 1024 * 1024) {
    return `${(n / 1024).toFixed(1)} KB`;
  }
  return `${(n / 1024 / 1024).toFixed(2)} MB`;
}

function humanCount(n) {
  return n.toLocaleString('en-US');
}

/** 极简 logger：不引第三方依赖，保持工具可零依赖落地。 */
const logger = {
  info: (msg) => console.log(msg),
  ok: (msg) => console.log(`\u2713 ${msg}`),
  warn: (msg) => console.warn(`! ${msg}`),
  error: (msg) => console.error(`\u2717 ${msg}`),
  step: (msg) => console.log(`\n\u25b8 ${msg}`),
};

module.exports = {
  resolveFromProject,
  tryResolveFromProject,
  toPosixRel,
  sha1,
  formatBytes,
  humanCount,
  logger,
  isSharedPath,
  keyForModule,
  readAppId,
  makeModuleKeyResolver,
  keyIsShared,
  displayPathFromKey,
};
