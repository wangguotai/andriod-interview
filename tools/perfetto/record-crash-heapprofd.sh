#!/usr/bin/env bash
# 一次抓取：Perfetto trace（ftrace + atrace/dalvik） + heapprofd（native 堆分配）
#          → 然后触发一次 crash → 用 Perfetto 分析"崩之前发生了什么"
#
# 用法：
#   tools/perfetto/record-crash-heapprofd.sh [--activity A] [--tap-text "文字"]
#                                           [--tap-delay N] [-d SECONDS]
#
# 关键设计（都是实测踩出来的）：
#   · heapprofd 用 **pid 附加**（本机 HeapprofdConfig 无 target_cmdline）
#   · 点击在**设备侧** sleep 后执行（宿主侧 sleep 会引入漂移，落在窗口外）
#   · 先 force-stop → 起 perfetto → 拉 App（atrace_apps 是进程启动时读的）
#
# 产物：
#   <OUT>.perfetto            合并 trace（ftrace + heapprofd + 帧时间线）
#   <OUT>.logcat.txt          同期 logcat（异常栈/tombstone）
#   <OUT>.procstat.txt        pid 前后对比（证明真的死了）
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CFG="$HERE/crash-heapprofd.cfg"
TP="$HERE/bin/trace_processor_shell"

if ! command -v adb >/dev/null 2>&1; then
  for c in "$HOME/Library/Android/sdk/platform-tools" "${ANDROID_HOME:-}/platform-tools"; do
    [ -x "$c/adb" ] && export PATH="$PATH:$c" && break
  done
fi
command -v adb >/dev/null 2>&1 || { echo "错误：找不到 adb" >&2; exit 2; }

SERIAL=""; PKG="com.example.myapplication"; DURATION=25; OUT=""
ACTIVITY=""; TAP_TEXT=""; TAP_DELAY=8; ANALYZE=0

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
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "未知参数：$1" >&2; exit 2 ;;
  esac
done

ADB=(adb)
[ -n "$SERIAL" ] && ADB=(adb -s "$SERIAL")

