#!/usr/bin/env bash
# 录制 Android 滑动性能 trace（Perfetto），用于分析 RecyclerView / 列表滚动卡顿。
#
# 用法：
#   tools/perfetto/record-scroll.sh [-s SERIAL] [-p PACKAGE] [-d SECONDS] [-o OUT] [--activity A] [--auto-swipe] [--analyze]
#
# 例子：
#   # 手动滑：脚本开始抓后，自己去屏幕上滑列表，10 秒后自动停止
#   tools/perfetto/record-scroll.sh -s emulator-5554 -p com.example.myapplication
#
#   # 自动滑：脚本自己发起 10 次上滑（适合无人值守 / CI）
#   tools/perfetto/record-scroll.sh -p com.example.myapplication --auto-swipe --analyze
#
# 产物：<OUT>（默认 tools/perfetto/out/<时间戳>-scroll.perfetto）
#   打开：浏览器进 https://ui.perfetto.dev → Open trace file
#   或：  tools/perfetto/analyze_trace.py <trace>   （命令行读，见该脚本头部）
#
# 退出码：0 成功；非 0 见下方错误分支。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
CFG="$HERE/scroll-trace.cfg"

# Android SDK platform-tools 常不在 PATH 里，兜底几个常见位置
if ! command -v adb >/dev/null 2>&1; then
  for c in "$HOME/Library/Android/sdk/platform-tools" "$ANDROID_HOME/platform-tools" "$ANDROID_SDK_ROOT/platform-tools"; do
    [ -x "$c/adb" ] && export PATH="$PATH:$c" && break
  done
fi
command -v adb >/dev/null 2>&1 || { echo "错误：找不到 adb，请把 platform-tools 加进 PATH" >&2; exit 2; }

SERIAL=""
PKG="com.example.myapplication"
DURATION=10
ACTIVITY=""
OUT=""
AUTO_SWIPE=0
ANALYZE=0

while [ $# -gt 0 ]; do
  case "$1" in
    -s) SERIAL="$2"; shift 2 ;;
    -p) PKG="$2"; shift 2 ;;
    -d) DURATION="$2"; shift 2 ;;
    -o) OUT="$2"; shift 2 ;;
    --activity) ACTIVITY="$2"; shift 2 ;;
    --auto-swipe) AUTO_SWIPE=1; shift ;;
    --analyze) ANALYZE=1; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
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
"${ADB[@]}" shell "pidof $PKG" >/dev/null 2>&1 || echo "提示：应用 $PKG 当前未运行，稍后会尝试启动它" >&2

DEV="/data"
[ -z "$OUT" ] && { mkdir -p "$HERE/out"; OUT="$HERE/out/$(date +%Y%m%d-%H%M%S)-scroll.perfetto"; }
mkdir -p "$(dirname "$OUT")"

# ── 生成设备端配置（把 __APP__ 换成真实包名）──
TMP_CFG="$(mktemp -t perfetto-cfg.XXXXXX)"
trap 'rm -f "$TMP_CFG"' EXIT
sed "s/__APP__/$PKG/" "$CFG" > "$TMP_CFG"
# 时长以参数为准（覆盖配置里的 duration_ms）
if [ "$DURATION" != "10" ]; then
  printf '\n# override\nduration_ms: %s\n' "$DURATION" >> "$TMP_CFG"
fi

# perfetto 只保证对 /data/misc/perfetto-configs 与 /data/misc/perfetto-traces 有读/写权限
"${ADB[@]}" shell 'mkdir -p /data/misc/perfetto-configs /data/misc/perfetto-traces'
"${ADB[@]}" push "$TMP_CFG" /data/misc/perfetto-configs/scroll-trace.cfg >/dev/null
TRACE_ON_DEV="/data/misc/perfetto-traces/scroll-trace.perfetto"
"${ADB[@]}" shell "rm -f $TRACE_ON_DEV"

# ── 抓取 ──
echo "▶ 开始抓取 ${DURATION}s  [设备=${SERIAL:-默认} 包=$PKG]"
if [ -n "$ACTIVITY" ]; then
  "${ADB[@]}" shell "am start -n $PKG/$ACTIVITY" >/dev/null 2>&1 || true
  sleep 3   # 等首帧稳定，避免把冷启动算进滚动数据
fi

"${ADB[@]}" shell "perfetto -c /data/misc/perfetto-configs/scroll-trace.cfg --txt -o $TRACE_ON_DEV" >/dev/null 2>&1 &
PERFETTO_PID=$!

sleep 1
if [ "$AUTO_SWIPE" -eq 1 ]; then
  # 自动上滑若干次，覆盖约 2 屏内容；间隔略大于滑动时长，模拟连续滚动
  SWIPES=$(( DURATION > 2 ? DURATION - 2 : 1 ))
  echo "▶ 自动滑动 $SWIPES 次（如需更真实，请改用不带 --auto-swipe 的手动模式）"
  for _ in $(seq 1 "$SWIPES"); do
    "${ADB[@]}" shell input swipe 540 1800 540 700 200
  done
else
  echo "▶ 现在请在设备上滑动列表 ${DURATION}s …"
fi

wait "$PERFETTO_PID" || true

# ── 取回 ──
"${ADB[@]}" shell "ls -l $TRACE_ON_DEV" >/dev/null 2>&1 || { echo "错误：设备上没有生成 trace，请检查 perfetto 是否报权限错误" >&2; exit 5; }
"${ADB[@]}" pull "$TRACE_ON_DEV" "$OUT" >/dev/null 2>&1
SIZE=$(wc -c < "$OUT" | tr -d ' ')
[ "$SIZE" -lt 4096 ] && { echo "错误：trace 过小（${SIZE}B），抓取可能失败" >&2; exit 5; }

echo "✔ 已保存：$OUT  (${SIZE} bytes)"
echo "  在线打开：https://ui.perfetto.dev → Open trace file"
echo "  命令行读：\"$HERE/analyze_trace.py\" \"$OUT\""

if [ "$ANALYZE" -eq 1 ]; then
  echo
  "$HERE/analyze_trace.py" "$OUT"
fi
