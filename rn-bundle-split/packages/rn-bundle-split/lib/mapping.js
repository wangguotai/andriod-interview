'use strict';

/**
 * mapping 文件的读写与校验。
 *
 * 设计目标：**mapping 一旦生成，就是业务工程唯一需要知道的东西**。
 * 业务工程不需要 base 工程源码、不需要 base bundle、不需要 node_modules 版本对齐，
 * 只要拿到 mapping.json，就能：
 *   1. 算出与 base 完全一致的 module id；
 *   2. 知道哪些模块（按路径）已经在 base 里，需要从业务包里剔除。
 */
const fs = require('fs');
const path = require('path');
const { sha1, logger } = require('./util');

const SCHEMA_VERSION = 1;
const TOOL_NAME = 'rn-bundle-split';

function createSplitMapping({
  projectDir,
  platform,
  dev,
  minify,
  buildType,
  appId,
  hermesEnabled,
  globalPrefix,
  bundleFile,
  bundleCode,
  runtimePreCodeChars,
  sourceMapFile,
  moduleIdByPath,
}) {
  const modules = {};
  const entries = [...moduleIdByPath.entries()].sort((a, b) => a[1] - b[1]);
  for (const [moduleKey, id] of entries) {
    modules[moduleKey] = id;
  }
  return {
    schemaVersion: SCHEMA_VERSION,
    tool: TOOL_NAME,
    createdAt: new Date().toISOString(),
    platform,
    buildType,
    appId: appId || null,
    dev,
    minify,
    hermes: {
      enabled: Boolean(hermesEnabled),
      bytecodeSuffix: '.hbc',
    },
    globalPrefix: globalPrefix || '',
    idMask: {
      // 与 metro-runtime/polyfills/require.js 的 unpackModuleId 保持一致
      shift: 16,
      localMask: 65535,
    },
    base: {
      bundleFile: bundleFile ? path.basename(bundleFile) : null,
      bundleBytes: bundleCode != null ? Buffer.byteLength(bundleCode) : null,
      bundleSha1: bundleCode != null ? sha1(bundleCode) : null,
      // runtime 前缀（prelude + polyfills + InitializeCore）在文件里的字符数，
      // 业务包剥离工具在关闭 sourcemap 时用它做兜底校验。
      runtimePreCodeChars: runtimePreCodeChars ?? null,
      moduleCount: entries.length,
      modules,
    },
    sourceMap: sourceMapFile ? path.basename(sourceMapFile) : null,
  };
}

/**
 * 生成「设备版」mapping：把 module id 按 segment/local 打包。
 *
 * 只在做 RAM bundle / segment 懒加载时才需要。
 * id = (segmentId << 16) + localId，其中 segment 0 是主包。
 */
function createDeviceMapping(mapping) {
  const segmentId = 1;
  const modulesById = {};
  for (const [modulePath, id] of Object.entries(mapping.base.modules)) {
    modulesById[id] = { path: modulePath, segmentId };
  }
  return {
    ...mapping,
    device: {
      segmentId,
      moduleIds: Object.keys(mapping.base.modules).length,
    },
    modulesById,
  };
}

function writeMapping(file, mapping) {
  fs.mkdirSync(path.dirname(path.resolve(file)), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(mapping, null, 2) + '\n', 'utf8');
  return file;
}

function readMapping(file) {
  const abs = path.resolve(file);
  if (!fs.existsSync(abs)) {
    throw new Error(`mapping 文件不存在: ${abs}`);
  }
  let parsed;
  try {
    parsed = JSON.parse(fs.readFileSync(abs, 'utf8'));
  } catch (e) {
    throw new Error(`mapping 文件不是合法 JSON: ${abs}\n${e.message}`);
  }
  validateMapping(parsed, abs);
  return parsed;
}

function validateMapping(mapping, file) {
  const where = file ? ` (${file})` : '';
  if (mapping == null || typeof mapping !== 'object') {
    throw new Error(`mapping 内容不是对象${where}`);
  }
  if (mapping.tool !== TOOL_NAME) {
    throw new Error(
      `mapping 不是由 ${TOOL_NAME} 生成（tool=${JSON.stringify(mapping.tool)}）${where}`,
    );
  }
  if (mapping.schemaVersion !== SCHEMA_VERSION) {
    throw new Error(
      `mapping schemaVersion 不支持：期望 ${SCHEMA_VERSION}，实际 ${mapping.schemaVersion}${where}`,
    );
  }
  if (!mapping.base || typeof mapping.base.modules !== 'object') {
    throw new Error(`mapping 缺少 base.modules${where}`);
  }
  const ids = new Map();
  for (const [modulePath, id] of Object.entries(mapping.base.modules)) {
    if (!Number.isInteger(id)) {
      throw new Error(`module id 不是整数: ${modulePath} -> ${id}${where}`);
    }
    if (ids.has(id)) {
      // 哈希取模理论上存在碰撞，这里显式报错而不是静默出错（见 docs 的诚实边界）。
      throw new Error(
        `module id 冲突: id=${id} 同时属于 ${ids.get(id)} 与 ${modulePath}${where}`,
      );
    }
    ids.set(id, modulePath);
  }
  return true;
}

/**
 * 提供与 ID_MASK_SHIFT 一致的打包/解包能力，方便业务侧直接调用。
 * 与 metro runtime 的 packModuleId / unpackModuleId 等价。
 */
function packModuleId({ segmentId, localId }, shift = 16) {
  return (segmentId << shift) + localId;
}

function unpackModuleId(moduleId, shift = 16) {
  const localMask = ~0 >>> shift;
  return {
    segmentId: moduleId >>> shift,
    localId: moduleId & localMask,
  };
}

module.exports = {
  SCHEMA_VERSION,
  TOOL_NAME,
  createSplitMapping,
  createDeviceMapping,
  writeMapping,
  readMapping,
  validateMapping,
  packModuleId,
  unpackModuleId,
  logger,
};
