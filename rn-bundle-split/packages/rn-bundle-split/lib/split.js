'use strict';

/**
 * split 命令：在业务工程里产出「只含业务代码」的 bundle。
 *
 * ── 为什么业务包必须是 modulesOnly ────────────────────────────────────
 * base 与业务包共用同一张全局模块表（metro-runtime 的 `modules` Map，
 * 由 prelude 的 `var modules = clear()` 创建，global.__d/__r 闭包持有）。
 * 若业务包也带 runtime prelude，`__c()` 会执行 `modules = new Map()` ——
 * 把 base 注册的几百个模块**整个清空**，业务代码一 require react-native 就崩。
 * 因此业务包走 `modulesOnly: true`：只输出 __d 定义，不输出任何 prelude，
 * 同时也不输出 __r(entry) —— 入口启动由我们在最后手工追加。
 *
 * ── 这一步之后业务包里还剩什么 ──────────────────────────────────────
 * 只剩业务自身的 __d 定义（react-native / react 等全部来自 base），
 * 以及业务模块 dependencyMap 里指向 base 的数字引用（运行时靠 base 提供）。
 */
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');
const {
  resolveFromProject,
  tryResolveFromProject,
  toPosixRel,
  makeModuleKeyResolver,
  readAppId,
  formatBytes,
  humanCount,
  logger,
} = require('./util');
const { readMapping } = require('./mapping');
const { hashModuleId } = require('./moduleIdFactory');
const { analyzeBundle, parseRunModuleIds, collectReferencedIds } = require('./inspect');
const { loadProjectMetroConfig, createServer } = require('./metro');
const { applyPreset } = require('./preset');
const { stripBaseModules, runtimeShim } = require('./strip');
const { compileHermes } = require('./build');

