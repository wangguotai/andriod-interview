#!/usr/bin/env bash
# 录制 Android 冷启动 trace（Perfetto 相位 + ART 方法级），用于定位静态代码块/clinit 耗时。
#
# 用法：
#   tools/perfetto/record-startup.sh [-s SERIAL] [-p PACKAGE] [-a ACTIVITY] [-d SECONDS] [-o OUT] [--analyze]
#
# 例子：
#   tools/perfetto/record-startup.sh -p com.example.myapplication -a com.interview.home.HomeActivity --analyze
#
# 产物（都在 out/ 下，已 gitignore）：
#   <时间戳>-startup.perfetto      相位 trace（Perfetto UI 可开）
#   <时间戳>-startup.art.trace     ART 方法 trace（含每个 <clinit>，analyze_startup.py 解析）
#
# ═══════════════════════════════════════════════════════════════════════════
#  为什么是"两条腿"（实测踩过的坑，写清楚免得后人重走）
# ═══════════════════════════════════════════════════════════════════════════
#  1. Perfetto 的 atrace `dalvik` 分类在 Android 12 上**不产出 Java <clinit> 切片**。
#     实测：strings trace | grep -c clinit == 0，只有 art:: 的 native 切片。
#     所以想"找静态代码块耗时"，单靠 Perfetto 抓不到 —— 得用 ART 方法 trace。
#  2. `am start --start-profiler <file>` 是**唯一**能在进程启动**之前**就开始
#     方法级采样的入口（`am profile start` 要等进程起来，会漏掉 Application.clinit）。
#     代价：需要 debuggable 应用（debug 包天然满足）。
#  3. ART 方法 trace 的 ts/dur 单位是**微秒**，且切片是"进入/退出"成对的 call 记录。
#     analyze_startup.py 已按此口径解析，直接把 .art.trace 喂给 trace_processor
#     也能当普通 trace 查（本仓实测可行）。
#  4. 抓取顺序照旧：先 force-stop → 再起 perfetto → 等 atrace_apps 生效 → 再拉进程。
#     atrace_apps 是进程**启动时**读的，顺序反了 app 侧埋点全丢。
#
# 退出码：0 成功；非 0 见下方错误分支。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CFG="$HERE/startup-trace.cfg"

# Android SDK platform-tools 常不在 PATH 里，兜底几个常见位置
if ! command -v adb >/dev/null 2>&1; then
  for c in "$HOME/Library/Android/sdk/platform-tools" "$ANDROID_HOME/platform-tools" "$ANDROID_SDK_ROOT/platform-tools"; do
    [ -x "$c/adb" ] && export PATH="$PATH:$c" && break
  done
fi
command -v adb >/dev/null 2>&1 || { echo "错误：找不到 adb，请把 platform-tools 加进 PATH" >&2; exit 2; }

SERIAL=""
PKG="com.example.myapplication"
ACTIVITY="com.interview.home.HomeActivity"
DURATION=12
OUT=""
ANALYZE=0
SAMPLING=""   # 空 = 全量方法 trace（默认）。填毫秒则走采样模式。

while [ $# -gt 0 ]; do
  case "$1" in
    -s) SERIAL="$2"; shift 2 ;;
    -p) PKG="$2"; shift 2 ;;
    -a) ACTIVITY="$2"; shift 2 ;;
    -d) DURATION="$2"; shift 2 ;;
    -o) OUT="$2"; shift 2 ;;
    --sampling) SAMPLING="$2"; shift 2 ;;
    --analyze) ANALYZE=1; shift ;;
    -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

