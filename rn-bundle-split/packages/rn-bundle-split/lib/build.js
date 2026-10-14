'use strict';

/**
 * build 命令实现：产出 base bundle + mapping。
 *
 * 关键决策与理由（都在下文代码注释里给了源码依据）：
 *  1. module id 必须显式关闭 minify 对 id 的影响：id 是写死在 __d 第 2 个参数里的
 *     数字字面量，terser 的 mangle/compress 不会改数字字面量，
 *     因此 minify 与否不影响 id 表（已在真实 bundle 上验证：489 个 __d、
 *     depMap 均为数字），但为了 mapping 可复现，默认仍按 --dev/--minify 一个口径产出。
 *  2. base 必须是「完整可执行」的：__d 定义 + __r(InitializeCore) + __r(entry)。
 *  3. 业务工程走 modulesOnly（不重复 prelude），因此业务包里不会出现
 *     `global.__c()` 这种会清空全局模块表的调用。
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
const { createStableModuleIdFactory } = require('./moduleIdFactory');
const { analyzeBundle, parseRunModuleIds, collectReferencedIds } = require('./inspect');
const {
  createSplitMapping,
  createDeviceMapping,
  writeMapping,
} = require('./mapping');
const { loadProjectMetroConfig, createServer } = require('./metro');

function parseArgs(argv) {
  const args = {_: []};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const key = a.slice(2);
      const next = argv[i + 1];
      if (next === undefined || next.startsWith('--')) {
        args[key] = true;
      } else {
        args[key] = next;
        i++;
      }
    } else {
      args._.push(a);
    }
  }
  return args;
}

function bool(v, dflt = false) {
  if (v === undefined) {
    return dflt;
  }
  if (v === true) {
    return true;
  }
  if (v === false) {
    return false;
  }
  return v !== 'false' && v !== '0';
}

function ensureFile(p, label) {
  if (!fs.existsSync(p)) {
    throw new Error(`${label} 不存在: ${p}`);
  }
  return p;
}

const DEFAULT_ENTRY = {
  android: 'index.js',
  ios: 'index.js',
};

/**
 * 载入依赖图并产出 bundle。
 *
 * 走 metro 的 Server.build（与 RN CLI 完全同一条路径，见
 * @react-native/community-cli-plugin/dist/commands/bundle/buildBundle.js:88），
 * 只额外覆写 serializer.createModuleIdFactory。
 */
async function buildBundle({projectDir, entryFile, platform, dev, minify, modulesOnly, salt, appId, mappingForBusiness}) {
  const {config} = await loadProjectMetroConfig(projectDir, {platform});

  const effectiveAppId = appId || readAppId(projectDir);
  const resolveKey = makeModuleKeyResolver(projectDir, effectiveAppId);
  let createModuleId;
  if (mappingForBusiness) {
    // 业务工程也可以直接走这里：用 mapping 查表
    const {createModuleIdFactoryFromMapping} = require('./moduleIdFactory');
    createModuleId = createModuleIdFactoryFromMapping(resolveKey, mappingForBusiness, {salt});
  } else {
    createModuleId = createStableModuleIdFactory(projectDir, resolveKey, salt);
  }

  const mergedConfig = {
    ...config,
    serializer: {
      ...config.serializer,
      createModuleIdFactory: () => createModuleId,
    },
  };

  const server = createServer(mergedConfig);
  try {
    const bundle = await server.build({
      entryFile,
      platform,
      dev,
      minify,
      modulesOnly: Boolean(modulesOnly),
      runModule: !modulesOnly,
    });
    return {
      code: bundle.code,
      map: bundle.map,
      createModuleId,
      server,
      config: mergedConfig,
    };
  } finally {
    server.end();
  }
}

