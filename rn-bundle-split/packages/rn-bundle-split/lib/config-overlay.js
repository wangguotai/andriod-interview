'use strict';

/**
 * 把「拆分后的打包配置」落到业务工程里。
 *
 * 业务工程只需要跑标准 `react-native bundle`，因此必须通过 metro.config.js 生效。
 * 这里只做两件小事：
 *   1. 在业务幻目 node_modules 下建一个指向本工具的软链（不复制文件、不写依赖）；
 *   2. 写一个 preset 文件，并把 metro.config.js 包一层（先备份原始文件）。
 *
 * 之所以要「包一层」而不是直接改：业务工程原有的 customSerializer / resolver
 * 配置必须原样保留，我们只在最外层做 merge。
 */
const fs = require('fs');
const path = require('path');
const { logger } = require('./util');

const PRESET_BASENAME = 'rn-bundle-split.preset.js';
const CONFIG_BASENAME = 'rn-bundle-split.config.json';
const BACKUP_SUFFIX = '.rnbs-backup';

/** 业务工程侧识别的 metro config 模板特征。 */
const TEMPLATE_SIGNATURE = /mergeConfig\s*\(\s*getDefaultConfig\s*\(\s*__dirname\s*\)/;

function findMetroConfig(projectDir) {
  for (const name of ['metro.config.js', 'metro.config.cjs', 'metro.config.mjs']) {
    const p = path.join(projectDir, name);
    if (fs.existsSync(p)) {
      return p;
    }
  }
  return null;
}

function linkToolIntoProject(projectDir, toolRoot) {
  const nodeModules = path.join(projectDir, 'node_modules');
  if (!fs.existsSync(nodeModules)) {
    throw new Error(
      `业务工程缺少 node_modules: ${nodeModules}\n请先在该工程执行 npm install`,
    );
  }
  const target = path.join(nodeModules, 'rn-bundle-split');
  if (fs.existsSync(target) || isSymlink(target)) {
    const stat = fs.lstatSync(target);
    if (stat.isSymbolicLink()) {
      const current = fs.realpathSync(target);
      if (current === fs.realpathSync(toolRoot)) {
        return target;
      }
      fs.unlinkSync(target);
    } else {
      logger.warn(
        `${target} 已存在且不是软链，跳过链接。请确认它不会与本工具重名冲突。`,
      );
      return target;
    }
  }
  fs.symlinkSync(fs.realpathSync(toolRoot), target, 'junction');
  return target;
}

function isSymlink(p) {
  try {
    return fs.lstatSync(p).isSymbolicLink();
  } catch (e) {
    return false;
  }
}

function writeSplitConfig(projectDir, config) {
  const file = path.join(projectDir, CONFIG_BASENAME);
  fs.writeFileSync(file, JSON.stringify(config, null, 2) + '\n', 'utf8');
  return file;
}

function writePreset(projectDir, { mappingConfig, toolPackageName = 'rn-bundle-split' }) {
  const file = path.join(projectDir, PRESET_BASENAME);
  const body = `'use strict';
/**
 * 由 rn-bundle-split 生成，请勿手改。
 * 作用：让本工程产出的 bundle 「只保留业务代码」——
 *   - module id 改用与 base bundle 一致的稳定哈希（靠 mapping 查表）
 *   - base 里已有模块的代码与 verboseName 全部剥离
 *
 * 修改拆分参数请编辑 ${CONFIG_BASENAME}，不要改这个文件。
 */
const splitConfig = require('./${CONFIG_BASENAME}');

function resolveTool() {
  try {
    return require(${JSON.stringify(toolPackageName)});
  } catch (e) {
    if (process.env.RN_BUNDLE_SPLIT_HOME) {
      return require(process.env.RN_BUNDLE_SPLIT_HOME);
    }
    throw new Error(
      '无法解析 ' + ${JSON.stringify(toolPackageName)} +
        '，请在该工程执行 rn-bundle-split init，或设置 RN_BUNDLE_SPLIT_HOME'
    );
  }
}

module.exports = function preset(projectConfig) {
  const tool = resolveTool();
  return tool.applyPreset(projectConfig, splitConfig, __dirname);
};
`;
  fs.writeFileSync(file, body, 'utf8');
  return file;
}

/**
 * 把 metro.config.js 改成「先走 preset 再走原配置」。
 * 幂等：重复执行不会叠加包装。
 */
function wrapMetroConfig(projectDir) {
  const metroConfigPath = findMetroConfig(projectDir);
  if (!metroConfigPath) {
    return { wrapped: false, reason: '未找到 metro.config.js' };
  }
  const current = fs.readFileSync(metroConfigPath, 'utf8');
  if (current.includes(PRESET_BASENAME)) {
    return { wrapped: true, reason: '已经集成过（幂等跳过）', file: metroConfigPath };
  }

  const backupPath = metroConfigPath + BACKUP_SUFFIX;
  if (!fs.existsSync(backupPath)) {
    fs.copyFileSync(metroConfigPath, backupPath);
  }

  const isTemplate = TEMPLATE_SIGNATURE.test(current);
  let next;
  if (isTemplate) {
    // 模板形态：保留用户手写内容，用 preset 包裹最终导出。
    next = `${current.trimEnd()}

// ── rn-bundle-split 注入：只保留业务代码 ──────────────────────────────
// 原始配置已备份为 ${path.basename(metroConfigPath)}${BACKUP_SUFFIX}
const __rnbsPreset = require('./${PRESET_BASENAME}');
module.exports = __rnbsPreset(module.exports);
`;
  } else {
    next = `${current.trimEnd()}

// ── rn-bundle-split 注入：只保留业务代码 ──────────────────────────────
// 原始配置已备份为 ${path.basename(metroConfigPath)}${BACKUP_SUFFIX}
const __rnbsPreset = require('./${PRESET_BASENAME}');
module.exports = __rnbsPreset(module.exports);
`;
  }
  fs.writeFileSync(metroConfigPath, next, 'utf8');
  return { wrapped: true, file: metroConfigPath, backup: backupPath, template: isTemplate };
}

function restoreMetroConfig(projectDir) {
  const metroConfigPath = findMetroConfig(projectDir);
  if (!metroConfigPath) {
    throw new Error('未找到 metro.config.js');
  }
  const backupPath = metroConfigPath + BACKUP_SUFFIX;
  if (!fs.existsSync(backupPath)) {
    return { restored: false, reason: `没有备份文件 ${path.basename(backupPath)}` };
  }
  fs.copyFileSync(backupPath, metroConfigPath);
  for (const f of [PRESET_BASENAME, CONFIG_BASENAME]) {
    const p = path.join(projectDir, f);
    if (fs.existsSync(p) && process.env.RNBS_KEEP_FILES !== '1') {
      fs.unlinkSync(p);
    }
  }
  return { restored: true, file: metroConfigPath };
}

module.exports = {
  PRESET_BASENAME,
  CONFIG_BASENAME,
  BACKUP_SUFFIX,
  findMetroConfig,
  linkToolIntoProject,
  writeSplitConfig,
  writePreset,
  wrapMetroConfig,
  restoreMetroConfig,
};
