'use strict';

/**
 * 业务包裁剪：把 base 里已有的模块定义从业务 bundle 中整段摘掉。
 *
 * ── 一个必须讲清楚的边界 ─────────────────────────────────────────────
 * 「去掉 base 的内容」去掉的是 **模块定义（__d 语句）**，不是 **模块引用**。
 * 业务代码 import 'react-native' 时，编译产物里仍然会出现 react-native 的 id；
 * 这个 id 之所以能解析，正是因为 base bundle 已经把该模块注册进同一张全局表
 * （metro-runtime/polyfills/require.js 里的 `modules` Map，挂在 global.__r 上）。
 * 所以正确处理是：
 *   - 删掉 id ∈ base 的 __d 语句（这是体积的绝对大头：react-native + react 本体）
 *   - 保留 id ∉ base 的 __d 语句（业务自身代码）
 *   - 业务模块 dependencyMap 里的 base id **保留**（运行时要靠 base 提供的注册）
 * 若把 depMap 里的 base id 也删掉，运行时 r(下标) 会拿到 undefined 并崩溃。
 */
const fs = require('fs');
const { analyzeBundle } = require('./inspect');
const { formatBytes, humanCount, logger } = require('./util');

/**
 * 兜底 runtime 垫片。
 *
 * 业务 bundle 正常情况下总是在 base 之后执行，__d/__r 已由 base 提供。
 * 这个垫片只在「base 还没跑」时补一个最小实现，让拆分产物仍可单独加载/单测。
 * 注意：绝不能在 base 已存在时重置全局表 —— 否则会把 base 注册的模块全冲掉。
 */
function runtimeShim(globalPrefix = '') {
  const d = `${globalPrefix}__d`;
  return `;(function(g){if(typeof g.__r==="function"&&typeof g[${JSON.stringify(
    d,
  )}]==="function")return;var m=g.__r?null:new Map();if(!g.__r){g.__r=function(id){var M=m.get(id);if(!M)throw new Error('Requiring unknown module "'+id+'".（base bundle 未加载？）');if(!M.isInitialized){M.isInitialized=true;M.factory(g,g.__r,g.__r.importDefault||g.__r,g.__r.importAll||g.__r,M.publicModule,M.publicModule.exports,M.dependencyMap);}return M.publicModule.exports;};}if(!g[${JSON.stringify(
    d,
  )}]){g[${JSON.stringify(
    d,
  )}]=function(factory,moduleId,dependencyMap){m.set(moduleId,{factory:factory,dependencyMap:dependencyMap,isInitialized:false,publicModule:{exports:{}}});};}g.__c=function(){m=new Map();return m;};}(typeof globalThis!=="undefined"?globalThis:this));`;
}

/**
 * 把 `[1,2,3]` 或 `{0:1,1:2,paths:{...}}` 形态的 dependencyMap 统一成纯数字数组。
 *
 * 两种形态都必须保留「下标 -> id」的顺序，因为编译后的代码靠 `r(下标)` 取依赖。
 * dev 下 metro 会额外塞一个 paths 对象（异步分包的 URL 映射），拆分后这些分包
 * 不属于本工程，必须去掉，否则会把业务包指向别的 bundle 的地址。
 */
