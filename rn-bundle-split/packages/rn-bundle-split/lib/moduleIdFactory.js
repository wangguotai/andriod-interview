'use strict';

/**
 * module id 计算：这是整个拆分方案的地基。
 *
 * 约束：base 工程与业务工程是两个独立进程、独立依赖图，必须能各自算出
 * **完全一致**的 id 表。因此 id 只能是「文件身份的纯函数」。
 *
 * 算法：
 *   id = parseInt(sha1(工程内相对路径).slice(0, 8), 16) % 2^20
 *
 * 为什么是 2^20：
 *   metro-runtime 的 unpackModuleId 用 `moduleId >>> 16` 取 segmentId、
 *   `moduleId & 0xffff` 取 localId。即低 16 位属于「段内局部 id」。
 *   把 id 限制在 20 位以内（低 16 位 + 4 位裕量），既远离有符号 32 位溢出，
 *   也不与 segment 打包约定打架。段懒加载时再叠加 segmentId<<16。
 *
 * 代价（诚实说明）：哈希取模存在碰撞可能。489 个模块时碰撞概率约
 *   n²/(2·2^20) ≈ 0.11%，量级可观，因此写入 mapping 时必须显式检测碰撞并报错，
 *   绝不能静默出错。
 *   若碰撞，可通过 --id-salt 更换盐值重新生成（base 与业务必须用同一盐）。
 */
const { sha1 } = require('./util');

const LOCAL_ID_BITS = 20;
const LOCAL_ID_SPACE = 1 << LOCAL_ID_BITS;

function hashModuleId(moduleKey, salt = '') {
  const key = salt ? `${salt}:${moduleKey}` : moduleKey;
  return parseInt(sha1(key).slice(0, 8), 16) % LOCAL_ID_SPACE;
}

/**
 * @param {string} projectDir 目标工程根目录
 * @param {(abs: string) => string} resolveKey 绝对路径 -> module key（见 util.makeModuleKeyResolver）
 * @param {string} salt
 */
function createStableModuleIdFactory(projectDir, resolveKey, salt = '') {
  const seen = new Map();
  const reverse = new Map(); // id -> key，用于碰撞检测
  const collisions = [];
  const createModuleId = (absolutePath) => {
    const cached = seen.get(absolutePath);
    if (cached !== undefined) {
      return cached;
    }
    const key = resolveKey(absolutePath);
    const id = hashModuleId(key, salt);
    seen.set(absolutePath, id);
    if (reverse.has(id) && reverse.get(id) !== key) {
      collisions.push({ id, a: reverse.get(id), b: key });
    } else {
      reverse.set(id, key);
    }
    return id;
  };
  createModuleId.__kind = 'rn-bundle-split-stable';
  createModuleId.__getCollisions = () => collisions.slice();
  createModuleId.__getEntries = () => seen;
  createModuleId.__resolveKey = resolveKey;
  return createModuleId;
}

/** 业务工程侧：不需要遍历依赖图，直接从 mapping 查表。 */
function createModuleIdFactoryFromMapping(resolveKey, mapping, { salt = '' } = {}) {
  const modules = mapping.base.modules;
  const factory = (absolutePath) => {
    const key = resolveKey(absolutePath);
    if (Object.prototype.hasOwnProperty.call(modules, key)) {
      return modules[key];
    }
    // 不在 base 里 -> 是业务新增模块，用同一套哈希算法（与 base 生成时同算法）
    return hashModuleId(key, salt);
  };
  factory.__resolveKey = resolveKey;
  return factory;
}

module.exports = {
  LOCAL_ID_BITS,
  LOCAL_ID_SPACE,
  hashModuleId,
  createStableModuleIdFactory,
  createModuleIdFactoryFromMapping,
};