/** 用 hermesc 把 JS bundle 编成 Hermes 字节码。 */
function compileHermes({projectDir, jsFile, outFile, flags = ['-O']}) {
  const hermesc = findHermesc(projectDir);
  if (!hermesc) {
    return {ok: false, reason: '未找到 hermesc（react-native/sdks/hermesc）'};
  }
  const res = spawnSync(hermesc, [...flags, '-emit-binary', '-out', outFile, jsFile], {
    stdio: ['ignore', 'pipe', 'pipe'],
    encoding: 'utf8',
  });
  if (res.status !== 0) {
    return {
      ok: false,
      reason: `hermesc 失败（exit ${res.status}）: ${(res.stderr || '').trim().split('\n').slice(0, 3).join(' | ')}`,
    };
  }
  return {ok: true, hermesc, outFile};
}

function findHermesc(projectDir) {
  const rnDir = path.dirname(
    resolveFromProject(projectDir, 'react-native/package.json'),
  );
  const candidates = [
    'sdks/hermesc/osx-bin/hermesc',
    'sdks/hermesc/linux64-bin/hermesc',
    'sdks/hermesc/win64-bin/hermesc.exe',
    'sdks/hermesc/linux64-bin/hermesc',
  ];
  for (const c of candidates) {
    const p = path.join(rnDir, c);
    if (fs.existsSync(p)) {
      return p;
    }
  }
  return null;
}