async function runBusinessBuild(argv) {
  const {parseArgs, bool, DEFAULT_ENTRY, parseModuleRunOrder} = require('./build');
  const args = parseArgs(argv);
  const projectDir = path.resolve(args.project || process.cwd());
  const platform = args.platform || 'android';
  const dev = bool(args.dev, false);
  const minify = dev ? false : bool(args.minify, true);
  const entryFile = args.entry || DEFAULT_ENTRY[platform] || 'index.js';
  const salt = typeof args['id-salt'] === 'string' ? args['id-salt'] : '';
  const appId = typeof args['app-id'] === 'string' ? args['app-id'] : readAppId(projectDir);
  const mappingPath = path.resolve(
    args.mapping || path.join(projectDir, 'output', 'base.android.release.mapping.json'),
  );
  const outDir = path.resolve(args.out || path.join(projectDir, 'output'));
  const buildType = dev ? 'debug' : 'release';
  const wantHermes = bool(args.hermes, false);
  const wantSourceMap = bool(args['source-map'], false);
  const doStripCheck = bool(args.strip, true);

  const bundleName =
    args['bundle-name'] || `business.${platform}.${buildType}`;
  const bundleOut = path.join(outDir, `${bundleName}${wantHermes ? '.js' : '.bundle'}`);
  const reportOut = path.join(outDir, `${bundleName}.report.txt`);

  logger.step('产出业务 bundle（仅业务代码）');
  logger.info(`  工程        : ${projectDir}`);
  logger.info(`  mapping     : ${mappingPath}`);
  logger.info(`  platform    : ${platform}`);
  logger.info(`  entry       : ${entryFile}`);
  logger.info(`  模式        : ${buildType}${minify ? ' (minify)' : ''}`);
  logger.info(`  appId       : ${appId}`);

  const mapping = readMapping(mappingPath);
  const baseIds = new Set(Object.values(mapping.base.modules));
  const splitConfig = {
    mapping: mappingPath,
    idSalt: salt || (mapping.idSalt || ''),
    globalPrefix: mapping.globalPrefix || '',
    appId,
  };

  // 1) 载入工程配置，叠加「mapping 驱动」的 serializer
  const {config} = await loadProjectMetroConfig(projectDir, {platform});
  // 内部传参用：applyPreset 的结果只额外多一个不可枚举属性，
  // 因此可以安全地交给 metro 的 Server 做校验。
  const withPreset = applyPreset(config, splitConfig, projectDir);
  const diagnostics = withPreset.__rnBundleSplit.diagnostics;

  // 2) modulesOnly 构建：只出 __d
  const server = createServer(withPreset);
  let built;
  try {
    built = await server.build({
      entryFile,
      platform,
      dev,
      minify,
      modulesOnly: true,
      runModule: false,
    });
  } finally {
    server.end();
  }

  // 3) 入口 module id：用同一份确定性工厂算出来，并校验它确实出现在产物里
  const createModuleId = withPreset.serializer.createModuleIdFactory();
  const entryAbs = path.resolve(projectDir, entryFile);
  let entryId = createModuleId(entryAbs);
  let code = built.code;

  // minify 场景下 modulesOnly 产物可能被压成一行，仍要确认入口 id 存在
  let analysis = analyzeBundle(code, projectDir);
  if (!analysis.moduleIds.has(entryId)) {
    // 兜底：用同一个 key 解析器 + 哈希直接算（entry 一般是业务新增模块）
    const altId = hashModuleId(
      makeModuleKeyResolver(projectDir, appId)(entryAbs),
      splitConfig.idSalt,
    );
    if (analysis.moduleIds.has(altId)) {
      logger.warn(
        `入口 id 从图里取到 ${entryId}，但产物中实际是 ${altId}，已自动改用后者`,
      );
      entryId = altId;
    } else {
      throw new Error(
        `无法在业务产物中定位入口模块 id（尝试过 ${entryId}）。\n` +
          `产物中的模块 id: ${[...analysis.moduleIds].join(', ')}\n` +
          `这通常意味着 entry 路径被解析成了别的 realpath，请检查 ${entryAbs} 是否为软链。`,
      );
    }
  }

  // 4) 追加运行时垫片 + 入口启动语句
  const shim = runtimeShim(splitConfig.globalPrefix);
  code = `${code.trimEnd()}\n${shim}\n__r(${entryId});\n`;

  // 5) 兜底裁剪：正常走 preset 时这里应是 0 个残留；
  //    若 preset 未生效（例如被工程自己的 customSerializer 完全接管），
  //    这一步会把 base 定义外科手术式摘掉，并给出自检。
  let stripReport = null;
  if (doStripCheck) {
    const stripped = stripBaseModules({
      bundleCode: code,
      projectDir,
      baseIds,
      globalPrefix: splitConfig.globalPrefix,
      shim: false, // 上面已经插过垫片
    });
    if (stripped.removed > 0) {
      logger.warn(
        `preset 未完全生效：兜底裁剪又摘掉了 ${stripped.removed} 个 base 模块定义。` +
          `请检查工程的 customSerializer / processModuleFilter 是否覆盖了 preset。`,
      );
    }
    code = stripped.code;
    stripReport = stripped;
  }

  fs.mkdirSync(outDir, {recursive: true});
  fs.writeFileSync(bundleOut, code, 'utf8');

  const finalAnalysis = analyzeBundle(code, projectDir);
  const modulePathsById = new Map();
  for (const [p, id] of Object.entries(mapping.base.modules)) {
    modulePathsById.set(id, {path: p, source: 'base'});
  }

  // ── 报告 ────────────────────────────────────────────────
  const report = [];
  report.push(`业务 bundle 报告`);
  report.push(`生成时间    : ${new Date().toISOString()}`);
  report.push(`mapping     : ${path.basename(mappingPath)} (base 模块 ${humanCount(baseIds.size)} 个)`);
  report.push(`模式        : ${buildType}${minify ? ' + minify' : ''}`);
  report.push('');
  report.push(`业务模块定义: ${humanCount(finalAnalysis.statements.length)} 个`);
  for (const st of finalAnalysis.statements) {
    report.push(`  id=${st.moduleId}`);
  }
  report.push('');
  report.push(`id 命中 base: ${humanCount(diagnostics.hits)} 次`);
  report.push(`业务新增模块: ${humanCount(diagnostics.misses.size)} 个`);
  for (const m of [...diagnostics.misses].sort()) {
    report.push(`  ${m}`);
  }
  report.push('');
  report.push(`体积        : ${formatBytes(code.length)}`);
  report.push(`入口        : __r(${entryId})`);
  report.push(`尾部启动顺序: ${parseModuleRunOrder(finalAnalysis).join(' -> ')}`);
  if (diagnostics.idCollisions.length > 0) {
    report.push('');
    report.push(`! id 碰撞 ${diagnostics.idCollisions.length} 处：`);
    for (const c of diagnostics.idCollisions.slice(0, 10)) {
      report.push(`  id=${c.id}: ${c.a} <-> ${c.b}`);
    }
  }
  fs.writeFileSync(reportOut, report.join('\n') + '\n', 'utf8');

  logger.ok(`业务 bundle  : ${bundleOut}`);
  logger.info(`  大小             : ${formatBytes(code.length)}`);
  logger.info(`  业务模块定义     : ${humanCount(finalAnalysis.statements.length)} 个`);
  logger.info(`  id 命中 base     : ${humanCount(diagnostics.hits)} 次`);
  logger.info(
    `  业务新增模块     : ${humanCount(diagnostics.misses.size)} 个${diagnostics.misses.size ? ' -> ' + [...diagnostics.misses].join(', ') : ''}`,
  );
  logger.info(`  入口启动         : __r(${entryId})`);
  logger.ok(`报告         : ${reportOut}`);
  if (diagnostics.idCollisions.length > 0) {
    logger.error(
      `id 碰撞 ${diagnostics.idCollisions.length} 处，必须换 --id-salt 重新生成 base 与业务包`,
    );
    process.exitCode = 1;
  }

  if (wantHermes) {
    const hbc = `${bundleOut.replace(/\.js$/, '')}.hbc`;
    const jsFile = bundleOut.endsWith('.js') ? bundleOut : `${bundleOut}.js`;
    if (!bundleOut.endsWith('.js')) {
      // 让 hermesc 拿到 .js 扩展名，避免个别版本按扩展名判断输入类型
      fs.writeFileSync(jsFile, code, 'utf8');
    }
    const res = compileHermes({projectDir, jsFile, outFile: hbc});
    if (res.ok) {
      logger.ok(`业务字节码    : ${hbc} (${formatBytes(fs.statSync(hbc).size)})`);
    } else {
      logger.warn(`hermes 编译跳过：${res.reason}`);
    }
  }

  return {bundleOut, reportOut, entryId, businessModuleIds: [...finalAnalysis.moduleIds]};
}

module.exports = {runBusinessBuild};
