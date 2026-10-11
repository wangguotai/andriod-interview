#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════
#  图片资源 → WebP 批量转换
# ═══════════════════════════════════════════════════════════════════════
#
#  用法：
#    tools/opt/webp-res.sh                      # 默认 dry-run，只报告
#    tools/opt/webp-res.sh --apply              # 真正转换并删除原图
#    tools/opt/webp-res.sh --apply --q 90       # 指定质量（默认 85）
#    tools/opt/webp-res.sh --lossless --apply   # 无损（图标/线条图更合适）
#    tools/opt/webp-res.sh --dir app/src/main/res
#
#  ─── 为什么这个转换是**安全**的（不是凭感觉）───
#
#  Android 的资源引用按**资源名**解析，与文件扩展名无关：
#    · XML 里 `@drawable/foo`、`@mipmap/ic_launcher`  —— 不含后缀
#    · 代码里 `R.drawable.foo`                          —— 不含后缀
#  所以把 foo.png 换成 foo.webp **不需要改写任何引用**。这一点让整个转换
#  退化成"原地换编码"，而不是"改文件名 + 全仓找引用"的高风险操作。
#
#  ─── 仍然会跳过的东西（跳过原因都写在输出里）───
#
#  · *.9.png  九宫格：WebP 不支持 9-patch 的拉伸标记，转了必崩
#  · 已存在同名 .webp 的 png —— 会造成资源名冲突，直接报错退出
#  · mipmap/ic_launcher*  可选跳过（--skip-launcher）：
#    启动图标由 Launcher 进程跨进程加载，个别 ROM 有兼容性传说；
#    图标本身只有几十 KB，收益极小，保守起见留 PNG 更稳。
#
#  ─── 和其他开关的关系 ───
#
#  本脚本改的是**工作区源文件**（不是构建产物），因此：
#    1. 默认 dry-run，必须显式 --apply —— 与 Gradle 侧 opt.webp.apply 的
#       "默认不落盘"是同一套安全哲学。
#    2. 转换后请用 `git diff --stat` 复核，转换是一次性提交，
#       不要混在业务改动里 —— 二进制 diff 无法 review，只能靠单独提交定位。
#    3. 转完后体积收益主要体现在 APK 的 res/（本仓库实测 1.57 MB），
#       WebP 相比 PNG 通常再省 25~35%。
# ═══════════════════════════════════════════════════════════════════════
set -euo pipefail

APPLY=0
QUALITY=85
LOSSLESS=0
SKIP_LAUNCHER=0
TARGET_DIR=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --apply)          APPLY=1; shift ;;
        --lossless)       LOSSLESS=1; shift ;;
        --skip-launcher)  SKIP_LAUNCHER=1; shift ;;
        -q|--q)           QUALITY="${2:?--q 需要一个 0-100 的值}"; shift 2 ;;
        --dir)            TARGET_DIR="${2:?--dir 需要一个路径}"; shift 2 ;;
        -h|--help)        sed -n '2,50p' "$0"; exit 0 ;;
        *) echo "未知参数: $1（用 --help 看用法）" >&2; exit 2 ;;
    esac
done

# 定位仓库根：本脚本在 tools/opt/ 下
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
[[ -n "$TARGET_DIR" ]] || TARGET_DIR="$ROOT/app/src/main/res"

if ! command -v cwebp >/dev/null 2>&1; then
    echo "✗ 找不到 cwebp。安装：brew install webp" >&2
    exit 1
fi
if [[ ! -d "$TARGET_DIR" ]]; then
    echo "✗ 目录不存在: $TARGET_DIR" >&2
    exit 1
fi

