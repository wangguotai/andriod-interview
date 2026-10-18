#!/usr/bin/env bash
# 抓取「崩溃现场」trace（Perfetto），用于分析 crash 前后发生了什么。
#
# 用法：
#   tools/perfetto/record-crash.sh [-s SERIAL] [-p PACKAGE] [-d SECONDS] [-o OUT]
#                                  [--tap-text "按钮文字"] [--tap-delay N]
#                                  [--activity A] [--analyze]
#
# 例子：
#   # 抓「主线程崩」：起 trace → 启动 App → 3 秒后点「⚠主线程崩」→ 收尾
#   tools/perfetto/record-crash.sh --activity com.interview.稳定性监控.StabilityLabActivity \
#       --tap-text "⚠主线程崩" --tap-delay 3
#
#   # 抓 Native SEGV
#   tools/perfetto/record-crash.sh --tap-text "⚠空指针(SEGV)" --tap-delay 3
#
# 产物：
#   <OUT>                          Perfetto trace
#   <OUT>.logcat.txt               同期 logcat（异常栈/DEBUG 日志）
#   <OUT>.procstat.txt             崩溃后进程状态（pid 是否变化证明真的死了）
#
# 为什么要三个一起抓：
#   Perfetto **没有** crash 数据源 —— 它抓不到「崩溃」这个事件本身。
#   它能回答的是「死之前发生了什么」（线程状态/slice/CPU/阻塞原因）。
#   「怎么死的」要靠 logcat 的异常栈、tombstone、ExitInfo。
#   两者靠**时间对齐**拼起来。所以本脚本一次把三样都拉回来。
#
# 退出码：0 成功；非 0 见下方错误分支。
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CFG="$HERE/crash-trace.cfg"

if ! command -v adb >/dev/null 2>&1; then
  for c in "$HOME/Library/Android/sdk/platform-tools" "${ANDROID_HOME:-}/platform-tools" "${ANDROID_SDK_ROOT:-}/platform-tools"; do
    [ -x "$c/adb" ] && export PATH="$PATH:$c" && break
  done
fi
command -v adb >/dev/null 2>&1 || { echo "错误：找不到 adb，请把 platform-tools 加进 PATH" >&2; exit 2; }

SERIAL=""
PKG="com.example.myapplication"
DURATION=20
OUT=""
ACTIVITY=""
TAP_TEXT=""
TAP_DELAY=3
ANALYZE=0

while [ $# -gt 0 ]; do
  case "$1" in
    -s) SERIAL="$2"; shift 2 ;;
    -p) PKG="$2"; shift 2 ;;
    -d) DURATION="$2"; shift 2 ;;
    -o) OUT="$2"; shift 2 ;;
    --activity) ACTIVITY="$2"; shift 2 ;;
    --tap-text) TAP_TEXT="$2"; shift 2 ;;
    --tap-delay) TAP_DELAY="$2"; shift 2 ;;
    --analyze) ANALYZE=1; shift ;;
    -h|--help) sed -n '2,25p' "$0"; exit 0 ;;
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
  echo "错误：没有已连接的设备。" >&2; exit 3
elif [ "$DEV_COUNT" -gt 1 ]; then
  echo "错误：检测到多台设备，必须用 -s <serial> 指定。当前：" >&2
  "${ADB[@]}" devices | awk 'NR>1 && $2=="device"{print "  "$1}' >&2
  exit 3
fi

"${ADB[@]}" shell "command -v perfetto" >/dev/null 2>&1 || { echo "错误：设备上没有 perfetto" >&2; exit 4; }

[ -z "$OUT" ] && { mkdir -p "$HERE/out/crash"; OUT="$HERE/out/crash/$(date +%Y%m%d-%H%M%S)-crash.perfetto"; }
mkdir -p "$(dirname "$OUT")"

# ── 生成设备端配置 ──
TMP_CFG="$(mktemp -t crash-cfg.XXXXXX)"
trap 'rm -f "$TMP_CFG"' EXIT
# 先删模板里的 duration_ms 再统一追加，否则重复字段会被 protobuf 拒绝（实测坑）
sed "s/__APP__/$PKG/" "$CFG" | grep -v '^[[:space:]]*duration_ms' > "$TMP_CFG"
printf '\nduration_ms: %s\n' "$(( DURATION * 1000 ))" >> "$TMP_CFG"

# perfetto 只对这两个目录有权限（SELinux，实测）
"${ADB[@]}" shell 'mkdir -p /data/misc/perfetto-configs /data/misc/perfetto-traces'
"${ADB[@]}" push "$TMP_CFG" /data/misc/perfetto-configs/crash-trace.cfg >/dev/null
TRACE_ON_DEV="/data/misc/perfetto-traces/crash-trace.perfetto"
"${ADB[@]}" shell "rm -f $TRACE_ON_DEV"

echo "▶ 开始抓取 ${DURATION}s  [包=$PKG]"
# atrace_apps 是进程启动时读取的：必须「先停 app → 起 perfetto → 再拉起 app」
"${ADB[@]}" shell "am force-stop $PKG" >/dev/null 2>&1 || true
"${ADB[@]}" shell "logcat -c" >/dev/null 2>&1 || true

