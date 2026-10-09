#!/usr/bin/env bash
#
# 录制 heapprofd（Perfetto Native 堆分配剖析）并给出逐调用点的字节归因。
#
# 用法：
#   tools/perfetto/record-heapprofd.sh [-s SERIAL] [-p PACKAGE] [-d SECONDS]
#                                      [-o OUT] [--tap "X Y" [@秒]]… [--analyze]
#   tools/perfetto/record-heapprofd.sh --analyze-only <trace 文件>
#
# 例：
#   # 交互模式：脚本起好 trace 后自己在屏幕上操作，30 秒后自动停
#   tools/perfetto/record-heapprofd.sh -p com.example.myapplication
#
#   # 无人值守：t=8s 装探针，t=15s 点「造 NATIVE 负载」（坐标用 uiautomator dump 取）
#   tools/perfetto/record-heapprofd.sh -s emulator-5554 \
#       --tap "539 791" @8 --tap "201 917" @15 -d 50 --analyze
#
#   # 只分析已有 trace（不重新录制）
#   tools/perfetto/record-heapprofd.sh --analyze-only tools/perfetto/out/xxx.perfetto
#
# 产物：<OUT>（默认 tools/perfetto/out/<时间戳>-heapprofd.perfetto）
#   浏览器：https://ui.perfetto.dev → Open trace file（看 heap profile 火焰图）
#   命令行：--analyze 打印逐 dump 汇总 + 存活字节 Top 调用点
#
# ══════════════════════════════════════════════════════════════════════════
# 本脚本沉淀的六个实测坑（真机 Redmi K40 / Android 12 / MIUI 上逐一踩过）
# ══════════════════════════════════════════════════════════════════════════
#
# 坑① 手测两台设备的 HeapprofdConfig **都没有 target_cmdline**（Android 12/MIUI
#     真机、Android 16 模拟器都报 "No field named target_cmdline"）。包名定向
#     得靠官方 tools/heap_profile 脚本或 perfetto_cmd 内部机制；手写配置的
#     通用姿势只有 **pid 附加**。脚本先试包名模式，验证失败自动退到 pid 模式
#     （要求 App 已在运行；user build 上附加 debuggable/profileable App 可行，
#     logcat 会出现 "heapprofd_client initialized" 作为成功标志）。
#     另注意官方文档：附加模式下 profiling 要到**下一次分配**之后几百毫秒才
#     真正生效 —— 附加后立刻做的突发分配可能漏记。脚本把点按放在 t≥8s 就是
#     给附加留生效时间。
#
# 坑② 设备端 pbtxt 解析器**吃不了 UTF-8 注释**（报错会指向别的字段，极具
#     误导性），也吃不了单行紧凑嵌套。所以 heapprofd.cfg 必须纯 ASCII、
#     一行一个字段 —— 中文解释放本文件，不放 .cfg。
#
# 坑③ 客户端共享内存环默认很小，分配突发会**静默丢数据**
#     （stat: heapprofd_buffer_overran）。必须 shmem_size_bytes=16MB +
#     block_client=true（宁可阻塞被测线程也不丢 —— 这套数字要当 memtrace
#     的对拍基准，丢了就没有意义）。
#
# 坑④ ⚠️ MIUI 的 SELinux 拒绝 heapprofd 守护进程读 App 自己的文件
#     （avc: denied { search } ... scontext=u:r:heapprofd:s0
#      tcontext=object_r:app_data_file:s0）⇒ **栈帧落在 App 自己 .so 里的
#     分配无法 unwind，样本被整个丢弃**。表现为：框架分配（位图等，栈全在
#     系统库）能记到，App JNI 分配一条都看不到。这不是抓取姿势问题，
#     是该机型的策略边界。要归因 App 自己的 native 代码：换 AOSP 系镜像
#     （本仓的 API 36 模拟器实测可 unwind 到 App 的 Java/JIT 帧），或用本仓
#     memtrace（bytehook + 离线符号化，不受此限制）。
#     判别：adb logcat -d | grep heapprofd 出现 avc denied。
#
# 坑⑤ 抓 trace 和点按钮如果放在**宿主侧**两个进程里各 sleep，工具调用间隔
#     会引入几十秒漂移，点击可能落在 trace 窗口之外。必须把所有点击拼成
#     一条 `adb shell "sleep 8; input tap …; sleep 7; input tap …"`，
#     挂设备时钟、与 perfetto 同时发起。
#
# 坑⑥ 模拟器闲置十几分钟后**屏幕会灭**，input tap 全部白点（trace 完美但
#     一条负载日志都没有）。抓之前 `svc power stayon true` + 前台拉起目标
#     Activity，抓完 `svc power stayon false`。
#
# 本脚本按 macOS 自带 bash 3.2 写（不用 mapfile / ${arr[-1]}；变量后紧跟
# 全角字符必须加花括号，否则 set -u 下会报 unbound variable）。

