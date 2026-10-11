#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════
#  无用资源 / 无用代码 审计报告
# ═══════════════════════════════════════════════════════════════════════
#
#  用法：
#    tools/opt/unused-report.sh              # 能跑多少跑多少
#    tools/opt/unused-report.sh --apk <path> # 同时分析已有 APK 的构成
#
#  ─── 这个脚本的定位：**只报告，不删除** ───
#
#  为什么不做"自动删除"：
#    资源是否真的无用，静态分析给不出确定答案。本仓库就有两个反例：
#      · HomeCatalog 用 `Class<out Activity>` 登记式入口 —— 编译器看得见，
#        但"它是否被认为可达"取决于 R8 的入口规则；
#      · 测试/演示资源可能只在 androidTest 或运行时字符串拼接里被引用。
#    任何"我们帮你删了"的自动化，误删的代价（运行时缺资源 → 崩溃/白屏）
#    都远高于漏报的代价（多留几个 KB）。所以：
#      · 报告 → 本脚本
#      · 删除 → 只有 R8 shrinkResources 一条路（opt.shrink 开关）
#    一个动作一个入口，避免"两套删除逻辑各自为政"。
#
#  ─── 产出 ───
#    app/build/reports/opt/
#      ├── unused-resources.txt   lint UnusedResources 摘要
#      ├── removed-code.txt       R8 删掉的类/成员（需已开 opt.shrink）
#      ├── mapping.txt            混淆映射（解 release 崩溃栈用）
#      └── apk-composition.txt    APK 分类体积明细
# ═══════════════════════════════════════════════════════════════════════
set -uo pipefail   # 注意：不用 -e —— 本脚本要在子任务失败时继续收集其他数据

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

OUT="$ROOT/app/build/reports/opt"
mkdir -p "$OUT"

APK=""
SKIP_LINT=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --apk)       APK="${2:?--apk 需要路径}"; shift 2 ;;
        --skip-lint) SKIP_LINT=1; shift ;;
        -h|--help)   sed -n '2,40p' "$0"; exit 0 ;;
        *) echo "未知参数: $1" >&2; exit 2 ;;
    esac
done

hr() { printf '─%.0s' {1..72}; echo; }
section() { echo; hr; echo "  $1"; hr; }

# ─────────────────────────────────────────────────────────────────────
section "1. APK 构成（先看谁是体积大头，再决定审什么）"
# ─────────────────────────────────────────────────────────────────────
# 为什么要分类而不是只看总大小：本仓库实测 dex 占解压后 81%，
# 而"资源太多"的直觉是错的 —— 不先看构成就优化，等于蒙眼打靶。
APK="${APK:-$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk}"
if [[ -f "$APK" ]]; then
    echo "APK: $APK"
    ls -l "$APK" | awk '{printf "文件大小: %.2f MB\n", $5/1048576}'
    python3 - "$APK" > "$OUT/apk-composition.txt" <<'PY'
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])

def cat(n):
    if n.startswith("lib/"): return "lib/.so"
    if n.endswith(".dex"): return "dex"
    if n == "resources.arsc" or n.startswith("res/"): return "res"
    if n.startswith("assets/"): return "assets"
    if n == "AndroidManifest.xml": return "manifest"
    return "other"

raw, comp = {}, {}
for i in z.infolist():
    c = cat(i.filename)
    raw[c] = raw.get(c, 0) + i.file_size
    comp[c] = comp.get(c, 0) + i.compress_size

tr = sum(raw.values())
print(f"{'类别':<14}{'解压MB':>9}{'压缩MB':>9}{'占解压比':>10}")
for k in sorted(raw, key=lambda x: -raw[x]):
    print(f"{k:<14}{raw[k]/1048576:>9.2f}{comp[k]/1048576:>9.2f}{raw[k]*100/tr:>9.1f}%")
print(f"{'合计':<14}{tr/1048576:>9.2f}{sum(comp.values())/1048576:>9.2f}")

# 单文件 Top：找出"某个 .so / 某张图异常大"这种情况
print("\n单文件 Top 15（按解压后）:")
for i in sorted(z.infolist(), key=lambda x: -x.file_size)[:15]:
    print(f"  {i.file_size:>9} {i.filename}")
PY
    cat "$OUT/apk-composition.txt"
    echo
    echo "→ 已写入 $OUT/apk-composition.txt"
else
    echo "（未找到 APK，先跑 ./gradlew :app:assembleRelease）"
fi

# ─────────────────────────────────────────────────────────────────────
section "2. R8 实际删掉的东西（需已开 opt.shrink）"
# ─────────────────────────────────────────────────────────────────────
# usage.txt 是 R8 的"删除清单"，比任何静态猜测都准 —— 它是编译期真实可达性分析。
FOUND_USAGE=0
while IFS= read -r f; do
    FOUND_USAGE=1
    echo "→ $f  ($(wc -l < "$f" | tr -d ' ') 行)"
done < <(find app/build/outputs/mapping -name 'usage.txt' 2>/dev/null)

