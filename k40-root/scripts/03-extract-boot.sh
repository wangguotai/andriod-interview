#!/usr/bin/env bash
# 解包 fastboot 线刷包，提取 boot.img / init_boot.img，并判断该补丁哪个
# 用法: ./scripts/03-extract-boot.sh [/Volumes/data/k40-rom]
set -euo pipefail

WORK="${1:-/Volumes/data/k40-rom}"
cd "$WORK"

TGZ=$(ls -1 alioth_images_*.tgz 2>/dev/null | head -1)
[ -n "$TGZ" ] || { echo "!! 未找到 alioth_images_*.tgz，请先运行 02-download-firmware.sh"; exit 1; }
echo "线刷包: $TGZ"

DIR="${TGZ%.tgz}"

echo
echo "=== 解包（约 5.9GB 压缩包 → 约 12GB，需要几分钟）==="
if [ -d "$DIR/images" ]; then
  echo "已解包，跳过。"
else
  tar -xzf "$TGZ"
fi

echo
echo "=== 包内 boot 相关分区镜像 ==="
ls -la "$DIR/images/" | grep -iE 'boot|dtbo|vbmeta' || true

echo
echo "=== 关键判定：该补丁 boot 还是 init_boot ==="
mkdir -p "$WORK/boot"
BOOT_SRC=""
if [ -f "$DIR/images/init_boot.img" ]; then
  echo "检测到 init_boot.img → 【必须补丁 init_boot.img】"
  BOOT_SRC="$DIR/images/init_boot.img"
  TARGET="init_boot"
else
  echo "未检测到 init_boot.img → 【补丁 boot.img】"
  BOOT_SRC="$DIR/images/boot.img"
  TARGET="boot"
fi

[ -f "$BOOT_SRC" ] || { echo "!! 找不到 $BOOT_SRC"; exit 1; }

cp -f "$BOOT_SRC" "$WORK/boot/stock-${TARGET}.img"
cp -f "$BOOT_SRC" "$WORK/boot/to-patch-${TARGET}.img"

echo
echo "已导出："
echo "  原厂备份: $WORK/boot/stock-${TARGET}.img"
echo "  待补丁  : $WORK/boot/to-patch-${TARGET}.img"
echo "  补丁目标分区: $TARGET"
echo
echo "MD5（原厂备份，务必留存以便救砖回刷）："
md5 "$WORK/boot/stock-${TARGET}.img" 2>/dev/null || md5sum "$WORK/boot/stock-${TARGET}.img"
echo
echo "下一步：把 to-patch-${TARGET}.img 用 adb push 到手机，用 Magisk App 打补丁。"
echo "  adb push '$WORK/boot/to-patch-${TARGET}.img' /sdcard/Download/"
