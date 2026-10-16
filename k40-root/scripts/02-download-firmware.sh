#!/usr/bin/env bash
# 下载 alioth 官方 fastboot 线刷包并校验 MD5
# 用法: ./scripts/02-download-firmware.sh [/Volumes/data/k40-rom]
set -euo pipefail

DEST="${1:-/Volumes/data/k40-rom}"
VERSION="OS1.0.6.0.TKHCNXM"
FILE="alioth_images_${VERSION}_20240605.0000.00_13.0_cn_9c952f8190.tgz"
WANT_MD5="9c952f8190ae6d841f10a2c1f74958d2"

mkdir -p "$DEST"
cd "$DEST"

# 按实测速度排序：bkt-sgp 最快（约 274 KB/s），cdnorg 次之（约 24 KB/s）
MIRRORS=(
  "https://bkt-sgp-miui-ota-update-alisgp.oss-ap-southeast-1.aliyuncs.com/$VERSION/$FILE"
  "https://cdnorg.d.miui.com/$VERSION/$FILE"
  "https://bn.d.miui.com/$VERSION/$FILE"
  "https://hugeota.d.miui.com/$VERSION/$FILE"
)

need_kb=14000000   # 约 13.4 GB（包 5.9GB + 解包后另需）
avail_kb=$(df -k "$DEST" | awk 'NR==2{print $4}')
echo "目标目录 : $DEST"
echo "可用空间 : $((avail_kb/1024/1024)) GiB"
if [ "$avail_kb" -lt "$need_kb" ]; then
  echo "!! 空间不足，至少需要约 14 GiB（解包后还要占空间）"; exit 1
fi

if [ -f "$FILE" ]; then
  echo "已存在同名文件，跳过下载，直接校验。"
else
  for m in "${MIRRORS[@]}"; do
    echo
    echo "尝试镜像: $m"
    # -C - 断点续传；失败自动换下一个镜像
    if curl -fL --retry 5 --retry-delay 5 -C - --progress-bar \
            -o "$FILE.part" "$m"; then
      mv "$FILE.part" "$FILE"
      echo "下载完成: $FILE"
      break
    fi
    echo "该镜像失败，尝试下一个..."
  done
fi

[ -f "$FILE" ] || { echo "!! 所有镜像均失败"; exit 1; }

echo
echo "=== 校验 MD5 ==="
echo "期望: $WANT_MD5"
if command -v md5 >/dev/null 2>&1; then
  GOT=$(md5 -q "$FILE")
else
  GOT=$(md5sum "$FILE" | awk '{print $1}')
fi
echo "实际: $GOT"
if [ "$GOT" = "$WANT_MD5" ]; then
  echo "✅ MD5 校验通过"
else
  echo "❌ MD5 不匹配！文件可能损坏或版本不符，请删除重下：rm '$DEST/$FILE'"
  exit 1
fi