if [[ $FOUND_USAGE == 1 ]]; then
    # 合并所有变体，去重
    find app/build/outputs/mapping -name 'usage.txt' -exec cat {} + 2>/dev/null \
        | sort -u > "$OUT/removed-code.txt"
    echo "已写入 $OUT/removed-code.txt（去重后 $(wc -l < "$OUT/removed-code.txt" | tr -d ' ') 行）"
    echo
    echo "被删得最多的包（Top 10）:"
    # usage.txt 的行格式：com.example.Foo: 或 com.example.Foo.bar():12:13 -> ...
    grep -oE '^[a-z][a-z0-9_]*(\.[a-z0-9_]+){0,3}' "$OUT/removed-code.txt" \
        | sort | uniq -c | sort -rn | head -10 || true
    echo
    echo "提示: 若在列表里看到本仓库自己的类（com/interview/**）被整类删除，"
    echo "      说明它真的不可达；但若是 JNI 桥接类，请立刻核对 proguard-rules.pro。"
else
    echo "（未找到 usage.txt —— 说明当前不是 opt.shrink 构建）"
    echo "  跑一次:  ./gradlew :app:assembleRelease -Popt.shrink"
fi

# ─────────────────────────────────────────────────────────────────────
section "3. 无用资源（lint UnusedResources）"
# ─────────────────────────────────────────────────────────────────────
# 为什么单独跑 lint 而不是塞进主构建：
#   本仓库有既存 lint 债务（见 app/build.gradle.kts 的 CI 卡口注释），
#   完整 lint 耗时长且噪声大。这里只取 UnusedResources 一项，
#   且**作为独立步骤**——不想让它拖慢日常 assembleRelease。
if [[ $SKIP_LINT == 1 ]]; then
    echo "（--skip-lint 已跳过）"
else
    echo "执行: ./gradlew :app:lintRelease -PthreadLintOnly=false"
    echo "（只关心 UnusedResources 结果，其余 lint 债务不阻断）"
    if ./gradlew :app:lintRelease --console=plain -q >/dev/null 2>&1; then
        REPORT=$(find app/build/reports -name 'lint-results-release.xml' 2>/dev/null | head -1)
        if [[ -n "$REPORT" ]]; then
            python3 - "$REPORT" > "$OUT/unused-resources.txt" <<'PY'
import sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.argv[1])
issues = [i for i in tree.iter("issue") if i.get("id") in ("UnusedResources", "UnusedIds")]
if not issues:
    print("没有 UnusedResources 发现。")
for i in issues:
    loc = i.find("location")
    f = loc.get("file") if loc is not None else "?"
    ln = loc.get("line") if loc is not None else "?"
    print(f"{i.get('severity','?'):<8} {f}:{ln}")
    print(f"         {i.get('message','')}")
PY
            cat "$OUT/unused-resources.txt"
            echo
            echo "→ 已写入 $OUT/unused-resources.txt"
        else
            echo "（lint 未产出 XML 报告）"
        fi
    else
        echo "（lint 执行失败或有 error 级问题而中断 —— 这不影响体积优化主路径）"
    fi
fi

# ─────────────────────────────────────────────────────────────────────
section "4. 依赖清点（哪些依赖在源码里零引用）"
# ─────────────────────────────────────────────────────────────────────
# 这一步是"启发式"的：源码零引用 ≠ 无用（可能是运行时反射/服务加载）。
# 所以只列出来供人工确认，绝不自动移除。
echo "在 app/src/main/java **与 app/src/main/res** 里做符号搜索，命中 0 次的列为疑似无用："
echo "（必须同时搜 res —— constraintlayout 就只在 activity_live_data_demo.xml 里出现，"
echo "  只搜 Kotlin 会得到\"可删\"的错误结论。这是本脚本踩过的坑。）"
declare -a PROBES=(
    "constraintlayout:androidx.constraintlayout"
    "material:com.google.android.material"
    "rxjava3:io.reactivex"
    "logging-interceptor:HttpLoggingInterceptor"
    "glide:bumptech"
    "retrofit:retrofit2"
    "fragment:androidx.fragment"
    "recyclerview:androidx.recyclerview"
)
for probe in "${PROBES[@]}"; do
    name="${probe%%:*}"; sym="${probe#*:}"
    n=$(grep -rl -- "$sym" app/src/main/java app/src/main/res 2>/dev/null | wc -l | tr -d ' ')
    if [[ "$n" == "0" ]]; then
        printf '  ✗ %-22s %-32s 命中 0 处  ← 疑似可删\n' "$name" "$sym"
    else
        printf '  · %-22s %-32s 命中 %s 个文件\n' "$name" "$sym" "$n"
    fi
done
echo
echo "⚠️ 命中 0 ≠ 一定无用。移除前请确认没有：运行时反射加载、"
echo "   ServiceLoader、或被 xshell/独立 APK 以其他方式复用。"
echo "   ⚠️ 另有\"引用但永不触发\"的情形：adapter-rxjava3 被 Common.kt 的"
echo "      RxJava3CallAdapterFactory.create() import 着，但 GitHub 接口没有任何"
echo "      返回 Observable/Single 的方法 —— 引用存在、功能为死。这类只能靠人读。"

section "完成"
echo "报告目录: $OUT"
echo "提醒: 本脚本不删除任何东西。真正的删除动作只有 opt.shrink 一处。"
