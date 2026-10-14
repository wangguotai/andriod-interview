'use strict';

/**
 * init / uninit：让业务工程「一行命令接入」。
 *
 * init 做四件事：
 *   1. 校验 mapping 是否存在且合法（schemaVersion / tool 校验）
 *   2. 在业务工程 node_modules 下建软链，指向本工具（不写 package.json、不装依赖）
 *   3. 写 rn-bundle-split.config.json（业务工程唯一需要维护的文件）
 *   4. 包一层 metro.config.js，并给出可撤销的备份
 */
const fs = require('fs');
const path = require('path');
const { readMapping } = require('./mapping');
const overlay = require('./config-overlay');
const { logger, resolveFromProject, readAppId } = require('./util');
const { parseArgs, bool } = require('./build');

function runInit(argv, toolRoot) {
  const args = parseArgs(argv);
  const projectDir = path.resolve(args.project || process.cwd());
  if (!args.mapping) {
    throw new Error(
      '缺少 --mapping <base 的 mapping.json>\n' +
        '例：rn-bundle-split init --mapping ../host-app/output/base.android.release.mapping.json',
    );
  }
  const mappingPath = path.resolve(projectDir, args.mapping);

  logger.step('接入 rn-bundle-split');
  logger.info(`  业务工程 : ${projectDir}`);

  // 1) mapping 校验
  const mapping = readMapping(mappingPath);
  const moduleCount = Object.keys(mapping.base.modules).length;
  logger.ok(`mapping 合法 : ${mappingPath}`);
  logger.info(
    `  platform=${mapping.platform} buildType=${mapping.buildType} ` +
      `hermes=${mapping.hermes && mapping.hermes.enabled} 模块数=${moduleCount}`,
  );

  // 2) 软链本工具
  const link = overlay.linkToolIntoProject(projectDir, toolRoot);
  logger.ok(`工具已链接 : ${link}`);

  // 3) 写 config
  const configPath = path.join(projectDir, overlay.CONFIG_BASENAME);
  const mappingForConfig = path
    .relative(projectDir, mappingPath)
    .split(path.sep)
    .join('/');
  const splitConfig = {
    $comment:
      '由 rn-bundle-split init 生成。mapping 指向 base 工程产出的映射文件；' +
      'idSalt 必须与生成 base 时一致（不一致会算出不同 module id）；' +
      'appId 用于隔离「工程自有源码」的 module key，不要随意改动。',
    mapping: mappingForConfig,
    idSalt: typeof args['id-salt'] === 'string' ? args['id-salt'] : mapping.idSalt || '',
    globalPrefix: mapping.globalPrefix || '',
    appId: typeof args['app-id'] === 'string' ? args['app-id'] : readAppId(projectDir),
  };
  fs.writeFileSync(configPath, JSON.stringify(splitConfig, null, 2) + '\n', 'utf8');
  logger.ok(`拆分配置   : ${configPath}`);
  logger.info(`  mapping = ${mappingForConfig}`);
  logger.info(`  appId   = ${splitConfig.appId}`);

  // 4) 写 preset（业务工程的 metro.config.js 会 require 它）
  const presetPath = overlay.writePreset(projectDir, {});
  logger.ok(`预设文件   : ${presetPath}`);

  // 4) 包一层 metro.config.js
  const wrap = overlay.wrapMetroConfig(projectDir);
  if (!wrap.wrapped) {
    logger.warn(`未能自动集成：${wrap.reason}`);
    logger.info(
      '  请手工在 metro.config.js 里改为：\n' +
        `    module.exports = require('./${overlay.PRESET_BASENAME}')(module.exports);`,
    );
  } else {
    logger.ok(`metro 配置已集成 : ${wrap.file}`);
    if (wrap.backup) {
      logger.info(`  原始文件备份为 ${path.basename(wrap.backup)}（uninit 可还原）`);
    } else {
      logger.info('  已是集成状态，未重复改动');
    }
  }

  logger.info('');
  logger.info('下一步：');
  logger.info('  npx rn-bundle-split split --mapping ' + mappingForConfig);
  logger.info('  # 或直接走标准命令（preset 已生效）：npx react-native bundle ...');
  logger.info('');
  logger.info('注意：');
  logger.info('  - 业务包必须与 base 同口径（同 dev/minify、同 hermes 开关）');
  logger.info('  - 业务包在 base 之后加载；若单独加载会缺 RN 本体');

  return {mappingPath, configPath, wrap};
}

function runUninit(argv) {
  const args = parseArgs(argv);
  const projectDir = path.resolve(args.project || process.cwd());
  logger.step('撤销 rn-bundle-split 接入');
  const res = overlay.restoreMetroConfig(projectDir);
  if (!res.restored) {
    logger.warn(`未还原：${res.reason}`);
    return res;
  }
  logger.ok(`metro.config.js 已还原 : ${res.file}`);
  logger.info(
    `  ${overlay.PRESET_BASENAME} / ${overlay.CONFIG_BASENAME} 已删除（RNBS_KEEP_FILES=1 可保留）`,
  );
  return res;
}

module.exports = {runInit, runUninit};
