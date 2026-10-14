#!/usr/bin/env node
'use strict';

const path = require('path');
const TOOL_ROOT = path.resolve(__dirname, '..');

const USAGE = `
rn-bundle-split —— React Native bundle 拆分（base 包 + 业务包）

用法:
  rn-bundle-split base     [选项]      # 在「基础工程」产出 base bundle + mapping
  rn-bundle-split init     [选项]      # 在「业务工程」接入拆分配置（幂等）
  rn-bundle-split split    [选项]      # 在「业务工程」产出只含业务代码的 bundle
  rn-bundle-split verify   [选项]      # 校验业务包已剥离 base 内容
  rn-bundle-split inspect  <bundle>    # 只读分析一个 bundle 的结构
  rn-bundle-split uninit   [选项]      # 撤销 init 对 metro.config.js 的改动

通用选项:
  --project <dir>       目标工程根目录（默认当前目录）
  --platform <android|ios>                                       默认 android
  --entry <file>        入口文件（默认 index.js）
  --dev                 产出 debug 版本（不 minify）
  --id-salt <salt>      哈希盐值；base 与业务必须一致
  --app-id <id>         工程自有源码的命名空间（默认取 package.json 的 name）；
                        base 与业务必须用各自不同的 appId，避免同名文件互相顶掉
  --out <dir>           输出目录（默认 <project>/output）

base 专属:
  --minify / --no-minify       默认 release 下 minify
  --hermes                     额外用 hermesc 编译出 .hbc
  --bundle-name <name>         base bundle 文件名
  --mapping-name <name>        mapping 文件名
  --source-map / --no-source-map

init 专属:
  --mapping <file>      必填：base 产出或拷贝过来的 mapping.json

split 专属:
  --mapping <file>      必填：base 的 mapping.json
  --hermes                     额外编译业务 .hbc
  --no-strip                   关闭「兜底裁剪 + 自检」

verify 专属:
  --mapping <file>      mapping 路径
  --business <file>     业务 bundle 路径
  --base <file>         base bundle 路径（用于体积对比与拼接执行）
  --max-ratio <0.5>     业务包/base 体积比上限
  --no-runtime          只做静态校验（默认会拼接执行一遍）
`;

function main() {
  const argv = process.argv.slice(2);
  const cmd = argv[0];
  const rest = argv.slice(1);

  if (!cmd || cmd === 'help' || cmd === '--help' || cmd === '-h') {
    console.log(USAGE);
    process.exit(cmd ? 0 : 1);
  }

  // inspect 单独处理：参数是文件路径
  if (cmd === 'inspect') {
    const {analyzeBundle, formatStripReport} = require(TOOL_ROOT);
    const fs = require('fs');
    const file = rest[0];
    if (!file) {
      console.error('inspect 需要一个 bundle 文件路径');
      process.exit(1);
    }
    const code = fs.readFileSync(path.resolve(file), 'utf8');
    const projectDir = rest[1] ? path.resolve(rest[1]) : process.cwd();
    const a = analyzeBundle(code, projectDir);
    console.log(`bundle      : ${path.resolve(file)}`);
    console.log(`字符数      : ${a.totalChars}`);
    console.log(`运行时前缀  : ${a.runtimePrefixChars} 字符`);
    console.log(`__d 定义    : ${a.statements.length} 个`);
    console.log(`module id   : ${[...a.moduleIds].sort((x, y) => x - y).join(', ')}`);
    console.log(`尾部语句    : ${a.postStatements.map((s) => s.code.trim()).join(' | ')}`);
    if (a.healthWarning) {
      console.log(`! ${a.healthWarning}`);
    }
    return;
  }

  const handlers = {
    base: () => require(TOOL_ROOT).runBaseBuild(rest),
    split: () => require(TOOL_ROOT).runBusinessBuild(rest),
    verify: () => require(TOOL_ROOT).runVerify(rest),
    init: () => require(TOOL_ROOT + '/lib/commands').runInit(rest, TOOL_ROOT),
    uninit: () => require(TOOL_ROOT + '/lib/commands').runUninit(rest),
  };

  if (!handlers[cmd]) {
    console.error(`未知命令: ${cmd}`);
    console.log(USAGE);
    process.exit(1);
  }

  Promise.resolve(handlers[cmd]()).catch((e) => {
    console.error(`\n\u2717 ${e && e.message ? e.message : e}`);
    if (process.env.RNBS_DEBUG === '1' && e && e.stack) {
      console.error(e.stack);
    }
    process.exit(1);
  });
}

main();
