#!/usr/bin/env bash
# 刷入 Magisk 补丁镜像并验证 root
# 前置：BL 已解锁 + 已用 Magisk 打好补丁 + 手机已在 fastboot 模式
# 用法: ./scripts/04-flash-magisk.sh <补丁镜像路径> [boot|init_boot]
set -euo pipefail

FB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/fastboot"
[ -x "$FB" ] || FB=$(command -v fastboot)
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
[ -x "$ADB" ] || ADB=$(command -v adb)

PATCHED="${1:?用法: $0 <magisk_patched-xxx.img> [boot|init_boot]}"
TARGET="${2:-boot}"

[ -f "$PATCHED" ] || { echo "!! 找不到文件: $PATCHED"; exit 1; }
case "$TARGET" in boot|init_boot) ;; *) echo "!! 第二参数只能是 boot 或 init_boot"; exit 1;; esac

echo "=== 检查 fastboot 设备 ==="
$FB devices
if ! $FB devices | grep -q .; then
  echo "!! fastboot 未检测到设备。"
  echo "   请关机后按住【音量下 + 电源】进入 FASTBOOT 模式，再插 USB。"
  exit 1
fi

echo
echo "=== 设备信息 ==="
$FB getvar product 2>&1 | head -1 || true
$FB getvar current-slot 2>&1 | head -1 || true
SLOT=$($FB getvar current-slot 2>&1 | grep -oE '_(a|b)' | head -1)

echo
echo "=== 安全检查：确认 BL 已解锁 ==="
UNLOCKED=$($FB getvar unlocked 2>&1 | grep -oE '(yes|no)' | head -1)
if [ "$UNLOCKED" != "yes" ]; then
  echo "!! 设备仍为锁定状态（unlocked=$UNLOCKED），不能刷入。"
  echo "   请先完成小米解锁流程。"
  exit 1
fi
echo "BL 已解锁 ✅"

echo
echo "======================================================="
echo "即将刷入: $PATCHED"
echo "目标分区: $TARGET (当前槽: ${SLOT:-未知})"
echo "将同时写入 ${TARGET}_a 和 ${TARGET}_b 两个槽。"
echo "======================================================="
read -r -p "确认继续？输入 yes 回车: " ok
[ "$ok" = "yes" ] || { echo "已取消。"; exit 0; }

echo
echo "=== 刷入 $TARGET ==="
$FB flash "${TARGET}_a" "$PATCHED"
$FB flash "${TARGET}_b" "$PATCHED"

echo
echo "=== 重启到系统 ==="
$FB reboot

echo "等待开机（最多 180 秒）..."
$ADB wait-for-device
for i in $(seq 1 90); do
  state=$($ADB shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')
  [ "$state" = "1" ] && break
  sleep 2
done

echo
echo "=== Root 验证 ==="
$ADB shell 'su -c id' 2>&1 | tr -d '\r' || true
$ADB shell 'magisk -V; magisk -v' 2>&1 | tr -d '\r' || true

echo
echo "若上面输出含 uid=0(root) 即成功。"
echo "首次开机 Magisk App 可能提示需要额外设置（修复运行环境 / 重启），按提示操作。"
echo
echo "救砖回刷原厂 boot（如开不了机）："
echo "  fastboot flash ${TARGET}_a boot/stock-${TARGET}.img"
echo "  fastboot flash ${TARGET}_b boot/stock-${TARGET}.img"
