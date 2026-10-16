#!/usr/bin/env bash
# 并行分块下载器 v2（解决小米 CDN 单连接限速 + 保证断点续传）
# 关键设计：curl 直接以追加方式写入分块文件，任何中断都保留已下字节
# 用法: ./scripts/05-parallel-download.sh [URL] [输出文件] [并发数]
set -uo pipefail

URL="${1:-https://bkt-sgp-miui-ota-update-alisgp.oss-ap-southeast-1.aliyuncs.com/OS1.0.6.0.TKHCNXM/alioth_images_OS1.0.6.0.TKHCNXM_20240605.0000.00_13.0_cn_9c952f8190.tgz}"
OUT="${2:-/Volumes/data/k40-rom/alioth_images_OS1.0.6.0.TKHCNXM_20240605.0000.00_13.0_cn_9c952f8190.tgz}"
JOBS="${3:-32}"

OUTDIR=$(dirname "$OUT")
BASE="$OUTDIR/.chunks"
mkdir -p "$OUTDIR" "$BASE"

TOTAL=$(curl -sIL --max-time 30 "$URL" | tr -d '\r' | awk 'tolower($1)=="content-length:"{v=$2} END{print v}')
[ -n "${TOTAL:-}" ] && [ "$TOTAL" -gt 0 ] 2>/dev/null || { echo "!! 无法获取文件大小"; exit 1; }
echo "总大小: $TOTAL 字节 ($(( TOTAL/1024/1024 )) MiB)   并发: $JOBS"

# 分块粒度固定为 64 MiB，块数由文件大小决定（比固定并发更能跑满带宽）
CHUNKSZ=$(( 64 * 1024 * 1024 ))
NCHUNK=$(( (TOTAL + CHUNKSZ - 1) / CHUNKSZ ))
echo "块大小: 64 MiB   总块数: $NCHUNK"

part_of() { printf '%s/part_%05d' "$BASE" "$1"; }

# ---- 生成待下块列表 ----
TASKLIST="$BASE/tasks.txt"
: > "$TASKLIST"
for (( i=0; i<NCHUNK; i++ )); do
  s=$(( i * CHUNKSZ ))
  e=$(( s + CHUNKSZ - 1 )); [ "$e" -ge "$TOTAL" ] && e=$(( TOTAL - 1 ))
  want=$(( e - s + 1 ))
  p=$(part_of "$i")
  have=0; [ -f "$p" ] && have=$(stat -f%z "$p" 2>/dev/null || echo 0)
  [ "$have" -ge "$want" ] && continue
  echo "$i $s $e $want $p" >> "$TASKLIST"
done
PENDING=$(grep -c . "$TASKLIST" 2>/dev/null || echo 0)
echo "待下载: $PENDING / $NCHUNK 块"

if [ "$PENDING" -gt 0 ]; then
  cat > "$BASE/worker.sh" <<'WORKER'
#!/usr/bin/env bash
set -uo pipefail
idx="$1"; s="$2"; e="$3"; want="$4"; p="$5"
for attempt in $(seq 1 40); do
  have=0; [ -f "$p" ] && have=$(stat -f%z "$p" 2>/dev/null || echo 0)
  [ "$have" -ge "$want" ] && exit 0
  rs=$(( s + have ))
  # 直接追加写入分块文件：中断保留进度。
  # --speed-limit/--speed-time 用于检测“卡死”连接（<4KB/s 持续 25s 则断开重连）
  curl -sL --fail --retry 0 \
       --speed-limit 4096 --speed-time 25 \
       -r "${rs}-${e}" "$URL" >> "$p"
  sleep 1
done
have=0; [ -f "$p" ] && have=$(stat -f%z "$p" 2>/dev/null || echo 0)
[ "$have" -ge "$want" ] && exit 0 || exit 1
WORKER
  chmod +x "$BASE/worker.sh"
  export URL   # worker.sh 是独立进程，必须导出才能读到

  awk '{print $1" "$2" "$3" "$4" "$5}' "$TASKLIST" | \
    xargs -P "$JOBS" -n 5 "$BASE/worker.sh"
  echo "并行阶段结束。"
fi

# ---- 完整性校验 ----
echo
ok=1; done_cnt=0
for (( i=0; i<NCHUNK; i++ )); do
  s=$(( i * CHUNKSZ ))
  e=$(( s + CHUNKSZ - 1 )); [ "$e" -ge "$TOTAL" ] && e=$(( TOTAL - 1 ))
  want=$(( e - s + 1 ))
  p=$(part_of "$i")
  have=0; [ -f "$p" ] && have=$(stat -f%z "$p" 2>/dev/null || echo 0)
  if [ "$have" -eq "$want" ]; then done_cnt=$(( done_cnt + 1 ))
  else echo "!! 块 $i 不完整: $have / $want"; ok=0; fi
done
echo "完整块: $done_cnt / $NCHUNK"
[ "$ok" -eq 1 ] || { echo "存在不完整块，请重新运行本脚本（自动续传）。"; exit 1; }

# ---- 合并 ----
echo "=== 合并 ==="
: > "$OUT"
for (( i=0; i<NCHUNK; i++ )); do cat "$(part_of $i)" >> "$OUT"; done
GOT=$(stat -f%z "$OUT")
echo "合并大小: $GOT (期望 $TOTAL)"
[ "$GOT" -eq "$TOTAL" ] || { echo "❌ 大小不符"; exit 1; }

# ---- MD5 ----
echo "=== MD5 校验（大文件需几分钟）==="
EXPECT="9c952f8190ae6d841f10a2c1f74958d2"
GOTMD5=$(md5 -q "$OUT" 2>/dev/null || md5sum "$OUT" | awk '{print $1}')
echo "期望: $EXPECT"
echo "实际: $GOTMD5"
if [ "$GOTMD5" = "$EXPECT" ]; then
  echo "✅ 固件校验通过"; rm -rf "$BASE"
else
  echo "❌ MD5 不匹配"; exit 1
fi
