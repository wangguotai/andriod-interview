'use strict';

/**
 * 业务工程侧的唯一入口：applyPreset(projectConfig, splitConfig, projectDir)
 *
 * 只做两件事，全部走 Metro 公开扩展点：
 *   1. serializer.createModuleIdFactory —— 换掉自增 id，改成
 *      「先查 base mapping，查不到再用同一套路径哈希」
 *   2. serializer.processModuleFilter —— 剥掉 base 里已有模块的 verboseName
 *      （dev 模式必须；否则 base 与业务包的同名 verboseName 会互相覆盖
 *        global.__r 的 verboseName 登记，Fast Refresh / require-by-name 直接错乱）
 *
 * 为什么不用「过滤掉 base 模块」的直觉做法：那样业务模块的 dependencyMap 里
 * 仍会保留 base 的 id，运行时 r(下标) 取到 undefined。base 模块本来就该由
 * base 提供注册 —— 引用必须保留，只是业务包里不再重复定义。
 */
const path = require('path');
const { readMapping } = require('./mapping');
const { hashModuleId } = require('./moduleIdFactory');
const { makeModuleKeyResolver, readAppId } = require('./util');

function applyPreset(projectConfig, splitConfig, projectDir) {
  if (!splitConfig || !splitConfig.mapping) {
    throw new Error(
      'rn-bundle-split: rn-bundle-split.config.json 缺少 mapping 字段',
    );
  }
  const mappingPath = path.isAbsolute(splitConfig.mapping)
    ? splitConfig.mapping
    : path.resolve(projectDir, splitConfig.mapping);
  const mapping = readMapping(mappingPath);
  const baseModules = mapping.base.modules;
  const baseIds = new Set(Object.values(baseModules));
  const salt = splitConfig.idSalt || '';
  const globalPrefix = splitConfig.globalPrefix || mapping.globalPrefix || '';
  const appId = splitConfig.appId || readAppId(projectDir);

  // 与 base 产出 mapping 时**完全同一个** key 解析器
  const resolveKey = makeModuleKeyResolver(projectDir, appId);

  // 诊断统计，打包结束后由 verify 命令读取
  const diagnostics = {
    mappingPath,
    appId,
    baseModuleCount: baseIds.size,
    hits: 0,
    misses: new Set(),
    idCollisions: [],
  };

  const baseCreateModuleIdFactory =
    projectConfig.serializer && projectConfig.serializer.createModuleIdFactory
      ? projectConfig.serializer.createModuleIdFactory
      : null;

  const createModuleIdFactory = () => {
    const idsByPath = new Map();
    const idOwner = new Map();
    return (absolutePath) => {
      const cached = idsByPath.get(absolutePath);
      if (cached !== undefined) {
        return cached;
      }
      const key = resolveKey(absolutePath);
      let id;
      if (Object.prototype.hasOwnProperty.call(baseModules, key)) {
        id = baseModules[key];
        diagnostics.hits += 1;
      } else {
        // 业务新增模块：与 base 生成时同一套算法（同一 salt），保证跨工程稳定
        id = hashModuleId(key, salt);
        diagnostics.misses.add(key);
      }
      idsByPath.set(absolutePath, id);
      const owner = idOwner.get(id);
      if (owner !== undefined && owner !== key) {
        diagnostics.idCollisions.push({ id, a: owner, b: key });
      } else {
        idOwner.set(id, key);
      }
      return id;
    };
  };

  const baseProcessModuleFilter =
    projectConfig.serializer && projectConfig.serializer.processModuleFilter;

  const processModuleFilter = (module) => {
    // 保留原有过滤逻辑（工程可能自定义）
    if (typeof baseProcessModuleFilter === 'function') {
      if (!baseProcessModuleFilter(module)) {
        return false;
      }
    }
    // 非 JS 模块由 serializer 自行处理，这里不拦
    const isJs = Array.isArray(module.output)
      ? module.output.some((o) => o.type && o.type.startsWith('js/'))
      : true;
    if (!isJs) {
      return true;
    }
    const key = resolveKey(module.path);
    if (Object.prototype.hasOwnProperty.call(baseModules, key)) {
      // 这个模块由 base 提供。业务包里必须保留它的引用（dependencyMap 里的 id），
      // 但不需要重复登记 verboseName。
      return false;
    }
    return true;
  };

  const serializer = {
    ...(projectConfig.serializer || {}),
    createModuleIdFactory,
    processModuleFilter,
  };

  const merged = {
    ...projectConfig,
    serializer,
  };
  // 内部传参：把诊断与 base 表挂在**不可枚举**属性上。
  // 这样它不会出现在 metro 的 config 校验里（否则会报 Unknown option 警告），
  // 但仍可被 split/verify 读取。
  Object.defineProperty(merged, '__rnBundleSplit', {
    value: {
      mappingPath,
      baseIds,
      baseModules,
      diagnostics,
      globalPrefix,
      splitConfig,
      appId,
      resolveKey,
      isBaseModule(modulePath) {
        return Object.prototype.hasOwnProperty.call(
          baseModules,
          resolveKey(modulePath),
        );
      },
    },
    enumerable: false,
    configurable: true,
  });
  return merged;
}

module.exports = { applyPreset };