"${ADB[@]}" shell "perfetto -c /data/misc/perfetto-configs/crash-trace.cfg --txt -o $TRACE_ON_DEV" >/dev/null 2>&1 &
PERFETTO_PID=$!

sleep 2   # 等 atrace 标签生效

if [ -n "$ACTIVITY" ]; then
  "${ADB[@]}" shell "am start -n $PKG/$ACTIVITY" >/dev/null 2>&1 || true
  sleep 3
fi

# ── 记录「本次运行」的 pid ──
# 必须在 force-stop + am start **之后**取！在之前取拿到的是上一次的旧 pid，
# 于是 pid_before != pid_after 恒成立、verdict 永远说"崩溃了"——判断完全失效（实测踩过）。
PID_BEFORE=$("${ADB[@]}" shell "pidof $PKG" 2>/dev/null | tr -d '\r' || true)
echo "▶ 本次运行 pid = ${PID_BEFORE:-<未运行>}"

# ── 按文字找按钮并点击（不硬编码坐标，换机型/换布局都不怕）──
tap_by_text() {
  local want="$1"
  "${ADB[@]}" shell "uiautomator dump /sdcard/_ui.xml" >/dev/null 2>&1 || return 1
  local xml
  xml=$("${ADB[@]}" shell "cat /sdcard/_ui.xml" 2>/dev/null | tr -d '\r')
  # 从 text="..." 节点里定位 bounds="[x1,y1][x2,y2]"，取中心点
  local coords
  coords=$(printf '%s' "$xml" | python3 -c "
import sys, re
want = sys.argv[1]
data = sys.stdin.read()
for m in re.finditer(r'<node[^>]*>', data):
    node = m.group(0)
    t = re.search(r'text=\"([^\"]*)\"', node)
    if not t or t.group(1) != want:
        continue
    b = re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', node)
    if not b:
        continue
    x1,y1,x2,y2 = map(int, b.groups())
    print((x1+x2)//2, (y1+y2)//2)
    break
" "$want")
  [ -z "$coords" ] && return 1
  echo "▶ 点击「${want}」@ $coords"
  "${ADB[@]}" shell "input tap $coords"
  return 0
}

if [ -n "$TAP_TEXT" ]; then
  echo "▶ ${TAP_DELAY}s 后点击「${TAP_TEXT}」触发崩溃…"
  sleep "$TAP_DELAY"
  if ! tap_by_text "$TAP_TEXT"; then
    # 按钮可能在屏幕外 —— 向上滚一屏再试
    "${ADB[@]}" shell "input swipe 540 1600 540 900 300" >/dev/null 2>&1 || true
    sleep 1
    tap_by_text "$TAP_TEXT" || echo "⚠ 没找到「${TAP_TEXT}」，请手动点击" >&2
  fi
else
  echo "▶ 现在请在设备上手动触发崩溃（${DURATION}s 内）…"
fi

wait "$PERFETTO_PID" || true

# ── 收尾取证 ──
PID_AFTER=$("${ADB[@]}" shell "pidof $PKG" 2>/dev/null | tr -d '\r' || true)
{
  echo "pid_before=$PID_BEFORE"
  echo "pid_after=$PID_AFTER"
  if [ -n "$PID_BEFORE" ] && [ "$PID_BEFORE" != "$PID_AFTER" ]; then
    echo "verdict=进程已死（pid 变了：$PID_BEFORE → ${PID_AFTER:-<无>}）—— 符合崩溃特征"
  elif [ -n "$PID_BEFORE" ] && [ -z "$PID_AFTER" ]; then
    echo "verdict=进程已死（未重启）—— 符合崩溃特征"
  elif [ -z "$PID_BEFORE" ]; then
    echo "verdict=抓取时未拿到 pid —— 无法判断（App 可能启动失败）"
  else
    echo "verdict=pid 未变（$PID_BEFORE）—— 没崩，或崩溃被 catch 住了"
  fi
} | tee "$OUT.procstat.txt"

"${ADB[@]}" shell "logcat -d" > "$OUT.logcat.txt" 2>/dev/null || true

"${ADB[@]}" shell "ls -l $TRACE_ON_DEV" >/dev/null 2>&1 || { echo "错误：设备上没有生成 trace" >&2; exit 5; }
"${ADB[@]}" pull "$TRACE_ON_DEV" "$OUT" >/dev/null 2>&1
SIZE=$(wc -c < "$OUT" | tr -d ' ')
[ "$SIZE" -lt 4096 ] && { echo "错误：trace 过小（${SIZE}B）" >&2; exit 5; }

echo "✔ trace   ：$OUT  (${SIZE} bytes)"
echo "✔ logcat  ：$OUT.logcat.txt"
echo "✔ 进程状态：$OUT.procstat.txt"
echo "  在线打开：https://ui.perfetto.dev → Open trace file"

if [ "$ANALYZE" -eq 1 ]; then
  echo
  "$HERE/analyze_trace.py" "$OUT" 2>/dev/null || echo "(analyze_trace.py 未适配 crash trace，可忽略)"
fi