function normalizeDepMap(statement, code) {
  if (statement.depMapStart == null || statement.depMapKind !== 'array') {
    return null;
  }
  const slice = code.slice(statement.depMapStart, statement.depMapEnd);
  // 形如 {0:12,1:34,paths:{...}} —— 取数字键的取值并按数字键排序
  const objectMap = /^\s*\{/.test(slice);
  if (objectMap) {
    const pairs = [];
    const re = /(\d+)\s*:\s*(\d+)/g;
    let m;
    while ((m = re.exec(slice)) !== null) {
      pairs.push([Number(m[1]), Number(m[2])]);
    }
    pairs.sort((a, b) => a[0] - b[0]);
    const arr = [];
    for (const [idx, id] of pairs) {
      // 保持下标对齐：缺失下标用 0 占位（编译产物不会引用缺失下标）
      while (arr.length < idx) {
        arr.push(0);
      }
      arr.push(id);
    }
    return {text: `[${arr.join(',')}]`, ids: arr.filter((n) => n > 0), hadPaths: /paths\s*:/.test(slice)};
  }
  const ids = (slice.match(/-?\d+/g) || []).map(Number).filter((n) => Number.isInteger(n) && n >= 0);
  return {text: slice.replace(/\s+/g, ''), ids, hadPaths: false};
}

/**
 * @param {object} opts
 * @param {string} opts.bundleCode 业务 bundle 原文
 * @param {string} opts.projectDir 业务工程根目录（用于解析 @babel/parser）
 * @param {Set<number>} opts.baseIds base 里已有的 module id
 * @param {string} opts.globalPrefix
 * @param {boolean} opts.runtimeShim
 */
function stripBaseModules({bundleCode, projectDir, baseIds, globalPrefix = '', shim = true}) {
  const analysis = analyzeBundle(bundleCode, projectDir);
  const code = bundleCode;

  const removals = [];
  const kept = [];
  const keptIds = new Set();
  const depRewrites = [];
  const warnings = [];

  for (const st of analysis.statements) {
    if (st.moduleId != null && baseIds.has(st.moduleId)) {
      removals.push(st);
      continue;
    }
    kept.push(st);
    if (st.moduleId != null) {
      if (keptIds.has(st.moduleId)) {
        warnings.push(`业务包内出现重复 module id=${st.moduleId}`);
      }
      keptIds.add(st.moduleId);
    }
  }

  // 收集需要改写的片段（从后往前 splice，避免偏移失效）
  const edits = [];
  for (const st of kept) {
    const dep = normalizeDepMap(st, code);
    if (!dep) {
      continue;
    }
    if (dep.hadPaths) {
      warnings.push(
        `module id=${st.moduleId} 带异步分包 paths，已移除（分包 URL 不属于本工程）`,
      );
    }
    const original = code.slice(st.depMapStart, st.depMapEnd);
    if (original.replace(/\s+/g, '') !== dep.text.replace(/\s+/g, '')) {
      edits.push({start: st.depMapStart, end: st.depMapEnd, text: dep.text});
      depRewrites.push({moduleId: st.moduleId, before: original.slice(0, 60), after: dep.text.slice(0, 60)});
    }
  }
  for (const st of removals) {
    edits.push({start: st.start, end: st.end, text: ''});
  }

  // 预处理：先统一删除，再插入垫片
  edits.sort((a, b) => b.start - a.start);
  let out = code;
  for (const e of edits) {
    out = out.slice(0, e.start) + e.text + out.slice(e.end);
  }
  // 清理删除后留下的连续空行
  out = out.replace(/\n{3,}/g, '\n\n').trim();

  let shimInserted = false;
  if (shim) {
    const s = runtimeShim(globalPrefix);
    // 垫片必须在使用之前定义：放到第一个顶层语句之前
    const reanalyzed = analyzeBundle(out, projectDir);
    const insertAt =
      reanalyzed.statements.length > 0
        ? reanalyzed.statements[0].start
        : reanalyzed.postStatements.length > 0
          ? reanalyzed.postStatements[0].start
          : out.length;
    out = out.slice(0, insertAt) + s + '\n' + out.slice(insertAt);
    shimInserted = true;
  }

  // ── 裁剪后自检 ────────────────────────────────────────────────
  const after = analyzeBundle(out, projectDir);
  const leftovers = after.statements.filter(
    (st) => st.moduleId != null && baseIds.has(st.moduleId),
  );
  if (leftovers.length > 0) {
    throw new Error(
      `裁剪自检失败：业务包里仍残留 ${leftovers.length} 个 base 模块定义（这是工具 bug）`,
    );
  }
  const unresolved = new Set();
  for (const st of after.statements) {
    const refs = [...collectIds(st, out)];
    for (const id of refs) {
      if (!baseIds.has(id) && !after.moduleIds.has(id) && id > 0) {
        unresolved.add(id);
      }
    }
  }
  if (unresolved.size > 0) {
    warnings.push(
      `${unresolved.size} 个依赖 id 既不在 base 也不在业务包内（运行时会取到 undefined）: ` +
        [...unresolved].slice(0, 10).join(', ') +
        (unresolved.size > 10 ? ' ...' : ''),
    );
  }

  return {
    code: out,
    removed: removals.length,
    kept: after.statements.length,
    depRewrites: depRewrites.length,
    shimInserted,
    warnings,
    stats: {
      beforeChars: code.length,
      afterChars: out.length,
      removedChars: removals.reduce((n, s) => n + (s.end - s.start), 0),
      keptModuleIds: [...after.moduleIds].sort((a, b) => a - b),
      runModuleIds: require('./inspect').parseRunModuleIds(after.postStatements),
    },
  };
}

function collectIds(statement, code) {
  return require('./inspect').collectReferencedIds(statement, code);
}

/** 把裁剪结果写成文件 + 人类可读报告。 */
function writeStripResult(outFile, result) {
  fs.writeFileSync(outFile, result.code, 'utf8');
  return outFile;
}

function formatStripReport(result) {
  const {stats} = result;
  const lines = [];
  lines.push(`  移除 base 模块定义 : ${humanCount(result.removed)} 个`);
  lines.push(`  保留业务模块定义   : ${humanCount(result.kept)} 个（id: ${stats.keptModuleIds.join(', ')}）`);
  lines.push(`  dependencyMap 改写 : ${humanCount(result.depRewrites)} 处`);
  lines.push(`  兜底 runtime 垫片  : ${result.shimInserted ? '已插入' : '未插入'}`);
  lines.push(
    `  编辑前           : ${formatBytes(stats.beforeChars)} (${humanCount(stats.beforeChars)} 字符)`,
  );
  lines.push(
    `  剥离 base 定义   : -${formatBytes(stats.removedChars)}`,
  );
  lines.push(
    `  最终业务包       : ${formatBytes(stats.afterChars)} (${humanCount(stats.afterChars)} 字符)`,
  );
  if (stats.runModuleIds.length > 0) {
    lines.push(`  末尾 __r 入口     : ${stats.runModuleIds.join(', ')}`);
  }
  for (const w of result.warnings) {
    lines.push(`  ! ${w}`);
  }
  return lines.join('\n');
}

module.exports = {
  stripBaseModules,
  writeStripResult,
  formatStripReport,
  runtimeShim,
  normalizeDepMap,
  logger,
};