DEV_COUNT=$("${ADB[@]}" devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
if [ -z "$SERIAL" ] && [ "$DEV_COUNT" -gt 1 ]; then
  echo "错误：多台设备，必须 -s <serial>" >&2; exit 3
fi

[ -z "$OUT" ] && { mkdir -p "$HERE/out/crash"; OUT="$HERE/out/crash/$(date +%Y%m%d-%H%M%S)-crash-hp.perfetto"; }
mkdir -p "$(dirname "$OUT")"

# ── ① 拉起 App（先拿到 pid，heapprofd 附加要用）──
"${ADB[@]}" shell "am force-stop $PKG" >/dev/null 2>&1 || true
"${ADB[@]}" shell "logcat -c" >/dev/null 2>&1 || true

echo "▶ 开始抓取 ${DURATION}s（ftrace + heapprofd）"

# ── ② 先拉 App 拿 pid（heapprofd 附加模式需要）──
# 简化做法：先拉 App 拿到 pid，再写配置、起 perfetto。这会丢掉冷启动段，
# 但换来 heapprofd 一定能附加上（本场景要的是"崩之前"的堆分配，不是启动）。
if [ -n "$ACTIVITY" ]; then
  "${ADB[@]}" shell "am start -n $PKG/$ACTIVITY" >/dev/null 2>&1 || true
  sleep 3
fi
PID=$("${ADB[@]}" shell "pidof $PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}')
if [ -z "$PID" ]; then
  echo "错误：拿不到 $PKG 的 pid" >&2; exit 4
fi
echo "▶ 目标 pid = $PID"

# ── ③ 生成设备端配置（替换 __APP__ / __PID__）──
TMP_CFG="$(mktemp -t crashhp.XXXXXX)"
trap 'rm -f "$TMP_CFG"' EXIT
sed -e "s/__APP__/$PKG/" -e "s/__PID__/$PID/" "$CFG" \
  | grep -v '^[[:space:]]*duration_ms' > "$TMP_CFG"
printf '\nduration_ms: %s\n' "$(( DURATION * 1000 ))" >> "$TMP_CFG"

"${ADB[@]}" shell 'mkdir -p /data/misc/perfetto-configs /data/misc/perfetto-traces'
"${ADB[@]}" push "$TMP_CFG" /data/misc/perfetto-configs/crash-heapprofd.cfg >/dev/null
TRACE_ON_DEV="/data/misc/perfetto-traces/crash-hp.perfetto"
"${ADB[@]}" shell "rm -f $TRACE_ON_DEV"

"${ADB[@]}" shell "perfetto -c /data/misc/perfetto-configs/crash-heapprofd.cfg --txt -o $TRACE_ON_DEV" >/dev/null 2>&1 &
PERFETTO_PID=$!

sleep 6   # 给 heapprofd 附加 + 生效留时间（附加后要下一次分配才真正生效）

# ── ④ 设备侧定时点击（宿主侧 sleep 会漂移，点击可能落在窗口外）──
if [ -n "$TAP_TEXT" ]; then
  "${ADB[@]}" shell "uiautomator dump /sdcard/_ui.xml" >/dev/null 2>&1 || true
  XML=$("${ADB[@]}" shell "cat /sdcard/_ui.xml" 2>/dev/null | tr -d '\r')
  COORDS=$(printf '%s' "$XML" | python3 -c "
import sys, re
want = sys.argv[1]; data = sys.stdin.read()
for m in re.finditer(r'<node[^>]*>', data):
    node = m.group(0)
    t = re.search(r'text=\"([^\"]*)\"', node)
    if not t or t.group(1) != want: continue
    b = re.search(r'bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"', node)
    if not b: continue
    x1,y1,x2,y2 = map(int, b.groups()); print(f'{(x1+x2)//2} {(y1+y2)//2}'); break
" "$TAP_TEXT")
  if [ -n "$COORDS" ]; then
    echo "▶ ${TAP_DELAY}s 后点击「${TAP_TEXT}」@ $COORDS"
    # ⭐ 关键：sleep 与 tap 都放设备侧，与 perfetto 共用设备时钟
    "${ADB[@]}" shell "sleep $TAP_DELAY; input tap $COORDS" >/dev/null 2>&1 || true
  else
    echo "⚠ 未找到「${TAP_TEXT}」，改用手动触发（${DURATION}s 内）" >&2
  fi
fi

wait "$PERFETTO_PID" || true

# ── ⑤ 收尾取证 ──
# ⚠️ 必须带 `|| true`：进程真崩了以后 `pidof` 返回非 0，在 `set -e` + `pipefail`
#    下会**直接终止脚本** —— 于是"崩溃成功"反而导致脚本失败、拿不到产物。
#    这是个只在"真崩了"时才暴露的坑（没崩时 pidof 成功，一切正常）。
PID_AFTER=$("${ADB[@]}" shell "pidof $PKG" 2>/dev/null | tr -d '\r' | awk '{print $1}' || true)
{
  echo "pid_before=$PID"; echo "pid_after=$PID_AFTER"
  if [ "$PID" != "$PID_AFTER" ]; then
    echo "verdict=进程已死（pid $PID → ${PID_AFTER:-<无>}）—— 符合崩溃特征"
  else
    echo "verdict=pid 未变 —— 没崩，或崩溃被 catch"
  fi
} | tee "$OUT.procstat.txt"

"${ADB[@]}" shell "logcat -d" > "$OUT.logcat.txt" 2>/dev/null || true
"${ADB[@]}" pull "$TRACE_ON_DEV" "$OUT" >/dev/null 2>&1
SIZE=$(wc -c < "$OUT" | tr -d ' ')
echo "✔ trace ：$OUT ($SIZE bytes)"
echo "✔ logcat：$OUT.logcat.txt"
echo "✔ 状态  ：$OUT.procstat.txt"

if [ "$ANALYZE" -eq 1 ]; then
  echo; "$HERE/analyze-crash.sh" "$OUT" || true
fi