set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CFG="$HERE/heapprofd.cfg"
TP="$HERE/bin/trace_processor_shell"

# ════════════════ 参数 ════════════════
SERIAL=""; PKG="com.example.myapplication"; DURATION=30; OUT=""
TAPS=(); ANALYZE=0; ANALYZE_ONLY=""    # TAPS 元素形如 "8 539 791"（秒 x y）
while [[ $# -gt 0 ]]; do
  case "$1" in
    -s) SERIAL="$2"; shift 2 ;;
    -p) PKG="$2"; shift 2 ;;
    -d) DURATION="$2"; shift 2 ;;
    -o) OUT="$2"; shift 2 ;;
    --analyze-only) ANALYZE_ONLY="$2"; shift 2 ;;
    --tap) COORD="$2"; AT=""; shift 2
           if [[ "${1:-}" == @* ]]; then AT="${1#@}"; shift; fi
           if [ -z "$AT" ]; then          # 没给 @秒：第一条默认 12s，其后默认 上一条+10s
             if [ "${#TAPS[@]}" -eq 0 ]; then AT=12
             else LAST="${TAPS[$(( ${#TAPS[@]} - 1 ))]}"; AT=$(( ${LAST%% *} + 10 )); fi
           fi
           TAPS+=("$AT $COORD") ;;
    --analyze) ANALYZE=1; shift ;;
    -h|--help) sed -n '3,70p' "$0"; exit 0 ;;
    *) echo "未知参数：$1（--help 看用法）" >&2; exit 2 ;;
  esac
done

# ════════════════ 纯分析模式 ════════════════
TPQ() { "$TP" -q /dev/stdin "${1:-$OUT}" 2>/dev/null | tr '\r' '\n' \
        | grep -vE '^\[|^Loading|^Error stats|^ +|^--|"name","idx","value"|^"[a-z_]+","[0-9]+","[0-9]+"$'; }
report() {  # $1 = trace 文件
  echo "── 逐 dump 汇总（每 5s 窗口：净分配字节 / 净对象数）──"
  TPQ "$1" <<'SQL'
select ts, count(*) as rows_n, sum(size) as net_bytes, sum(count) as net_objs
from heap_profile_allocation group by ts order by ts;
SQL
  echo
  echo "── 存活字节 Top15 调用点（叶帧 + 模块；大分配是精确值，小分配是抽样外推）──"
  TPQ "$1" <<'SQL'
select sum(size) as bytes, sum(count) as objs,
       f.name as leaf_frame, ifnull(m.name,'?') as module
from heap_profile_allocation a
join stack_profile_callsite c on a.callsite_id = c.id
join stack_profile_frame f on c.frame_id = f.id
left join stack_profile_mapping m on f.mapping = m.id
group by a.callsite_id order by bytes desc limit 15;
SQL
  echo
  echo "── App 自己 .so 的帧有没有被记到（坑④自检：0 = 中招，换设备或用 memtrace）──"
  TPQ "$1" <<'SQL'
select count(*) as app_frames_recorded
from stack_profile_frame f join stack_profile_mapping m on f.mapping = m.id
where m.name like '%/base.apk%' or m.name like '%libmemtrace%';
SQL
}
if [ -n "$ANALYZE_ONLY" ]; then
  [ -f "$ANALYZE_ONLY" ] || { echo "错误：找不到 $ANALYZE_ONLY" >&2; exit 2; }
  report "$ANALYZE_ONLY"
  exit 0
