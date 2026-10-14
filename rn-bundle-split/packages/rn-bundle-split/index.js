'use strict';

const { applyPreset } = require('./lib/preset');
const { stripBaseModules, formatStripReport } = require('./lib/strip');
const { readMapping, writeMapping, validateMapping } = require('./lib/mapping');
const {
  hashModuleId,
  createStableModuleIdFactory,
  createModuleIdFactoryFromMapping,
} = require('./lib/moduleIdFactory');
const { analyzeBundle } = require('./lib/inspect');
const { runBaseBuild } = require('./lib/build');
const { runBusinessBuild } = require('./lib/split');
const { runVerify } = require('./lib/verify');
const overlay = require('./lib/config-overlay');

module.exports = {
  // 业务工程侧（被 metro.config.js / preset 调用）
  applyPreset,
  // 直接调用（不走 metro）
  stripBaseModules,
  formatStripReport,
  // 数据面
  readMapping,
  writeMapping,
  validateMapping,
  hashModuleId,
  createStableModuleIdFactory,
  createModuleIdFactoryFromMapping,
  analyzeBundle,
  // 命令实现
  runBaseBuild,
  runBusinessBuild,
  runVerify,
  overlay,
};
