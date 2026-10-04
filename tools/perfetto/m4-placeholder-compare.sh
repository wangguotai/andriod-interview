#!/usr/bin/env bash
# M4 端到端对照：同一段滚动，只切换 Rust 占位色开关，各抓一份 Perfetto trace。
#
# 为什么不用 tools/perfetto/record-scroll.sh 的 --auto-swipe：
#   1. 它的核心价值在于「批处理调用」，这个脚本需要额外的一次「点开关」动作；
#   2. 它只朝一个方向滑，只滑不会让图片**再次出现**，被复用，看不到占位色的差别。
#      所以这里改成「往下滑 4 次，再往上滑 4 次」的往返，覆盖 bind 复用。
#
# 用法：m4-placeholder-compare.sh <on|off> <输出文件> [SKIP_TAP=1]
#   on  ：脚本会点击 sw_placeholder 把它打开（实验组）
#   off ：配合 SKIP_TAP=1，保持 app 默认关闭状态（对照组）
#
# 例：
#   SERIAL=<serial> tools/perfetto/m4-placeholder-compare.sh on  out/m4-on.perfetto
#   SKIP_TAP=1 SERIAL=<serial> tools/perfetto/m4-placeholder-compare.sh off out/m4-off.perfetto
set -euo pipefail

SERIAL="${SERIAL:-57d05823}"
PKG="com.example.myapplication"
ACT="com.interview.image.ImageLabActivity"
CFG_SRC="${CFG_SRC:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/scroll-trace.cfg}"
MODE="$1"
OUT="$2"
DURATION="${DURATION:-16}"

ADB="adb -s $SERIAL"

if ! printf '%s\n' "$PATH" | grep -q platform-tools; then
  export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"
fi

# 定位 sw_placeholder 的屏幕坐标（不写死，因为上方内存面板的行数会随状态变化）
dump_bounds() {
  $ADB shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
  $ADB shell cat /sdcard/ui.xml 2>/dev/null \
    | tr '>' '>\n' \
    | grep -o 'resource-id="'"$PKG"':id/sw_placeholder"[^/]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' \
    | grep -o 'bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' \
    | head -1
}

echo "▶ [M4/$MODE] 准备 trace 配置"
TMP_CFG="$(mktemp -t m4cfg.XXXXXX)"
trap 'rm -f "$TMP_CFG"' EXIT
sed "s/__APP__/$PKG/" "$CFG_SRC" | grep -v '^[[:space:]]*duration_ms' > "$TMP_CFG"
printf '\nduration_ms: %s\n' "$(( DURATION * 1000 ))" >> "$TMP_CFG"

$ADB shell 'mkdir -p /data/misc/perfetto-configs /data/misc/perfetto-traces'
$ADB push "$TMP_CFG" /data/misc/perfetto-configs/m4.cfg >/dev/null
TRACE_DEV=/data/misc/perfetto-traces/m4.perfetto
$ADB shell "rm -f $TRACE_DEV"

# atrace 的 app 标签在进程启动时读取：必须先起 perfetto 再拉 app
echo "▶ [M4/$MODE] 起 perfetto（${DURATION}s）"
$ADB shell "am force-stop $PKG" >/dev/null 2>&1 || true
$ADB shell "perfetto -c /data/misc/perfetto-configs/m4.cfg --txt -o $TRACE_DEV" >/dev/null 2>&1 &
PERFETTO_PID=$!
sleep 2

echo "▶ [M4/$MODE] 启动页面"
$ADB shell "am start -n $PKG/$ACT" >/dev/null 2>&1 || true
sleep 6          # 等首屏 + 首批主色调算完

# 关键：整段滚动期间开关状态保持固定（这就是本次实验的唯一变量）
# SKIP_TAP=1 时不点击，保持 app 的默认状态（默认关闭 = 对照组）
if [ "${SKIP_TAP:-0}" = "1" ]; then
  echo "▶ [M4/$MODE] SKIP_TAP=1：保持开关默认状态（关闭 = 对照组）"
else
BOUNDS="$(dump_bounds)"
if [ -n "$BOUNDS" ]; then
  COORDS="$(printf '%s' "$BOUNDS" | grep -o '[0-9]\+')"
  X1=$(printf '%s\n' "$COORDS" | sed -n 1p); Y1=$(printf '%s\n' "$COORDS" | sed -n 2p)
  X2=$(printf '%s\n' "$COORDS" | sed -n 3p); Y2=$(printf '%s\n' "$COORDS" | sed -n 4p)
  CX=$(( (X1 + X2) / 2 )); CY=$(( (Y1 + Y2) / 2 ))
  echo "▶ [M4/$MODE] sw_placeholder @ ($CX,$CY)"
  $ADB shell input tap "$CX" "$CY"
  sleep 2      # 等开关生效 + replay 完成
else
  echo "⚠ [M4/$MODE] 未找到 sw_placeholder，跳过点击（开关将保持默认）" >&2
fi
fi

echo "▶ [M4/$MODE] 往返滚动（下 4 次、上 4 次 ×2 轮）：让图片真正被复用"
for _round in 1 2; do
  for _ in 1 2 3 4; do $ADB shell input swipe 540 1700 540 600 180; sleep 0.35; done
  for _ in 1 2 3 4; do $ADB shell input swipe 540 600 540 1700 180; sleep 0.35; done
done

wait "$PERFETTO_PID" || true

$ADB shell "ls -l $TRACE_DEV" >/dev/null 2>&1 || { echo "错误：设备上没有 trace" >&2; exit 5; }
$ADB pull "$TRACE_DEV" "$OUT" >/dev/null 2>&1
SIZE=$(wc -c < "$OUT" | tr -d ' ')
echo "✔ [M4/$MODE] 已保存 $OUT ($SIZE bytes)"
