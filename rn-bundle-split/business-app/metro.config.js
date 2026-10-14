const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');

/**
 * 业务工程的 Metro 配置。
 *
 * 与普通 RN 工程完全一致 —— 拆分的接入全靠 `rn-bundle-split init`
 * 往这个文件尾部追加的一层 preset 包装，原始配置不动。
 *
 * @type {import('metro-config').MetroConfig}
 */
const config = {};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);

// ── rn-bundle-split 注入：只保留业务代码 ──────────────────────────────
// 原始配置已备份为 metro.config.js.rnbs-backup
const __rnbsPreset = require('./rn-bundle-split.preset.js');
module.exports = __rnbsPreset(module.exports);
