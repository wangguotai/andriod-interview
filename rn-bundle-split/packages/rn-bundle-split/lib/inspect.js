'use strict';

/**
 * 用 @babel/parser 把 bundle 文本解析成 AST，只取「顶层 __d 语句的边界」。
 *
 * 为什么不自己写字符扫描器：minified bundle 里字符串、模板串、正则字面量中
 * 都可能出现 `__d(function`、`)`、`}` 等片段，手写括号配对必然踩坑。
 * 工程里本来就有 @babel/parser（metro 的传递依赖），直接复用最可靠。
 *
 * 只用 AST 取区间（range），**不做代码生成** —— 这样才能做到外科手术式裁剪，
 * 不改变原有字节、不破坏 sourcemap 的行映射。
 */
const { resolveFromProject } = require('./util');

function loadParser(projectDir) {
  try {
    return resolveFromProject(projectDir, '@babel/parser');
  } catch (e) {
    throw new Error(
      `需要 @babel/parser 来解析 bundle（通常随 metro 一起安装）。\n` +
        `在 ${projectDir} 下解析失败：${e.message}`,
    );
  }
}

const DEFINE_NAMES = new Set(['__d']);

/** 判断 callee 是否为 __d（支持 global.__d / xxx__d 这种带前缀写法）。 */
function isDefineCallee(node) {
  if (!node) {
    return false;
  }
  if (node.type === 'Identifier') {
    return DEFINE_NAMES.has(node.name) || node.name.endsWith('__d');
  }
  if (node.type === 'MemberExpression' && !node.computed) {
    return node.property.type === 'Identifier' && node.property.name === '__d';
  }
  return false;
}

/**
 * 解析 bundle，返回：
 *   - runtimePrefixChars: 运行时前缀（prelude + polyfills）的字符长度，
 *     即「第一个顶层 __d 语句之前」的原文长度。若前缀里出现 __d，视为异常。
 *   - statements: 顶层 __d 语句列表，含精确定位信息
 *   - postStatements: __d 之后的所有顶层语句（__r(...)、sourceMappingURL 等）
 *   - moduleIds: 出现过的 module id 集合
 */
function analyzeBundle(code, projectDir) {
  const parser = loadParser(projectDir);
  const ast = parser.parse(code, {
    sourceType: 'script',
    allowReturnOutsideFunction: true,
    allowAwaitOutsideFunction: true,
    errorRecovery: true,
    ranges: true,
  });

  const body = ast.program.body;
  const statements = [];
  const postStatements = [];
  const moduleIds = new Set();
  let seenDefine = false;
  let firstDefineStart = -1;
  let runtimePrefixChars = 0;
  let healthWarning = null;

  for (const node of body) {
    const isExpr = node.type === 'ExpressionStatement';
    const call = isExpr ? node.expression : null;
    const isDefine = call && call.type === 'CallExpression' && isDefineCallee(call.callee);

    if (!isDefine) {
      if (!seenDefine) {
        // 运行时段：prelude / polyfill / 任何非 __d 顶层代码
        runtimePrefixChars = Math.max(runtimePrefixChars, node.end);
      } else {
        postStatements.push({
          start: node.start,
          end: node.end,
          code: code.slice(node.start, node.end),
          type: node.type,
        });
      }
      continue;
    }

    seenDefine = true;
    if (firstDefineStart === -1) {
      firstDefineStart = node.start;
    }

    const args = call.arguments;
    const idArg = args[1];
    let moduleId = null;
    if (idArg && idArg.type === 'NumericLiteral') {
      moduleId = idArg.value;
    } else if (idArg && idArg.type === 'StringLiteral') {
      // dev 模式下 id 仍应是数字；字符串只可能出现在 __r("verboseName") 里
      moduleId = null;
    }
    const depArg = args[2];
    const verboseArg = args[3];
    if (Number.isInteger(moduleId)) {
      moduleIds.add(moduleId);
    } else if (!healthWarning) {
      healthWarning = `第 ${statements.length} 个 __d 的 module id 不是数字字面量（args[1=${JSON.stringify(
        idArg && idArg.type,
      )}]）—— 可能不是标准 metro 产物`;
    }

    statements.push({
      start: node.start,
      end: node.end,
      moduleId,
      depMapStart: depArg ? depArg.start : null,
      depMapEnd: depArg ? depArg.end : null,
      depMapKind: depArg ? (depArg.type === 'ArrayExpression' ? 'array' : depArg.type) : null,
      verboseNameStart: verboseArg ? verboseArg.start : null,
      verboseNameEnd: verboseArg ? verboseArg.end : null,
      verboseName: verboseArg && verboseArg.type === 'StringLiteral' ? verboseArg.value : null,
      code: code.slice(node.start, node.end),
    });
  }

  // 前缀里不允许夹带 __d —— 否则「按 __d 边界切分」的假设不成立
  if (firstDefineStart > 0) {
    const prefixText = code.slice(0, firstDefineStart);
    if (prefixText.includes('__d(')) {
      healthWarning =
        (healthWarning ? healthWarning + '；' : '') +
        '运行时前缀里检测到 __d( —— 该 bundle 结构非标准，裁剪可能不安全';
    }
  }

  return {
    runtimePrefixChars: firstDefineStart === -1 ? code.length : runtimePrefixChars,
    firstDefineStart,
    statements,
    postStatements,
    moduleIds,
    healthWarning,
    totalChars: code.length,
  };
}

/**
 * 取出一条 __d 语句「引用了哪些 module id」。
 *
 * 关键坑：不能只看 args[2]（dependencyMap）。
 * metro 的 inlineRequires 插件（RN 默认开启 inlineRequires: true）会把
 * `var x = require('./y')` 内联成 `r(ID)`，此时 ./y 只出现在
 * dependencyMap 里，而内联后的调用通过闭包变量名指向 dependencyMap 下标 ——
 * 也就是说 args[2] 仍然是完整的「可静态解析依赖表」。
 * 但为了稳妥（脚本类模块、async require 等），这里同时统计 args[2] 里的数字
 * 与整个语句里出现的 `r(NUMBER)` 调用。
 */
function collectReferencedIds(statement, code) {
  const ids = new Set();
  if (statement.depMapStart != null && statement.depMapKind === 'array') {
    const slice = code.slice(statement.depMapStart, statement.depMapEnd);
    const matches = slice.match(/-?\d+/g);
    if (matches) {
      for (const m of matches) {
        const n = Number(m);
        if (Number.isInteger(n) && n >= 0) {
          ids.add(n);
        }
      }
    }
  }
  // requireFn 调用：地铁里形如 r(123) / r(123).default
  const reqRe = /(?:^|[^\w$.])r\(\s*(\d+)\s*\)/g;
  let m;
  while ((m = reqRe.exec(statement.code)) !== null) {
    ids.add(Number(m[1]));
  }
  return ids;
}

/** 从 `__r(123)` 这类尾部语句里取 module id。 */
function parseRunModuleIds(postStatements) {
  const ids = [];
  for (const s of postStatements) {
    const m = /^__r\(\s*(\d+)\s*\)\s*;?$/.exec(s.code.trim());
    if (m) {
      ids.push(Number(m[1]));
    }
  }
  return ids;
}

module.exports = {
  analyzeBundle,
  collectReferencedIds,
  parseRunModuleIds,
};
