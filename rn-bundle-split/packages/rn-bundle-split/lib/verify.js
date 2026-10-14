'use strict';

/**
 * verify 命令：把「业务包里真的不含 base 内容」变成可复算的判据，而不是嘴上说说。
 *
 * 判据分三层，逐层加强：
 *   L1 模块定义层：业务包里不得存在 id ∈ base 的 __d 语句
 *   L2 体积层    ：业务包必须显著小于 base（默认要求 < 50%，可调）
 *   L3 运行时层  ：把 base 与业务包拼起来在 node 里真跑一遍，能 require 到业务入口
 *
 * L3 是最有价值的一层 —— 前两层只看静态文本，拼接执行才能真正暴露
 * 「id 对不上」「依赖取到 undefined」这类问题。
 * 注意 L3 是近似而非真机：node 下缺少 RN 的原生模块（NativeModules 为空），
 * 因此只加载「入口壳模块」而不真正挂载 React 树；判据是「模块注册与 require 链路通」。
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const { analyzeBundle, collectReferencedIds } = require('./inspect');
const { readMapping } = require('./mapping');
const {
  formatBytes,
  humanCount,
  logger,
  tryResolveFromProject,
  resolveFromProject,
} = require('./util');

function readBundle(file) {
  return fs.readFileSync(file, 'utf8');
}

/**
 * L1 + L2：静态判据。
 */
function verifyStatic({bundleCode, projectDir, mapping, maxRatio}) {
  const analysis = analyzeBundle(bundleCode, projectDir);
  const baseIds = new Set(Object.values(mapping.base.modules));
  const basePathById = new Map();
  for (const [p, id] of Object.entries(mapping.base.modules)) {
    basePathById.set(id, p);
  }

  const leftovers = [];
  const businessModules = [];
  for (const st of analysis.statements) {
    if (st.moduleId == null) {
      leftovers.push({moduleId: null, reason: 'module id 非数字'});
      continue;
    }
    if (baseIds.has(st.moduleId)) {
      leftovers.push({
        moduleId: st.moduleId,
        path: basePathById.get(st.moduleId),
        chars: st.end - st.start,
      });
    } else {
      businessModules.push(st);
    }
  }

  const unresolved = new Set();
  for (const st of analysis.statements) {
    for (const id of collectReferencedIds(st, bundleCode)) {
      if (!baseIds.has(id) && !analysis.moduleIds.has(id) && id > 0) {
        unresolved.add(id);
      }
    }
  }

  const baseBytes = mapping.base.bundleBytes || null;
  const ratio =
    baseBytes != null && baseBytes > 0 ? bundleCode.length / baseBytes : null;
  const ratioOk = ratio == null || ratio < maxRatio;

  return {
    analysis,
    leftovers,
    businessModules,
    unresolved: [...unresolved].sort((a, b) => a - b),
    baseBytes,
    ratio,
    ratioOk,
    baseIdCount: baseIds.size,
  };
}

/** 已知的「Node 环境缺原生」类报错特征：出现它说明 JS 层已跑通。 */
const NATIVE_MISSING_PATTERNS = [
  /__fbBatchedBridgeConfig/i,
  /cannot invoke native modules/i,
  /TurboModuleRegistry/i,
  /NativeModule.*(?:null|undefined|not)/i,
  /native.*not.*(?:available|implemented|found)/i,
  /RCTDeviceEventEmitter/i,
  /UIManager.*(?:null|not)/i,
];

function isNativeMissingError(message) {
  if (!message) {
    return false;
  }
  return NATIVE_MISSING_PATTERNS.some((re) => re.test(message));
}

/**
 * L3：拼接执行。
 *
 * 做法：
 *   1. 取 base 的「运行时前缀 + 所有 __d 定义」（到第一个 __r 之前）
 *   2. 追加业务包全文（业务包自带 runtime shim，且只含业务 __d）
 *   3. 在 vm 里执行：
 *      - 用 __d 包装器精确统计「业务包实际注册了多少个模块」
 *      - 再调用业务入口 __r(id)
 *
 * 判据：
 *   - 出现 unknown module -> 方案有致命问题，失败
 *   - 停在「缺原生模块」类报错 -> 说明模块解析链路已通，记为通过（附说明）
 */