async function runBaseBuild(argv) {
  const args = parseArgs(argv);
  const projectDir = path.resolve(args.project || process.cwd());
  const platform = args.platform || 'android';
  const dev = bool(args.dev, false);
  const minify = dev ? false : bool(args.minify, true);
  const entryFile = args.entry || DEFAULT_ENTRY[platform] || 'index.js';
  const outDir = path.resolve(args.out || path.join(projectDir, 'output'));
  const salt = typeof args['id-salt'] === 'string' ? args['id-salt'] : '';
  const appId = typeof args['app-id'] === 'string' ? args['app-id'] : readAppId(projectDir);
  const buildType = dev ? 'debug' : 'release';
  const wantHermes = bool(args.hermes, false);
  const wantSourceMap = bool(args['source-map'], !dev);

  const bundleName =
    args['bundle-name'] ||
    `base.${platform}.${buildType}${wantHermes ? '.hbc' : '.bundle'}`;
  const bundleOut = path.join(outDir, bundleName);
  const mappingOut = path.join(
    outDir,
    args['mapping-name'] || `base.${platform}.${buildType}.mapping.json`,
  );

  logger.step(`产出 base bundle`);
  logger.info(`  工程      : ${projectDir}`);
  logger.info(`  platform  : ${platform}`);
  logger.info(`  entry     : ${entryFile}`);
  logger.info(`  模式      : ${buildType}${minify ? ' (minify)' : ''}`);
  logger.info(`  id salt   : ${salt || '(无)'}`);
  logger.info(`  appId     : ${appId}`);

  const built = await buildBundle({
    projectDir,
    entryFile,
    platform,
    dev,
    minify,
    modulesOnly: false,
    salt,
    appId,
  });

  const analysis = analyzeBundle(built.code, projectDir);
  const entries = built.createModuleId.__getEntries();
  const collisions = built.createModuleId.__getCollisions();
  if (collisions.length > 0) {
    throw new Error(
      `module id 哈希碰撞 ${collisions.length} 处，无法生成 mapping：\n` +
        collisions
          .slice(0, 5)
          .map((c) => `  id=${c.id}: ${c.a} <-> ${c.b}`)
          .join('\n') +
        `\n请用 --id-salt <新盐值> 重新生成（base 与下游业务必须使用同一盐）。`,
    );
  }

  // moduleIdByPath 从 module key 推导：entries 的 key 是绝对路径
  const moduleIdByPath = new Map();
  for (const [abs, id] of entries.entries()) {
    moduleIdByPath.set(makeModuleKeyResolver(projectDir, appId)(abs), id);
  }

  fs.mkdirSync(outDir, {recursive: true});
  fs.writeFileSync(bundleOut, built.code, 'utf8');
  if (wantSourceMap && built.map) {
    fs.writeFileSync(`${bundleOut}.map`, built.map, 'utf8');
  }

  const hermesResult = wantHermes
    ? compileHermes({projectDir, jsFile: bundleOut, outFile: `${bundleOut}.hbc`})
    : {ok: false, skipped: true};

  const mapping = createSplitMapping({
    projectDir,
    platform,
    dev,
    minify,
    buildType,
    appId,
    hermesEnabled: wantHermes,
    globalPrefix: built.config.transformer.globalPrefix || '',
    bundleFile: wantHermes && hermesResult.ok ? `${bundleName}.hbc` : bundleName,
    bundleCode: built.code,
    runtimePreCodeChars: analysis.runtimePrefixChars,
    sourceMapFile: wantSourceMap ? `${bundleName}.map` : null,
    moduleIdByPath,
  });
  writeMapping(mappingOut, mapping);

  // ── 报告 ──────────────────────────────────────────────
  const jsFiles = new Set(moduleIdByPath.keys());
  const graphRefs = new Set();
  for (const st of analysis.statements) {
    for (const id of collectReferencedIds(st, built.code)) {
      graphRefs.add(id);
    }
  }
  const runIds = parseModuleRunOrder(analysis);
  const prefixHasDefine = analysis.healthWarning != null;

  logger.ok(`base bundle  : ${bundleOut}`);
  logger.info(`  文件大小        : ${formatBytes(Buffer.byteLength(built.code))}`);
  logger.info(`  模块数量        : ${humanCount(moduleIdByPath.size)}`);
  logger.info(
    `  运行时前缀      : ${humanCount(analysis.runtimePrefixChars)} 字符（prelude + polyfills）`,
  );
  logger.info(`  模块定义 __d   : ${humanCount(analysis.statements.length)}`);
  logger.info(`  启动入口        : ${runIds.map((r) => `__r(${r})`).join(' ')}`);
  if (analysis.postStatements.length > 0) {
    logger.info(
      `  尾部语句        : ${analysis.postStatements.map((s) => s.code.trim().slice(0, 40)).join(' | ')}`,
    );
  }
  logger.ok(`mapping      : ${mappingOut}`);
  logger.info(`  记录模块        : ${humanCount(Object.keys(mapping.base.modules).length)} 个`);
  logger.info(
    `  路径风格        : node_modules 下共享键 + 「${appId}/」前缀的工程自有键（见 docs/mapping-format.md）`,
  );
  if (wantHermes) {
    if (hermesResult.ok) {
      const size = fs.statSync(`${bundleOut}.hbc`).size;
      logger.ok(`hermes 字节码 : ${bundleOut}.hbc (${formatBytes(size)})`);
    } else {
      logger.warn(`hermes 编译跳过：${hermesResult.reason}`);
      logger.info(`  原 JS bundle 仍可用（关闭 hermesEnabled 或手动补编译）`);
    }
  }
  if (prefixHasDefine) {
    logger.warn(analysis.healthWarning);
  }
  if (mapping.base.bundleSha1) {
    logger.info(`  bundle sha1     : ${mapping.base.bundleSha1.slice(0, 16)}…`);
  }

  return {bundleOut, mappingOut, mapping, analysis};
}

/**
 * 解析 bundle 末尾的启动顺序。
 * metro 的 getAppendScripts 会把 runBeforeMainModule（InitializeCore）+ entry
 * 依次写成 __r(id)，因此尾部可能有两行（真实 bundle 实测为 `__r(62); __r(0);`）。
 */
function parseModuleRunOrder(analysis) {
  const ids = [];
  for (const st of analysis.postStatements) {
    for (const m of st.code.matchAll(/__r\(\s*(\d+)\s*\)/g)) {
      ids.push(Number(m[1]));
    }
  }
  if (ids.length === 0) {
    // 回退到 parseRunModuleIds（只认「整行就是 __r(id)」）
    return parseRunModuleIds(analysis.postStatements);
  }
  return ids;
}

module.exports = {
  parseArgs,
  bool,
  buildBundle,
  runBaseBuild,
  compileHermes,
  findHermesc,
  ensureFile,
  parseModuleRunOrder,
  DEFAULT_ENTRY,
};