fi

# ════════════════ adb 选台（与 record-scroll.sh 同一套约定）════════════════
ADB=(adb)
if [ -n "$SERIAL" ]; then ADB+=(-s "$SERIAL"); else ADB+=(-s "$(adb devices | awk '/device$/{print $1; exit}')"); fi
"${ADB[@]}" get-state >/dev/null 2>&1 || { echo "错误：没有可用设备（-s 指定？）" >&2; exit 3; }
echo "设备：$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')  [${ADB[2]:-auto}]"
echo "系统：Android $("${ADB[@]}" shell getprop ro.build.version.release | tr -d '\r')  ⇒ user build 只能抓 debuggable/profileable App"

# ════════════════ trace_processor 兜底 ════════════════
if [ ! -x "$TP" ]; then
  echo "→ 本地缺 trace_processor_shell，下载到 tools/perfetto/bin/（一次性）"
  mkdir -p "$HERE/bin"
  curl -fsSL -o "$TP" 'https://get.perfetto.dev/trace_processor_shell-macos-arm64' \
    || curl -fsSL -o "$TP" 'https://get.perfetto.dev/trace_processor_shell-macos'
  chmod +x "$TP"
fi

# ════════════════ 配置生成：__TARGET_LINE__ → target_cmdline 或 pid（坑①）════════════════
# 用 awk 而不是 sed 注入：BSD sed 不解释替换串里的 \n
gen_cfg() {  # $1=targeting 行, $2=毫秒
  awk -v t="$1" -v ms="$2" '
    /^      __TARGET_LINE__$/ { print "      " t; next }
    { print }
    END { print ""; print "duration_ms: " ms }' "$CFG"
}

DEV_CFG="$(mktemp -t heapprofd-cfg.XXXXXX)"
trap 'rm -f "$DEV_CFG"' EXIT
"${ADB[@]}" shell 'mkdir -p /data/misc/perfetto-configs /data/misc/perfetto-traces'
TRACE_ON_DEV=/data/misc/perfetto-traces/heapprofd.perfetto
"${ADB[@]}" shell "rm -f $TRACE_ON_DEV /data/misc/perfetto-traces/hp-validate*.perfetto"

# 2 秒试跑验证包名模式（解析失败秒退并打印原因；坑①的自动降级）
gen_cfg "target_cmdline: \"$PKG\"" 2000 > "$DEV_CFG"
"${ADB[@]}" push "$DEV_CFG" /data/misc/perfetto-configs/heapprofd-val.cfg >/dev/null
VAL_OUT="$("${ADB[@]}" shell 'perfetto -c /data/misc/perfetto-configs/heapprofd-val.cfg --txt -o /data/misc/perfetto-traces/hp-validate.perfetto 2>&1; echo "EXIT:$?"' 2>&1 || true)"
if echo "$VAL_OUT" | grep -q 'EXIT:0'; then
  gen_cfg "target_cmdline: \"$PKG\"" "$(( DURATION * 1000 ))" > "$DEV_CFG"
  "${ADB[@]}" push "$DEV_CFG" /data/misc/perfetto-configs/heapprofd.cfg >/dev/null
  echo "→ 包名模式 OK（target_cmdline）"
else
  echo "→ 包名模式不可用，perfetto 原始报错："
  echo "$VAL_OUT" | grep -v 'EXIT:' | grep -v '^$' | head -3 | sed 's/^/      /'
  PID="$("${ADB[@]}" shell pidof "$PKG" | tr -d '\r ')"
  [ -z "$PID" ] && { echo "错误：pid 模式需要 App 已在运行 —— 先启动它" >&2; exit 4; }
  gen_cfg "pid: $PID" "$(( DURATION * 1000 ))" > "$DEV_CFG"
  "${ADB[@]}" push "$DEV_CFG" /data/misc/perfetto-configs/heapprofd.cfg >/dev/null
  echo "→ 退到 pid 附加模式：pid=$PID"