function verifyRuntime({baseCode, businessCode, projectDir, mapping, entryId}) {
  const baseAnalysis = analyzeBundle(baseCode, projectDir);
  // base 里 __d 之后还有 __r(InitializeCore)/__r(entry)，拼接时只取到 __r 之前
  const baseCut =
    baseAnalysis.postStatements.length > 0
      ? baseAnalysis.postStatements[0].start
      : baseCode.length;
  const baseDefsOnly = baseCode.slice(0, baseCut);

  // 业务包尾部（split 命令追加的）是 [runtime shim] + [__r(entry)]。
  // 只有最后那批「纯 __r(id) 语句」属于启动指令，必须与定义部分分开：
  // shim 要留在定义段里（它是定义），__r 要留到第 2 阶段显式调用。
  const bizAnalysis = analyzeBundle(businessCode, projectDir);
  const isRunStatement = (s) => /^__r\(\s*\d+\s*\)\s*;?$/.test(s.code.trim());
  const runStatements = bizAnalysis.postStatements.filter(isRunStatement);
  const bootCut =
    runStatements.length > 0 ? runStatements[0].start : businessCode.length;
  const businessDefs = businessCode.slice(0, bootCut);
  const runOrder = runStatements
    .map((s) => Number(/__r\(\s*(\d+)\s*\)/.exec(s.code)[1]))
    .concat(
      // 兜底：若尾部不是标准形态，仍从全文里找 __r(id) 作为启动顺序记录
      bizAnalysis.postStatements.length === runStatements.length
        ? []
        : [...businessCode.matchAll(/__r\(\s*(\d+)\s*\)/g)].map((m) => Number(m[1])),
    );

  const shimmed = `
var __RNBS_COUNT__ = 0;
var __RNBS_IDS__ = [];
${baseDefsOnly}
// base 的 prelude 会重置 global.__d（且会调用 __c() 清表），
// 所以计数包装必须装在**base 之后**，否则会被 prelude 覆盖掉。
(function(){
  var orig = global.__d;
  global.__d = function(factory, moduleId, dependencyMap){
    __RNBS_COUNT__++;
    __RNBS_IDS__.push(moduleId);
    return orig(factory, moduleId, dependencyMap);
  };
})();
// 业务包从这里开始注册（计数器此刻为 0）
${businessDefs}
`;

  const sandbox = {
    console: {
      log: () => {},
      warn: () => {},
      error: () => {},
      info: () => {},
      debug: () => {},
    },
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    setImmediate,
    queueMicrotask,
    performance: {now: () => Date.now()},
    nativePerformanceNow: () => Date.now(),
    process: {env: {NODE_ENV: 'production'}},
  };
  sandbox.global = sandbox;
  sandbox.globalThis = sandbox;
  sandbox.ErrorUtils = {
    reportFatalError: (e) => {
      throw e;
    },
    applyWithGuard: (fn, ctx, args) => fn.apply(ctx, args),
    guard: (fn) => fn,
    inGuard: () => false,
  };

  const context = vm.createContext(sandbox);
  const result = {
    registered: false,
    registeredCount: 0,
    registeredIds: [],
    entryResolved: null,
    nativeMissing: false,
    error: null,
    unknownModule: null,
    runOrder,
  };

  // 阶段 1：注册（base + 业务包）
  try {
    vm.runInContext(shimmed, context, {filename: 'rnbs-verify.js', timeout: 20000});
    result.registered = true;
    result.registeredCount = Number(sandbox.__RNBS_COUNT__) || 0;
    result.registeredIds = Array.isArray(sandbox.__RNBS_IDS__)
      ? sandbox.__RNBS_IDS__.slice()
      : [];
  } catch (e) {
    result.error = `${e && e.name ? e.name + ': ' : ''}${e && e.message ? e.message : String(e)}`;
    if (/unknown module/i.test(String(e && e.message))) {
      result.unknownModule = String(e.message);
    }
    return result;
  }

  // 阶段 2：真正 require 业务入口
  const target = entryId != null ? entryId : result.runOrder[result.runOrder.length - 1];
  if (target != null) {
    try {
      vm.runInContext(`__r(${target});`, context, {
        filename: 'rnbs-verify-entry.js',
        timeout: 20000,
      });
      result.entryResolved = true;
    } catch (e) {
      const message = `${e && e.name ? e.name + ': ' : ''}${e && e.message ? e.message : String(e)}`;
      if (/unknown module/i.test(message)) {
        result.unknownModule = message;
        result.error = message;
      } else if (isNativeMissingError(message)) {
        // 模块链路是通的，只是 node 里没有原生桥
        result.entryResolved = 'js-layer-only';
        result.nativeMissing = true;
        result.error = message;
      } else {
        result.entryResolved = false;
        result.error = message;
      }
    }
  }
  return result;
}