# ── 选设备 ──
DEV_COUNT=$("${ADB[@]}" devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
if [ -n "$SERIAL" ]; then
  "${ADB[@]}" get-state >/dev/null 2>&1 || { echo "错误：设备 $SERIAL 不可用" >&2; exit 3; }
elif [ "$DEV_COUNT" -eq 0 ]; then
  echo "错误：没有已连接的设备。用 -s <serial> 指定，或先 adb devices 确认。" >&2; exit 3
elif [ "$DEV_COUNT" -gt 1 ]; then
  echo "错误：检测到多台设备，必须用 -s <serial> 指定（避免误操作真机）。当前：" >&2
  "${ADB[@]}" devices | awk 'NR>1 && $2=="device"{print "  "$1}' >&2
  exit 3
fi

# ── 前置检查 ──
"${ADB[@]}" shell "command -v perfetto" >/dev/null 2>&1 || { echo "错误：设备上没有 perfetto（Android 9+ 才自带）" >&2; exit 4; }
# 方法级采样要求 debuggable；非 debuggable 时 ART trace 会是空的，提前告知。
# ⚠️ 判定别用 `grep -c 'flags=\[DEBUGGABLE'`：方括号/反斜杠经 adb shell 两层
#    quoted-heredoc 会被吃掉，debug 包也被误判成"不是 debuggable"。用固定串即可。
DBG=$("${ADB[@]}" shell "dumpsys package $PKG | grep -c DEBUGGABLE || true" | tr -d ' \r')
[ "${DBG:-0}" = "0" ] && echo "⚠ $PKG 不是 debuggable：ART 方法 trace 可能为空，静态块耗时将拿不到（相位 trace 仍可用）" >&2

STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$HERE/out"
[ -z "$OUT" ] && OUT="$HERE/out/${STAMP}-startup.perfetto"
mkdir -p "$(dirname "$OUT")"
ART_OUT="${OUT%.perfetto}.art.trace"

# ── 生成设备端配置 ──
TMP_CFG="$(mktemp -t perfetto-cfg.XXXXXX)"
trap 'rm -f "$TMP_CFG"' EXIT
sed "s/__APP__/$PKG/" "$CFG" | grep -v '^[[:space:]]*duration_ms' > "$TMP_CFG"
printf '\nduration_ms: %s\n' "$(( DURATION * 1000 ))" >> "$TMP_CFG"

"${ADB[@]}" shell 'mkdir -p /data/misc/perfetto-configs /data/misc/perfetto-traces'
"${ADB[@]}" push "$TMP_CFG" /data/misc/perfetto-configs/startup-trace.cfg >/dev/null
TRACE_ON_DEV="/data/misc/perfetto-traces/startup-trace.perfetto"
ART_ON_DEV="/data/local/tmp/startup.art.trace"

# ── 抓取 ──
echo "▶ 冷启动抓取 ${DURATION}s  [设备=${SERIAL:-默认} 包=$PKG activity=$ACTIVITY]"
"${ADB[@]}" shell "am force-stop $PKG" >/dev/null 2>&1 || true
"${ADB[@]}" shell "rm -f $TRACE_ON_DEV $ART_ON_DEV"
# 某些设备闲置后会灭屏，input 事件与首帧统计会失真
"${ADB[@]}" shell "svc power stayon true" >/dev/null 2>&1 || true

"${ADB[@]}" shell "perfetto -c /data/misc/perfetto-configs/startup-trace.cfg --txt -o $TRACE_ON_DEV" >/dev/null 2>&1 &

sleep 2   # 等 traced 应用 atrace 的 app 标签，再拉进程（顺序不能反）

# ⚠️ 不要加 `--sampling N`：ART 的**采样模式**根本不产出 `<clinit>` 记录
#    （实测：采样 1ms 只有 ~950 条方法记录、0 个 clinit；全量模式 29.9 万条、164 个 clinit）。
#    采样模式是为"找热点函数"设计的，偶发一次的 clinit 必然被漏。
#    profiling 本身有开销，会放大绝对值；但静态块的相对排序与结构结论可靠。
PROF_ARGS=""
[ -n "$SAMPLING" ] && PROF_ARGS="--sampling $SAMPLING"
"${ADB[@]}" shell "am start -W --start-profiler $ART_ON_DEV $PROF_ARGS -n $PKG/$ACTIVITY" 2>&1 | grep -E "TotalTime|WaitTime|Status" || true

# 抓满剩余时长，让 perfetto 自然结束（-d 秒）
sleep "$DURATION"
"${ADB[@]}" shell "am profile stop $PKG" >/dev/null 2>&1 || true

# ── 取回 ──
"${ADB[@]}" shell "ls -l $TRACE_ON_DEV" >/dev/null 2>&1 || { echo "错误：设备上没有生成 trace，请检查 perfetto 是否报权限错误" >&2; exit 5; }
"${ADB[@]}" pull "$TRACE_ON_DEV" "$OUT" >/dev/null 2>&1
SIZE=$(wc -c < "$OUT" | tr -d ' ')
[ "$SIZE" -lt 4096 ] && { echo "错误：trace 过小（${SIZE}B），抓取可能失败" >&2; exit 5; }

echo "✔ 相位 trace：$OUT  (${SIZE} bytes)"
echo "  在线打开：https://ui.perfetto.dev → Open trace file"

if "${ADB[@]}" shell "ls $ART_ON_DEV" >/dev/null 2>&1; then
  "${ADB[@]}" pull "$ART_ON_DEV" "$ART_OUT" >/dev/null 2>&1 || true
  if [ -s "$ART_OUT" ]; then
    echo "✔ ART 方法 trace：$ART_OUT  ($(wc -c < "$ART_OUT" | tr -d ' ') bytes)"
  else
    echo "⚠ ART 方法 trace 为空：确认应用是 debuggable（debug 包），或设备禁用了 sampling profiler。" >&2
    ART_OUT=""
  fi
else
  ART_OUT=""
fi

if [ "$ANALYZE" -eq 1 ]; then
  echo
  "$HERE/analyze_startup.py" "$OUT" ${ART_OUT:+"$ART_OUT"} --pkg "$PKG"
fi