fi

# ════════════════ 录制：点击拼成一条设备侧序列挂设备时钟（坑⑤）════════════════
echo "▶ 开始抓 ${DURATION}s"
if [ "${#TAPS[@]}" -gt 0 ]; then
  # 按 @秒 排序。元素含空格，绝不能 word-split（bash 3.2 没有 mapfile，用 while+read）
  SORTED=()
  while IFS= read -r line; do SORTED+=("$line"); done < <(printf '%s\n' "${TAPS[@]}" | sort -n)
  # 点按必须落在窗口内，否则序列会拖死整条 adb 会话
  LAST_T="${SORTED[$(( ${#SORTED[@]} - 1 ))]}"; LAST_AT="${LAST_T%% *}"
  if [ "$LAST_AT" -ge "$(( DURATION - 3 ))" ]; then
    echo "错误：最后一次点按 t=${LAST_AT}s 太晚，-d 至少要 $(( LAST_AT + 4 ))s" >&2; exit 6
  fi
  # 串成 sleep N; input tap x y; …（一次 adb 会话，零漂移）
  TAP_SEQ=""; PREV=0
  for t in "${SORTED[@]}"; do
    AT="${t%% *}"; XY="${t#* }"
    TAP_SEQ+="sleep $(( AT - PREV )); input tap $XY; "
    PREV=$AT
  done
  echo "▶ 点按计划（相对 trace 起点）："
  for t in "${SORTED[@]}"; do echo "    t=${t%% *}s  →  (${t#* })"; done
  "${ADB[@]}" shell "perfetto -c /data/misc/perfetto-configs/heapprofd.cfg --txt -o $TRACE_ON_DEV" >/dev/null 2>&1 &
  PERFETTO_PID=$!
  "${ADB[@]}" shell "$TAP_SEQ true" || true
  wait "$PERFETTO_PID" || true
else
  echo "▶ 现在请在设备上操作 ${DURATION}s …（每 5s 落一张存活快照）"
  "${ADB[@]}" shell "perfetto -c /data/misc/perfetto-configs/heapprofd.cfg --txt -o $TRACE_ON_DEV" >/dev/null 2>&1
fi

# ════════════════ 拉回 + 健康检查 ════════════════
"${ADB[@]}" shell "ls $TRACE_ON_DEV" >/dev/null 2>&1 || { echo "错误：设备上没有生成 trace" >&2; exit 5; }
[ -z "$OUT" ] && OUT="$HERE/out/$(date +%Y%m%d-%H%M%S)-heapprofd.perfetto"
mkdir -p "$HERE/out"
"${ADB[@]}" pull "$TRACE_ON_DEV" "$OUT" >/dev/null
echo "✔ 已保存：${OUT}（$(wc -c < "$OUT" | tr -d ' ') bytes）"

if "$TP" -q /dev/stdin "$OUT" 2>/dev/null <<'SQL' | grep -qE 'buffer_overran","[0-9]+","[1-9]'
select name, idx, value from stats where name = 'heapprofd_buffer_overran';
SQL
then
  echo "  ⚠️ heapprofd_buffer_overran > 0：客户端环被写爆，数据有丢 → 调大 shmem_size_bytes"
fi
if "${ADB[@]}" shell 'logcat -d' 2>/dev/null | grep -q 'avc: denied.*heapprofd'; then
  echo "  ⚠️ 检测到 SELinux 拒绝 heapprofd 读 App 文件（坑④）"
  echo "     本机型上栈帧落在 App 自己 .so 里的分配会被整个丢弃（框架分配不受影响）。"
  echo "     要归因 App native 代码：换 AOSP 系镜像，或用本仓 memtrace（bytehook）。"
fi

# ════════════════ 分析 ════════════════
if [ "$ANALYZE" -ne 1 ]; then
  echo "  浏览器打开：https://ui.perfetto.dev → Open trace file"
  echo "  命令行分析：$0 --analyze-only \"$OUT\""
  exit 0
fi
echo
report "$OUT"