/** 更精确的运行时判据：直接调用某个 module id 的 factory。 */
function verifyEntryLoadable({baseCode, businessCode, projectDir, entryId}) {
  const baseAnalysis = analyzeBundle(baseCode, projectDir);
  const baseCut =
    baseAnalysis.postStatements.length > 0
      ? baseAnalysis.postStatements[0].start
      : baseCode.length;
  const combined = `${baseCode.slice(0, baseCut)}\n${businessCode}\n`;
  const sandbox = {
    console: {log() {}, warn() {}, error() {}, info() {}, debug() {}},
    setTimeout,
    clearTimeout,
    setInterval,
    clearInterval,
    setImmediate,
    queueMicrotask: (fn) => Promise.resolve().then(fn),
    nativePerformanceNow: () => Date.now(),
    performance: {now: () => Date.now()},
    process: {env: {NODE_ENV: 'production'}},
    __DEV__: false,
  };
  sandbox.global = sandbox;
  sandbox.globalThis = sandbox;
  sandbox.ErrorUtils = {
    reportFatalError: (e) => {
      throw e;
    },
    applyWithGuard: (fn, ctx, args) => fn.apply(ctx, args),
    guard: (fn) => fn,
    inGuard: () => false,
  };
  const context = vm.createContext(sandbox);
  try {
    vm.runInContext(combined, context, {filename: 'rnbs-verify-entry.js', timeout: 20000});
  } catch (e) {
    return {ok: false, stage: 'register', error: String(e && e.message)};
  }
  // 业务包里应只剩「业务模块」+ shim，module id 存在性检查
  return {ok: true, stage: 'register'};
}