# ─── 先把候选与冲突一次性算清楚，再决定动不动手 ───
# 冲突（同名 webp 已存在）必须在**转换任何文件之前**就失败，
# 否则会出现"转了一半才发现冲突"的半成品状态。
# 注意：不用 mapfile —— macOS 自带 bash 3.2 没有它。
CANDIDATES=()
while IFS= read -r f; do CANDIDATES+=("$f"); done < <(
    find "$TARGET_DIR" -type f \( -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' \) \
        ! -name '*.9.png' | sort
)

CONFLICTS=()
NINE_PATCH=()
LAUNCHER=()
TODO=()
for f in ${CANDIDATES[@]+"${CANDIDATES[@]}"}; do
    base="${f%.*}"
    if [[ -e "$base.webp" ]]; then CONFLICTS+=("$f"); continue; fi
    if [[ "$SKIP_LAUNCHER" == 1 && "$f" == *"/mipmap-"*/ic_launcher* ]]; then
        LAUNCHER+=("$f"); continue
    fi
    TODO+=("$f")
done
while IFS= read -r f; do NINE_PATCH+=("$f"); done < <(
    find "$TARGET_DIR" -type f -name '*.9.png' | sort
)

if [[ ${#CONFLICTS[@]} -gt 0 ]]; then
    echo "✗ 发现资源名冲突（同名 .webp 已存在），拒绝继续：" >&2
    printf '    %s\n' "${CONFLICTS[@]}" >&2
    echo '  请先人工决定保留哪一个——自动挑一个会让 UI 表现取决于猜测。' >&2
    exit 1
fi

echo "目录      : $TARGET_DIR"
echo "模式      : $([[ $APPLY == 1 ]] && echo 'APPLY（会改源文件）' || echo 'DRY-RUN（只报告）')"
echo "质量      : q=$QUALITY$([[ $LOSSLESS == 1 ]] && echo ' (lossless)')"
echo "待转换    : ${#TODO[@]} 个"
[[ ${#LAUNCHER[@]} -gt 0 ]] && echo "跳过图标  : ${#LAUNCHER[@]} 个（--skip-launcher）"
[[ ${#NINE_PATCH[@]} -gt 0 ]] && echo "跳过9宫格 : ${#NINE_PATCH[@]} 个（WebP 不支持 9-patch）"
echo

if [[ ${#TODO[@]} -eq 0 ]]; then
    echo "没有需要转换的文件（已全部是 WebP，或都被跳过）。"
    exit 0
fi

# ─── 转换 ───
before_total=0; after_total=0; converted=0; failed=0
for f in ${TODO[@]+"${TODO[@]}"}; do
    out="${f%.*}.webp"
    before=$(stat -f%z "$f" 2>/dev/null || stat -c%s "$f")
    before_total=$((before_total + before))

    if [[ $APPLY == 0 ]]; then
        # dry-run 用临时文件估算体积，不触碰源目录
        tmp="$(mktemp -t webpXXXXXX).webp"
        flags=(-quiet)
        if [[ $LOSSLESS == 1 ]]; then flags+=(-lossless -z 9); else flags+=(-q "$QUALITY" -m 6); fi
        if cwebp "${flags[@]}" "$f" -o "$tmp" >/dev/null 2>&1; then
            after=$(stat -f%z "$tmp" 2>/dev/null || stat -c%s "$tmp")
            pct=$(awk "BEGIN{printf \"%.0f\", ($before-$after)*100/$before}")
            printf '  %-58s %7s -> %7s  (-%s%%)\n' "${f#$ROOT/}" "$before" "$after" "$pct"
            after_total=$((after_total + after))
        else
            printf '  %-58s 转换失败（跳过）\n' "${f#$ROOT/}"
            after_total=$((after_total + before)); failed=$((failed + 1))
        fi
        rm -f "$tmp"
    else
        flags=(-quiet)
        if [[ $LOSSLESS == 1 ]]; then flags+=(-lossless -z 9); else flags+=(-q "$QUALITY" -m 6); fi
        if cwebp "${flags[@]}" "$f" -o "$out" >/dev/null 2>&1; then
            rm -f "$f"
            after=$(stat -f%z "$out" 2>/dev/null || stat -c%s "$out")
            after_total=$((after_total + after)); converted=$((converted + 1))
            printf '  ✓ %s\n' "${out#$ROOT/}"
        else
            echo "  ✗ 转换失败: ${f#$ROOT/}" >&2
            after_total=$((after_total + before)); failed=$((failed + 1))
        fi
    fi
done

echo
pct=$(awk "BEGIN{ if ($before_total>0) printf \"%.1f\", ($before_total-$after_total)*100/$before_total; else print 0 }")
printf '合计: %.2f MB -> %.2f MB  (-%s%%)\n' \
    "$(awk "BEGIN{print $before_total/1048576}")" \
    "$(awk "BEGIN{print $after_total/1048576}")" "$pct"

if [[ $APPLY == 1 ]]; then
    echo
    echo "已转换 $converted 个文件，失败 $failed 个。"
    echo "下一步：git diff --stat 复核后**单独提交**（二进制 diff 无法 review）。"
else
    echo
    echo "这是 DRY-RUN。确认上面的清单后，加 --apply 真正执行。"
fi