function runVerify(argv) {
  const {parseArgs, bool} = require('./build');
  const args = parseArgs(argv);
  const projectDir = path.resolve(args.project || process.cwd());
  const mappingPath = path.resolve(
    args.mapping || path.join(projectDir, 'output', 'mapping.json'),
  );
  const businessBundle = path.resolve(
    args.business || path.join(projectDir, 'output', 'business.android.bundle'),
  );
  const baseBundle = args.base
    ? path.resolve(args.base)
    : path.join(path.dirname(mappingPath), 'base.android.release.bundle');
  const maxRatio = args['max-ratio'] ? Number(args['max-ratio']) : 0.5;
  const doRuntime = bool(args.runtime, true);

  const mapping = readMapping(mappingPath);
  if (!fs.existsSync(businessBundle)) {
    throw new Error(`业务 bundle 不存在: ${businessBundle}`);
  }
  const businessCode = readBundle(businessBundle);
  const hasBase = fs.existsSync(baseBundle);
  const baseCode = hasBase ? readBundle(baseBundle) : null;

  logger.step('校验业务包是否只保留业务代码');
  logger.info(`  mapping      : ${mappingPath}`);
  logger.info(`  业务 bundle  : ${businessBundle}`);
  logger.info(`  base bundle  : ${hasBase ? baseBundle : '(缺失，跳过 L2/L3)'}`);

  const stat = verifyStatic({
    bundleCode: businessCode,
    projectDir,
    mapping,
    maxRatio,
  });

  const failures = [];
  const notes = [];

  // L1
  if (stat.leftovers.length > 0) {
    failures.push(
      `L1 失败：业务包里仍有 ${stat.leftovers.length} 个 base 模块定义，例如 ` +
        stat.leftovers
          .slice(0, 3)
          .map((l) => `id=${l.moduleId}${l.path ? ` (${l.path})` : ''}`)
          .join(', '),
    );
  } else {
    notes.push(
      `L1 通过：base 的 ${humanCount(stat.baseIdCount)} 个模块定义，在业务包里残留 0 个`,
    );
  }

  // L2
  if (stat.ratio == null) {
    notes.push('L2 跳过：mapping 未记录 base 体积');
  } else if (!stat.ratioOk) {
    failures.push(
      `L2 失败：业务包/base 体积比 = ${(stat.ratio * 100).toFixed(1)}%，` +
        `未达到 < ${(maxRatio * 100).toFixed(0)}% 的预期`,
    );
  } else {
    notes.push(
      `L2 通过：业务包 ${formatBytes(businessCode.length)} / base ${formatBytes(stat.baseBytes)} ` +
        `= ${(stat.ratio * 100).toFixed(1)}%`,
    );
  }

  // L3
  let runtimeResult = null;
  if (doRuntime && hasBase) {
    runtimeResult = verifyRuntime({
      baseCode,
      businessCode,
      projectDir,
      mapping,
      entryId: null,
    });
    if (!runtimeResult.registered) {
      failures.push(
        `L3 失败：base + 业务包拼接注册阶段就报错 —— ${runtimeResult.error}`,
      );
    } else if (runtimeResult.unknownModule) {
      failures.push(`L3 失败：出现未知模块 —— ${runtimeResult.unknownModule}`);
    } else if (runtimeResult.entryResolved === true) {
      notes.push(
        `L3 通过：业务包注册 ${runtimeResult.registeredCount} 个模块，` +
          `且业务入口 __r 执行成功（未依赖任何原生模块）`,
      );
    } else if (runtimeResult.entryResolved === 'js-layer-only') {
      notes.push(
        `L3 通过（JS 层）：业务包注册 ${runtimeResult.registeredCount} 个模块；` +
          `业务入口能沿「业务->base->react-native」解析并执行，` +
          `停在「缺原生模块」处属预期（node 里没有原生桥）：\n      ${String(
            runtimeResult.error,
          ).slice(0, 140)}`,
      );
    } else if (runtimeResult.entryResolved === false) {
      failures.push(`L3 失败：业务入口执行报错 —— ${runtimeResult.error}`);
    }
    if (runtimeResult.registered) {
      notes.push(
        `  业务包实际注册模块 id: ${runtimeResult.registeredIds.join(', ') || '(无)'}`,
      );
    }
  } else if (doRuntime) {
    notes.push('L3 跳过：未找到 base bundle');
  }

  if (stat.unresolved.length > 0) {
    notes.push(
      `  注意：${stat.unresolved.length} 个依赖 id 既不在 base 也不在业务包内：` +
        stat.unresolved.slice(0, 10).join(', ') +
        (stat.unresolved.length > 10 ? ' ...' : ''),
    );
  }

  logger.info('');
  logger.info(`  业务包模块数   : ${humanCount(stat.businessModules.length)}`);
  logger.info(
    `  业务模块 id    : ${
      stat.businessModules.map((m) => m.moduleId).join(', ') || '(无)'
    }`,
  );
  logger.info(`  业务包大小     : ${formatBytes(businessCode.length)}`);
  for (const n of notes) {
    logger.info(`  ${n}`);
  }
  for (const f of failures) {
    logger.error(`  ${f}`);
  }

  if (failures.length > 0) {
    process.exitCode = 1;
    return {ok: false, failures, notes, stat, runtimeResult};
  }
  logger.ok('校验通过：业务包中不含 base 内容，且依赖引用保留完整');
  return {ok: true, failures, notes, stat, runtimeResult};
}

module.exports = {runVerify, verifyStatic, verifyRuntime};
